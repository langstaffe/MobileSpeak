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

internal class VoiceService : Service() {
    private var running = false
    private var worker: Thread? = null
    private var foregroundHasMicrophone = false
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
        running = true
        worker = Thread(::runLoop, "MobileSpeakAudio").also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) ClientSession.disconnect()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker?.takeIf { it !== Thread.currentThread() }?.join(1_000)
        audio.stop()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runLoop() {
        while (running) {
            ClientSession.pollCore()
            val ui = ClientSession.state.value
            val wantsMicrophone = ClientSession.appActive && ClientSession.microphonePermission &&
                !ui.microphoneMuted && !ui.deafened && ui.snapshot.status == "connected"
            if (wantsMicrophone && !foregroundHasMicrophone) {
                runCatching { showForeground(true) }.onFailure {
                    ClientSession.reportError("无法启动后台麦克风：${it.message}")
                    ClientSession.setAudio(inputMuted = true)
                }
            }
            runCatching { audio.tick(ui, foregroundHasMicrophone) }.onFailure {
                ClientSession.reportError("音频设备错误：${it.message}")
                ClientSession.setAudio(inputMuted = true)
                audio.stop()
                Thread.sleep(100)
            }
            if (!ClientSession.shouldRunService()) break
        }
        running = false
        audio.stop()
        stopSelf()
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
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "语音连接", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private companion object {
        const val CHANNEL = "mobilespeak.voice"
        const val NOTIFICATION_ID = 7
        const val ACTION_DISCONNECT = "dev.mobilespeak.mobilespeak.DISCONNECT"
    }
}
