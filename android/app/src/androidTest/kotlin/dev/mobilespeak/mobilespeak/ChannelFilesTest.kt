package dev.mobilespeak.mobilespeak

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChannelFilesTest {
    @Test fun fullStateDecodesZeroBytesAndKeepsTransferContext() {
        val json = JSONObject("""{"open":true,"server":"fixture","channel":7,"channelName":"当前频道","path":"/文档","sort":"name","status":"ready","error":null,"entries":[{"name":"很长的中文文件名称.pdf","size":0,"timestamp":1700000000,"directory":false,"icon":"file-text","localPath":null}],"transfers":[{"id":1,"server":"fixture","channel":7,"path":"/文档","name":"很长的中文文件名称.pdf","upload":false,"size":0,"transferred":0,"status":"failed","error":"files_disconnected","localPath":null}]}""")
        val files = json.channelFiles()
        assertEquals(0L, files.entries.single().size)
        assertEquals(7L, files.channel)
        assertEquals("files_disconnected", files.transfer(files.entries.single().name)?.error)
        assertNull(files.transfer("other.txt"))
        assertEquals("/文档", files.transfer(files.entries.single().name)?.path)
    }

    @Test fun nativeListRendersAtNormalAndLargeFontsWithoutAnotherDrawer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val files = ChannelFilesState(open = true, server = "fixture", channel = 7, channelName = "当前实际加入的频道", path = "/文档", status = "ready",
            entries = (0..40).map { ChannelFileEntry("很长的中文文件名称用于保留扩展名-$it.pdf", 0, 1700000000, false, "file-text", null) })
        for (font in listOf(1f, 2f)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                activity.setContent { CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                    MaterialTheme { Box(Modifier.fillMaxSize().background(Palette.bottom)) {
                        ChannelFilesDrawer(SessionUiState(snapshot = Snapshot(status = "connected"), channelFiles = files))
                    } }
                } }
            }
            instrumentation.waitForIdleSync()
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
            val before = nodes()
            val header = requireNotNull(before.firstOrNull { it.text?.toString() == files.channelName })
            val headerBounds = android.graphics.Rect().also(header::getBoundsInScreen)
            assertTrue(before.any { it.contentDescription?.toString()?.contains(".pdf") == true || it.text?.toString()?.contains(".pdf") == true })
            val scroll = requireNotNull(before.firstOrNull { it.isScrollable })
            assertTrue(scroll.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            android.os.SystemClock.sleep(300)
            val afterHeader = requireNotNull(nodes().firstOrNull { it.text?.toString() == files.channelName })
            assertEquals(headerBounds, android.graphics.Rect().also(afterHeader::getBoundsInScreen))
            val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertTrue(shot.width > 0 && shot.height > 0)
            java.io.File(context.getExternalFilesDir(null), "channel-files-$font.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
    }
}
