package dev.mobilespeak.mobilespeak

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class BookmarkMenuTest {
    @Test fun bookmarkMenuOpensEditAndDeleteConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Use the isolated test app", context.packageName.endsWith(".uitest"))
        ClientSession.initialize(context)
        val bookmark = requireNotNull(ClientSession.saveBookmark(null, "IconMenuTest", "example.invalid", "9987", "fixture", ""))
        fun node(label: String): AccessibilityNodeInfo? {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            return find(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun waitFor(label: String): AccessibilityNodeInfo {
            repeat(50) { node(label)?.let { return it }; SystemClock.sleep(100) }
            error("Missing $label")
        }
        fun click(label: String) {
            val target = generateSequence(waitFor(label)) { it.parent }.first { it.isClickable }
            assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        fun screenshot(name: String) {
            SystemClock.sleep(500)
            java.io.File(context.cacheDir, "$name.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val manage = context.getString(R.string.bookmark_manage, bookmark.title)
            click(manage)
            waitFor(context.getString(R.string.action_delete))
            screenshot("bookmark-menu")
            click(context.getString(R.string.action_edit))
            waitFor(context.getString(R.string.action_cancel))
            screenshot("bookmark-edit")
            click(context.getString(R.string.action_cancel))
            click(manage)
            click(context.getString(R.string.action_delete))
            waitFor(context.getString(R.string.action_cancel))
            screenshot("bookmark-delete-confirm")
            click(context.getString(R.string.action_cancel))
            waitFor(manage)
        }
        ClientSession.deleteBookmark(bookmark)
    }
}
