package dev.mobilespeak.mobilespeak

import android.Manifest
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class AudioEngine(private val context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val running = AtomicBoolean()
    private val routeHandler = Handler(Looper.getMainLooper())
    private val routeRequest = CommunicationRouteRequest()
    // Stream construction/release can block in the HAL; never hold the route callback monitor there.
    private val streamLock = Any()
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val signal = Object()
    private val capture = ShortArray(FRAME_SAMPLES)

    @Volatile private var connected = false
    @Volatile private var deafened = false
    @Volatile private var captureRequested = false
    @Volatile private var connectionVersion = 0L
    @Volatile private var captureVersion = 0L
    @Volatile private var outputDeviceId = 0
    @Volatile private var outputChannels = 0
    @Volatile private var outputRevision = 0L
    @Volatile private var record: AudioRecord? = null
    private var playbackThread: Thread? = null
    private var captureThread: Thread? = null
    @Volatile private var communicationMode = false
    private var modernRouteRequested = false
    private var legacySpeakerBefore: Boolean? = null
    private var legacySpeakerWritten: Boolean? = null
    private var legacyScoOwned = false
    private var legacyScoBefore: Boolean? = null
    private var legacyScoWritten = false
    private var scoReceiver: BroadcastReceiver? = null
    private var scoState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED
    private var headsetProfileState: Int? = null
    @Volatile private var legacyScoActive = false
    @Volatile private var legacyOutputChannels = 2
    private var routeTimeout: Runnable? = null
    private var routeSession = 0L
    private var lastCommunicationDeviceId: Int? = null
    private var communicationDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var captureOffset = 0
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val routingListener = AudioRouting.OnRoutingChangedListener { routing ->
        if (running.get() && connected && routing === record) logActualRoute(routing, "device callback")
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = routeChanged("added", addedDevices)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = routeChanged("removed", removedDevices)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        manager.registerAudioDeviceCallback(deviceCallback, routeHandler)
        if (Build.VERSION.SDK_INT >= 31) {
            communicationDeviceListener = AudioManager.OnCommunicationDeviceChangedListener(::communicationDeviceChanged)
                .also { manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, it) }
        }
        playbackThread = Thread(::playbackLoop, "MobileSpeakPlayback").also { it.start() }
        captureThread = Thread(::captureLoop, "MobileSpeakCapture").also { it.start() }
    }

    @Synchronized
    fun update(ui: SessionUiState, allowCapture: Boolean, reapplyRoute: Boolean = false) {
        if (!running.get()) return
        val nextConnected = ui.snapshot.status == "connected"
        val nextDeafened = ui.deafened
        val nextCaptureRequested = allowCapture && ClientSession.microphonePermission && !ui.microphoneMuted
        val connectionChanged = connected != nextConnected
        if (connectionChanged) connectionVersion++
        if (connected != nextConnected || deafened != nextDeafened || captureRequested != nextCaptureRequested) {
            Log.i(TAG, "Audio policy connected=$nextConnected listening=${!nextDeafened} capture=$nextCaptureRequested")
        }
        val captureStopped = captureRequested && !nextCaptureRequested
        if (connectionChanged || captureRequested != nextCaptureRequested) captureVersion++
        connected = nextConnected
        deafened = nextDeafened
        captureRequested = nextCaptureRequested
        if (captureStopped) ClientSession.captureStopped()
        if (connectionChanged && !nextConnected) restoreNormalMode()
        if (connectionChanged && nextConnected) {
            routeSession++
            routeRequest.reset()
            if (Build.VERSION.SDK_INT < 31) registerScoReceiver()
        }
        if (shouldReapplyCommunicationRoute(nextConnected, connectionChanged, reapplyRoute)) {
            refreshCommunicationRoute(if (connectionChanged) "connected" else "app foreground")
        }
        wakeWorkers()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        ClientSession.captureStopped()
        wakeWorkers()
        record?.runCatching { stop() }
        playbackThread?.interrupt()
        captureThread?.interrupt()
        playbackThread?.takeIf { it !== Thread.currentThread() }?.join()
        captureThread?.takeIf { it !== Thread.currentThread() }?.join()
        playbackThread = null
        captureThread = null
        if (Build.VERSION.SDK_INT >= 31) {
            communicationDeviceListener?.let(manager::removeOnCommunicationDeviceChangedListener)
            communicationDeviceListener = null
        }
        releaseCapture()
        restoreNormalMode()
        manager.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun playbackLoop() {
        var output = 0L
        var seenConnection = -1L
        var seenRevision = -1L
        var outputCommunication = false
        fun closeOutput() {
            if (output != 0L) NativeOutput.close(output)
            output = 0L
            outputDeviceId = 0
            outputChannels = 0
        }
        try {
            while (running.get()) {
                try {
                    if (seenConnection != connectionVersion || seenRevision != outputRevision ||
                        outputChannels != playbackChannels() || outputCommunication != communicationMode || !shouldPlay() ||
                        (output != 0L && NativeOutput.failed(output))) {
                        closeOutput()
                        seenConnection = connectionVersion
                        seenRevision = outputRevision
                    }
                    if (!shouldPlay()) {
                        await { shouldPlay() }
                        continue
                    }
                    if (output == 0L) {
                        val channels = playbackChannels()
                        val communication = communicationMode
                        output = ClientSession.openOutput(channels, communication)
                        check(output != 0L) { "Oboe output unavailable" }
                        outputChannels = channels
                        outputCommunication = communication
                        Log.i(TAG, "Oboe started usage=${if (communication) "voice-communication" else "media"} channels=$channels sampleRate=$SAMPLE_RATE")
                    }
                    outputDeviceId = NativeOutput.deviceId(output)
                    if (Build.VERSION.SDK_INT < 31) confirmLegacyStreams()
                    // Management only: callbacks consume PCM. Check stream errors even
                    // when Android delivers no device notification after a HAL failure.
                    awaitRetry(100)
                } catch (_: InterruptedException) {
                    // stop() wakes the management thread, whose finally closes Oboe.
                } catch (error: Throwable) {
                    closeOutput()
                    if (running.get() && connected) {
                        ClientSession.reportError(context.localized(R.string.error_with_detail, context.localized(R.string.error_audio_playback), error.message.orEmpty()))
                        awaitRetry()
                    }
                }
            }
        } finally { closeOutput() }
    }

    private fun captureLoop() {
        var seenCapture = captureVersion
        var submittedCaptureFrames = 0L
        while (running.get()) {
            try {
                if (seenCapture != captureVersion) {
                    releaseCapture()
                    seenCapture = captureVersion
                }
                if (!shouldCapture()) {
                    releaseCapture()
                    await { shouldCapture() }
                    continue
                }
                val input = ensureCapture()
                if (input == null) {
                    awaitRetry()
                    continue
                }
                val read = input.read(capture, captureOffset, capture.size - captureOffset, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 && running.get() && shouldCapture() && seenCapture == captureVersion -> {
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
                if (running.get() && shouldCapture()) {
                    ClientSession.reportError(context.localized(R.string.error_with_detail, context.localized(R.string.error_audio_recording), error.message.orEmpty()))
                    releaseCapture()
                    awaitRetry()
                }
            }
        }
        releaseCapture()
    }

    @Synchronized
    private fun ensureCommunicationMode() {
        if (!running.get() || !connected || communicationMode) return
        if (manager.mode == AudioManager.MODE_IN_CALL) {
            Log.w(TAG, "Communication route unavailable reason=system telephone call")
            return
        }
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        communicationMode = true
        outputRevision++
        captureVersion++
        Log.i(TAG, "Audio mode entered MODE_IN_COMMUNICATION sdk=${Build.VERSION.SDK_INT}")
    }

    @Synchronized
    private fun restoreNormalMode() {
        routeSession++
        useMediaRoute("session ended")
        scoReceiver?.let { context.unregisterReceiver(it) }
        scoReceiver = null
        headsetProfileState = null
        routeRequest.reset()
    }

    // Release only this app's communication request; keep failed-device history
    // and the legacy profile receiver until the session ends/reconnects.
    @Synchronized
    private fun useMediaRoute(reason: String) {
        legacyOutputChannels = 2
        if (communicationMode || modernRouteRequested || legacyScoOwned || legacySpeakerBefore != null) {
            releaseCommunicationRoute(reason)
        }
        if (communicationMode) {
            // MODE_NORMAL removes our request even when another app/telephone call owns the current mode.
            manager.mode = AudioManager.MODE_NORMAL
            Log.i(TAG, "Communication mode request released actualMode=${manager.mode}")
            outputRevision++
            captureVersion++
        }
        communicationMode = false
        wakeWorkers()
    }

    @SuppressLint("MissingPermission")
    private fun ensureCapture(): AudioRecord? = synchronized(streamLock) {
        if (!running.get() || !shouldCapture()) return@synchronized null
        record?.let { return@synchronized it }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@synchronized null
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
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        record = input // Any subsequent initialization failure is cleaned up by the capture loop.
        echoCanceler = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(input.audioSessionId) else null }.getOrNull()
        noiseSuppressor = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(input.audioSessionId) else null }.getOrNull()
        echoCanceler?.runCatching { enabled = true }
        noiseSuppressor?.runCatching { enabled = true }
        input.startRecording()
        captureOffset = 0
        input.addOnRoutingChangedListener(routingListener, routeHandler)
        Log.i(
            TAG,
            "AudioRecord started session=${input.audioSessionId} source=${MediaRecorder.AudioSource.VOICE_COMMUNICATION} " +
                "sampleRate=${input.sampleRate} channels=${input.channelCount} bufferFrames=${input.bufferSizeInFrames} aecAvailable=${AcousticEchoCanceler.isAvailable()} " +
                "aecCreated=${echoCanceler != null} aecEnabled=${echoCanceler?.enabled == true} " +
                "nsAvailable=${NoiseSuppressor.isAvailable()} nsCreated=${noiseSuppressor != null} " +
                "nsEnabled=${noiseSuppressor?.enabled == true}",
        )
        logActualRoute(input, "started")
        input
    }

    private fun releaseCapture() = synchronized(streamLock) {
        record?.let {
            Log.i(
                TAG,
                "AudioRecord releasing session=${it.audioSessionId} recordingState=${it.recordingState} " +
                    "aecEnabled=${echoCanceler?.enabled == true} nsEnabled=${noiseSuppressor?.enabled == true}",
            )
        }
        record?.runCatching { stop() }
        if (record != null) ClientSession.captureStopped()
        record?.removeOnRoutingChangedListener(routingListener)
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

    @Synchronized
    private fun routeChanged(change: String, devices: Array<out AudioDeviceInfo>) {
        if (!running.get() || !connected) return
        Log.i(TAG, "Audio devices $change types=${devices.joinToString { deviceTypeName(it.type) }}")
        // Re-read current devices; a queued removal from the previous session is not a route instruction.
        applyCommunicationRoute("devices $change")
        outputRevision++; wakeWorkers()
    }

    @Synchronized
    private fun communicationDeviceChanged(device: AudioDeviceInfo?) {
        if (Build.VERSION.SDK_INT < 31 || !running.get() || !connected || !communicationMode) return
        // The queued callback's device can already be obsolete. Query the actual current selection.
        observeModernRoute("communication callback")
    }

    @Synchronized
    private fun refreshCommunicationRoute(reason: String) {
        if (connected) applyCommunicationRoute(reason)
    }

    @Synchronized
    private fun applyCommunicationRoute(reason: String) {
        if (!running.get() || !connected) return
        if (manager.mode == AudioManager.MODE_IN_CALL) {
            Log.w(TAG, "Communication route suspended reason=system telephone call")
            useMediaRoute("system telephone call")
            return
        }
        val devices = if (Build.VERSION.SDK_INT >= 31) manager.availableCommunicationDevices else
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        routeRequest.retainDevices(devices.mapTo(mutableSetOf()) { it.id })
        val eligible = devices.filter { it.id !in routeRequest.failedDevices }
        val preferredType = preferredCommunicationDevice(connected, eligible.mapTo(mutableSetOf()) { it.type })
        val requested = eligible.firstOrNull { it.type == preferredType }
        if (requested == null || !requiresCommunicationMode(requested.type)) {
            // Media output follows Android's current speaker/wired/media route.
            // VOICE_COMMUNICATION capture and its session AEC stay enabled.
            useMediaRoute(reason)
            Log.i(TAG, "Media route reason=$reason preferred=${deviceTypeName(requested?.type)}")
            return
        }
        ensureCommunicationMode()
        if (!communicationMode) return
        if (Build.VERSION.SDK_INT >= 31) applyModernCommunicationRoute(reason, requested)
        else applyLegacyCommunicationRoute(reason, requested)
    }

    @RequiresApi(31)
    private fun applyModernCommunicationRoute(reason: String, device: AudioDeviceInfo) {
        if (routeRequest.targetId == device.id) return
        releaseCommunicationRoute("new target")
        val token = routeRequest.request(device.id) ?: return
        try {
            val accepted = manager.setCommunicationDevice(device)
            modernRouteRequested = accepted
            Log.i(TAG, "Communication route request reason=$reason target=${deviceTypeName(device.type)} accepted=$accepted token=$token")
            if (!accepted) { failRoute(token, "setCommunicationDevice rejected"); return }
            scheduleRouteTimeout(token)
            observeModernRoute("request observation")
        } catch (error: RuntimeException) { failRoute(token, "setCommunicationDevice ${error.javaClass.simpleName}") }
    }

    @RequiresApi(31)
    private fun observeModernRoute(reason: String) {
        val actual = manager.communicationDevice
        if (lastCommunicationDeviceId != actual?.id) {
            lastCommunicationDeviceId = actual?.id
            outputRevision++; wakeWorkers()
            Log.i(TAG, "Communication device actual=${deviceTypeName(actual?.type)} reason=$reason")
        }
        if (actual?.id == routeRequest.targetId && routeRequest.confirm(routeRequest.token, actual?.id, SystemClock.elapsedRealtime())) {
            cancelRouteTimeout()
            Log.i(TAG, "Communication route confirmed actual=${deviceTypeName(actual?.type)} token=${routeRequest.token}")
        } else if (routeRequest.confirmed && actual?.id != routeRequest.targetId) {
            failRoute(routeRequest.token, "confirmed communication device lost", recoverEstablished = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyCommunicationRoute(reason: String, device: AudioDeviceInfo) {
        // Phone output also supports mono while SCO is pending. Keep one stream through recovery;
        // switch back to stereo only when the final target is a phone/wired device.
        legacyOutputChannels = if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 1 else 2
        wakeWorkers()
        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            if (routeRequest.targetId == device.id) return
            releaseCommunicationRoute("new target")
            val token = routeRequest.request(device.id) ?: return
            try {
                // Keep usable phone audio while SCO is pending; relinquish the speaker when the link connects.
                setLegacySpeaker(true)
                if (!manager.isBluetoothScoAvailableOffCall) { failRoute(token, "SCO unavailable off-call"); return }
                legacyScoBefore = manager.isBluetoothScoOn
                // startBluetoothSco owns this app's request even when a sticky CONNECTED state already exists.
                legacyScoOwned = true
                manager.startBluetoothSco()
                Log.i(TAG, "SCO request reason=$reason target=bluetooth-sco state=$scoState token=$token")
                scheduleRouteTimeout(token)
                if (scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED) confirmSco(token)
            } catch (error: RuntimeException) { failRoute(token, "startBluetoothSco ${error.javaClass.simpleName}") }
        } else {
            if (routeRequest.targetId != null || legacyScoOwned) releaseCommunicationRoute("non-SCO target")
            setLegacySpeaker(device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            Log.i(TAG, "Communication route request reason=$reason target=${deviceTypeName(device.type)} speaker=${manager.isSpeakerphoneOn}")
        }
    }

    @Suppress("DEPRECATION")
    private fun registerScoReceiver() {
        val session = routeSession
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                synchronized(this@AudioEngine) {
                    if (!running.get() || !connected || routeSession != session) return
                    if (intent.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        if (state !in BluetoothProfile.STATE_DISCONNECTED..BluetoothProfile.STATE_DISCONNECTING) return
                        val previous = headsetProfileState
                        headsetProfileState = state
                        if (previous != state) Log.i(TAG, "Headset profile state=$state previous=$previous session=$session")
                        if (state == BluetoothProfile.STATE_CONNECTED && previous != state) {
                            // Some devices can retain audio ports across a quick HFP reconnect: no device-added callback.
                            val ids = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                                .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }.mapTo(mutableSetOf()) { it.id }
                            routeRequest.devicesReconnected(ids)
                            applyCommunicationRoute("headset profile connected")
                        }
                        return
                    }
                    // registerReceiver already returned the initial state. Its queued sticky delivery may be older than a new request.
                    if (isInitialStickyBroadcast) return
                    if (!intent.hasExtra(AudioManager.EXTRA_SCO_AUDIO_STATE)) {
                        Log.w(TAG, "SCO broadcast ignored reason=missing state")
                        return
                    }
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                    val previous = scoState
                    scoState = state
                    if (previous != state) Log.i(TAG, "SCO state=$state previous=$previous owned=$legacyScoOwned session=$session")
                    if (!legacyScoOwned) {
                        if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) applyCommunicationRoute("SCO released")
                        return
                    }
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> confirmSco(routeRequest.token)
                        AudioManager.SCO_AUDIO_STATE_ERROR -> {
                            // ERROR means the SCO state could not be obtained; establishment failure is DISCONNECTED.
                            // Some devices emit ERROR between CONNECTING and CONNECTED. Keep the bounded request,
                            // and give actual stream routes precedence over this unavailable state observation.
                            Log.w(TAG, "SCO state unavailable actualOutput=${deviceTypeName(actualOutputType())} actualInput=${deviceTypeName(record?.routedDevice?.type)}")
                            if (scoObservationFailed(state, routeRequest.confirmed, actualScoStreams())) failRoute(routeRequest.token, "SCO state unavailable and actual route lost")
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> if (previous != state || routeRequest.confirmed) {
                            failRoute(routeRequest.token, "SCO disconnected", recoverEstablished = true)
                        }
                    }
                }
            }
        }
        scoReceiver = receiver
        headsetProfileState = null
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED).apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        scoState = sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED) ?: AudioManager.SCO_AUDIO_STATE_DISCONNECTED
        Log.i(TAG, "SCO initial state=$scoState session=$session")
    }

    @Suppress("DEPRECATION")
    private fun confirmSco(token: Long) {
        if (!legacyScoOwned || !routeRequest.isPending(token) || legacyScoActive) return
        if (manager.mode == AudioManager.MODE_IN_CALL) { failRoute(token, "SCO reserved by telephone call"); return }
        manager.isBluetoothScoOn = true
        legacyScoWritten = true
        setLegacySpeaker(false)
        legacyScoActive = true
        outputRevision++
        Log.i(TAG, "SCO link connected requestedRoute=${manager.isBluetoothScoOn} token=$token; awaiting actual stream routes")
        confirmLegacyStreams()
        wakeWorkers()
    }

    @Synchronized
    private fun confirmLegacyStreams() {
        if (!legacyScoOwned || !legacyScoActive || !routeRequest.isPending(routeRequest.token)) return
        if (actualScoStreams() && routeRequest.confirm(routeRequest.token, routeRequest.targetId, SystemClock.elapsedRealtime())) {
            cancelRouteTimeout()
            Log.i(TAG, "SCO communication confirmed output=${deviceTypeName(actualOutputType())} input=${deviceTypeName(record?.routedDevice?.type)} " +
                "listening=${shouldPlay()} capture=${shouldCapture()} token=${routeRequest.token}")
        }
    }

    private fun actualOutputType(): Int? = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.id == outputDeviceId }?.type

    @Suppress("DEPRECATION")
    private fun actualScoStreams() =
        (!shouldPlay() || (outputChannels == playbackChannels() &&
            (actualOutputType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                // OpenSL ES (API 24/25) cannot report a routed device ID. Its voice
                // stream follows the system SCO route; capture still verifies its device.
                (outputDeviceId == 0 && legacyScoActive && manager.isBluetoothScoOn)))) &&
            (!shouldCapture() || record?.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    private fun scheduleRouteTimeout(token: Long) {
        cancelRouteTimeout()
        val session = routeSession
        routeTimeout = Runnable {
            synchronized(this) {
                if (running.get() && connected && communicationMode && routeSession == session && routeRequest.isPending(token)) {
                    failRoute(token, "communication route timed out")
                }
            }
        }.also { routeHandler.postDelayed(it, ROUTE_TIMEOUT_MS) }
    }

    private fun cancelRouteTimeout() {
        routeTimeout?.let(routeHandler::removeCallbacks)
        routeTimeout = null
    }

    private fun failRoute(token: Long, reason: String, recoverEstablished: Boolean = false) {
        val recoveryTarget = if (recoverEstablished) {
            val available = if (Build.VERSION.SDK_INT >= 31) manager.availableCommunicationDevices else
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val stillAvailable = available.any { it.id == routeRequest.targetId } &&
                (Build.VERSION.SDK_INT >= 31 || headsetProfileState != BluetoothProfile.STATE_DISCONNECTED)
            routeRequest.recoverAfterLoss(token, stillAvailable, manager.mode == AudioManager.MODE_IN_CALL, SystemClock.elapsedRealtime())
        } else null
        if (!routeRequest.fail(token)) return
        Log.w(TAG, "Communication route failed reason=$reason token=$token; releasing request and falling back")
        releaseCommunicationRoute(reason)
        if (recoveryTarget != null && running.get() && connected && communicationMode) {
            // Earbuds in the case can reject the first recovery before being taken out. This budget only
            // advances on real route-loss events; no delayed retry, polling, or retry after a timeout/rejection.
            routeRequest.failedDevices.remove(recoveryTarget)
            applyCommunicationRoute("bounded recovery after established route lost")
        } else applyCommunicationRoute("fallback: $reason")
    }

    @Suppress("DEPRECATION")
    private fun setLegacySpeaker(enabled: Boolean) {
        val before = manager.isSpeakerphoneOn
        if (before == enabled) return
        if (legacySpeakerBefore == null) legacySpeakerBefore = before
        manager.isSpeakerphoneOn = enabled
        outputRevision++; wakeWorkers()
        legacySpeakerWritten = enabled
        Log.i(TAG, "Legacy speaker request before=$before requested=$enabled actual=${manager.isSpeakerphoneOn}")
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun releaseCommunicationRoute(reason: String) {
        cancelRouteTimeout()
        routeRequest.release()
        if (legacyScoActive) outputRevision++
        legacyScoActive = false
        if (Build.VERSION.SDK_INT >= 31) {
            if (modernRouteRequested) manager.clearCommunicationDevice()
            modernRouteRequested = false
        } else {
            if (legacyScoOwned) {
                legacyScoOwned = false
                manager.stopBluetoothSco()
                if (manager.mode != AudioManager.MODE_IN_CALL && legacyScoWritten && legacyScoBefore == false && manager.isBluetoothScoOn) manager.isBluetoothScoOn = false
                legacyScoBefore = null
                legacyScoWritten = false
                Log.i(TAG, "SCO request released reason=$reason")
            }
            if (manager.mode != AudioManager.MODE_IN_CALL && legacySpeakerWritten != null && manager.isSpeakerphoneOn == legacySpeakerWritten) {
                manager.isSpeakerphoneOn = legacySpeakerBefore == true
            }
            legacySpeakerWritten = null
            legacySpeakerBefore = null
        }
        wakeWorkers()
    }

    private fun playbackChannels() = if (Build.VERSION.SDK_INT < 31) legacyOutputChannels else 2

    private fun logActualRoute(routing: AudioRouting, reason: String) {
        val stream = "input"
        val device = routing.routedDevice
        Log.i(TAG, "Audio actual $stream=${deviceTypeName(device?.type)} deviceId=${device?.id} reason=$reason")
        if (routing is AudioRecord) {
            manager.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == routing.audioSessionId }?.let {
                Log.i(TAG, "AudioRecord format session=${routing.audioSessionId} clientRate=${it.clientFormat.sampleRate} " +
                    "deviceRate=${it.format.sampleRate} deviceChannels=${it.format.channelCount}")
            }
        }
        if (Build.VERSION.SDK_INT < 31) confirmLegacyStreams()
    }

    private fun wakeWorkers() = synchronized(signal) { signal.notifyAll() }

    private companion object {
        const val TAG = "MobileSpeakAudio"
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        const val ROUTE_TIMEOUT_MS = 30_000L
        const val CAPTURE_LOG_INTERVAL_FRAMES = 500L
    }
}

