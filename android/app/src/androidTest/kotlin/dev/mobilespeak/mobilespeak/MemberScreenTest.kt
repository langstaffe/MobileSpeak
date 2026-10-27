package dev.mobilespeak.mobilespeak

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class MemberScreenTest {
    @Test fun selfIdentityTracksSnapshotsAndOnlyPeersKeepPrivateChatAndPressFeedback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Unlock the Android device for native touch/pixel checks",
            !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        ClientSession.initialize(context)
        assumeTrue("Member fixtures must not replace a live session", !ClientSession.shouldRunService())
        fun member(id: Long, name: String = "Same nickname", uid: String? = "uid-$id") =
            Member(id, 1, uid, name, null, emptyList(), emptyList(), null, false, false, false)
        val own = member(7)
        val peer = member(9)
        var snapshot by mutableStateOf(Snapshot(status = "connected", ownClient = 7, clients = listOf(peer, own)))
        var width by mutableStateOf(320.dp)
        var font by mutableFloatStateOf(1f)
        var language by mutableStateOf("en")
        val chats = mutableListOf<Long>()
        val suffixes = mapOf("en" to " (You)", "zh-Hans" to "（我）", "zh-Hant" to "（我）", "de" to " (du)",
            "ru" to " (вы)", "pl" to " (ty)", "es-MX" to " (tú)", "fr" to " (vous)", "pt-BR" to " (você)", "es" to " (tú)", "ja" to "（自分）", "ko" to " (나)")
        assertEquals(AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.map { it.code }.toSet(), suffixes.keys)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { current ->
                activity = current
                current.setContent {
                    val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
                    val localized = context.createConfigurationContext(configuration)
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                        LocalResources provides localized.resources, LocalDensity provides Density(density, font), LocalContentColor provides Palette.text) {
                        MaterialTheme(colorScheme = darkColorScheme(background = Palette.background, surface = Palette.card, onSurface = Palette.text)) {
                            ProvideTextStyle(TextStyle(color = Palette.text, fontSize = 17.sp, lineHeight = 20.sp)) {
                                Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
                                    Box(Modifier.width(width).height(520.dp)) {
                                        MemberScreen(SessionUiState(snapshot = snapshot,
                                            unread = Unread(privateCounts = mapOf("uid-7" to 88, "uid-9" to 4)))) { chats += it.id }
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
            fun nodes(): List<SemanticsNode> = onMain {
                fun root(view: View): androidx.compose.ui.node.RootForTest? = (view as? androidx.compose.ui.node.RootForTest)
                    ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                collect(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode)
            }
            fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(350) }
            fun descriptions() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            fun label(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() == value }
            fun clickable(node: SemanticsNode) = generateSequence(node) { it.parent }.firstOrNull { it.config.contains(SemanticsActions.OnClick) }
            fun touch(action: Int, bounds: Rect, down: Long, delta: Float = 0f) = onMain {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.center.x, bounds.center.y + delta, 0)
                activity.window.decorView.dispatchTouchEvent(event); event.recycle()
            }
            fun shot(name: String): Bitmap {
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "members-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return bitmap
            }
            settle()
            var selfLabel = label("Same nickname (You)")
            val peerLabel = label("Same nickname")
            assertTrue("The original snapshot order must survive", peerLabel.boundsInWindow.top < selfLabel.boundsInWindow.top)
            assertNull("Self must remain readable without a click action", clickable(selfLabel))
            assertFalse(selfLabel.config.contains(SemanticsProperties.Disabled))
            assertNotNull(clickable(peerLabel))
            assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "88" } == true })
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "4" } == true })

            // Real pointer events check the production indication, activation and cancellation paths.
            for ((name, node) in listOf("self" to selfLabel, "peer" to peerLabel)) {
                val bounds = node.boundsInWindow
                val before = shot("$name-before")
                val down = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, bounds, down); SystemClock.sleep(250)
                val pressed = shot("$name-held")
                var changed = 0
                for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
                    if (before.getPixel(x, y) != pressed.getPixel(x, y)) changed++
                }
                if (name == "self") assertEquals("Self has no pressed visual state", 0, changed)
                else assertTrue("Peer content retains its existing pressed fade", changed > 20)
                touch(MotionEvent.ACTION_UP, bounds, down); settle()
                assertEquals(if (name == "self") emptyList<Long>() else listOf(9L), chats)
                before.recycle(); pressed.recycle()
            }
            val peerBounds = clickable(label("Same nickname"))!!.boundsInWindow
            val cancelled = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, peerBounds, cancelled); touch(MotionEvent.ACTION_CANCEL, peerBounds, cancelled)
            settle(); assertEquals(listOf(9L), chats)
            val identityChanged = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, peerBounds, identityChanged)
            onMain { snapshot = snapshot.copy(ownClient = 9) }; settle()
            touch(MotionEvent.ACTION_UP, peerBounds, identityChanged); settle()
            assertEquals("A row becoming self must cancel an in-flight private-chat press", listOf(9L), chats)
            assertTrue(label("Same nickname (You)").boundsInWindow.top < label("Same nickname").boundsInWindow.top)

            onMain { snapshot = snapshot.copy(ownClient = 7, clients = listOf(own)) }; settle()
            assertEquals(listOf("Same nickname (You)"), descriptions().filter { it.startsWith("Same nickname") })
            onMain { snapshot = snapshot.copy(clients = listOf(peer.copy(uid = own.uid), own)) }; settle()
            assertNotNull("UID and name matches cannot turn a peer into self", clickable(label("Same nickname")))
            assertNull(clickable(label("Same nickname (You)")))
            onMain { snapshot = snapshot.copy(ownClient = null, clients = listOf(peer, own)) }; settle()
            assertFalse(descriptions().any { it.contains("(You)") })
            assertEquals(2, nodes().count { it.config.contains(SemanticsActions.OnClick) })
            onMain { snapshot = snapshot.copy(ownClient = 9, connectionId = "second-server", clients = listOf(own, peer.copy(name = "Renamed by server"))) }; settle()
            assertNotNull(clickable(label("Same nickname")))
            assertNull(clickable(label("Renamed by server (You)")))
            onMain { snapshot = Snapshot(status = "reconnecting") }; settle()
            assertFalse(descriptions().any { it.contains("(You)") })
            onMain { snapshot = Snapshot(status = "connected", ownClient = 42, connectionId = "reconnected",
                clients = listOf(member(42, "New connection", null), member(9, "UID unavailable", null))) }; settle()
            assertNull(clickable(label("New connection (You)")))
            assertTrue(clickable(label("UID unavailable"))!!.config.contains(SemanticsProperties.Disabled))
            assertEquals(listOf(9L), chats)

            val groups = (1L..4L).map { GroupIcon(it, "Server Group $it", if (it == 1L) 300 else 100, null) }
            val longName = "Very long nickname 用户👩🏽‍💻é ".repeat(6)
            val ordinaryHeights = mutableMapOf<String, Float>()
            for ((lang, suffix) in suffixes) for ((screen, scale) in listOf(320 to 1f, 320 to 2f, 390 to 1f)) {
                onMain {
                    language = lang; font = scale; width = screen.dp
                    snapshot = Snapshot(status = "connected", ownClient = 7, clients = listOf(own.copy(name = longName,
                        serverGroupIcons = groups, channelGroupIcon = groups.first().copy(id = 3, name = "Channel Admin"))))
                }; settle()
                selfLabel = label(longName + suffix)
                assertNull(clickable(selfLabel))
                assertEquals(1, descriptions().count { it == longName + suffix })
                val icon = label("Server Group 1")
                assertTrue("The name/marker must stop before right-side icons", selfLabel.boundsInWindow.right <= icon.boundsInWindow.left + 1)
                assertTrue("The icon strip must stay inside the row", label("Channel Admin").boundsInWindow.right <= width.value * context.resources.displayMetrics.density)
                if (scale == 1f) ordinaryHeights[lang] = selfLabel.boundsInWindow.height
                else assertTrue("The text must grow with the system font setting", selfLabel.boundsInWindow.height > ordinaryHeights.getValue(lang) * 1.3f)
                shot("$lang-width-$screen-font-$scale").recycle()
            }

            // Dragging a member still scrolls and cancels its private-chat activation.
            onMain {
                language = "en"; font = 1f; width = 320.dp
                snapshot = Snapshot(status = "connected", ownClient = 7,
                    clients = listOf(own.copy(name = "Self")) + (10L..35L).map { member(it, "Peer $it") })
            }; settle()
            val scrollingPeer = clickable(label("Peer 12"))!!.boundsInWindow
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, scrollingPeer, down)
            for (step in 1..5) { touch(MotionEvent.ACTION_MOVE, scrollingPeer, down, -step * 20 * context.resources.displayMetrics.density); SystemClock.sleep(30) }
            touch(MotionEvent.ACTION_UP, scrollingPeer, down, -100 * context.resources.displayMetrics.density)
            settle()
            assertEquals(listOf(9L), chats)
            assertTrue("Member list still scrolls", clickable(label("Peer 12"))!!.boundsInWindow.top < scrollingPeer.top)
        }
    }
}
