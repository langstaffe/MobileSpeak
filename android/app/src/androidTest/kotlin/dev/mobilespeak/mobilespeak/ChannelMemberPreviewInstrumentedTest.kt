package dev.mobilespeak.mobilespeak

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

class ChannelMemberPreviewInstrumentedTest {
    @Test fun actualCardsAdaptWithoutOverlappingChatAndKeepClicks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val channels = (1L..5L).map { Channel(it, if (it == 1L) 0 else it - 1, 0,
            listOf("Current channel", "Ordinary channel", "Empty channel", "Empty child", "Deep channel")[it.toInt() - 1], false, true, "fixture-$it", null) }
        var total by mutableStateOf(5)
        var width by mutableStateOf(320.dp)
        var height by mutableStateOf(720.dp)
        var font by mutableStateOf(1f)
        var language by mutableStateOf("en")
        var nickname by mutableStateOf("Solo User")
        var status by mutableStateOf(0)
        var selects = 0
        var chats = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { current ->
                activity = current
                current.setContent {
                    val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
                    val localized = context.createConfigurationContext(configuration)
                    val members = listOf(1L to 1, 2L to total, 5L to 10).flatMap { (channel, count) ->
                        (0 until count).map { Member(channel * 1000 + it, channel, "fixture-$channel-$it", if (channel == 2L && count == 1) nickname else "A$it User",
                            null, emptyList(), emptyList(), null, status == 1, status == 2, it == 0, away = status == 3) }
                    }
                    CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized,
                        LocalConfiguration provides configuration, LocalResources provides localized.resources, LocalContentColor provides Palette.text) {
                        MaterialTheme(colorScheme = darkColorScheme(background = Palette.background, surface = Palette.card, onSurface = Palette.text)) {
                            ProvideTextStyle(TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontFamily = FontFamily.SansSerif)) {
                                Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
                                    Box(Modifier.width(width).height(height).testTag("preview-fixture")) {
                                        ChannelScreen(SessionUiState(snapshot = Snapshot(status = "connected", ownClient = 1000, channels = channels, clients = members)),
                                            { selects++ }, { chats++ })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            fun <T> onMain(block: () -> T): T {
                var result: T? = null
                instrumentation.runOnMainSync { result = block() }
                @Suppress("UNCHECKED_CAST") return result as T
            }
            fun nodes(): List<SemanticsNode> {
                fun root(view: View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? ViewGroup)?.let { group ->
                    (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) }
                }
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                return collect(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode)
            }
            for (lang in listOf("en", "zh-Hans")) for ((screen, scale) in listOf(320 to 1f, 280 to 1f, 390 to 1.4f, 220 to 2f)) {
                val cases = listOf(5 to "A0 User", 9 to "A0 User", 10 to "A0 User", 100 to "A0 User",
                    0 to "A0 User", 1 to "Solo User", 2 to "Solo User", 1 to "王小明",
                    1 to ("👩🏽‍💻é用户 " + "long nickname ".repeat(12)), 1 to "", 1 to "Server renamed user",
                    2 to "Server renamed user", 1 to "Solo User", 0 to "", 5 to "A0 User")
                for ((caseIndex, fixture) in cases.withIndex()) {
                    val (count, name) = fixture
                    onMain { language = lang; width = screen.dp; font = scale; total = count; nickname = name; status = when(caseIndex) { 7 -> 1; 8 -> 2; 10 -> 3; else -> 0 } }
                    SystemClock.sleep(150); instrumentation.waitForIdleSync()
                    onMain {
                        val all = nodes()
                        val summaries = all.filter { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.startsWith("· ") == true }
                        assertEquals("Empty channels must have no preview", if(count == 0) 2 else 3, summaries.size)
                        for (summary in summaries) {
                            val column = generateSequence(summary.parent) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                            val totalHere = summary.config[SemanticsProperties.Text].single().text.split(' ')[1].toInt()
                            val available = column.size.width - with(Density(density)) { 30.dp.roundToPx() }
                            val stride = with(Density(density)) { 24.dp.roundToPx() + 5.dp.roundToPx() }
                            assertTrue(summary.boundsInRoot.right <= column.boundsInRoot.right + 1)
                            val layout = mutableListOf<TextLayoutResult>()
                            summary.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
                            val naturalWidth = ceil(layout.single().multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
                            val expectedAvatars = channelPreviewAvatarCount(available, naturalWidth, totalHere, stride)
                            assertEquals("Text must remain complete whenever it fits", naturalWidth > available, layout.single().isLineEllipsized(0))
                            val namesHere = descendants(column).filter {
                                it.config.contains(SemanticsProperties.HideFromAccessibility) &&
                                it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == (if(summary === summaries.first()) "A0 User" else nickname) &&
                                    kotlin.math.abs(it.boundsInRoot.center.y - summary.boundsInRoot.center.y) < 1
                            }
                            if (totalHere != 1 || (summary !== summaries.first() && nickname.isEmpty())) assertTrue(namesHere.isEmpty())
                            val nameNode = namesHere.singleOrNull()
                            val extra = nameNode?.let { it.size.width + with(Density(density)) { 5.dp.roundToPx() } } ?: 0
                            assertEquals(expectedAvatars * stride + extra.toFloat(), summary.boundsInRoot.left - column.boundsInRoot.left - with(Density(density)) { 30.dp.roundToPx() }, 1f)
                            if (nameNode != null) {
                                assertEquals(summary.boundsInRoot.left - with(Density(density)) { 5.dp.roundToPx() }, nameNode.boundsInRoot.right, 1f)
                                val nameLayout = mutableListOf<TextLayoutResult>()
                                nameNode.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(nameLayout)
                                assertEquals(1, nameLayout.single().lineCount)
                                assertEquals(layout.single().layoutInput.style, nameLayout.single().layoutInput.style)
                                assertTrue(nameNode.config.contains(SemanticsProperties.HideFromAccessibility))
                            }
                            if (totalHere == 1) {
                                val accessible = summary.config[SemanticsProperties.ContentDescription].single()
                                assertTrue(accessible.contains(if(summary === summaries.first()) "A0 User" else nickname))
                                assertTrue(accessible.endsWith(summary.config[SemanticsProperties.Text].single().text))
                                assertEquals(1, descendants(column).count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(accessible) == true })
                            } else if (status == 0) {
                                val avatars = descendants(column).filter { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.matches(Regex("A[0-9]")) == true }
                                assertEquals(expectedAvatars, avatars.size)
                                avatars.zipWithNext().forEach { (a, b) -> assertEquals(stride.toFloat(), b.boundsInRoot.center.x - a.boundsInRoot.center.x, 1f) }
                            }
                        }
                        if (count == 5 || count == 1 || count == 2 || count == 0) {
                            val fixture = all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "preview-fixture" }
                            val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                            val cropped = Bitmap.createBitmap(shot, fixture.positionOnScreen.x.roundToInt(), fixture.positionOnScreen.y.roundToInt(), fixture.size.width, minOf(fixture.size.height, shot.height - fixture.positionOnScreen.y.roundToInt()))
                            File(context.getExternalFilesDir(null), "channel-preview-$lang-$screen-$scale-case-$caseIndex.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            cropped.recycle(); shot.recycle()
                        }
                    }
                }
            }
            onMain {
                val all = nodes()
                val column = all.first { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "Current channel" }.let { title ->
                    generateSequence(title.parent) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                }
                assertTrue(column.config[SemanticsActions.OnClick].action!!.invoke()); assertEquals(1, selects)
                val chat = all.first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull()?.contains("Current channel") == true }
                val button = generateSequence(chat) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke()); assertEquals(1, chats)
            }
            onMain { width = 320.dp; height = 320.dp; font = 1f; language = "en"; total = 1; nickname = "Solo User"; status = 0 }
            SystemClock.sleep(200); instrumentation.waitForIdleSync()
            fun column(title: String) = onMain {
                nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == title }.let {
                    generateSequence(it.parent) { node -> node.parent }.first { node -> node.config.contains(SemanticsActions.OnClick) }
                }
            }
            fun touch(action: Int, x: Float, y: Float, down: Long) = onMain {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                activity.window.decorView.dispatchTouchEvent(event); event.recycle()
            }
            val current = column("Current channel").boundsInRoot
            var down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, current.center.x, current.center.y, down)
            SystemClock.sleep(150)
            touch(MotionEvent.ACTION_CANCEL, current.center.x, current.center.y, down)
            instrumentation.waitForIdleSync(); assertEquals(1, selects)
            down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, current.center.x, current.center.y, down)
            touch(MotionEvent.ACTION_UP, current.center.x, current.center.y, down)
            instrumentation.waitForIdleSync(); assertEquals(2, selects)
            val ordinary = column("Ordinary channel").boundsInRoot
            down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, ordinary.center.x, ordinary.center.y, down)
            for (step in 1..5) {
                touch(MotionEvent.ACTION_MOVE, ordinary.center.x, ordinary.center.y - 20 * step * density, down)
                SystemClock.sleep(30)
            }
            touch(MotionEvent.ACTION_UP, ordinary.center.x, ordinary.center.y - 100 * density, down)
            SystemClock.sleep(200); instrumentation.waitForIdleSync()
            assertEquals("Dragging must cancel the card click", 2, selects)
            assertTrue("The channel list must still scroll", column("Ordinary channel").boundsInRoot.top < ordinary.top)
        }
    }
}