internal fun preferredCommunicationDevice(connected: Boolean, availableTypes: Set<Int>): Int? =
    if (!connected) null else availableTypes.minByOrNull(::communicationDevicePriority)
        ?.takeIf { communicationDevicePriority(it) < Int.MAX_VALUE }

internal fun requiresCommunicationMode(type: Int) = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> true
    else -> false
}

internal fun shouldReapplyCommunicationRoute(
    connected: Boolean,
    connectionChanged: Boolean,
    returnedToForeground: Boolean,
) = connected && (connectionChanged || returnedToForeground)

// A2DP follows the media route; never select it as a communication device.
internal fun communicationDevicePriority(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 2
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
    else -> Int.MAX_VALUE
}

// Route request identity is local to this AudioEngine, not a second session/audio lifecycle.
internal class CommunicationRouteRequest {
    var token = 0L; private set
    var targetId: Int? = null; private set
    var confirmed = false; private set
    val failedDevices = mutableSetOf<Int>()
    private var recoveryDevice: Int? = null
    private var recoveryUntil = 0L
    private var recoveryRemaining = 0
    private var confirmedAt = 0L
    fun request(id: Int): Long? {
        if (targetId == id || id in failedDevices) return null
        token++; targetId = id; confirmed = false
        return token
    }
    fun confirm(value: Long, actualId: Int?, now: Long = 0): Boolean {
        if (!isPending(value) || actualId != targetId) return false
        confirmed = true
        confirmedAt = now
        return true
    }
    fun isPending(value: Long) = value == token && targetId != null && !confirmed
    fun fail(value: Long): Boolean {
        if (value != token || targetId == null) return false
        failedDevices.add(targetId!!)
        release()
        return true
    }
    fun release() { token++; targetId = null; confirmed = false }
    fun recoverAfterLoss(value: Long, deviceAvailable: Boolean, telephoneCall: Boolean, now: Long): Int? {
        val id = targetId ?: return null
        if (value != token || !deviceAvailable || telephoneCall) return null
        // Some wireless headsets can connect briefly (<1s) while in the case and immediately drop again.
        // Only a route that remained confirmed for 2s starts another episode inside this window.
        // This is checked on a loss event, with no sleep, delayed retry, or periodic task.
        if (confirmed && (recoveryDevice != id || now >= recoveryUntil || now - confirmedAt >= 2_000)) {
            recoveryDevice = id
            recoveryUntil = now + 30_000
            recoveryRemaining = 3
        }
        if (recoveryDevice != id || now >= recoveryUntil || recoveryRemaining == 0) return null
        recoveryRemaining--
        return id
    }
    private fun clearRecovery() { recoveryDevice = null; recoveryUntil = 0; recoveryRemaining = 0 }
    fun reset() { release(); failedDevices.clear(); clearRecovery() }
    fun retainDevices(ids: Set<Int>) {
        failedDevices.retainAll(ids)
        if (recoveryDevice !in ids) clearRecovery()
    }
    fun devicesReconnected(ids: Set<Int>) {
        failedDevices.removeAll(ids)
        if (recoveryDevice in ids) clearRecovery()
    }
}

internal fun scoObservationFailed(state: Int, confirmed: Boolean, actualStreamsReady: Boolean) =
    state == AudioManager.SCO_AUDIO_STATE_ERROR && confirmed && !actualStreamsReady

private fun deviceTypeName(type: Int?) = when (type) {
    null -> "none"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone-mic"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-device"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb-accessory"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
    AudioDeviceInfo.TYPE_HEARING_AID -> "hearing-aid"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble-headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble-speaker"
    else -> "type-$type"
}

internal fun shouldPlayAudio(connected: Boolean, deafened: Boolean) = connected && !deafened

internal fun shouldCaptureAudio(connected: Boolean, captureRequested: Boolean) = connected && captureRequested
