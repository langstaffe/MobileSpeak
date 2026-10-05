import AVFoundation
import OSLog

// Engine/session control stays on one queue; render and capture use the existing
// thread-safe Rust bridge. Never wait for hardware initialization on the UI thread.
final class PhoneAudio: @unchecked Sendable {
    private let queue = DispatchQueue(label: "MobileSpeak.Audio", qos: .userInitiated)
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "MobileSpeak", category: "audio")
    private let handle: UnsafeMutableRawPointer
    private var engine: AVAudioEngine?
    private var source: AVAudioSourceNode?
    private var capturing = false
    private let lock = NSLock()
    private var generation = 0
    private var enabled = true
    private let playback: UnsafeRawPointer
    private var capturedFrames = 0
    var frameCounts: (rendered: Int, captured: Int) {
        lock.lock(); defer { lock.unlock() }
        return (Int(ts_render_count(playback)), capturedFrames)
    }
    @MainActor var inputFormat: AVAudioFormat? { get async { await onQueue { self.engine?.inputNode.outputFormat(forBus: 0) } } }
    @MainActor var isVoiceProcessingEnabled: Bool { get async { await onQueue { self.engine?.inputNode.isVoiceProcessingEnabled == true } } }
    @MainActor var isRunning: Bool { get async { await onQueue { self.engine?.isRunning == true } } }
    @MainActor var isCapturing: Bool { get async { await onQueue { self.capturing } } }
    func setListening(_ value: Bool) {
        if !value { ts_playback_active(playback, false) }
        queue.async {
            self.enabled = value
            ts_playback_active(self.playback, value && self.engine?.isRunning == true)
        }
    }
    init(handle: UnsafeMutableRawPointer) {
        self.handle = handle
        self.playback = ts_playback_acquire(handle)!
    }
    deinit {
        guard let engine else { ts_playback_release(playback); return }
        let playback = playback
        queue.async {
            ts_playback_active(playback, false)
            engine.stop()
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            ts_playback_release(playback)
        }
    }
    @MainActor private func onQueue<T>(_ operation: @escaping () -> T) async -> T {
        await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: operation()) }
        }
    }
    private var currentGeneration: Int {
        lock.lock(); defer { lock.unlock() }
        return generation
    }
    // MainActor submits each operation before suspending, preserving the order
    // of start/stop requests. Cancellation cannot interrupt a blocking system API.
    @MainActor func perform(_ operation: @escaping () throws -> Void) async throws {
        let requested = currentGeneration
        try await onQueue {
            Result {
                guard requested == self.currentGeneration else { throw CancellationError() }
                try operation()
                guard requested == self.currentGeneration else {
                    self.stopEngine()
                    throw CancellationError()
                }
            }
        }.get()
    }
    @MainActor func start() async throws { try await perform { try self.startEngine() } }
    @MainActor func startCapture() async throws { try await perform { try self.installCaptureTap() } }
    @MainActor func ensureRunning() async throws { try await perform { try self.ensureEngineRunning() } }
    func permission() async -> Bool {
        let started = ProcessInfo.processInfo.systemUptime
        let allowed = await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
        logger.info("Audio permission finished elapsed_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000)) allowed=\(allowed)")
        return allowed
    }
    private func startEngine() throws {
        guard engine == nil else { return }
        let started = ProcessInfo.processInfo.systemUptime
        var previous = started
        logger.info("Audio startup begin main_thread=\(Thread.isMainThread)")
        func mark(_ phase: String) {
            let now = ProcessInfo.processInfo.systemUptime
            logger.info("Audio startup phase=\(phase, privacy: .public) step_ms=\(Int((now - previous) * 1000)) total_ms=\(Int((now - started) * 1000))")
            previous = now
        }
        defer { logger.info("Audio startup end total_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000)) running=\(self.engine?.isRunning == true)") }
        let session = AVAudioSession.sharedInstance()
        defer {
            if self.engine == nil { try? session.setActive(false, options: .notifyOthersOnDeactivation) }
        }
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP, .mixWithOthers])
        try session.setPreferredSampleRate(48_000)
        try session.setPreferredIOBufferDuration(0.02)
        mark("session_configuration")
        try session.setActive(true)
        mark("session_active")
        let engine = AVAudioEngine()
        // Materialize both sides of RemoteIO before starting it. Creating the input
        // for the first time on an already running output-only engine can give 0 Hz.
        let input = engine.inputNode
        try input.setVoiceProcessingEnabled(true)
        mark("voice_processing")
        let output = engine.outputNode
        let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
        let pcm = playback
        let source = AVAudioSourceNode(format: format) { _, _, count, list in
            let buffers = UnsafeMutableAudioBufferListPointer(list)
            // The source format is planar stereo, independent of the hardware route.
            // AVAudioEngine performs device sample-rate/channel conversion downstream.
            if buffers.count == 2, let left = buffers[0].mData, let right = buffers[1].mData {
                _ = ts_render(pcm, left.assumingMemoryBound(to: Float.self),
                              right.assumingMemoryBound(to: Float.self), Int(count), 1)
            } else {
                for buffer in buffers {
                    if let data = buffer.mData { memset(data, 0, Int(buffer.mDataByteSize)) }
                }
            }
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
        engine.connect(engine.mainMixerNode, to: output, format: output.inputFormat(forBus: 0))
        engine.prepare()
        mark("graph_prepare")
        ts_playback_active(playback, enabled)
        do { try engine.start() } catch {
            ts_playback_active(playback, false)
            throw error
        }
        self.engine = engine; self.source = source
        mark("engine_start")
        logger.info("Audio started: input \(engine.inputNode.outputFormat(forBus: 0).description, privacy: .public); output \(output.inputFormat(forBus: 0).description, privacy: .public)")
    }
    private func installCaptureTap() throws {
        guard !capturing else { return }
        let started = ProcessInfo.processInfo.systemUptime
        defer { logger.info("Audio capture startup elapsed_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000)) capturing=\(self.capturing)") }
        try ensureEngineRunning()
        guard let engine else { throw audioError(L10n.string("error_audio_engine_not_started")) }
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1),
              let converter = AVAudioConverter(from: inputFormat, to: format) else {
            throw audioError(L10n.format("error_audio_format", inputFormat.sampleRate, Int64(inputFormat.channelCount)))
        }
        var pending = [Int16]()
        pending.reserveCapacity(1920)
        input.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [self] buffer, _ in
            let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * 48_000 / inputFormat.sampleRate) + 32)
            guard let converted = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }
            var supplied = false
            var error: NSError?
            converter.convert(to: converted, error: &error) { _, status in
                if supplied { status.pointee = .noDataNow; return nil }
                supplied = true; status.pointee = .haveData; return buffer
            }
            guard error == nil, let samples = converted.floatChannelData?[0] else { return }
            for i in 0..<Int(converted.frameLength) {
                let value = samples[i].isFinite ? max(-1, min(1, samples[i])) : 0
                pending.append(Int16(value * 32767))
                if pending.count == 960 {
                    _ = pending.withUnsafeBufferPointer { ts_capture(handle, $0.baseAddress, $0.count) }
                    lock.lock(); capturedFrames += 1; lock.unlock()
                    pending.removeAll(keepingCapacity: true)
                }
            }
        }
        capturing = true
    }
    private func ensureEngineRunning() throws {
        guard let engine else { try startEngine(); return }
        if !engine.isRunning {
            let started = ProcessInfo.processInfo.systemUptime
            defer { logger.info("Audio restart end total_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000)) running=\(self.engine?.isRunning == true)") }
            ts_playback_active(playback, false)
            try AVAudioSession.sharedInstance().setActive(true)
            logger.info("Audio restart session_active elapsed_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000))")
            engine.prepare()
            ts_playback_active(playback, enabled)
            do { try engine.start() } catch { ts_playback_active(playback, false); throw error }
            logger.info("Audio engine restarted after configuration change")
        }
    }
    private func audioError(_ message: String) -> NSError {
        NSError(domain: "MobileSpeak", code: 2, userInfo: [NSLocalizedDescriptionKey: message])
    }
    func stopCapture() {
        queue.async { self.removeCaptureTap() }
    }
    private func removeCaptureTap() {
        if capturing {
            engine?.inputNode.removeTap(onBus: 0); capturing = false
            _ = "{\"type\":\"capture_stopped\"}".withCString { ts_command(handle, $0) }
        }
    }
    func pauseForInterruption() {
        lock.lock(); generation += 1; lock.unlock()
        ts_playback_active(playback, false)
        queue.async {
            self.removeCaptureTap()
            ts_playback_active(self.playback, false)
            self.engine?.pause()
        }
    }
    func stop() {
        lock.lock(); generation += 1; lock.unlock()
        ts_playback_active(playback, false)
        queue.async { self.stopEngine() }
    }
    private func stopEngine() {
        ts_playback_active(playback, false)
        removeCaptureTap()
        guard let engine else { return }
        engine.stop(); self.engine = nil; source = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
