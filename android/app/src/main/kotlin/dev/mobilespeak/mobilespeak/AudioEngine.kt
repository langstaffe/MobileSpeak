package dev.mobilespeak.mobilespeak

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

internal class AudioEngine(private val context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val running = AtomicBoolean()
    private val routeVersion = AtomicInteger()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val signal = Object()
    private val capture = ShortArray(FRAME_SAMPLES)
    private val playback = FloatArray(FRAME_SAMPLES * 2)
    private val voiceAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Volatile private var connected = false
    @Volatile private var deafened = false
    @Volatile private var captureRequested = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var record: AudioRecord? = null
    private var playbackThread: Thread? = null
    private var captureThread: Thread? = null
    private var communicationMode = false
    private var captureOffset = 0
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = routeChanged("added", addedDevices)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = routeChanged("removed", removedDevices)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        manager.registerAudioDeviceCallback(deviceCallback, null)
        playbackThread = Thread(::playbackLoop, "MobileSpeakPlayback").also { it.start() }
        captureThread = Thread(::captureLoop, "MobileSpeakCapture").also { it.start() }
    }

    fun update(ui: SessionUiState, allowCapture: Boolean) {
        val nextConnected = ui.snapshot.status == "connected"
        val nextDeafened = ui.deafened
        val nextCaptureRequested = allowCapture && ClientSession.microphonePermission && !ui.microphoneMuted
        if (connected != nextConnected || deafened != nextDeafened || captureRequested != nextCaptureRequested) {
            Log.i(TAG, "Audio policy connected=$nextConnected listening=${!nextDeafened} capture=$nextCaptureRequested")
        }
        connected = nextConnected
        deafened = nextDeafened
        captureRequested = nextCaptureRequested
        wakeWorkers()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        wakeWorkers()
        record?.runCatching { stop() }
        track?.runCatching { pause() }
        playbackThread?.interrupt()
        captureThread?.interrupt()
        playbackThread?.takeIf { it !== Thread.currentThread() }?.join(STOP_TIMEOUT_MS)
        captureThread?.takeIf { it !== Thread.currentThread() }?.join(STOP_TIMEOUT_MS)
        playbackThread = null
        captureThread = null
        releaseCapture()
        releaseOutputSession()
        manager.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun playbackLoop() {
        var seenRoute = routeVersion.get()
        while (running.get()) {
            try {
                if (!connected) {
                    releaseOutputSession()
                    await { connected }
                    seenRoute = routeVersion.get()
                    continue
                }
                ensureCommunicationMode()
                if (seenRoute != routeVersion.get()) {
                    releaseTrack()
                    seenRoute = routeVersion.get()
                }
                if (!shouldPlay()) {
                    pauseOutput()
                    await { !connected || shouldPlay() }
                    continue
                }
                val output = ensureOutput()
                val count = ClientSession.playback(playback).coerceIn(0, playback.size)
                if (count < playback.size) playback.fill(0f, count, playback.size)
                if (!writeFully(output, playback, true)) releaseTrack()
            } catch (_: InterruptedException) {
                // stop() interrupts blocking waits after flipping running to false.
            } catch (error: Throwable) {
                if (running.get()) {
                    ClientSession.reportError("音频播放错误：${error.message}")
                    releaseTrack()
                    awaitRetry()
                }
            }
        }
        releaseOutputSession()
    }

    private fun captureLoop() {
        var seenRoute = routeVersion.get()
        var submittedCaptureFrames = 0L
        while (running.get()) {
            try {
                if (seenRoute != routeVersion.get()) {
                    releaseCapture()
                    seenRoute = routeVersion.get()
                }
                if (!shouldCapture()) {
                    releaseCapture()
                    await { shouldCapture() }
                    continue
                }
                ensureCommunicationMode()
                val input = ensureCapture()
                if (input == null) {
                    awaitRetry()
                    continue
                }
                val read = input.read(capture, captureOffset, capture.size - captureOffset, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 -> {
                        captureOffset += read
                        if (captureOffset == capture.size) {
                            val result = ClientSession.capture(capture)
                            submittedCaptureFrames++
                            if (submittedCaptureFrames % CAPTURE_LOG_INTERVAL_FRAMES == 0L) {
                                Log.i(TAG, "Microphone submitted frames=$submittedCaptureFrames samples=${capture.size} sampleRate=$SAMPLE_RATE result=$result")
                            }
                            captureOffset = 0
                        }
                    }
                    read == 0 -> awaitRetry(5)
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.w(TAG, "AudioRecord dead object; rebuilding")
                        releaseCapture()
                    }
                    read < 0 -> throw IllegalStateException("AudioRecord.read returned $read")
                }
            } catch (_: InterruptedException) {
                // stop() interrupts blocking waits after flipping running to false.
            } catch (error: Throwable) {
                if (running.get()) {
                    ClientSession.reportError("音频录制错误：${error.message}")
                    ClientSession.setAudio(inputMuted = true)
                    releaseCapture()
                    awaitRetry()
                }
            }
        }
        releaseCapture()
    }

    private fun ensureOutput(): AudioTrack {
        track?.let {
            if (it.playState != AudioTrack.PLAYSTATE_PLAYING) {
                it.play()
                Log.i(TAG, "AudioTrack resumed session=${it.audioSessionId}")
            }
            return it
        }
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        check(min > 0) { "AudioTrack minimum buffer unavailable: $min" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(voiceAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(max(min, playback.size * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(output.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialize" }
        track = output
        playback.fill(0f)
        check(writeFully(output, playback, false)) { "AudioTrack startup write failed" }
        output.play()
        val stream = if (Build.VERSION.SDK_INT >= 26) voiceAttributes.volumeControlStream else AudioManager.STREAM_VOICE_CALL
        Log.i(TAG, "AudioTrack started usage=${voiceAttributes.usage} stream=$stream bufferFrames=${output.bufferSizeInFrames}")
        return output
    }

    private fun writeFully(output: AudioTrack, samples: FloatArray, stopWhenInactive: Boolean): Boolean {
        var offset = 0
        var zeroWrites = 0
        while (running.get() && offset < samples.size && (!stopWhenInactive || shouldPlay())) {
            val written = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            when {
                written > 0 -> {
                    offset += written
                    zeroWrites = 0
                }
                written == 0 && zeroWrites++ < MAX_ZERO_WRITES -> Thread.yield()
                written == AudioTrack.ERROR_DEAD_OBJECT -> {
                    Log.w(TAG, "AudioTrack dead object; rebuilding")
                    return false
                }
                !running.get() -> return false
                else -> throw IllegalStateException("AudioTrack.write returned $written")
            }
        }
        return offset == samples.size
    }

    private fun pauseOutput() {
        track?.runCatching {
            if (playState != AudioTrack.PLAYSTATE_PLAYING) return@runCatching
            Log.i(TAG, "AudioTrack paused session=$audioSessionId")
            pause()
            flush()
        }
    }

    @Synchronized
    private fun releaseTrack() {
        track?.let { output ->
            Log.i(TAG, "AudioTrack stopped underruns=${output.underrunCount}")
            output.runCatching { pause() }
            output.runCatching { flush() }
            output.runCatching { release() }
        }
        track = null
    }

    @Synchronized
    private fun ensureCommunicationMode() {
        if (communicationMode) return
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        communicationMode = true
        Log.i(TAG, "Audio mode entered MODE_IN_COMMUNICATION")
    }

    @Synchronized
    private fun restoreNormalMode() {
        if (communicationMode) {
            manager.mode = AudioManager.MODE_NORMAL
            Log.i(TAG, "Audio mode restored MODE_NORMAL")
        }
        communicationMode = false
    }

    @Synchronized
    private fun releaseOutputSession() {
        releaseTrack()
        restoreNormalMode()
    }

    @SuppressLint("MissingPermission")
    private fun ensureCapture(): AudioRecord? {
        record?.let { return it }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "AudioRecord minimum buffer unavailable: $min" }
        val input = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(min, capture.size * 2 * 4))
            .build()
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            ClientSession.setAudio(inputMuted = true)
            return null
        }
        echoCanceler = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(input.audioSessionId) else null
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(input.audioSessionId) else null
        echoCanceler?.enabled = true
        noiseSuppressor?.enabled = true
        input.startRecording()
        captureOffset = 0
        record = input
        Log.i(
            TAG,
            "AudioRecord started session=${input.audioSessionId} source=${MediaRecorder.AudioSource.VOICE_COMMUNICATION} " +
                "sampleRate=$SAMPLE_RATE aecAvailable=${AcousticEchoCanceler.isAvailable()} " +
                "aecCreated=${echoCanceler != null} aecEnabled=${echoCanceler?.enabled == true} " +
                "nsAvailable=${NoiseSuppressor.isAvailable()} nsCreated=${noiseSuppressor != null} " +
                "nsEnabled=${noiseSuppressor?.enabled == true}",
        )
        return input
    }

    @Synchronized
    private fun releaseCapture() {
        record?.let {
            Log.i(
                TAG,
                "AudioRecord releasing session=${it.audioSessionId} recordingState=${it.recordingState} " +
                    "aecEnabled=${echoCanceler?.enabled == true} nsEnabled=${noiseSuppressor?.enabled == true}",
            )
        }
        record?.runCatching { stop() }
        record?.runCatching { release() }
        record = null
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        captureOffset = 0
    }

    private fun shouldPlay() = shouldPlayAudio(connected, deafened)
    private fun shouldCapture() = shouldCaptureAudio(connected, captureRequested)

    @Throws(InterruptedException::class)
    private fun await(condition: () -> Boolean) {
        synchronized(signal) {
            while (running.get() && !condition()) signal.wait()
        }
    }

    private fun awaitRetry(milliseconds: Long = 100) {
        try {
            synchronized(signal) { if (running.get()) signal.wait(milliseconds) }
        } catch (_: InterruptedException) {
            // stop() has already changed running and interrupted the worker.
        }
    }

    private fun routeChanged(change: String, devices: Array<out AudioDeviceInfo>) {
        Log.i(TAG, "Audio route $change: ${devices.joinToString { "${it.type}:${it.productName}" }}")
        routeVersion.incrementAndGet()
        wakeWorkers()
    }

    private fun wakeWorkers() = synchronized(signal) { signal.notifyAll() }

    private companion object {
        const val TAG = "MobileSpeakAudio"
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        const val MAX_ZERO_WRITES = 3
        const val STOP_TIMEOUT_MS = 2_000L
        const val CAPTURE_LOG_INTERVAL_FRAMES = 500L
    }
}

internal fun shouldPlayAudio(connected: Boolean, deafened: Boolean) = connected && !deafened

internal fun shouldCaptureAudio(connected: Boolean, captureRequested: Boolean) = connected && captureRequested
