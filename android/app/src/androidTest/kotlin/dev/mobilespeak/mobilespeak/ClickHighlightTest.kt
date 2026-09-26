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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    @Test fun realListAndNavigationComposablesKeepTouchAndAccessibilityWithoutRectangle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Unlock the Android device before testing the app's pressed state",
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        ClientSession.initialize(context)
        assumeTrue("Isolated UI fixture must not use a live connection",
            !ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")

        val channel = Channel(1, 0, 0, "Fixture channel", false, true, "fixture", null)
        val member = Member(2, 1, "fixture-user", "Fixture member", null, emptyList(), emptyList(), null, false, false, false)
        val bookmark = Bookmark(title = "Fixture bookmark", host = "example.invalid", port = 9987,
            nickname = "fixture", password = "", automaticallyNamed = false)
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
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.TopCenter) {
                                when (mode) {
                                    "channel" -> ChannelScreen(ui, { clicks.incrementAndGet() }, { clicks.incrementAndGet() })
                                    "member" -> MemberScreen(ui) { clicks.incrementAndGet() }
                                    "bookmark", "bookmark_add" -> BookmarkScreen(ui,
                                        { clicks.incrementAndGet() }, {}, {}, { clicks.incrementAndGet() })
                                    else -> Row(Modifier.fillMaxWidth()) {
                                        NavigationItem(label, UiIcons.People, selected = true, unread = 3) { clicks.incrementAndGet() }
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
                val node = waitForClickable(label)
                assertTrue("$mode must remain keyboard focusable", node.isFocusable)
                assertTrue("$mode must retain TalkBack click", node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                if (mode == "navigation") assertTrue("selected tab state must remain", node.isSelected)
                val bounds = Rect().also(node::getBoundsInScreen)
                val before = instrumentation.uiAutomation.takeScreenshot()
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                val down = SystemClock.uptimeMillis()
                instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
                SystemClock.sleep(250)
                val pressed = instrumentation.uiAutomation.takeScreenshot()
                instrumentation.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
                instrumentation.waitForIdleSync()
                assertNoPressedRectangle(mode, before, pressed, bounds)
                before.recycle(); pressed.recycle()
                assertEquals("$mode must still handle a physical tap", 1, clicks.get())
                assertTrue("$mode must support TalkBack activation", waitForClickable(label).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertEquals(2, clicks.get())
                assertTrue("$mode must take keyboard focus", waitForClickable(label).performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                instrumentation.waitForIdleSync()
                assertEquals("$mode must respond to keyboard activation", 3, clicks.get())
            }
        }
    }

    private fun waitForClickable(label: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && (node.text?.toString()?.contains(label) == true ||
                        node.contentDescription?.toString()?.contains(label) == true)) {
                generateSequence(node) { it.parent }.firstOrNull { it.isClickable }?.let { return it }
            }
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        repeat(30) {
            find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)?.let { return it }
            SystemClock.sleep(50)
        }
        error("No clickable accessibility node for $label")
    }

    private fun assertNoPressedRectangle(mode: String, before: Bitmap, pressed: Bitmap, area: Rect) {
        var sampled = 0
        var changed = 0
        for (y in (area.top + 8) until (area.bottom - 8) step 4) {
            for (x in (area.left + 8) until (area.right - 8) step 4) {
                if (x !in 0 until before.width || y !in 0 until before.height) continue
                sampled++
                val a = before.getPixel(x, y)
                val b = pressed.getPixel(x, y)
                if (kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                    kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                    kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) > 12) changed++
            }
        }
        assertTrue("$mode pressed pixels changed $changed/$sampled (rectangular indication)", sampled > 50 && changed * 20 < sampled)
    }
}
