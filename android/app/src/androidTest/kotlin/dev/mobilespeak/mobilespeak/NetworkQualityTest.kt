package dev.mobilespeak.mobilespeak

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NetworkQualityTest {
    @Test fun actualDrawerRevealsNaturalToolLayoutDuringDragAndAnimation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        ClientSession.initialize(context)
        org.junit.Assume.assumeTrue("Use an offline profile", !ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")
        val density = context.resources.displayMetrics.density
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("zh-Hans")) }
        val localized = context.createConfigurationContext(configuration)
        val quality = NetworkQuality(rttMs = 34.0, deviationMs = 0.7, packetLossPercent = 0.0,
            rttGrade = NetworkGrade.GOOD, deviationGrade = NetworkGrade.GOOD, packetLossGrade = NetworkGrade.GOOD,
            iconGrade = NetworkGrade.GOOD, axisMaxMs = 50.0, nowSecond = 29,
            samples = (0L..29L).map { NetworkSample(it, 34.0, NetworkGrade.GOOD) })
        var ui by mutableStateOf(SessionUiState(snapshot = Snapshot(status = "connected", ownClient = 1,
            channels = listOf(Channel(1, 0, 0, "Default Channel", false, true, "fixture", null)),
            clients = listOf(Member(1, 1, "fixture", "Fixture", null, emptyList(), emptyList(), null, false, false, false)),
            networkQuality = quality), microphoneMuted = false))
        var expanded by mutableStateOf(true)
        var font by mutableStateOf(1f)
        var available by mutableStateOf(520.dp)
        var width by mutableStateOf(320.dp)
        var navigationClicks = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { current ->
                activity = current
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized,
                        LocalConfiguration provides configuration, LocalResources provides localized.resources,
                        LocalContentColor provides Palette.text) {
                        MaterialTheme(colorScheme = darkColorScheme(background = Palette.background, surface = Palette.card,
                            primary = Palette.accent, onSurface = Palette.text)) {
                            ProvideTextStyle(TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontFamily = FontFamily.SansSerif)) {
                                Box(Modifier.size(width, available + 72.dp).background(Palette.background).semantics { testTagsAsResourceId = true }) {
                                    Button(onClick = { navigationClicks++ }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp).testTag("fixture-navigation")) { Text("Navigation") }
                                    Box(Modifier.fillMaxWidth().height(available)) {
                                        VoiceDrawer(ui, {}, expanded, { expanded = it }, available, width, Modifier.align(Alignment.BottomCenter))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            fun <T> onMain(block: () -> T): T {
                var value: T? = null
                instrumentation.runOnMainSync { value = block() }
                @Suppress("UNCHECKED_CAST") return value as T
            }
            fun settle(ms: Long = 600) { android.os.SystemClock.sleep(ms); instrumentation.runOnMainSync { } }
            fun nodes(): List<SemanticsNode> {
                fun root(view: android.view.View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? android.view.ViewGroup)?.let { group ->
                    (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) }
                }
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                return collect(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode)
            }
            fun tagged(tag: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            val labels = listOf(R.string.files_tools, R.string.files_title, R.string.files_subtitle).map(localized::getString)
            fun parts() = listOf(tagged("channel-files-tool"), tagged("channel-files-tool-icon")) + labels.map { label ->
                nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == label }
            }
            fun accessibility(): List<android.view.accessibility.AccessibilityNodeInfo> {
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo?): List<android.view.accessibility.AccessibilityNodeInfo> =
                    if (node == null || !node.refresh()) emptyList() else listOf(node) + (0 until node.childCount).flatMap { collect(node.getChild(it)) }
                return collect(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun screenshot(name: String) {
                android.util.Log.i("DrawerReveal", onMain { "$name pane=${tagged("voice-drawer").size} card=${tagged("channel-files-tool").size} icon=${tagged("channel-files-tool-icon").size} visibleCard=${tagged("channel-files-tool").boundsInRoot}" })
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            var down = 0L
            var finger = Offset.Zero
            var gestureActive = false
            fun send(action: Int, position: Offset = finger) {
                finger = position
                if (action == android.view.MotionEvent.ACTION_DOWN) gestureActive = true
                if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) gestureActive = false
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, finger.x, finger.y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                settle(35)
            }
            fun begin() {
                down = android.os.SystemClock.uptimeMillis()
                val pane = onMain { tagged("voice-drawer") }
                send(android.view.MotionEvent.ACTION_DOWN, pane.positionOnScreen + Offset(pane.size.width / 2f, 24 * density))
            }
            try { for ((caseWidth, caseHeight, scale) in listOf(Triple(320, 520, 1f), Triple(320, 520, 2f), Triple(280, 360, 1f), Triple(280, 360, 2f))) {
                onMain { width = caseWidth.dp; available = caseHeight.dp; font = scale; expanded = true }
                settle()
                val baseline = onMain {
                    val origin = tagged("voice-drawer").positionInRoot
                    parts().map { it.size to (it.positionInRoot - origin) }
                }
                assertTrue(baseline[0].first.height >= 72 * density - 1)
                assertEquals(26 * density, baseline[1].first.height.toFloat(), 1f)
                val fullHeight = caseHeight * .7f
                val travel = fullHeight - 64
                val bottom = onMain { tagged("voice-drawer").let { it.positionOnScreen.y + it.size.height } }
                fun check(expectedHeight: Float? = null) {
                    val pane = tagged("voice-drawer")
                    expectedHeight?.let { assertEquals(it * density, pane.size.height.toFloat(), 2f) }
                    parts().zip(baseline).forEach { (part, original) ->
                        assertEquals("Natural size at $caseWidth/$caseHeight/$scale", original.first, part.size)
                        val offset = part.positionInRoot - pane.positionInRoot
                        assertEquals(original.second.x, offset.x, 1f); assertEquals(original.second.y, offset.y, 1f)
                    }
                }
                fun move(height: Float) {
                    val destination = Offset(finger.x, bottom - height * density + 24 * density)
                    val origin = finger
                    for (step in 1..4) send(android.view.MotionEvent.ACTION_MOVE, origin + (destination - origin) * (step / 4f))
                    onMain { check(height) }
                }
                fun observe(closing: Boolean) {
                    var previous = onMain { tagged("voice-drawer").size.height }
                    repeat(8) {
                        settle(25)
                        val current = onMain {
                            if (nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "channel-files-tool" }) check()
                            tagged("voice-drawer").size.height
                        }
                        if (closing) assertTrue("Closing stays continuous", current <= previous + 1)
                        else assertTrue("Rebound stays continuous", current >= previous - 1)
                        previous = current
                    }
                    settle()
                }
                screenshot("reveal-$caseWidth-$caseHeight-$scale-full")
                begin()
                val middle = (baseline[0].second.y / density + baseline[0].first.height / density / 2).coerceIn(64 + travel * .35f, 64 + travel * .75f)
                for ((name, height) in listOf("near" to 64 + travel * .9f, "middle" to middle, "small" to 64 + travel * .15f)) {
                    move(height)
                    screenshot("reveal-$caseWidth-$caseHeight-$scale-close-$name")
                }
                settle(200)
                assertTrue("Hidden card is absent from accessibility", accessibility().none { it.viewIdResourceName == "channel-files-tool" && it.isVisibleToUser })
                assertTrue("Focus must leave clipped content", instrumentation.uiAutomation.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.viewIdResourceName != "channel-files-tool")
                for ((name, height) in listOf("middle" to middle, "near" to 64 + travel * .9f, "full" to fullHeight)) {
                    move(height)
                    screenshot("reveal-$caseWidth-$caseHeight-$scale-open-$name")
                }
                send(android.view.MotionEvent.ACTION_CANCEL); observe(closing = false)
                onMain { check(fullHeight) }
                begin(); move(64 + travel * .55f)
                settle(100); send(android.view.MotionEvent.ACTION_UP); observe(closing = false)
                onMain { check(fullHeight) }
                begin(); move(64 + travel * .15f)
                settle(100); send(android.view.MotionEvent.ACTION_CANCEL); observe(closing = false)
                onMain { check(fullHeight) }

                // A partly clipped card can extend behind navigation in layout, but cannot take its tap.
                begin(); move((baseline[0].second.y / density + baseline[0].first.height / density / 2 - 36).coerceIn(80f, fullHeight - 16))
                onMain { expanded = false }
                send(android.view.MotionEvent.ACTION_CANCEL)
                val nav = onMain { tagged("fixture-navigation") }
                down = android.os.SystemClock.uptimeMillis()
                send(android.view.MotionEvent.ACTION_DOWN, nav.positionOnScreen + Offset(nav.size.width / 2f, nav.size.height / 2f))
                send(android.view.MotionEvent.ACTION_UP)
                assertEquals(1, navigationClicks)
                navigationClicks = 0
                observe(closing = true)
                assertEquals(64 * density, onMain { tagged("voice-drawer").size.height.toFloat() }, 1f)
                assertTrue(onMain { nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "channel-files-tool" } })

                // Downward overscroll must also stay inside the drawer's container, above navigation.
                begin()
                send(android.view.MotionEvent.ACTION_MOVE, finger + Offset(0f, 64 * density))
                send(android.view.MotionEvent.ACTION_CANCEL)
                val topNav = onMain { tagged("fixture-navigation") }
                down = android.os.SystemClock.uptimeMillis()
                send(android.view.MotionEvent.ACTION_DOWN, topNav.positionOnScreen + Offset(topNav.size.width / 2f, 4 * density))
                send(android.view.MotionEvent.ACTION_UP)
                assertEquals("Overscrolled content cannot take a navigation tap", 1, navigationClicks)
                navigationClicks = 0
                settle()

                // Click-triggered springs and disconnection use the same clipping layout as a held drag.
                fun clickHandle() = onMain {
                    val handle = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(localized.getString(if (expanded) R.string.voice_drawer_collapse else R.string.voice_drawer_expand)) == true }
                    assertTrue(handle.config[SemanticsActions.OnClick].action!!())
                }
                clickHandle(); observe(closing = false); onMain { check(fullHeight) }
                clickHandle(); observe(closing = true)
                clickHandle(); settle(100)
                val openingHeight = onMain {
                    val height = tagged("voice-drawer").size.height
                    ui = ui.copy(snapshot = ui.snapshot.copy(status = "disconnected"))
                    height
                }
                settle(40) // Let the connection update cancel the previous frame's opening animation.
                val closingHeight = onMain { tagged("voice-drawer").size.height }
                if (openingHeight > 64 * density + 1 && openingHeight < fullHeight * density - 1) {
                    assertTrue("Disconnect must not jump to either anchor", closingHeight > 64 * density && closingHeight < fullHeight * density)
                }
                observe(closing = true)
                assertEquals(64 * density, onMain { tagged("voice-drawer").size.height.toFloat() }, 1f)
                assertTrue(onMain { nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "channel-files-tool" } })
                onMain { ui = ui.copy(snapshot = ui.snapshot.copy(status = "connected")); expanded = true }
            } } finally { if (gestureActive) send(android.view.MotionEvent.ACTION_CANCEL) }

            // Accessibility focus requires touch exploration; enable it only after physical gesture checks.
            onMain { width = 320.dp; available = 520.dp; font = 1f; expanded = true }
            settle()
            val automation = instrumentation.uiAutomation
            val originalFlags = automation.serviceInfo.flags
            try {
                automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE }
                settle(200)
                assertTrue(context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled)
                val card = requireNotNull(accessibility().firstOrNull { it.viewIdResourceName == "channel-files-tool" && it.isVisibleToUser })
                assertTrue(card.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                // Resize without removing the composed card; clipping must remove its focus and visibility.
                onMain { available = 180.dp }; settle(200)
                assertTrue(onMain { tagged("channel-files-tool").size.height >= 72 * density - 1 })
                assertTrue(accessibility().none { it.viewIdResourceName == "channel-files-tool" && it.isVisibleToUser })
                assertTrue(automation.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.viewIdResourceName != "channel-files-tool")
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }
    }

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
                    if (node == null || !node.refresh()) return
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
            android.os.SystemClock.sleep(300); instrumentation.waitForIdleSync()
            check(false)
            android.os.SystemClock.sleep(1000); instrumentation.waitForIdleSync()
            check(true)
            android.os.SystemClock.sleep(1000); instrumentation.waitForIdleSync()
            check(false)
        }
    }
    @Test fun bridgePreservesNullsGradesAndMissingSeconds() {
        val snapshot = JSONObject("""{"status":"connected","channels":[],"clients":[],"networkQuality":{"rttMs":42.5,"deviationMs":6.05,"packetLossPercent":4.0,"rttGrade":"good","deviationGrade":"good","packetLossGrade":"poor","iconGrade":"poor","axisMaxMs":2000,"nowSecond":33,"samples":[{"second":29,"rttMs":1234,"grade":"poor"},{"second":31,"rttMs":50,"grade":"good","packetLossPercent":null,"packetLossGrade":null},{"second":33,"rttMs":42.5,"grade":"good","packetLossPercent":2.004,"packetLossGrade":"fair"}]}}""").snapshot()
        val q = requireNotNull(snapshot.networkQuality)
        assertEquals("43", q.latencyText); assertEquals("6.1", q.deviationText); assertEquals("4.0", q.lossText)
        assertEquals(NetworkGrade.POOR, q.iconGrade); assertEquals(NetworkGrade.GOOD, q.rttGrade)
        assertEquals(listOf(29L, 31L, 33L), q.samples.map { it.second }); assertEquals(2000.0, q.axisMaxMs, 0.0)
        assertNull(q.samples[0].packetLossFraction); assertNull(q.samples[1].packetLossFraction)
        assertEquals(2.004, requireNotNull(q.samples[2].packetLossPercent), 0.0)
        assertEquals(NetworkGrade.FAIR, q.samples[2].packetLossGrade)
        assertEquals(0.02004, requireNotNull(q.samples[2].packetLossFraction), 1e-12)
        for (fields in listOf("\"packetLossPercent\":2", "\"packetLossGrade\":\"fair\"", "\"packetLossPercent\":null,\"packetLossGrade\":\"fair\"", "\"packetLossPercent\":2,\"packetLossGrade\":null")) {
            val json = JSONObject("""{"axisMaxMs":100,"nowSecond":0,"samples":[{"second":0,"rttMs":50,"grade":"good",$fields}]}""")
            assertNull(json.networkQuality().samples.single().packetLossFraction)
        }
        assertNull(JSONObject("""{"status":"disconnected","channels":[],"clients":[],"networkQuality":null}""").snapshot().networkQuality)
    }

    @Test fun historyUsesHardEdgesInsideEachNativeBarAndUpdatesWithState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val colors = listOf(0xFF3DBE78.toInt(), 0xFFE9B44C.toInt(), 0xFFFF375F.toInt(), 0xFFFF8000.toInt(), 0xFF581C87.toInt())
        val losses = mapOf(0L to 0.0, 1L to 2.0, 2L to 10.0, 3L to 100.0, 4L to 2.0, 5L to 120.0,
            7L to -1.0, 8L to Double.NaN, 9L to Double.POSITIVE_INFINITY, 10L to 2.0, 11L to .0001, 13L to 100.0, 15L to 50.0)
        var quality by mutableStateOf(NetworkQuality(axisMaxMs = 200.0, nowSecond = 29,
            samples = (0L..29L).filter { it != 14L }.map { second ->
                NetworkSample(second, if (second == 4L) 150.0 else if (second == 13L) 0.0 else if (second == 15L) 2.0 else 50.0,
                    if (second == 4L) NetworkGrade.FAIR else NetworkGrade.GOOD,
                    if (second == 6L) null else losses[second] ?: 10.0,
                    if (second == 6L || second == 10L) null else if (second == 1L || second == 4L) NetworkGrade.FAIR else NetworkGrade.POOR)
            }))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            var density = 1f
            scenario.onActivity {
                activity = it; density = it.resources.displayMetrics.density
                it.setContent { MaterialTheme {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        NetworkChart(quality, Modifier.size(366.dp, 240.dp).background(Palette.bottom).testTag("loss-chart"))
                    }
                } }
            }
            fun <T> onMain(block: () -> T): T {
                var result: T? = null
                instrumentation.runOnMainSync { result = block() }
                @Suppress("UNCHECKED_CAST") return result as T
            }
            fun chartNode(): SemanticsNode {
                fun root(view: android.view.View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? android.view.ViewGroup)?.let { group ->
                    (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) }
                }
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                return collect(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode)
                    .single { it.config.getOrNull(SemanticsProperties.TestTag) == "loss-chart" }
            }
            fun render(name: String): android.graphics.Bitmap {
                android.os.SystemClock.sleep(300); instrumentation.waitForIdleSync()
                val bounds = onMain { chartNode().let { android.graphics.Rect(it.positionOnScreen.x.toInt(), it.positionOnScreen.y.toInt(), it.positionOnScreen.x.toInt() + it.size.width, it.positionOnScreen.y.toInt() + it.size.height) } }
                val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                val bitmap = android.graphics.Bitmap.createBitmap(screen, bounds.left, bounds.top, bounds.width(), bounds.height())
                screen.recycle()
                File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                return bitmap
            }
            fun matches(bitmap: android.graphics.Bitmap, x: Int, y: Int, color: Int): Boolean {
                val pixel = bitmap.getPixel(x, y)
                return listOf(16, 8, 0).all { kotlin.math.abs((pixel shr it and 255) - (color shr it and 255)) <= 4 }
            }
            fun bars(bitmap: android.graphics.Bitmap): List<IntRange> {
                val y = bitmap.height - density.toInt() - 2
                val ranges = mutableListOf<IntRange>()
                var start: Int? = null
                for (x in 0..bitmap.width) {
                    val filled = x < bitmap.width && colors.any { matches(bitmap, x, y, it) }
                    if (filled && start == null) start = x
                    if (!filled && start != null) { ranges += start..(x - 1); start = null }
                }
                return ranges
            }
            val image = render("loss-history-normal")
            val ranges = bars(image)
            assertEquals(quality.samples.size, ranges.size)
            for ((sample, range) in quality.samples.zip(ranges)) {
                val x = (range.first + range.last) / 2
                val latency = if (sample.grade == NetworkGrade.FAIR) colors[1] else colors[0]
                val loss = if (sample.packetLossGrade == NetworkGrade.FAIR) colors[3] else colors[4]
                val rows = (0 until image.height).filter { y -> colors.any { matches(image, x, y, it) } }
                val first = rows.first(); val last = rows.last(); val count = last - first + 1
                val p = sample.packetLossFraction ?: 0.0
                if (p == 0.0 || p == 1.0) {
                    assertTrue("Solid sample ${sample.second}", rows.all { matches(image, x, it, if (p == 0.0) latency else loss) })
                } else {
                    val boundary = last + 1 - count * p
                    for (y in first..last) if (kotlin.math.abs(y + .5 - boundary) > 1.5) {
                        assertTrue("Hard edge sample ${sample.second}, y=$y, boundary=$boundary", matches(image, x, y, if (y + .5 < boundary) latency else loss))
                    }
                    assertEquals("Raw ratio sample ${sample.second}", count * p, rows.count { matches(image, x, it, loss) }.toDouble(), 1.5)
                }
                if (sample.second == 3L) {
                    assertFalse("Rounded top corner", matches(image, range.first, first, loss))
                    assertTrue("Flat baseline", matches(image, range.first, last - 1, loss))
                }
                if (sample.second == 13L) assertTrue(count <= density.toInt() + 1)
            }
            val firstCenter = (ranges[0].first + ranges[0].last) / 2
            val secondCenter = (ranges[1].first + ranges[1].last) / 2
            val gapCenter = secondCenter + (secondCenter - firstCenter) * 13
            assertFalse("Missing second stays empty", colors.any { matches(image, gapCenter, image.height - density.toInt() - 2, it) })
            assertTrue(onMain { chartNode().config[SemanticsProperties.ContentDescription].single().contains("2.0") })
            onMain { quality = quality.copy(nowSecond = 30) }
            val advanced = render("loss-history-advanced")
            val advancedRanges = bars(advanced)
            assertEquals(ranges.size - 1, advancedRanges.size)
            assertEquals((ranges.last().first + ranges.last().last) / 2.0 - (secondCenter - firstCenter),
                (advancedRanges.last().first + advancedRanges.last().last) / 2.0, 2.0)
            onMain { quality = quality.copy(axisMaxMs = 500.0) }
            val scaled = render("loss-history-axis")
            val scaledLast = bars(scaled).last(); val scaledX = (scaledLast.first + scaledLast.last) / 2
            val scaledRows = (0 until scaled.height).filter { y -> colors.any { matches(scaled, scaledX, y, it) } }
            assertTrue(scaledRows.size < image.height * .12)
            assertEquals(scaledRows.size * .1, scaledRows.count { matches(scaled, scaledX, it, colors[4]) }.toDouble(), 1.5)
            onMain { quality = quality.copy(samples = emptyList()) }
            val empty = render("loss-history-disconnected")
            assertTrue(bars(empty).isEmpty())
            onMain { quality = NetworkQuality(axisMaxMs = 100.0, nowSecond = 0, samples = listOf(NetworkSample(0, 50.0, NetworkGrade.GOOD, 100.0, NetworkGrade.POOR))) }
            val restored = render("loss-history-reconnected")
            assertEquals(1, bars(restored).size)
            listOf(image, advanced, scaled, empty, restored).forEach { it.recycle() }
        }
    }

    @Test fun nativePanelRendersLongValuesAtNarrowWidthAndLargeFont() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((width, scale) in listOf(320 to 1f, 240 to 1f, 320 to 2f)) {
                scenario.onActivity { activity ->
                    activity.setContent {
                        val density = LocalDensity.current.density
                        CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                            MaterialTheme {
                                Box(Modifier.fillMaxSize().background(Palette.bottom).safeDrawingPadding()) {
                                    NetworkQualityPanel(NetworkQuality(rttMs = 1234.0, deviationMs = 1234.5, packetLossPercent = 4.0,
                                        rttGrade = NetworkGrade.POOR, deviationGrade = NetworkGrade.POOR, packetLossGrade = NetworkGrade.POOR,
                                        iconGrade = NetworkGrade.POOR, axisMaxMs = 2000.0, nowSecond = 29,
                                        samples = (0L..29L).filter { it != 24L }.map { NetworkSample(it,
                                            if (it == 20L) 1234.0 else if (it == 25L) 150.0 else 42.0,
                                            if (it == 20L) NetworkGrade.POOR else if (it == 25L) NetworkGrade.FAIR else NetworkGrade.GOOD,
                                            if (it == 25L) 2.0 else 10.0, if (it == 25L) NetworkGrade.FAIR else NetworkGrade.POOR) }), Modifier.width(width.dp).background(Palette.bottom))
                                }
                            }
                        }
                    }
                }
                android.os.SystemClock.sleep(300)
                instrumentation.waitForIdleSync()
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(instrumentation.targetContext.getExternalFilesDir(null), "network-panel-$width-$scale.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                val descriptions = mutableListOf<String>()
                val elements = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null || !node.refresh()) return
                    if (node.isVisibleToUser) elements += node
                    node.contentDescription?.toString()?.let(descriptions::add)
                    for (i in 0 until node.childCount) collect(node.getChild(i))
                }
                collect(instrumentation.uiAutomation.rootInActiveWindow)
                assertTrue(descriptions.toString(), descriptions.any { it.contains("999") && it.contains("999.9") })
                assertTrue(descriptions.toString(), descriptions.any { it.contains("2000") && it.contains("29") })
                val chart = elements.single { it.contentDescription?.toString()?.startsWith(instrumentation.targetContext.localized(R.string.network_chart)) == true }
                val numbers = elements.single { it.contentDescription?.toString()?.contains("999.9") == true }
                val chartBounds = android.graphics.Rect().also(chart::getBoundsInScreen)
                val numberBounds = android.graphics.Rect().also(numbers::getBoundsInScreen)
                if (scale > 1f || width < 263) {
                    assertTrue("Narrow width and large text stack the chart below numbers", chartBounds.top > numberBounds.bottom)
                } else {
                    assertEquals(numberBounds.top, chartBounds.top)
                    assertEquals(numberBounds.bottom, chartBounds.bottom)
                }
            }
        }
    }
}
