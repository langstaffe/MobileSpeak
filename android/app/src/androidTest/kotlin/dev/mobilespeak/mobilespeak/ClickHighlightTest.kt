package dev.mobilespeak.mobilespeak

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ClickHighlightTest {
    @Test fun headerAndSettingsKeepPressSemanticsAndSingleAvatarFeedback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 31) instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        assumeTrue("Unlock the Android device before testing pressed pixels",
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        ClientSession.initialize(context)
        assumeTrue("UI checks must not use a live connection",
            !ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")
        val saved = ClientSession.state.value
        val permission = ClientSession.microphonePermission
        val launches = AtomicInteger()
        val permissions = AtomicInteger()
        val feedback = java.util.concurrent.ConcurrentLinkedQueue<HapticFeedbackType>()
        val haptic = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback.add(hapticFeedbackType) }
        }
        val preview = java.io.File(context.cacheDir, "highlight-avatar.jpg")
        preview.outputStream().use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            compress(Bitmap.CompressFormat.JPEG, 90, it); recycle()
        } }
        androidx.test.runner.intent.IntentStubberRegistry.load(object : androidx.test.runner.intent.IntentStubber {
            override fun getActivityResultForIntent(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.type != "image/*") return null
                assertEquals("Feedback must precede each picker launch", launches.incrementAndGet(), feedback.size)
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        })
        try {
            ClientSession.setMicrophonePermission(false)
            ClientSession.setAudio(inputMuted = true, deafened = false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for ((mode, resource) in listOf("off" to R.string.action_disconnect,
                    "choose" to R.string.avatar_choose, "change" to R.string.avatar_change,
                    "clear" to R.string.avatar_remove, "microphone" to R.string.settings_microphone,
                    "listening" to R.string.settings_listening)) {
                    feedback.clear(); launches.set(0); permissions.set(0)
                    scenario.onActivity { activity -> activity.setContent {
                        FeedbackTestTheme {
                            androidx.compose.runtime.key(mode) { CompositionLocalProvider(LocalHapticFeedback provides haptic) {
                                Box(Modifier.fillMaxSize().background(Palette.background).systemBarsPadding()) {
                                    if (mode == "off") Header(saved.copy(snapshot = Snapshot(status = "connected")))
                                    else SettingsScreen(ClientSession.state.collectAsState().value.copy(
                                        avatarPreviewPath = if (mode == "change") preview.absolutePath else saved.avatarPreviewPath,
                                    ).let { if (mode == "choose") it.copy(avatarPreviewPath = null) else it },
                                        { permissions.incrementAndGet() }, {})
                                }
                            } }
                        }
                    } }
                    instrumentation.waitForIdleSync()
                    repeat(30) {
                        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) SystemClock.sleep(100)
                    }
                    assertEquals("Fixture must be foreground", context.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
                    assertEquals("Composition must not generate feedback", 0, feedback.size)
                    val label = context.localized(resource)
                    val node = waitForClickable(label)
                    assertTrue(node.isFocusable)
                    assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    if (mode in listOf("microphone", "listening")) assertTrue(node.isCheckable)
                    val bounds = Rect().also(node::getBoundsInScreen)
                    val touchMode = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(touchMode, SystemClock.uptimeMillis(), action, 5f, 310f, 0)
                        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    val before = stableScreenshot(bounds)
                    val down = SystemClock.uptimeMillis()
                    instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0))
                    SystemClock.sleep(250)
                    val pressed = instrumentation.uiAutomation.takeScreenshot()
                    assertEquals("Press alone must not activate feedback", 0, feedback.size)
                    instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0))
                    instrumentation.waitForIdleSync()
                    for ((suffix, shot) in listOf("before" to before, "pressed" to pressed)) {
                        java.io.File(context.cacheDir, "highlight-$mode-$suffix.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    assertNoPressedRectangle(mode, before, pressed, bounds)
                    if (mode in listOf("off", "choose", "change", "clear")) assertContentDim(mode, before, pressed, bounds, 0.75f)
                    before.recycle(); pressed.recycle()
                    fun afterActivation(count: Int) {
                        assertEquals("$mode must generate one feedback per activation", if (mode == "off") 0 else count, feedback.size)
                        if (mode in listOf("choose", "change", "clear")) assertTrue(feedback.all { it == HapticFeedbackType.SegmentTick })
                        if (mode in listOf("choose", "change")) assertEquals(count, launches.get())
                        if (mode == "microphone") assertEquals("Microphone enable must request permission", count, permissions.get())
                        if (mode == "clear") {
                            assertTrue(waitForClickable(context.localized(R.string.action_cancel)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            instrumentation.waitForIdleSync()
                            SystemClock.sleep(250)
                            assertEquals(saved.avatarPreviewPath, ClientSession.state.value.avatarPreviewPath)
                            assertEquals(saved.avatarRevision, ClientSession.state.value.avatarRevision)
                            assertEquals("Cancel must not generate extra feedback", count, feedback.size)
                        }
                    }
                    afterActivation(1)
                    SystemClock.sleep(600)
                    assertTrue("$mode must respond to accessibility activation", waitForClickable(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync(); afterActivation(2)
                    SystemClock.sleep(600)
                    assertTrue(waitForClickable(label).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                    instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                    instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                    instrumentation.waitForIdleSync(); afterActivation(3)
                    if (mode in listOf("microphone", "listening")) {
                        for ((index, x) in listOf(bounds.left + 12f, bounds.right - 12f).withIndex()) {
                            val time = SystemClock.uptimeMillis()
                            instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, bounds.exactCenterY(), 0))
                            instrumentation.sendPointerSync(MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, bounds.exactCenterY(), 0))
                            instrumentation.waitForIdleSync(); afterActivation(4 + index)
                        }
                    }
                    if (mode == "listening") {
                        assertTrue(ClientSession.state.value.deafened)
                        val microphone = waitForClickable(context.localized(R.string.settings_microphone))
                        assertTrue("Microphone must be disabled when listening is off", !microphone.isEnabled)
                        microphone.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        instrumentation.waitForIdleSync()
                        assertEquals(5, feedback.size)
                        assertEquals(0, permissions.get())
                    }
                }
                for (busy in listOf(saved.copy(avatarClearingLocally = true), saved.copy(avatarStatus = "clearing"))) {
                    scenario.onActivity { activity -> activity.setContent {
                        FeedbackTestTheme { CompositionLocalProvider(LocalHapticFeedback provides haptic) { SettingsScreen(busy, {}, {}) } }
                    } }
                    instrumentation.waitForIdleSync()
                    val count = feedback.size
                    repeat(30) { if (waitForClickable(context.localized(R.string.avatar_remove)).isEnabled) SystemClock.sleep(50) }
                    val clear = waitForClickable(context.localized(R.string.avatar_remove))
                    assertTrue(!clear.isEnabled)
                    val area = Rect().also(clear::getBoundsInScreen)
                    val disabled = stableScreenshot(area)
                    var redPixels = 0
                    for (y in area.top until area.bottom step 2) for (x in area.left until area.right step 2) {
                        val pixel = disabled.getPixel(x,y)
                        if (android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel) + 20) redPixels++
                    }
                    disabled.recycle()
                    assertTrue("Disabled clear must retain its destructive color at reduced opacity", redPixels > 20)
                    clear.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    instrumentation.waitForIdleSync()
                    assertEquals("Disabled clear must not trigger feedback", count, feedback.size)
                }
            }
        } finally {
            androidx.test.runner.intent.IntentStubberRegistry.reset()
            ClientSession.setMicrophonePermission(permission)
            ClientSession.setAudio(inputMuted = saved.microphoneMuted, deafened = saved.deafened)
            preview.delete()
        }
    }

    @Test fun realListAndNavigationComposablesKeepTouchAndAccessibilityWithoutRectangle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 31) instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        assumeTrue("Unlock the Android device before testing the app's pressed state",
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        ClientSession.initialize(context)
        assumeTrue("Isolated UI fixture must not use a live connection",
            !ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")

        val channel = Channel(1, 0, 0, "Fixture channel", false, true, "fixture", null)
        val member = Member(2, 1, "fixture-user", "Fixture member", null, emptyList(), emptyList(), null, false, false, false)
        val bookmark = Bookmark(title = "Fixture bookmark", host = "example.invalid", port = 9987,
            password = "", automaticallyNamed = false)
        val ui = SessionUiState(snapshot = Snapshot(status = "connected", ownClient = 99,
            channels = listOf(channel), clients = listOf(member)), bookmarks = listOf(bookmark))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((mode, label) in listOf(
                "channel" to channel.name,
                "member" to member.name,
                "navigation" to "Fixture tab",
                "bookmark" to bookmark.title,
                "bookmark_add" to context.localized(R.string.bookmark_add_server),
            )) {
                val clicks = AtomicInteger()
                scenario.onActivity { activity ->
                    activity.setContent {
                        FeedbackTestTheme {
                            Box(Modifier.fillMaxSize().background(Palette.background).systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
                                when (mode) {
                                    "channel" -> ChannelScreen(ui, { clicks.incrementAndGet() }, { clicks.incrementAndGet() })
                                    "member" -> MemberScreen(ui) { clicks.incrementAndGet() }
                                    "bookmark", "bookmark_add" -> BookmarkScreen(ui,
                                        { clicks.incrementAndGet() }, {}, {}, { clicks.incrementAndGet() })
                                    else -> Row(Modifier.fillMaxWidth()) {
                                        NavigationItem(label, UiIcons.People, selected = false, unread = 3) { clicks.incrementAndGet() }
                                    }
                                }
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                var foreground: String? = null
                for (attempt in 0 until 20) {
                    foreground = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                    if (foreground == context.packageName) break
                    SystemClock.sleep(50)
                }
                assertEquals("The test must inspect MobileSpeak, not another app", context.packageName, foreground)
                // Accessibility can expose the new composition before its first frame is committed.
                val frame = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    val view = activity.window.decorView
                    if (android.os.Build.VERSION.SDK_INT >= 29) view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                    else view.postOnAnimation { view.postOnAnimation { frame.countDown() } }
                    view.invalidate()
                }
                assertTrue("Fixture frame must be drawn before the baseline screenshot", frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
                val node = waitForClickable(label)
                assertTrue("$mode must remain keyboard focusable", node.isFocusable)
                assertTrue("$mode must retain TalkBack click", node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                if (mode == "navigation") assertTrue("unselected tab state must remain", !node.isSelected)
                val bounds = Rect().also(node::getBoundsInScreen)
                val before = stableScreenshot(bounds)
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                val down = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
                SystemClock.sleep(250)
                val pressed = instrumentation.uiAutomation.takeScreenshot()
                instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
                instrumentation.waitForIdleSync()
                java.io.File(context.cacheDir, "highlight-$mode-before.png").outputStream().use { before.compress(Bitmap.CompressFormat.PNG, 100, it) }
                java.io.File(context.cacheDir, "highlight-$mode-pressed.png").outputStream().use { pressed.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertNoPressedRectangle(mode, before, pressed, bounds)
                assertContentDim(mode, before, pressed, bounds, if (mode in listOf("navigation", "bookmark_add")) 0.4f else 0.75f)
                before.recycle(); pressed.recycle()
                assertEquals("$mode must still handle a physical tap", 1, clicks.get())
                val cancelDown = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(cancelDown, cancelDown, MotionEvent.ACTION_DOWN, x, y, 0))
                SystemClock.sleep(250)
                instrumentation.sendPointerSync(MotionEvent.obtain(cancelDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0))
                SystemClock.sleep(500)
                assertEquals("$mode cancelled press must not activate", 1, clicks.get())
                val recovered = stableScreenshot(bounds)
                val heldAgain = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(heldAgain, heldAgain, MotionEvent.ACTION_DOWN, x, y, 0))
                SystemClock.sleep(250)
                val secondPress = instrumentation.uiAutomation.takeScreenshot()
                instrumentation.sendPointerSync(MotionEvent.obtain(heldAgain, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0))
                assertContentDim("$mode after cancellation", recovered, secondPress, bounds,
                    if (mode in listOf("navigation", "bookmark_add")) 0.4f else 0.75f)
                recovered.recycle(); secondPress.recycle()
                assertTrue("$mode must support TalkBack activation", waitForClickable(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertEquals(2, clicks.get())
                assertTrue("$mode must take keyboard focus", waitForClickable(label).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                instrumentation.waitForIdleSync()
                assertEquals("$mode must respond to keyboard activation", 3, clicks.get())
                if (mode == "navigation") {
                    scenario.onActivity { activity -> activity.setContent {
                        FeedbackTestTheme { Row(Modifier.fillMaxWidth()) { NavigationItem(label, UiIcons.People, selected = true, unread = 3) {} } }
                    } }
                    instrumentation.waitForIdleSync()
                    repeat(30) {
                        if (!waitForClickable(label).isSelected) SystemClock.sleep(50)
                    }
                    assertTrue("Selected tabs must still expose their state", waitForClickable(label).isSelected)
                }
            }
        }
    }

    @Test fun actualDrawerCancelsAudioFeedbackWhenDragTakesOverAndKeepsClippedControlsOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 31) instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        ClientSession.initialize(context)
        assumeTrue(!ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")
        val savedPermission = ClientSession.microphonePermission
        val savedAudio = ClientSession.state.value
        ClientSession.setMicrophonePermission(false)
        val permissions = AtomicInteger()
        val navigation = AtomicInteger()
        var expanded by androidx.compose.runtime.mutableStateOf(true)
        var font by androidx.compose.runtime.mutableFloatStateOf(1f)
        var connected by androidx.compose.runtime.mutableStateOf(true)
        var deafened by androidx.compose.runtime.mutableStateOf(false)
        val density = context.resources.displayMetrics.density
        val channel = Channel(1, 0, 0, "Default Channel", false, true, "fixture", null)
        val fixture = SessionUiState(snapshot = Snapshot(status = "connected", ownClient = 1, channels = listOf(channel)), microphoneMuted = true)
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, font)) {
                    FeedbackTestTheme {
                        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Palette.background).systemBarsPadding()) {
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                                    VoiceDrawer(fixture.copy(deafened = deafened, snapshot = fixture.snapshot.copy(status = if (connected) "connected" else "disconnected")),
                                        { permissions.incrementAndGet() }, expanded, { expanded = it }, maxHeight, maxWidth)
                                }
                            }
                            Row(Modifier.fillMaxWidth()) { NavigationItem("Fixture navigation", UiIcons.People, false, 0) { navigation.incrementAndGet() } }
                        }
                    }
                }
            } }
            fun send(down: Long, action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            for (scale in listOf(1f, 2f)) {
                scenario.onActivity { font = scale; expanded = true; connected = true; deafened = false }
                SystemClock.sleep(1200)
                val mic = waitForClickable(context.localized(R.string.voice_enable_microphone))
                val area = Rect().also(mic::getBoundsInScreen)
                val before = stableScreenshot(area)
                val down = SystemClock.uptimeMillis()
                send(down, MotionEvent.ACTION_DOWN, area.exactCenterX(), area.exactCenterY())
                if (android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) {
                    SystemClock.sleep(60)
                    val immediate = instrumentation.uiAutomation.takeScreenshot()
                    val edgeX = area.centerX() + (18 * density).toInt()
                    val edgeY = area.centerY()
                    assertTrue("Reduced motion must immediately show the full audio circle",
                        android.graphics.Color.red(immediate.getPixel(edgeX,edgeY)) > android.graphics.Color.red(before.getPixel(edgeX,edgeY)) + 15)
                    immediate.recycle()
                }
                SystemClock.sleep(200)
                val pressed = instrumentation.uiAutomation.takeScreenshot()
                for ((phase, bitmap) in listOf("before" to before, "held" to pressed)) {
                    java.io.File(context.getExternalFilesDir(null), "audio-$scale-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                android.util.Log.i("PressFeedbackBounds", "audio/$scale: $area")
                var brighter = 0
                for (y in area.top + 4 until area.bottom - 4 step 3) for (x in area.left + 4 until area.right - 4 step 3) {
                    if (android.graphics.Color.red(pressed.getPixel(x,y)) > android.graphics.Color.red(before.getPixel(x,y)) + 10) brighter++
                }
                assertTrue("Audio press must draw its circular background at font $scale", brighter > 30)
                assertEquals(0, permissions.get())
                // The real drawer's Initial pass must cancel the child press and permission action.
                for (step in 1..4) { send(down, MotionEvent.ACTION_MOVE, area.exactCenterX(), area.exactCenterY() + step * 30 * density); SystemClock.sleep(30) }
                send(down, MotionEvent.ACTION_UP, area.exactCenterX(), area.exactCenterY() + 120 * density)
                SystemClock.sleep(600)
                assertEquals("Dragging the microphone must not request permission", 0, permissions.get())
                val collapsed = waitForClickable(context.localized(R.string.voice_enable_microphone))
                val restoredArea = Rect().also(collapsed::getBoundsInScreen)
                val restored = stableScreenshot(restoredArea)
                val ringPointX = restoredArea.centerX()
                val ringPointY = restoredArea.top + (8 * density).toInt()
                assertTrue("Cancelled audio background must disappear", android.graphics.Color.red(restored.getPixel(ringPointX,ringPointY)) < 90)
                val tap = SystemClock.uptimeMillis()
                send(tap, MotionEvent.ACTION_DOWN, restoredArea.exactCenterX(), restoredArea.exactCenterY())
                send(tap, MotionEvent.ACTION_UP, restoredArea.exactCenterX(), restoredArea.exactCenterY())
                SystemClock.sleep(300)
                assertEquals("A later tap must still request permission exactly once", 1, permissions.get())
                permissions.set(0)
                scenario.onActivity { deafened = true }
                instrumentation.waitForIdleSync()
                repeat(30) {
                    if (waitForClickable(context.localized(R.string.voice_enable_microphone)).isEnabled) SystemClock.sleep(50)
                }
                val disabled = waitForClickable(context.localized(R.string.voice_enable_microphone))
                assertTrue("Listening off must keep the microphone disabled", !disabled.isEnabled)
                val disabledBounds = Rect().also(disabled::getBoundsInScreen)
                val disabledBefore = stableScreenshot(disabledBounds)
                val disabledDown = SystemClock.uptimeMillis()
                send(disabledDown, MotionEvent.ACTION_DOWN, disabledBounds.exactCenterX(), disabledBounds.exactCenterY())
                SystemClock.sleep(200)
                val disabledHeld = instrumentation.uiAutomation.takeScreenshot()
                assertNoPressedRectangle("disabled microphone", disabledBefore, disabledHeld, disabledBounds)
                send(disabledDown, MotionEvent.ACTION_UP, disabledBounds.exactCenterX(), disabledBounds.exactCenterY())
                assertEquals(0, permissions.get())
                disabledBefore.recycle(); disabledHeld.recycle()
                scenario.onActivity { connected = false }
                SystemClock.sleep(600)
                val nav = waitForClickable("Fixture navigation")
                assertTrue(nav.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertEquals(if (scale == 1f) 1 else 2, navigation.get())
                before.recycle(); pressed.recycle(); restored.recycle()
            }
        } } finally {
            ClientSession.setMicrophonePermission(savedPermission)
            ClientSession.setAudio(inputMuted = savedAudio.microphoneMuted, deafened = savedAudio.deafened)
        }
    }

    @androidx.compose.runtime.Composable
    private fun FeedbackTestTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
        MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Palette.accent,
            onPrimary = Palette.text, onSurface = Palette.text, background = Palette.background, surface = Palette.card)) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.text) { content() }
        }
    }

    private fun assertContentDim(mode: String, before: Bitmap, pressed: Bitmap, area: Rect, expected: Float) {
        val backgrounds = mutableMapOf<Int,Int>()
        for (y in area.top.coerceAtLeast(0) until area.bottom.coerceAtMost(before.height) step 4)
            for (x in area.left.coerceAtLeast(0) until area.right.coerceAtMost(before.width) step 4) {
                val pixel = before.getPixel(x,y)
                backgrounds[pixel] = (backgrounds[pixel] ?: 0) + 1
            }
        val background = backgrounds.maxBy { it.value }.key
        fun channels(pixel: Int) = intArrayOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
        val bg = channels(background)
        val ratios = mutableListOf<Float>()
        for (y in area.top.coerceAtLeast(0) until area.bottom.coerceAtMost(before.height) step 2)
            for (x in area.left.coerceAtLeast(0) until area.right.coerceAtMost(before.width) step 2) {
                val a = channels(before.getPixel(x,y)); val b = channels(pressed.getPixel(x,y))
                val index = (0..2).maxBy { a[it] - bg[it] }
                if (a[index] - bg[index] > 80) ratios.add((b[index]-bg[index]).toFloat()/(a[index]-bg[index]))
            }
        ratios.sort()
        assertTrue("$mode needs visible foreground pixels", ratios.size > 15)
        assertEquals("$mode must dim content without a ripple", expected, ratios[ratios.size/2], 0.08f)
    }

    private fun stableScreenshot(area: Rect): Bitmap {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var previous = automation.takeScreenshot()
        var stableFrames = 0
        repeat(20) {
            SystemClock.sleep(100)
            val next = automation.takeScreenshot()
            var changed = 0
            var sampled = 0
            for (y in area.top.coerceAtLeast(0) until area.bottom.coerceAtMost(next.height) step 8) {
                for (x in area.left.coerceAtLeast(0) until area.right.coerceAtMost(next.width) step 8) {
                    sampled++
                    if (previous.getPixel(x, y) != next.getPixel(x, y)) changed++
                }
            }
            previous.recycle(); previous = next
            stableFrames = if (sampled > 0 && changed * 50 < sampled) stableFrames + 1 else 0
            if (stableFrames == 2) return previous // Include the system Activity fade, outside Compose's first committed frame.
        }
        previous.recycle()
        error("Fixture pixels did not settle")
    }

    private fun assertNoPressedRectangle(mode: String, before: Bitmap, pressed: Bitmap, area: Rect) {
        val backgroundCounts = mutableMapOf<Int, Int>()
        for (y in (area.top + 8) until (area.bottom - 8) step 4) for (x in (area.left + 8) until (area.right - 8) step 4) {
            if (x in 0 until before.width && y in 0 until before.height) {
                val color = before.getPixel(x,y)
                backgroundCounts[color] = (backgroundCounts[color] ?: 0) + 1
            }
        }
        val background = backgroundCounts.maxBy { it.value }.key
        var sampled = 0
        var changed = 0
        for (y in (area.top + 8) until (area.bottom - 8) step 4) for (x in (area.left + 8) until (area.right - 8) step 4) {
            if (x !in 0 until before.width || y !in 0 until before.height || before.getPixel(x,y) != background) continue
            if ((-2..2).any { dy -> (-2..2).any { dx -> before.getPixel((x+dx).coerceIn(0,before.width-1), (y+dy).coerceIn(0,before.height-1)) != background } }) continue
            sampled++
            if (pressed.getPixel(x,y) != background) changed++
        }
        assertTrue("$mode must preserve the surrounding background ($changed/$sampled changed)", sampled > 30 && changed * 100 < sampled)
    }
}

internal fun waitForClickable(label: String): AccessibilityNodeInfo {
    fun find(node: AccessibilityNodeInfo?, exact: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        if (node.isVisibleToUser && labels.any { if (exact) label in it.lines() else it.contains(label) }) {
            generateSequence(node) { it.parent }.firstOrNull { it.isClickable || it.isSelected || it.isCheckable }?.let { return it }
        }
        for (index in 0 until node.childCount) find(node.getChild(index), exact)?.let { return it }
        return null
    }
    repeat(30) {
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, true)?.let { return it }
        SystemClock.sleep(50)
    }
    return find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, false)
        ?: error("No clickable accessibility node for $label")
}
