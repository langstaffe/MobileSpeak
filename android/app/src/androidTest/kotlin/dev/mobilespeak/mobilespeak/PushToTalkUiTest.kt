package dev.mobilespeak.mobilespeak

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PushToTalkUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val lobby = Channel(1, 0, 0, "Lobby", false, true, "lobby", null)
    private val own = Member(1, 1, "self", "Me", null, emptyList(), emptyList(), null, false, false, false)
    private fun fixture() = SessionUiState(
        snapshot = Snapshot(status = "connected", serverId = "fixture", ownClient = 1, canSend = true,
            channels = listOf(lobby), clients = listOf(own)),
        speech = SpeechState("push_to_talk", "first", true, false), microphoneMuted = false, microphonePermission = true)

    @Test fun switchingHomeTabCancelsTheHeldSessionPress() {
        assumeUnlocked()
        fun field(name: String) = ClientSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(ClientSession) as kotlinx.coroutines.flow.MutableStateFlow<SessionUiState>
        val saved = state.value
        val handle = (field("handle\$delegate").get(ClientSession) as Lazy<*>).value as Long
        val poll = ClientSession::class.java.getDeclaredMethod("scheduleCorePoll").apply { isAccessible = true }
        val executor = field("coreExecutor").get(ClientSession) as java.util.concurrent.ExecutorService
        try {
            NativeCore.setNotifier(handle, Runnable {})
            executor.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { state.value = fixture() }
                instrumentation.waitForIdleSync()
                val bounds = buttonBounds()
                val downTime = pointer(MotionEvent.ACTION_DOWN, bounds)
                waitFor { field("heldSpeechToken").get(ClientSession) == "first" }
                val segment = ClientSession.captureSegment()
                val membersLabel = context.localized(R.string.tab_members)
                var members = find { it.text?.toString() == membersLabel || it.contentDescription?.toString() == membersLabel }
                while (members != null && !members.isClickable) members = members.parent
                assertNotNull(members)
                assertTrue(members!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitFor { ClientSession.captureSegment() > segment }
                assertNull("Leaving a home tab ends the session press", field("heldSpeechToken").get(ClientSession))
                pointer(MotionEvent.ACTION_MOVE, bounds, downTime)
                assertNull("Moving within the fixed button cannot resume the old press", field("heldSpeechToken").get(ClientSession))
                pointer(MotionEvent.ACTION_UP, bounds, downTime)
            }
        } finally {
            ClientSession.cancelPushToTalk()
            instrumentation.runOnMainSync { state.value = saved }
            NativeCore.setNotifier(handle, Runnable { poll.invoke(ClientSession) })
            poll.invoke(ClientSession)
        }
    }

    @Test fun immediateHoldSlideCancelTokenChangeAndDisposalUsePhysicalTouch() {
        assumeUnlocked()
        val events = CopyOnWriteArrayList<Pair<Boolean, String>>()
        val ui = mutableStateOf(fixture())
        val visible = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize().background(Palette.background).systemBarsPadding(), contentAlignment = Alignment.Center) {
                        if (visible.value) Column(Modifier.padding(20.dp)) {
                            PushToTalkButton(ui.value) { pressed, token -> events += pressed to token }
                        }
                    }
                }
            } }
            instrumentation.waitForIdleSync()
            val bounds = buttonBounds()
            val sampleX = bounds.left + minOf(bounds.width() / 4, bounds.height() / 2)
            val sampleY = bounds.centerY()
            val idleImage = instrumentation.uiAutomation.takeScreenshot()
            val idlePixel = idleImage.getPixel(sampleX, sampleY)
            idleImage.recycle()
            var downTime = pointer(MotionEvent.ACTION_DOWN, bounds)
            waitFor { events.size == 1 }
            assertEquals(true to "first", events.single())
            SystemClock.sleep(120)
            val pressedImage = instrumentation.uiAutomation.takeScreenshot()
            val pressedPixel = pressedImage.getPixel(sampleX, sampleY)
            File(context.cacheDir, "push-to-talk-pressed.png").outputStream().use {
                pressedImage.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            pressedImage.recycle()
            assertFalse("Press feedback must be checked before transmission turns green", ui.value.speech.transmitting)
            val idleChannels = listOf(Color.red(idlePixel), Color.green(idlePixel), Color.blue(idlePixel))
            val pressedChannels = listOf(Color.red(pressedPixel), Color.green(pressedPixel), Color.blue(pressedPixel))
            assertTrue("Native iOS press feedback brightens the surface rather than dimming it",
                pressedChannels.zip(idleChannels).all { (pressed, idle) -> pressed >= idle } &&
                    pressedChannels.zip(idleChannels).any { (pressed, idle) -> pressed > idle })
            // Moving out ends this press, and moving back cannot restart it.
            pointer(MotionEvent.ACTION_MOVE, bounds, downTime, y = bounds.top - 30f)
            waitFor { events.size == 2 }
            pointer(MotionEvent.ACTION_MOVE, bounds, downTime)
            pointer(MotionEvent.ACTION_UP, bounds, downTime)
            assertEquals(listOf(true to "first", false to "first"), events.toList())

            downTime = pointer(MotionEvent.ACTION_DOWN, bounds)
            waitFor { events.size == 3 }
            scenario.onActivity { ui.value = ui.value.copy(speech = ui.value.speech.copy(token = "new-channel")) }
            waitFor { events.size == 4 }
            assertEquals(false to "first", events.last())
            pointer(MotionEvent.ACTION_MOVE, bounds, downTime)
            pointer(MotionEvent.ACTION_UP, bounds, downTime)
            assertEquals(4, events.size)

            downTime = pointer(MotionEvent.ACTION_DOWN, bounds)
            waitFor { events.size == 5 }
            assertEquals(true to "new-channel", events.last())
            scenario.onActivity { ui.value = ui.value.copy(microphoneMuted = true) }
            waitFor { events.size == 6 }
            scenario.onActivity { ui.value = ui.value.copy(microphoneMuted = false) }
            instrumentation.waitForIdleSync()
            pointer(MotionEvent.ACTION_MOVE, bounds, downTime)
            pointer(MotionEvent.ACTION_UP, bounds, downTime)
            assertEquals("Unmute while held must not resume", 6, events.size)

            downTime = pointer(MotionEvent.ACTION_DOWN, bounds)
            waitFor { events.size == 7 }
            pointer(MotionEvent.ACTION_CANCEL, bounds, downTime)
            waitFor { events.size == 8 }
            downTime = pointer(MotionEvent.ACTION_DOWN, bounds)
            waitFor { events.size == 9 }
            scenario.onActivity { visible.value = false }
            waitFor { events.size == 10 }
            pointer(MotionEvent.ACTION_UP, bounds, downTime)
            assertEquals(false to "new-channel", events.last())
        }
    }

    @Test fun disabledHiddenAndActualTransmissionStatesRenderAndChatUsesCurrentChannel() {
        assumeUnlocked()
        val ui = mutableStateOf(fixture())
        val commands = CopyOnWriteArrayList<Pair<Boolean, String>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Column(Modifier.fillMaxSize().background(Palette.bottom).systemBarsPadding().padding(20.dp),
                        verticalArrangement = Arrangement.Center) {
                        PushToTalkButton(ui.value, onTransmit = { pressed, token -> commands += pressed to token })
                        Spacer(Modifier.height(20.dp))
                        PushToTalkButton(ui.value.copy(speech = ui.value.speech.copy(transmitting = true)), onTransmit = { _, _ -> })
                        Spacer(Modifier.height(20.dp))
                        PushToTalkButton(ui.value.copy(microphoneMuted = true), onTransmit = { _, _ -> })
                    }
                }
            } }
            instrumentation.waitForIdleSync()
            waitFor { allButtons().size == 3 }
            val buttons = allButtons().sortedBy { node -> Rect().also(node::getBoundsInScreen).top }
            screenshot("push-to-talk-native-states.png")
            println("PTT buttons: " + buttons.map { node -> "${node.contentDescription} enabled=${node.isEnabled} bounds=${Rect().also(node::getBoundsInScreen)}" })
            assertEquals(3, buttons.size)
            assertTrue(buttons[0].isEnabled)
            assertTrue(buttons[1].isEnabled)
            assertFalse(buttons[2].isEnabled)
            val heightDp = Rect().also(buttons[0]::getBoundsInScreen).height() / context.resources.displayMetrics.density
            assertEquals("Visible surface follows measured iOS native Join button", 68f, heightDp, 1f)
            // A pending DSP switch must disable locally before the shared available flag catches up.
            scenario.onActivity { ui.value = ui.value.copy(audioProcessingStatus = "switching") }
            instrumentation.waitForIdleSync()
            waitFor { allButtons().size == 3 && allButtons().none { it.isEnabled } }
            val switchingButton = allButtons().sortedBy { node -> Rect().also(node::getBoundsInScreen).top }.first()
            assertFalse(switchingButton.isEnabled)
            assertTrue("Fixture retains the stale shared availability", ui.value.speech.available)
            val switchingBounds = Rect().also(switchingButton::getBoundsInScreen)
            val switchDown = pointer(MotionEvent.ACTION_DOWN, switchingBounds)
            pointer(MotionEvent.ACTION_UP, switchingBounds, switchDown)
            assertTrue("DSP switching must not begin transmission", commands.isEmpty())
            scenario.onActivity { ui.value = ui.value.copy(audioProcessingStatus = "ready") }
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    ChatScreen(ChatTarget("client:peer", "Private peer", member = own.copy(id = 2, uid = "peer", name = "Private peer")),
                        ui.value, active = true, onBack = {})
                }
            } }
            instrumentation.waitForIdleSync()
            waitFor { find { it.text?.contains("Lobby") == true } != null }
            buttonBounds()
            screenshot("push-to-talk-private-chat.png")
            scenario.onActivity { ui.value = ui.value.copy(speech = SpeechState()) }
            instrumentation.waitForIdleSync()
            waitFor { find { it.contentDescription == context.localized(R.string.voice_hold_to_talk) } == null }
            assertNull(find { it.contentDescription == context.localized(R.string.voice_hold_to_talk) })
            assertNull(find { it.text?.contains("Lobby") == true })
        }
    }

    @Test fun fixedButtonRemainsUsableWhileDrawerExpandsAndNavigates() {
        assumeUnlocked()
        val ui = mutableStateOf(fixture())
        val expanded = mutableStateOf(false)
        val events = CopyOnWriteArrayList<Pair<Boolean, String>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Column(Modifier.fillMaxSize().background(Palette.background).systemBarsPadding()) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            Text("Channel list", Modifier.align(Alignment.TopCenter))
                            VoiceDrawer(ui.value, {}, expanded.value, { expanded.value = it }, maxHeight, maxWidth,
                                Modifier.align(Alignment.BottomCenter))
                        }
                        PushToTalkBar(ui.value, {}, onTransmit = { pressed, token -> events += pressed to token })
                        Spacer(Modifier.height(62.dp))
                    }
                }
            } }
            instrumentation.waitForIdleSync()
            val original = buttonBounds()
            scenario.onActivity { expanded.value = true }
            SystemClock.sleep(800)
            instrumentation.waitForIdleSync()
            assertEquals(original, buttonBounds())
            val down = pointer(MotionEvent.ACTION_DOWN, original)
            waitFor { events.size == 1 }
            pointer(MotionEvent.ACTION_MOVE, original, down, y = original.top + 5f)
            assertTrue("PTT must not drag the drawer", expanded.value)
            pointer(MotionEvent.ACTION_UP, original, down)
            waitFor { events.size == 2 }
            assertEquals(original, buttonBounds())
            // Drawer internal navigation never owns or moves the separate bottom row.
            scenario.onActivity { ui.value = ui.value.copy(userVolumesOpen = true) }
            SystemClock.sleep(500)
            instrumentation.waitForIdleSync()
            waitFor { find { it.text?.toString() == context.localized(R.string.volume_title) } != null }
            assertEquals(original, buttonBounds())
            val next = pointer(MotionEvent.ACTION_DOWN, original)
            waitFor { events.size == 3 }
            pointer(MotionEvent.ACTION_UP, original, next)
            waitFor { events.size == 4 }
            screenshot("push-to-talk-drawer-expanded.png")
            scenario.onActivity { ui.value = ui.value.copy(userVolumesOpen = false) }
        }
    }

    private fun assumeUnlocked() {
        assumeTrue("Unlock emulator for gesture verification", !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        ClientSession.initialize(context)
        assumeTrue("UI fixture cannot replace a live call", !ClientSession.shouldRunService())
    }
    private fun waitFor(predicate: () -> Boolean) {
        repeat(100) { if (predicate()) return; SystemClock.sleep(20) }
        assertTrue("UI did not reach expected state", predicate())
    }
    private fun pointer(action: Int, bounds: Rect, downTime: Long = SystemClock.uptimeMillis(),
        x: Float = bounds.exactCenterX(), y: Float = bounds.exactCenterY()): Long {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        instrumentation.waitForIdleSync()
        return downTime
    }
    private fun buttonBounds() = Rect().also {
        waitFor { find { it.contentDescription?.toString() == context.localized(R.string.voice_hold_to_talk) } != null }
        val node = find { it.contentDescription == context.localized(R.string.voice_hold_to_talk) }
        assertNotNull("Push to talk must remain visible", node)
        node!!.getBoundsInScreen(it)
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun allButtons(): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            node ?: return
            if (node.contentDescription in listOf(context.localized(R.string.voice_hold_to_talk), context.localized(R.string.voice_transmitting))) nodes += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return nodes
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
