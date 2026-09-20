package dev.mobilespeak.mobilespeak

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeCoreTest {
    @Suppress("DEPRECATION")
    @Test
    fun legacyEngineRestoresSpeakerAndReleasesItsRoute() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        val speakerBefore = manager.isSpeakerphoneOn
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
            assertTrue(waitUntil { manager.mode == AudioManager.MODE_IN_COMMUNICATION && manager.isSpeakerphoneOn })
            assertTrue(phoneStrategyUsesSpeaker())
            assertEquals(1, workerCount("MobileSpeakPlayback"))
            assertEquals(1, workerCount("MobileSpeakCapture"))

            manager.isSpeakerphoneOn = false
            engine.update(connected, allowCapture = false, reapplyRoute = true)
            assertTrue(waitUntil { manager.isSpeakerphoneOn })
            assertTrue(phoneStrategyUsesSpeaker())
            assertTrue(connected.microphoneMuted)
            assertTrue(connected.deafened)

            engine.update(SessionUiState(), allowCapture = false)
            assertTrue(waitUntil { manager.mode == AudioManager.MODE_NORMAL && manager.isSpeakerphoneOn == speakerBefore })
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
        assertEquals(CommunicationRouteTarget.SPEAKER, communicationRouteTarget(true, builtInTypes))
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
            assertTrue(NativeCore.playback(handle, FloatArray(1920)) in 0..1920)
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

    private fun phoneStrategyUsesSpeaker(): Boolean {
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("dumpsys media.audio_policy"),
        ).bufferedReader().use { it.readText() }
        return Regex("STRATEGY_PHONE[\\s\\S]{0,400}AUDIO_DEVICE_OUT_SPEAKER").containsMatchIn(output)
    }
}
