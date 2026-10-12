package dev.mobilespeak.mobilespeak

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
        var font by mutableStateOf(1f)
        var language by mutableStateOf("en")
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
                        (0 until count).map { Member(channel * 1000 + it, channel, "fixture-$channel-$it", "A$it User",
                            null, emptyList(), emptyList(), null, false, false, it == 0) }
                    }
                    CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized,
                        LocalConfiguration provides configuration, LocalResources provides localized.resources, LocalContentColor provides Palette.text) {
                        MaterialTheme(colorScheme = darkColorScheme(background = Palette.background, surface = Palette.card, onSurface = Palette.text)) {
                            ProvideTextStyle(TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontFamily = FontFamily.SansSerif)) {
                                Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
                                    Box(Modifier.width(width).height(720.dp).testTag("preview-fixture")) {
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
            for (lang in listOf("en", "zh-Hans")) for ((screen, scale) in listOf(320 to 1f, 280 to 1f, 390 to 1.4f)) {
                for (count in listOf(5, 9, 10, 100, 5)) {
                    onMain { language = lang; width = screen.dp; font = scale; total = count }
                    SystemClock.sleep(150); instrumentation.waitForIdleSync()
                    onMain {
                        val all = nodes()
                        val summaries = all.filter { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.startsWith("· ") == true }
                        assertEquals("Empty channels must have no preview", 3, summaries.size)
                        for (summary in summaries) {
                            val column = generateSequence(summary.parent) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
                            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                            val avatars = descendants(column).filter { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.matches(Regex("A[0-9]")) == true }
                            val totalHere = summary.config[SemanticsProperties.Text].single().text.split(' ')[1].toInt()
                            val available = column.size.width - with(Density(density)) { 30.dp.roundToPx() }
                            val stride = with(Density(density)) { 24.dp.roundToPx() + 5.dp.roundToPx() }
                            assertTrue(summary.boundsInRoot.right <= column.boundsInRoot.right + 1)
                            val layout = mutableListOf<TextLayoutResult>()
                            summary.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
                            val naturalWidth = ceil(layout.single().multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
                            assertEquals(channelPreviewAvatarCount(available, naturalWidth, totalHere, stride), avatars.size)
                            assertEquals("Text must remain complete whenever it fits", naturalWidth > available, layout.single().isLineEllipsized(0))
                            if (avatars.isNotEmpty()) {
                                assertEquals(5 * density, summary.boundsInRoot.left - (avatars.last().boundsInRoot.center.x + 12 * density), 1f)
                                avatars.zipWithNext().forEach { (a, b) -> assertEquals(stride.toFloat(), b.boundsInRoot.center.x - a.boundsInRoot.center.x, 1f) }
                            }
                        }
                        if (count == 5) {
                            val fixture = all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "preview-fixture" }
                            val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                            val cropped = Bitmap.createBitmap(shot, fixture.positionOnScreen.x.roundToInt(), fixture.positionOnScreen.y.roundToInt(), fixture.size.width, minOf(fixture.size.height, shot.height - fixture.positionOnScreen.y.roundToInt()))
                            File(context.getExternalFilesDir(null), "channel-preview-$lang-$screen-$scale.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        }
    }
}
