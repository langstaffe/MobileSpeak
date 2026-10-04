package dev.mobilespeak.mobilespeak

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NetworkQualityTest {
    @Test fun actualDrawerKeepsHeaderVisibleAndShowsNetworkOnlyWhenOpened() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val quality = NetworkQuality(rttMs = 96.0, deviationMs = 35.0, packetLossPercent = 0.8,
            rttGrade = NetworkGrade.GOOD, deviationGrade = NetworkGrade.GOOD, packetLossGrade = NetworkGrade.GOOD,
            iconGrade = NetworkGrade.GOOD, axisMaxMs = 200.0, nowSecond = 29,
            samples = (0L..29L).map { NetworkSample(it, 96.0, NetworkGrade.GOOD) })
        val ui = SessionUiState(snapshot = Snapshot(status = "connected", ownClient = 1,
            channels = listOf(Channel(1, 0, 0, "Default Channel", false, true, "fixture", null)),
            clients = listOf(Member(1, 1, "fixture", "Fixture", null, emptyList(), emptyList(), null, false, false, false)),
            networkQuality = quality))
        var density = 1f
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                density = activity.resources.displayMetrics.density
                activity.setContent {
                    var expanded by remember { mutableStateOf(false) }
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().background(Palette.background)) {
                            VoiceDrawer(ui, {}, expanded, { expanded = it }, 520.dp, 320.dp, Modifier.align(Alignment.BottomCenter).width(320.dp))
                        }
                    }
                }
            }
            fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
                val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    if (node.isVisibleToUser) result += node
                    for (i in 0 until node.childCount) collect(node.getChild(i))
                }
                collect(instrumentation.uiAutomation.rootInActiveWindow)
                return result
            }
            fun label(node: android.view.accessibility.AccessibilityNodeInfo) = node.contentDescription?.toString() ?: node.text?.toString().orEmpty()
            fun bounds(node: android.view.accessibility.AccessibilityNodeInfo) = android.graphics.Rect().also(node::getBoundsInScreen)
            fun check(expanded: Boolean) {
                val all = nodes()
                val handleLabel = context.localized(if (expanded) R.string.voice_drawer_collapse else R.string.voice_drawer_expand)
                val handle = requireNotNull(all.firstOrNull { label(it) == handleLabel })
                val top = bounds(handle).top
                for (title in listOf(context.localized(R.string.status_connected_to_channel, "Default Channel"),
                    context.localized(if (ui.microphoneMuted) R.string.voice_enable_microphone else R.string.voice_mute),
                    context.localized(if (ui.deafened) R.string.voice_enable_listening else R.string.voice_disable_listening))) {
                    val frame = bounds(requireNotNull(all.firstOrNull { label(it) == title }))
                    assertTrue(title, frame.top >= top - density)
                    assertTrue(title, frame.bottom <= top + 65 * density)
                }
                val chart = all.firstOrNull { label(it).startsWith(context.localized(R.string.network_chart)) }
                if (expanded) {
                    val chartBounds = bounds(requireNotNull(chart))
                    assertTrue(chartBounds.top >= top + 63 * density)
                    val numbers = requireNotNull(all.firstOrNull { label(it).contains("96") && label(it).contains("35.0") })
                    val numberBounds = bounds(numbers)
                    assertEquals(numberBounds.top, chartBounds.top)
                    assertEquals(numberBounds.bottom, chartBounds.bottom)
                    assertEquals(123 * density, (chartBounds.left - numberBounds.left).toFloat(), 1f)
                } else assertNull(chart)
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.cacheDir, "drawer-network-$expanded.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(handle.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            }
            instrumentation.waitForIdleSync()
            check(false)
            android.os.SystemClock.sleep(1000); instrumentation.waitForIdleSync()
            check(true)
            android.os.SystemClock.sleep(1000); instrumentation.waitForIdleSync()
            check(false)
        }
    }
    @Test fun bridgePreservesNullsGradesAndMissingSeconds() {
        val snapshot = JSONObject("""{"status":"connected","channels":[],"clients":[],"networkQuality":{"rttMs":42.5,"deviationMs":6.05,"packetLossPercent":4.0,"rttGrade":"good","deviationGrade":"good","packetLossGrade":"poor","iconGrade":"poor","axisMaxMs":2000,"nowSecond":31,"samples":[{"second":29,"rttMs":1234,"grade":"poor"},{"second":31,"rttMs":42.5,"grade":"good"}]}}""").snapshot()
        val q = requireNotNull(snapshot.networkQuality)
        assertEquals("43", q.latencyText); assertEquals("6.1", q.deviationText); assertEquals("4.0", q.lossText)
        assertEquals(NetworkGrade.POOR, q.iconGrade); assertEquals(NetworkGrade.GOOD, q.rttGrade)
        assertEquals(listOf(29L, 31L), q.samples.map { it.second }); assertEquals(2000.0, q.axisMaxMs, 0.0)
        assertNull(JSONObject("""{"status":"disconnected","channels":[],"clients":[],"networkQuality":null}""").snapshot().networkQuality)
    }

    @Test fun nativePanelRendersLongValuesAtNarrowWidthAndLargeFont() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (scale in listOf(1f, 2f)) {
                scenario.onActivity { activity ->
                    activity.setContent {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                            MaterialTheme {
                                NetworkQualityPanel(NetworkQuality(rttMs = 1234.0, deviationMs = 1234.5, packetLossPercent = 4.0,
                                    rttGrade = NetworkGrade.POOR, deviationGrade = NetworkGrade.POOR, packetLossGrade = NetworkGrade.POOR,
                                    iconGrade = NetworkGrade.POOR, axisMaxMs = 2000.0, nowSecond = 29,
                                    samples = (0L..29L).filter { it != 24L }.map { NetworkSample(it, if (it == 20L) 1234.0 else 42.0, if (it == 20L) NetworkGrade.POOR else NetworkGrade.GOOD) }), Modifier.width(320.dp))
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(instrumentation.targetContext.cacheDir, "network-panel-$scale.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                val descriptions = mutableListOf<String>()
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    node.contentDescription?.toString()?.let(descriptions::add)
                    for (i in 0 until node.childCount) collect(node.getChild(i))
                }
                collect(instrumentation.uiAutomation.rootInActiveWindow)
                assertTrue(descriptions.toString(), descriptions.any { it.contains("999") && it.contains("999.9") })
                assertTrue(descriptions.toString(), descriptions.any { it.contains("2000") && it.contains("29") })
            }
        }
    }
}
