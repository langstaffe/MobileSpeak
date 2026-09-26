import AVFoundation
import OSLog

final class PhoneAudio {
    private let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "MobileSpeak", category: "audio")
    private let handle: UnsafeMutableRawPointer
    private var engine: AVAudioEngine?
    private var source: AVAudioSourceNode?
    private var capturing = false
    private let lock = NSLock()
    private var enabled = true
    private var renderedBuffers = 0
    private var capturedFrames = 0
    var frameCounts: (rendered: Int, captured: Int) {
        lock.lock(); defer { lock.unlock() }
        return (renderedBuffers, capturedFrames)
    }
    var inputFormat: AVAudioFormat? { engine?.inputNode.outputFormat(forBus: 0) }
    var isVoiceProcessingEnabled: Bool { engine?.inputNode.isVoiceProcessingEnabled == true }
    var isRunning: Bool { engine?.isRunning == true }
    var isCapturing: Bool { capturing }
    var listening: Bool {
        get { lock.lock(); defer { lock.unlock() }; return enabled }
        set { lock.lock(); enabled = newValue; lock.unlock() }
    }
    init(handle: UnsafeMutableRawPointer) { self.handle = handle }
    func permission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
    }
    func start() throws {
        guard engine == nil else { return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP, .mixWithOthers])
        try session.setPreferredSampleRate(48_000)
        try session.setPreferredIOBufferDuration(0.02)
        try session.setActive(true)
        let engine = AVAudioEngine()
        // Materialize both sides of RemoteIO before starting it. Creating the input
        // for the first time on an already running output-only engine can give 0 Hz.
        let input = engine.inputNode
        try input.setVoiceProcessingEnabled(true)
        let output = engine.outputNode
        let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
        var frame = [Float](repeating: 0, count: 1920)
        var offset = 1920
        let source = AVAudioSourceNode(format: format) { [self] _, _, count, list in
            lock.lock(); renderedBuffers += 1; lock.unlock()
            let buffers = UnsafeMutableAudioBufferListPointer(list)
            let audible = listening
            for i in 0..<Int(count) {
                if offset >= 1920 {
                    let length = frame.withUnsafeMutableBufferPointer { ts_playback(handle, $0.baseAddress, $0.count) }
                    if length == 0 { frame.withUnsafeMutableBufferPointer { $0.initialize(repeating: 0) } }
                    offset = 0
                }
                for channel in 0..<buffers.count {
                    buffers[channel].mData!.assumingMemoryBound(to: Float.self)[i] = audible ? frame[offset + min(channel, 1)] : 0
                }
                offset += 2
            }
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
        engine.connect(engine.mainMixerNode, to: output, format: output.inputFormat(forBus: 0))
        engine.prepare()
        do { try engine.start() } catch {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            throw error
        }
        self.engine = engine; self.source = source; listening = true
        logger.info("Audio started: input \(engine.inputNode.outputFormat(forBus: 0).description, privacy: .public); output \(output.inputFormat(forBus: 0).description, privacy: .public); route \(session.currentRoute.description, privacy: .public)")
    }
    func startCapture() throws {
        guard !capturing else { return }
        try ensureRunning()
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
    func ensureRunning() throws {
        guard let engine else { try start(); return }
        if !engine.isRunning {
            try AVAudioSession.sharedInstance().setActive(true)
            engine.prepare()
            try engine.start()
            logger.info("Audio engine restarted after configuration change")
        }
    }
    private func audioError(_ message: String) -> NSError {
        NSError(domain: "MobileSpeak", code: 2, userInfo: [NSLocalizedDescriptionKey: message])
    }
    func stopCapture() {
        if capturing {
            engine?.inputNode.removeTap(onBus: 0); capturing = false
            _ = "{\"type\":\"capture_stopped\"}".withCString { ts_command(handle, $0) }
        }
    }
    func pauseForInterruption() {
        stopCapture()
        engine?.pause()
    }
    func stop() {
        stopCapture(); engine?.stop(); engine = nil; source = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
