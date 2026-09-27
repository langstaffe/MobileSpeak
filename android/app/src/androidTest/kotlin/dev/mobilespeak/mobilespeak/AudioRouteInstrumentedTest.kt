package dev.mobilespeak.mobilespeak

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioRouteInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test fun authorizedExistingServerKeepsCoreChannelAndAudioThreadsForOneMinute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("audioRouteServer")
        assumeTrue("An explicitly authorized existing test server is required", server != null)
        val context = instrumentation.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor { ClientSession.state.value.bookmarks.isNotEmpty() }
            assumeTrue("Do not replace an existing connection", ClientSession.state.value.snapshot.status == "disconnected")
            val bookmark = requireNotNull(ClientSession.state.value.bookmarks.firstOrNull { it.host == server }) { "No authorized existing bookmark" }
            val manager = context.getSystemService(AudioManager::class.java)
            val expectedBluetooth = args.getString("expectBluetooth") == "true"
            val before = ClientSession.state.value
            try {
                instrumentation.runOnMainSync { ClientSession.connect(bookmark) }
                waitFor { ClientSession.state.value.snapshot.status == "connected" }
                val connected = ClientSession.state.value.snapshot
                val core = ClientSession.javaClass.getDeclaredMethod("getHandle").apply { isAccessible = true }.invoke(ClientSession)
                fun ownChannel() = ClientSession.state.value.snapshot.clients.firstOrNull { member -> member.id == connected.ownClient }?.channel
                val channel = ownChannel()
                if (!before.microphoneMuted && !before.deafened && ClientSession.microphonePermission) {
                    waitFor {
                        manager.activeRecordingConfigurations.any { configuration ->
                            configuration.audioDevice?.type == if (expectedBluetooth) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else AudioDeviceInfo.TYPE_BUILTIN_MIC
                        }
                    }
                }
                waitFor { if (expectedBluetooth) manager.isBluetoothScoOn else manager.isSpeakerphoneOn }
                Log.i("MobileSpeakRouteTest", "One-minute observation started bluetooth=$expectedBluetooth; actual output is recorded by AudioEngine")
                fun observeMinute() = repeat(6) {
                    SystemClock.sleep(10_000)
                    val current = ClientSession.state.value
                    assertEquals("connected", current.snapshot.status)
                    assertEquals(connected.ownClient, current.snapshot.ownClient)
                    assertEquals(connected.serverId, current.snapshot.serverId)
                    assertEquals(channel, ownChannel())
                    assertEquals(core, ClientSession.javaClass.getDeclaredMethod("getHandle").apply { isAccessible = true }.invoke(ClientSession))
                    assertEquals(before.microphoneMuted, current.microphoneMuted)
                    assertEquals(before.deafened, current.deafened)
                    assertEquals(before.noiseSuppression, current.noiseSuppression)
                    val workers = Thread.getAllStackTraces().keys.filter { thread -> thread.isAlive && thread.name in setOf("MobileSpeakPlayback", "MobileSpeakCapture") }
                    assertEquals("Exactly one existing worker for each direction", 2, workers.size)
                    assertEquals(2, workers.map { thread -> thread.name }.toSet().size)
                }
                observeMinute()
                if (args.getString("audioRouteHotSwitches") == "true") {
                    repeat(3) { round ->
                        for (bluetooth in listOf(false, true)) {
                            Log.i("MobileSpeakRouteTest", "Hot switch round=${round + 1} action=${if (bluetooth) "connect headphones" else "disconnect headphones"}")
                            waitFor(timeoutMs = 90_000) {
                                val types = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device -> device.type }
                                (AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types) == bluetooth
                            }
                            waitFor { if (bluetooth) manager.isBluetoothScoOn else manager.isSpeakerphoneOn && !manager.isBluetoothScoOn }
                            if (!before.microphoneMuted && !before.deafened && ClientSession.microphonePermission) waitFor {
                                manager.activeRecordingConfigurations.any { configuration -> configuration.audioDevice?.type ==
                                    if (bluetooth) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else AudioDeviceInfo.TYPE_BUILTIN_MIC }
                            }
                            Log.i("MobileSpeakRouteTest", "Hot switch round=${round + 1} actualInputConfirmed bluetooth=$bluetooth; one-minute observation")
                            observeMinute()
                        }
                    }
                }
            } finally {
                instrumentation.runOnMainSync { ClientSession.disconnect() }
                waitFor { ClientSession.state.value.snapshot.status == "disconnected" }
                waitFor { Thread.getAllStackTraces().keys.none { thread -> thread.isAlive && thread.name in setOf("MobileSpeakPlayback", "MobileSpeakCapture") } }
                Log.i("MobileSpeakRouteTest", "Observation finished; audio workers released")
            }
        }
    }

    private fun waitFor(timeoutMs: Long = 35_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for actual connection/audio route", condition())
    }
}
