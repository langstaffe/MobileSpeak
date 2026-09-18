package dev.mobilespeak.mobilespeak

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class AudioEngine(private val context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureOffset = 0
    private val capture = ShortArray(960)
    private val playback = FloatArray(1920)
    private val interrupted = AtomicBoolean(false)
    private val routeChanged = AtomicBoolean(false)
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val paused = change != AudioManager.AUDIOFOCUS_GAIN
        interrupted.set(paused)
        ClientSession.setAudioInterrupted(paused)
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            routeChanged.set(true)
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            routeChanged.set(true)
        }
    }

    fun tick(ui: SessionUiState, allowCapture: Boolean) {
        if (ui.snapshot.status != "connected") {
            stop()
            Thread.sleep(30)
            return
        }
        ensureOutput()
        if (routeChanged.getAndSet(false)) stopCapture()
        val wantsCapture = allowCapture && ClientSession.microphonePermission && !ui.microphoneMuted &&
            !ui.deafened && !interrupted.get()
        if (wantsCapture) ensureCapture() else stopCapture()

        if (!ui.deafened && !interrupted.get()) {
            val count = ClientSession.playback(playback)
            if (count > 0 && (track?.write(playback, 0, count, AudioTrack.WRITE_NON_BLOCKING) ?: -1) < 0) {
                stop()
                return
            }
        }
        val input = record
        if (input != null) {
            val read = input.read(capture, captureOffset, capture.size - captureOffset, AudioRecord.READ_BLOCKING)
            if (read > 0) {
                captureOffset += read
                if (captureOffset == capture.size) {
                    ClientSession.capture(capture)
                    captureOffset = 0
                }
            } else if (read < 0) {
                stopCapture()
            } else {
                Thread.sleep(10)
            }
        } else {
            Thread.sleep(20)
        }
    }

    fun stop() {
        stopCapture()
        track?.runCatching { pause(); flush(); release() }
        track = null
        manager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let(manager::abandonAudioFocusRequest)
        else @Suppress("DEPRECATION") manager.abandonAudioFocus(focusListener)
        focusRequest = null
        manager.mode = AudioManager.MODE_NORMAL
    }

    private fun ensureOutput() {
        if (track != null) return
        val playbackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(min, playback.size * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.registerAudioDeviceCallback(deviceCallback, null)
        if (Build.VERSION.SDK_INT >= 26) {
            val focusAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(focusAttributes)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also(manager::requestAudioFocus)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureCapture() {
        if (record != null || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val min = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val input = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(min, capture.size * 2 * 4))
            .build()
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            ClientSession.setAudio(inputMuted = true)
            return
        }
        echoCanceler = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(input.audioSessionId) else null
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(input.audioSessionId) else null
        echoCanceler?.enabled = true
        noiseSuppressor?.enabled = true
        input.startRecording()
        captureOffset = 0
        record = input
    }

    private fun stopCapture() {
        record?.runCatching { stop(); release() }
        record = null
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        captureOffset = 0
    }
}
