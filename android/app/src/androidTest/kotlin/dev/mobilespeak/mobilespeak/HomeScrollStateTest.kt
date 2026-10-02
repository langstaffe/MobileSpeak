package dev.mobilespeak.mobilespeak

import android.app.KeyguardManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HomeScrollStateTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var activity: MainActivity

    @Test fun tabsKeepIndependentOffsetsAcrossNavigationDisconnectAndShorterLists() {
        assumeTrue("Unlock the device for UI acceptance", !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        ClientSession.initialize(context)
        assumeTrue("Scroll fixtures must not replace a real connection", !ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")
        val handle = (field("handle\$delegate").get(ClientSession) as Lazy<*>).value as Long
        val poll = ClientSession.javaClass.getDeclaredMethod("scheduleCorePoll").apply { isAccessible = true }
        val saved = ClientSession.state.value
        val channels = (1..120).map { Channel(it.toLong(), 0, (it - 1).toLong(), "Channel %03d".format(it), false, true, "fixture-$it", null) }
        val members = (1..120).map { Member(it.toLong(), 1, "fixture-$it", "Member %03d".format(it), null, emptyList(), emptyList(), null, false, false, false) }
        val own = members.first().copy(id = 1000, channel = 20, uid = "fixture-own", name = "Own fixture")
        val fixture = saved.copy(snapshot = Snapshot(status = "connected", server = "Scroll fixture", serverId = "scroll-fixture", ownClient = own.id,
            channels = channels, clients = members + own), error = null, avatarStatus = "failed", avatarDetail = "Fixture detail\n".repeat(30))
        try {
            // Keep late initialization and chat visibility notifications from replacing the fixture.
            NativeCore.setNotifier(handle, Runnable {})
            (field("coreExecutor").get(ClientSession) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity = it }
                publish(fixture)
                scroll(index = 20, pixels = 37f)
                val channelPosition = position()
                assertTrue(channelPosition.axis > 0)
                click(context.localized(R.string.tab_members))
                scroll(index = 37, pixels = 29f)
                val memberPosition = position()
                assertTrue(memberPosition.axis > channelPosition.axis)
                click(context.localized(R.string.tab_settings))
                scroll(pixels = 170f)
                val settingsPosition = position()
                assertTrue(settingsPosition.axis > 0)
                repeat(4) {
                    click(context.localized(R.string.tab_channels)); assertEquals(channelPosition, position())
                    click(context.localized(R.string.tab_members)); assertEquals(memberPosition, position())
                    click(context.localized(R.string.tab_settings)); assertEquals(settingsPosition, position())
                }

                click(context.localized(R.string.tab_channels))
                click(context.localized(R.string.channel_chat_open, channels[19].name))
                assertTrue(onMain { nodes().any { it.config.contains(SemanticsProperties.EditableText) } })
                click(context.localized(R.string.action_back)); assertEquals(channelPosition, position())
                click(context.localized(R.string.tab_members))
                click(requireNotNull(memberPosition.first))
                assertTrue(onMain { nodes().any { it.config.contains(SemanticsProperties.EditableText) } })
                click(context.localized(R.string.action_back)); assertEquals(memberPosition, position())

                click(context.localized(R.string.tab_settings))
                scroll(pixels = 100000f)
                val settingsBottom = position()
                click(context.localized(R.string.settings_language))
                click(context.localized(R.string.action_back)); assertEquals(settingsBottom, position())
                publish(fixture.copy(snapshot = Snapshot()))
                assertEquals("Disconnect keeps settings offset", settingsBottom, position())
                click(context.localized(R.string.tab_channels))
                publish(fixture)
                assertEquals("A new connection starts channels at the top", 0f, position().axis, 0f)
                click(context.localized(R.string.tab_members))
                assertEquals("A new connection starts members at the top", 0f, position().axis, 0f)
                click(context.localized(R.string.tab_settings)); assertEquals(settingsBottom, position())

                click(context.localized(R.string.tab_channels)); scroll(index = 90, pixels = 19f)
                click(context.localized(R.string.tab_members)); scroll(index = 100, pixels = 23f)
                click(context.localized(R.string.tab_settings))
                publish(fixture.copy(snapshot = fixture.snapshot.copy(channels = channels.take(4), clients = members.take(4) + own.copy(channel = 1))))
                click(context.localized(R.string.tab_channels)); assertLegalShortList()
                click(context.localized(R.string.tab_members)); assertLegalShortList()
                click(context.localized(R.string.tab_settings)); assertEquals(settingsBottom, position())
            }
        } finally {
            instrumentation.runOnMainSync { state.value = saved }
            NativeCore.setNotifier(handle, Runnable { poll.invoke(ClientSession) })
            poll.invoke(ClientSession)
        }
    }

    private data class Position(val axis: Float, val first: String?, val top: Float?)

    private fun position(): Position = onMain {
        val scroll = scrollNode()
        val first = descendants(scroll).filter { node ->
            node.boundsInRoot.height > 0 && node.boundsInRoot.bottom > scroll.boundsInRoot.top &&
                texts(node).any { it.matches(Regex("(Channel|Member) [0-9]{3}")) }
        }.minByOrNull { it.positionInRoot.y }
        Position(scroll.config[SemanticsProperties.VerticalScrollAxisRange].value(),
            first?.let { texts(it).first { text -> text.matches(Regex("(Channel|Member) [0-9]{3}")) } },
            first?.let { it.positionInRoot.y - scroll.positionInRoot.y })
    }

    private fun assertLegalShortList() {
        val position = position()
        onMain {
            val scroll = scrollNode()
            val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(range.value().isFinite() && range.value() >= 0 && range.value() <= range.maxValue())
            assertEquals(5, scroll.config[SemanticsProperties.CollectionInfo].rowCount)
        }
        assertTrue("The first visible item belongs to the shortened list: $position", requireNotNull(position.first).substringAfterLast(' ').toInt() in 1..4)
        assertTrue("The native lazy position has a legal first index", position.axis < 5 * 500)
    }

    private fun scroll(index: Int? = null, pixels: Float) {
        index?.let { onMain { assertTrue(scrollNode().config[SemanticsActions.ScrollToIndex].action!!(it)) }; settle() }
        onMain { assertTrue(scrollNode().config[SemanticsActions.ScrollBy].action!!(0f, pixels)) }
        settle()
    }

    private fun click(label: String) {
        onMain {
            val node = nodes().firstOrNull { node ->
                (label in texts(node) || node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) &&
                    node.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            assertNotNull("Clickable $label", node)
            assertTrue(node!!.config[SemanticsActions.OnClick].action!!())
        }
        settle()
    }

    private fun publish(ui: SessionUiState) { instrumentation.runOnMainSync { state.value = ui }; settle() }
    private fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(500); instrumentation.waitForIdleSync() }
    private fun field(name: String) = ClientSession.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val state get() = field("mutableState").get(ClientSession) as MutableStateFlow<SessionUiState>
    private fun texts(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(): List<SemanticsNode> {
        fun root(view: View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) }
        }
        return descendants(requireNotNull(root(activity.window.decorView)).semanticsOwner.rootSemanticsNode)
    }
    private fun scrollNode() = nodes().first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.boundsInRoot.height > 0 }
    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
