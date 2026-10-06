package dev.mobilespeak.mobilespeak

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
        val command = ChannelFileTarget("fixture", 7, files.path).command()
        assertEquals("fixture", command.getString("server")); assertEquals(7L, command.getLong("channel"))
        assertEquals(files.path, command.getString("path")); assertEquals(files.path, command.getString("directory"))
    }

    @Test fun nativeListRendersAtNormalAndLargeFontsWithoutAnotherDrawer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val files = ChannelFilesState(open = true, server = "fixture", channel = 7, channelName = "当前实际加入的频道", path = "/文档/a very long folder path/子目录", status = "ready",
            entries = (0..40).map { ChannelFileEntry("很长的中文文件名称用于保留扩展名-$it.pdf", 0, 1700000000, false, "file-text", null) })
        for (language in listOf("en", "zh-Hans")) for (font in listOf(1f, 2f)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(configuration)
            val density = context.resources.displayMetrics.density
            scenario.onActivity { activity ->
                activity.setContent { CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized, LocalConfiguration provides configuration) {
                    MaterialTheme { Box(Modifier.width(320.dp).background(Palette.bottom)) {
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
            fun button(label: String) = requireNotNull(nodes().firstOrNull { (it.isClickable || it.className?.toString() == "android.widget.Button") && (it.text?.toString() == label || it.contentDescription?.toString() == label) })
            val refresh = button(localized.getString(R.string.files_reload))
            val sorting = button(localized.getString(R.string.files_sort_name))
            val refreshBounds = android.graphics.Rect().also(refresh::getBoundsInScreen)
            val sortBounds = android.graphics.Rect().also(sorting::getBoundsInScreen)
            assertTrue(refresh.isEnabled)
            assertTrue(refreshBounds.right <= sortBounds.left)
            assertTrue(sortBounds.right <= 320 * density)
            assertTrue(refreshBounds.height() >= 48 * density - 1)
            assertTrue(sortBounds.height() >= 48 * density - 1)
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
            java.io.File(context.getExternalFilesDir(null), "channel-files-$language-$font.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
            for ((status, connected) in listOf("ready" to true, "loading" to true, "failed" to true, "failed" to false)) {
                scenario.onActivity { activity ->
                    activity.setContent { CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized, LocalConfiguration provides configuration) {
                        MaterialTheme { Box(Modifier.width(320.dp).background(Palette.bottom)) {
                            ChannelFilesDrawer(SessionUiState(snapshot = Snapshot(status = if (connected) "connected" else "disconnected"),
                                channelFiles = files.copy(path = if (status == "ready") "/" else files.path, sort = "newest", status = status, entries = emptyList())))
                        } }
                    } }
                }
                instrumentation.waitForIdleSync()
                val refreshes = nodes().filter { (it.isClickable || it.className?.toString() == "android.widget.Button") && (it.text?.toString() == localized.getString(R.string.files_reload) || it.contentDescription?.toString() == localized.getString(R.string.files_reload)) }
                assertEquals(if (status == "failed") 2 else 1, refreshes.size)
                assertTrue(refreshes.all { it.isEnabled == (connected && status != "loading") })
                assertTrue(button(localized.getString(R.string.files_sort_newest)).isEnabled)
            }
        }
    }
}
