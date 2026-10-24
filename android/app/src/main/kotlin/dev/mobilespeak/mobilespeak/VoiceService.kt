package dev.mobilespeak.mobilespeak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class VoiceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundHasMicrophone = false
    private var microphoneUpgradeFailed = false
    private var microphoneRecoveryReported = false
    private var lastAppActive = false
    private var lastAudioPolicy: String? = null
    private lateinit var audio: AudioEngine
    private lateinit var wakeLock: PowerManager.WakeLock
    private var released = false // Main-thread lifecycle, including partially initialized failures.
    private var notificationConnectionId: String? = null

    @SuppressLint("WakelockTimeout") // The foreground call may last for hours; onDestroy always releases it.
    override fun onCreate() {
        super.onCreate()
        ClientSession.initialize(applicationContext)
        val connectionId = ClientSession.state.value.snapshot.connectionId
        ClientSession.voiceServiceCreated(this)
        Log.i(TAG, "Service created instance=${System.identityHashCode(this)}")
        try {
            audio = AudioEngine(applicationContext)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobileSpeak:Voice")
                .apply { acquire() }
            createChannel()
            showForeground(false)
            audio.start()
            serviceScope.launch {
                ClientSession.state.combine(ClientSession.appActive) { ui, active -> ui to active }
                    .collect { (ui, active) -> updateAudio(ui, active, userInitiated = false) }
            }
        } catch (error: Exception) {
            try { releaseResources() } finally {
                ClientSession.voiceServiceFailed(this, connectionId, error)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service start instance=${System.identityHashCode(this)} action=${intent?.action} startId=$startId")
        ClientSession.voiceServiceStarted(this, intent?.action == ACTION_DISCONNECT,
            if (intent?.hasExtra(EXTRA_CONNECTION_ID) == true) intent.getStringExtra(EXTRA_CONNECTION_ID)
            else ClientSession.state.value.snapshot.connectionId)
        if (released) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START_CALL -> startCall()
            ACTION_REFRESH_LANGUAGE -> refreshLanguage()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { releaseResources() } finally {
            ClientSession.voiceServiceDestroyed(this)
            Log.i(TAG, "Service destroyed instance=${System.identityHashCode(this)}")
            super.onDestroy()
        }
    }

    private fun releaseResources() {
        if (released) return
        released = true
        serviceScope.cancel()
        try { if (::audio.isInitialized) audio.stop() } finally {
            try { if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release() }
            finally { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateAudio(ui: SessionUiState, appActive: Boolean, userInitiated: Boolean) {
        if (released) return
        if (notificationConnectionId != ClientSession.state.value.snapshot.connectionId) showForeground(foregroundHasMicrophone)
        val returnedToForeground = appActive && !lastAppActive
        if (returnedToForeground) {
            microphoneUpgradeFailed = false
            microphoneRecoveryReported = false
        }
        lastAppActive = appActive
        val microphoneWanted = wantsForegroundMicrophone(
            ClientSession.shouldRunService(), ClientSession.microphonePermission,
            ui.microphoneMuted, ui.deafened,
        )
        if (!microphoneWanted) {
            microphoneUpgradeFailed = false
            microphoneRecoveryReported = false
        }
        if (microphoneWanted && !foregroundHasMicrophone &&
            (appActive || userInitiated) && !microphoneUpgradeFailed
        ) {
            runCatching { showForeground(true) }.onFailure {
                microphoneUpgradeFailed = true
                ClientSession.reportError(localizedFailureSource(R.string.error_background_microphone_enable, it))
            }
        }
        val allowCapture = allowsMicrophoneCapture(
            ui.snapshot.status == "connected", ClientSession.microphonePermission,
            ui.microphoneMuted, ui.deafened, foregroundHasMicrophone,
        )
        val audioPolicy = "connected=${ui.snapshot.status == "connected"} permission=${ClientSession.microphonePermission} " +
            "micOn=${!ui.microphoneMuted} listening=${!ui.deafened} foregroundMic=$foregroundHasMicrophone " +
            "appActive=$appActive capture=$allowCapture"
        if (lastAudioPolicy != audioPolicy) {
            Log.i(TAG, "Audio policy $audioPolicy")
            lastAudioPolicy = audioPolicy
        }
        if (ui.snapshot.status == "connected" && microphoneWanted &&
            !foregroundHasMicrophone && !appActive && !microphoneRecoveryReported
        ) {
            microphoneRecoveryReported = true
            ClientSession.reportError(LocalizedMessage(R.string.error_background_microphone_recovery))
        }
        val reapplyAudio = returnedToForeground || userInitiated
        audio.update(ui, allowCapture, reapplyRoute = reapplyAudio)
        if (shouldReassertAudioState(ui.snapshot.status == "connected", reapplyAudio)) {
            Log.i(TAG, "Reasserting current microphone and listening state after foreground recovery")
            ClientSession.setAudio()
        }
        // ClientSession serializes service stop/start with connection transitions.
    }

    fun refreshLanguage() {
        if (released) return
        createChannel()
        showForeground(foregroundHasMicrophone)
    }

    fun startCall() {
        updateAudio(ClientSession.state.value, ClientSession.appActive.value, userInitiated = true)
    }

    @Suppress("DEPRECATION")
    private fun showForeground(microphone: Boolean) {
        notificationConnectionId = ClientSession.state.value.snapshot.connectionId
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_DISCONNECT)
                .setData(android.net.Uri.fromParts("mobilespeak", notificationConnectionId ?: "idle", null))
                .putExtra(EXTRA_CONNECTION_ID, notificationConnectionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(localized(R.string.notification_call_title))
            .setContentText(localized(R.string.notification_call_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, localized(R.string.action_disconnect), disconnect)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (microphone && Build.VERSION.SDK_INT >= 30) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundHasMicrophone = microphone
        Log.i(TAG, "Foreground service microphone=$microphone")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, localized(R.string.notification_channel_voice), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        internal const val ACTION_START_CALL = "dev.mobilespeak.mobilespeak.START_CALL"
        internal const val ACTION_REFRESH_LANGUAGE = "dev.mobilespeak.mobilespeak.REFRESH_LANGUAGE"
        private const val CHANNEL = "mobilespeak.voice"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_DISCONNECT = "dev.mobilespeak.mobilespeak.DISCONNECT"
        private const val EXTRA_CONNECTION_ID = "connectionId"
        private const val TAG = "MobileSpeakVoice"
    }
}

internal fun wantsForegroundMicrophone(
    sessionRunning: Boolean,
    permissionGranted: Boolean,
    microphoneMuted: Boolean,
    deafened: Boolean,
) = sessionRunning && permissionGranted && !microphoneMuted && !deafened

internal fun allowsMicrophoneCapture(
    connected: Boolean,
    permissionGranted: Boolean,
    microphoneMuted: Boolean,
    deafened: Boolean,
    foregroundHasMicrophone: Boolean,
) = connected && permissionGranted && !microphoneMuted && !deafened && foregroundHasMicrophone

internal fun shouldReassertAudioState(connected: Boolean, reapplyRequested: Boolean) = connected && reapplyRequested
