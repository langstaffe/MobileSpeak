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

    @SuppressLint("WakelockTimeout") // The foreground call may last for hours; onDestroy always releases it.
    override fun onCreate() {
        super.onCreate()
        ClientSession.initialize(applicationContext)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> ClientSession.disconnect()
            ACTION_START_CALL -> updateAudio(ClientSession.state.value, ClientSession.appActive.value, userInitiated = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        audio.stop()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateAudio(ui: SessionUiState, appActive: Boolean, userInitiated: Boolean) {
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
                ClientSession.reportError("无法启用后台麦克风，请重新打开 MobileSpeak 后重试：${it.message}")
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
            ClientSession.reportError("后台麦克风暂不可用，请打开 MobileSpeak 自动恢复")
        }
        val reapplyAudio = returnedToForeground || userInitiated
        audio.update(ui, allowCapture, reapplyRoute = reapplyAudio)
        if (shouldReassertAudioState(ui.snapshot.status == "connected", reapplyAudio)) {
            Log.i(TAG, "Reasserting current microphone and listening state after foreground recovery")
            ClientSession.setAudio(inputMuted = ui.microphoneMuted, deafened = ui.deafened)
        }
        if (!ClientSession.shouldRunService()) stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun showForeground(microphone: Boolean) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("MobileSpeak 通话中")
            .setContentText("TeamSpeak 连接保持在后台")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开", disconnect)
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
                NotificationChannel(CHANNEL, "语音连接", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        internal const val ACTION_START_CALL = "dev.mobilespeak.mobilespeak.START_CALL"
        private const val CHANNEL = "mobilespeak.voice"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_DISCONNECT = "dev.mobilespeak.mobilespeak.DISCONNECT"
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
