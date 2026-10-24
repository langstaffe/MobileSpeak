package dev.mobilespeak.mobilespeak

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android Service/resources; only controller-to-main requests are held for deterministic ordering. */
class VoiceServiceLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val requests = HeldRequests()
    private lateinit var executor: ExecutorService
    private lateinit var state: MutableStateFlow<SessionUiState>
    private lateinit var gate: ConnectionGate
    private lateinit var savedState: SessionUiState
    private lateinit var originalHandler: Handler
    private var savedId: Any? = null
    private var savedCancelled = false
    private var savedPermission = false
    private var handle = 0L
    private val update = ClientSession::class.java.getDeclaredMethod("updateService").apply { isAccessible = true }
    private fun field(name: String) = ClientSession::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun gateField(name: String) = ConnectionGate::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun property(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
    private fun <T> core(block: () -> T): T = executor.submit<T> { block() }.get(10, TimeUnit.SECONDS)
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun actual(): VoiceService? = core { field("voiceService").get(ClientSession) as VoiceService? }

    @Before fun prepare() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        ClientSession.initialize(context)
        executor = field("coreExecutor").get(ClientSession) as ExecutorService
        handle = ClientSession::class.java.getDeclaredMethod("getHandle").apply { isAccessible = true }.invoke(ClientSession) as Long
        NativeCore.setNotifier(handle, Runnable {})
        core { }
        assertEquals("Do not interrupt an existing session", "disconnected", ClientSession.state.value.snapshot.status)
        assertNull(actual())
        @Suppress("UNCHECKED_CAST")
        val mutable = field("mutableState").get(ClientSession) as MutableStateFlow<SessionUiState>
        state = mutable
        gate = field("connectionGate").get(ClientSession) as ConnectionGate
        savedState = state.value
        savedId = gateField("id").get(gate)
        savedCancelled = gateField("cancelled").getBoolean(gate)
        savedPermission = ClientSession.microphonePermission
        originalHandler = field("serviceHandler").get(ClientSession) as Handler
        core {
            field("serviceHandler").set(ClientSession, requests)
            field("serviceRequested").setBoolean(ClientSession, false)
            field("microphonePermission").setBoolean(ClientSession, false)
            gateField("id").set(gate, null)
            gateField("cancelled").setBoolean(gate, false)
            state.value = savedState.copy(snapshot = Snapshot(), microphoneMuted = true)
        }
    }

    @After fun restore() {
        if (!::executor.isInitialized || !::originalHandler.isInitialized) return
        field("context").set(ClientSession, context.applicationContext)
        val service = actual()
        core { state.value = state.value.copy(snapshot = Snapshot()); update.invoke(ClientSession) }
        settle { actual() == null && !serviceExists() }
        service?.let(::assertReleased)
        core {
            gateField("id").set(gate, savedId)
            gateField("cancelled").setBoolean(gate, savedCancelled)
            field("microphonePermission").setBoolean(ClientSession, savedPermission)
            field("serviceRequested").setBoolean(ClientSession, false)
            field("serviceHandler").set(ClientSession, originalHandler)
            state.value = savedState
        }
        val schedule = ClientSession::class.java.getDeclaredMethod("scheduleCorePoll").apply { isAccessible = true }
        NativeCore.setNotifier(handle, Runnable { schedule.invoke(ClientSession) })
    }

    private fun session(status: String, id: String = "service-test", reconcile: Boolean = true) = core {
        gate.begin(id)
        state.value = state.value.copy(snapshot = Snapshot(status = status, connectionId = id), microphoneMuted = true)
        if (reconcile) update.invoke(ClientSession)
    }
    private fun startExternally(action: String? = null): VoiceService {
        main { context.startForegroundService(Intent(context, VoiceService::class.java).setAction(action)) }
        await { actual() != null }
        return requireNotNull(actual()).also { service ->
            main {
                assertTrue((property(service, "wakeLock") as PowerManager.WakeLock).isHeld)
                assertTrue((property(property(service, "audio")!!, "running") as AtomicBoolean).get())
            }
        }
    }
    private fun running(status: String = "connecting", id: String = "service-test"): VoiceService {
        session(status, id)
        settle { actual() != null && serviceExists() }
        return requireNotNull(actual())
    }
    private fun terminateByEnvelope(id: String = "service-test") = core {
        val apply = ClientSession::class.java.getDeclaredMethod("applyEnvelope", JSONObject::class.java).apply { isAccessible = true }
        apply.invoke(ClientSession, JSONObject("""{"snapshot":{"status":"disconnected","connectionId":"$id","channels":[],"clients":[]},
            "events":[{"type":"error","connectionId":"$id","code":"core_error","detail":"controlled failure"}],
            "chats":[],"unread":{"serverId":null,"channel":null,"channelCount":0,"privateCounts":{}}}"""))
    }

    @Test fun coldNullIntentAndUnrecordedCreationReleaseActualResources() {
        val service = startExternally()
        assertFalse(core { field("serviceRequested").getBoolean(ClientSession) })
        main { assertEquals(Service.START_STICKY, service.onStartCommand(null, Service.START_FLAG_RETRY, 2)) }
        settle { actual() == null && !serviceExists() }
        assertReleased(service)
    }

    @Test fun coldNotificationDisconnectAndLanguageRefreshCannotLeaveAnIdleService() {
        for (action in listOf("dev.mobilespeak.mobilespeak.DISCONNECT", VoiceService.ACTION_REFRESH_LANGUAGE, VoiceService.ACTION_START_CALL)) {
            val service = startExternally(action)
            settle { actual() == null && !serviceExists() }
            assertReleased(service)
        }
        ClientSession.refreshLanguage()
        drainRequests()
        assertFalse(serviceExists())
    }

    @Test fun allLiveStatesRetainOneInstanceAndNormalDisconnectReleasesAudio() {
        val service = running()
        val audio = property(service, "audio")!!
        for (status in listOf("connecting", "connected", "reconnecting")) {
            session(status)
            main {
                service.onStartCommand(null, 0, 3)
                service.onStartCommand(Intent().setAction(VoiceService.ACTION_START_CALL), 0, 4)
                service.onStartCommand(Intent().setAction(VoiceService.ACTION_REFRESH_LANGUAGE), 0, 5)
            }
            ClientSession.refreshLanguage()
            drainRequests()
            assertSame(service, actual())
            assertTrue(serviceExists())
            assertSame(audio, property(service, "audio"))
            assertEquals(2, audioWorkers().size)
            if (status == "connected") await { (property(audio, "outputHandle") as Long) != 0L }
        }
        ClientSession.disconnect()
        settle { actual() == null && !serviceExists() }
        assertReleased(service)
    }

    @Test fun languageSwitchUpdatesLiveNotificationWithoutReplacingServiceOrAudio() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val original = AppCompatDelegate.getApplicationLocales()
            try {
                main { AppLanguage.select(AppLanguage.ENGLISH) }
                assertEquals(AppLanguage.ENGLISH, AppLanguage.selected())
                val service = running()
                val audio = property(service, "audio")!!
                val connection = ClientSession.state.value.snapshot.connectionId
                val before = ClientSession.state.value
                val foregroundMicrophone = property(service, "foregroundHasMicrophone")
                val notifications = context.getSystemService(NotificationManager::class.java)
                data class Expected(val language: AppLanguage, val title: String, val body: String, val disconnect: String)
                for ((language, title, body, disconnect) in listOf(
                    Expected(AppLanguage.ENGLISH, "MobileSpeak Call", "TeamSpeak remains connected in the background", "Disconnect"),
                    Expected(AppLanguage.SIMPLIFIED_CHINESE, "MobileSpeak 通话中", "TeamSpeak 连接保持在后台", "断开连接"),
                    Expected(AppLanguage.TRADITIONAL_CHINESE, "MobileSpeak 通話中", "TeamSpeak 連線會在背景保持運作", "中斷連線"),
                )) {
                    main { AppLanguage.select(language) }
                    drainRequests()
                    try {
                        await { notifications.activeNotifications.firstOrNull { it.id == 7 }?.notification?.extras
                            ?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title }
                    } catch (error: IllegalStateException) {
                        val current = notifications.activeNotifications.map { it.id to it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) }
                        throw AssertionError("$language wanted $title, selected=${AppLanguage.selected()}, notifications=$current", error)
                    }
                    val notice = notifications.activeNotifications.single { it.id == 7 }.notification
                    assertEquals(title, notice.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
                    assertEquals(body, notice.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                    assertEquals(disconnect, notice.actions.single().title.toString())
                    assertSame(service, actual())
                    assertSame(audio, property(service, "audio"))
                    assertTrue((property(audio, "running") as AtomicBoolean).get())
                    assertEquals(foregroundMicrophone, property(service, "foregroundHasMicrophone"))
                    assertEquals(connection, ClientSession.state.value.snapshot.connectionId)
                    assertEquals(before.snapshot.status, ClientSession.state.value.snapshot.status)
                    assertEquals(before.microphoneMuted, ClientSession.state.value.microphoneMuted)
                    assertEquals(before.deafened, ClientSession.state.value.deafened)
                    assertEquals(before.noiseSuppression, ClientSession.state.value.noiseSuppression)
                    assertEquals(before.audioProcessingStatus, ClientSession.state.value.audioProcessingStatus)
                }
            } finally {
                main { AppCompatDelegate.setApplicationLocales(original) }
                ClientSession.refreshLanguage()
                drainRequests()
            }
        }
    }

    @Test fun creationBeforeAStartRequestIsRecordedRetainsAnActiveSession() {
        session("connecting", reconcile = false)
        val service = startExternally()
        drainRequests()
        assertSame(service, actual())
        assertTrue(serviceExists())
        terminateByEnvelope()
        settle { actual() == null && !serviceExists() }
        assertReleased(service)
    }

    @Test fun delayedStopAndOldNotificationCannotStopTheNextConnection() {
        val service = running(id = "old")
        val notifications = context.getSystemService(NotificationManager::class.java)
        await { notifications.activeNotifications.any { it.id == 7 } }
        val oldDisconnect = notifications.activeNotifications.single { it.id == 7 }.notification.actions.single().actionIntent
        session("disconnected", "old") // Stop is held on the controller's main handler.
        session("connecting", "next")
        drainRequests()
        assertSame(service, actual())
        main { service.onStartCommand(Intent().setAction("dev.mobilespeak.mobilespeak.DISCONNECT").putExtra("connectionId", "old"), 0, 6) }
        drainRequests()
        assertEquals("connecting", ClientSession.state.value.snapshot.status)
        assertTrue(serviceExists())
        // The reused instance's notification now points at the new connection.
        await { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 7 } }
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 7 }
        assertNotEquals("Each connection must own its notification action", oldDisconnect, notification.notification.actions.single().actionIntent)
        oldDisconnect.send()
        drainRequests()
        assertEquals("connecting", ClientSession.state.value.snapshot.status)
        notification.notification.actions.single().actionIntent.send()
        settle { actual() == null && !serviceExists() }
        assertReleased(service)
    }

    @Test fun acceptedStopThenNewConnectionRestartsAndLateOldDestroyDoesNotClearTheNewInstance() {
        val old = running(id = "old")
        session("disconnected", "old")
        val stop = requests.pending.poll(10, TimeUnit.SECONDS) ?: error("Missing stop request")
        main {
            stop.run() // Android has accepted the stop, but cannot run onDestroy until main returns.
            session("connecting", "next")
        }
        settle { actual()?.let { it !== old } == true && serviceExists() }
        val next = requireNotNull(actual())
        assertResourcesReleased(old)
        ClientSession.voiceServiceDestroyed(old) // Deliver the previous instance's callback again, after new creation.
        ClientSession.voiceServiceFailed(old, "old", IllegalStateException("late old instance failure"))
        drainRequests()
        assertSame(next, actual())
        assertTrue(serviceExists())
        terminateByEnvelope("next")
        settle { actual() == null && !serviceExists() }
        assertReleased(next)
    }

    @Test fun failedCreatedInstanceReleasesResourcesAndDisconnectsOnlyItsConnection() {
        val service = running()
        main {
            // Exercise the exact failure cleanup on a real, fully allocated Service.
            VoiceService::class.java.getDeclaredMethod("releaseResources").apply { isAccessible = true }.invoke(service)
            ClientSession.voiceServiceFailed(service, "service-test", IllegalStateException("controlled initialization failure"))
            service.stopSelf()
        }
        settle { actual() == null && !serviceExists() }
        assertEquals("disconnected", ClientSession.state.value.snapshot.status)
        assertTrue(ClientSession.state.value.error.orEmpty().contains("controlled initialization failure"))
        assertReleased(service)
    }

    @Test fun lateStartFailureDoesNotDisconnectANewerRequest() {
        val failureContext = object : ContextWrapper(context.applicationContext) {
            override fun startForegroundService(service: Intent): android.content.ComponentName? {
                // Hold the old main-thread operation while the executor accepts a new connection.
                core { field("context").set(ClientSession, context.applicationContext) }
                session("disconnected", "old")
                session("connecting", "next")
                throw IllegalStateException("late start failure")
            }
        }
        core { field("context").set(ClientSession, failureContext) }
        session("connecting", "old")
        settle { actual() != null && serviceExists() }
        assertEquals("next", ClientSession.state.value.snapshot.connectionId)
        assertEquals("connecting", ClientSession.state.value.snapshot.status)
        assertFalse(ClientSession.state.value.error.orEmpty().contains("late start failure"))
    }

    @Test fun startRequestFailureDisconnectsAndLeavesNoServiceOrResources() {
        val failureContext = object : ContextWrapper(context.applicationContext) {
            override fun startForegroundService(service: Intent): android.content.ComponentName? = throw IllegalStateException("controlled service start failure")
        }
        core { field("context").set(ClientSession, failureContext) }
        session("connecting")
        settle { ClientSession.state.value.snapshot.status == "disconnected" }
        assertTrue(ClientSession.state.value.error.orEmpty().contains("controlled service start failure"))
        assertNull(actual())
        assertFalse(serviceExists())
        assertTrue(audioWorkers().isEmpty())
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 7 })
        assertFalse(core { field("serviceRequested").getBoolean(ClientSession) })
    }

    private fun assertResourcesReleased(service: VoiceService) = main {
        assertTrue(property(service, "released") as Boolean)
        assertFalse((property(service, "wakeLock") as PowerManager.WakeLock).isHeld)
        val audio = property(service, "audio")!!
        assertFalse((property(audio, "running") as AtomicBoolean).get())
        assertNull(property(audio, "playbackThread"))
        assertNull(property(audio, "captureThread"))
        assertNull(property(audio, "record"))
        assertNull(property(audio, "communicationDeviceListener"))
        assertEquals(0L, property(audio, "outputHandle"))
    }
    private fun assertReleased(service: VoiceService) {
        assertResourcesReleased(service)
        await { !serviceExists() && context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 7 } }
        assertTrue(audioWorkers().isEmpty())
    }
    private fun audioWorkers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name in setOf("MobileSpeakPlayback", "MobileSpeakCapture") }
    @Suppress("DEPRECATION")
    private fun serviceExists() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any { it.service.className == VoiceService::class.java.name }
    private fun drainRequests() {
        core { }
        while (true) {
            val request = requests.pending.poll() ?: break
            main { request.run() }
            instrumentation.waitForIdleSync()
            core { }
        }
        instrumentation.waitForIdleSync()
        core { }
    }
    private fun settle(condition: () -> Boolean) = await { drainRequests(); condition() }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for service/resource callback" }
            instrumentation.waitForIdleSync()
            core { } // Executor and main-loop barriers, not a guessed sleep interval.
            Thread.yield()
        }
    }
    private class HeldRequests : Handler(Looper.getMainLooper()) {
        val pending = LinkedBlockingQueue<Runnable>()
        override fun sendMessageAtTime(msg: Message, uptimeMillis: Long): Boolean {
            pending.put(requireNotNull(msg.callback))
            return true
        }
    }
}
