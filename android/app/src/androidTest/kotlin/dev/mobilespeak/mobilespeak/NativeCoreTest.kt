package dev.mobilespeak.mobilespeak

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeCoreTest {
    @Suppress("DEPRECATION")
    @androidx.test.filters.SdkSuppress(maxSdkVersion = 30)
    @Test
    fun legacyPhoneKeepsMediaModeWithoutOwningSpeakerRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        assumeTrue("Phone speaker test requires no external headset", manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .none { communicationDevicePriority(it.type) < communicationDevicePriority(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) })
        val speakerBefore = manager.isSpeakerphoneOn
        assumeTrue("Requires no other ongoing call", manager.mode == AudioManager.MODE_NORMAL)
        val engine = AudioEngine(context)
        val connected = SessionUiState(
            snapshot = Snapshot(status = "connected"),
            microphoneMuted = true,
            deafened = true,
        )
        try {
            engine.start()
            engine.start()
            engine.update(connected, allowCapture = false, reapplyRoute = true)
            assertTrue(waitUntil { manager.mode == AudioManager.MODE_NORMAL })
            assertEquals(speakerBefore, manager.isSpeakerphoneOn)
            assertEquals(1, workerCount("MobileSpeakPlayback"))
            assertEquals(1, workerCount("MobileSpeakCapture"))

            // A slow HAL create/release must not prevent SCO/device callbacks from updating the route.
            val streams = checkNotNull(AudioEngine::class.java.getDeclaredField("streamLock").apply { isAccessible = true }.get(engine))
            val locked = CountDownLatch(1)
            val unlock = CountDownLatch(1)
            val routeUpdated = CountDownLatch(1)
            val slowHal = Thread {
                synchronized(streams) { locked.countDown(); unlock.await(2, TimeUnit.SECONDS) }
            }.apply { start() }
            val route = Thread {
                engine.update(connected, allowCapture = false, reapplyRoute = true)
                routeUpdated.countDown()
            }
            try {
                assertTrue(locked.await(1, TimeUnit.SECONDS))
                route.start()
                assertTrue("Route policy must proceed while the stream lock is held", routeUpdated.await(1, TimeUnit.SECONDS))
            } finally {
                unlock.countDown()
                slowHal.join(2_000)
                route.join(2_000)
            }

            engine.update(connected, allowCapture = false, reapplyRoute = true)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
            assertEquals(speakerBefore, manager.isSpeakerphoneOn)
            assertTrue(connected.microphoneMuted)
            assertTrue(connected.deafened)

            engine.update(SessionUiState(), allowCapture = false)
            assertTrue(waitUntil { manager.mode == AudioManager.MODE_NORMAL })
            assertEquals(speakerBefore, manager.isSpeakerphoneOn)
        } finally {
            engine.stop()
        }
        assertTrue(waitUntil { workerCount("MobileSpeakPlayback") == 0 && workerCount("MobileSpeakCapture") == 0 })
    }

    @Test
    fun physicalDeviceExposesBuiltInSpeakerForCommunicationPolicy() {
        val manager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(AudioManager::class.java)
        val builtInTypes = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.type }
            .filterTo(mutableSetOf()) {
                it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE || it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        assertTrue(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in builtInTypes)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, preferredCommunicationDevice(true, builtInTypes))
    }

    @Test fun oboeOutputStartsAndReleasesOnReconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AudioEngine(context)
        val field = AudioEngine::class.java.getDeclaredField("outputChannels").apply { isAccessible = true }
        val policy = SessionUiState(snapshot = Snapshot(status = "connected"), microphoneMuted = true)
        try {
            engine.start()
            repeat(2) {
                engine.update(policy, allowCapture = false)
                // Published only after NativeOutput.open successfully starts Oboe.
                assertTrue(waitUntil { field.getInt(engine) in 1..2 })
                engine.update(SessionUiState(), allowCapture = false)
                // closeOutput clears this only after the native close has returned.
                assertTrue(waitUntil { field.getInt(engine) == 0 })
            }
        } finally { engine.stop() }
        assertTrue(waitUntil { workerCount("MobileSpeakPlayback") == 0 && workerCount("MobileSpeakCapture") == 0 })
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 31)
    @Test fun modernPhoneKeepsMediaModeWithoutSelectingCommunicationDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        assumeTrue("Phone route test requires no external communication device", manager.availableCommunicationDevices
            .none { communicationDevicePriority(it.type) < communicationDevicePriority(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) })
        assumeTrue("Requires no other ongoing call", manager.mode == AudioManager.MODE_NORMAL)
        val deviceBefore = manager.communicationDevice?.id
        val engine = AudioEngine(context)
        try {
            engine.start()
            val policy = SessionUiState(snapshot = Snapshot(status = "connected"), microphoneMuted = true, deafened = true)
            engine.update(policy, allowCapture = false)
            assertTrue(waitUntil { manager.mode == AudioManager.MODE_NORMAL })
            assertEquals(deviceBefore, manager.communicationDevice?.id)
            repeat(3) { engine.update(policy, allowCapture = false, reapplyRoute = true) }
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
            assertEquals(deviceBefore, manager.communicationDevice?.id)
            assertEquals(1, workerCount("MobileSpeakPlayback"))
            assertEquals(1, workerCount("MobileSpeakCapture"))
            engine.update(SessionUiState(), allowCapture = false)
            assertTrue(waitUntil { manager.mode == AudioManager.MODE_NORMAL })
        } finally { engine.stop() }
        assertTrue(waitUntil { workerCount("MobileSpeakPlayback") == 0 && workerCount("MobileSpeakCapture") == 0 })
    }

    @Test
    fun jniStartsAndMovesUtf8AndExactAudioFrames() {
        val handle = NativeCore.create()
        assertTrue(handle != 0L)
        try {
            val initial = JSONObject(String(NativeCore.poll(handle), Charsets.UTF_8))
            val snapshot = initial.getJSONObject("snapshot")
            assertEquals("disconnected", snapshot.getString("status"))
            assertTrue(snapshot.getJSONArray("channels").length() == 0)
            assertTrue(snapshot.getJSONArray("clients").length() == 0)
            assertTrue(initial.getJSONArray("events").length() == 0)
            assertTrue(initial.isNull("chats"))
            val notified = CountDownLatch(1)
            NativeCore.setNotifier(handle, Runnable { notified.countDown() })
            assertEquals(-1, NativeCore.command(handle, "not json".toByteArray()))
            assertTrue(notified.await(1, TimeUnit.SECONDS))
            val command = """{"type":"connect","address":"127.0.0.1:1","name":"测试😀","password":"","identity":null}"""
            assertEquals(0, NativeCore.command(handle, command.toByteArray(Charsets.UTF_8)))
            assertEquals(0, NativeCore.capture(handle, ShortArray(960)))
            NativeCore.command(handle, """{"type":"disconnect"}""".toByteArray())
        } finally {
            NativeCore.destroy(handle)
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(100) {
            if (condition()) return true
            SystemClock.sleep(20)
        }
        return false
    }

    private fun workerCount(name: String) = Thread.getAllStackTraces().keys.count { it.name == name && it.isAlive }

}
