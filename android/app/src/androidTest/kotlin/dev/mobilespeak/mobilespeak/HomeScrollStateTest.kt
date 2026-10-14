package dev.mobilespeak.mobilespeak

import android.app.KeyguardManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.sp
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
        // Keep cache notifications and loading indicators out of the synthetic connection fixture.
        val fixture = saved.copy(snapshot = Snapshot(status = "connected", server = "Scroll fixture", serverId = "scroll-fixture", ownClient = own.id,
            channels = channels, clients = members + own, networkQuality = NetworkQuality(rttMs = 34.0, deviationMs = .7, packetLossPercent = 0.0,
                rttGrade = NetworkGrade.GOOD, deviationGrade = NetworkGrade.GOOD, packetLossGrade = NetworkGrade.GOOD, iconGrade = NetworkGrade.GOOD,
                axisMaxMs = 50.0, nowSecond = 29, samples = (0L..29L).map { NetworkSample(it, 34.0, NetworkGrade.GOOD) })),
            microphoneMuted = false, error = null, avatarStatus = "failed", avatarDetail = "Fixture detail\n".repeat(30),
            fileImporting = true, fileCachePending = false, fileCache = FileCacheState(bytes = 0))
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
                val viewport = onMain { scrollNode().boundsInRoot }
                click(context.localized(R.string.voice_drawer_expand))
                click(context.localized(R.string.tab_channels), nativeTouch = true)
                checkPartiallyRevealedToolAndCapture()
                click(context.localized(R.string.tab_settings), nativeTouch = true)
                repeat(4) { refresh ->
                    click(context.localized(R.string.tab_channels), nativeTouch = true); assertEquals(channelPosition, position())
                    click(context.localized(R.string.tab_members), nativeTouch = true); assertEquals(memberPosition, position())
                    click(context.localized(R.string.tab_settings), nativeTouch = true); assertEquals(settingsPosition, position())
                    assertEquals("Expansion keeps the list viewport", viewport, onMain { scrollNode().boundsInRoot })
                    publish(fixture.copy(snapshot = fixture.snapshot.copy(clients = fixture.snapshot.clients.map {
                        if (it.id == own.id) it.copy(speaking = refresh % 2 == 0) else it
                    })))
                    assertDrawer(expanded = true, enabled = true)
                }
                click(context.localized(R.string.voice_drawer_collapse))

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
                val collapsedTop = drawerTop()
                val animationScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                val animationWait = maxOf(500L, (800 * animationScale).toLong())
                click(context.localized(R.string.voice_drawer_expand))
                settle(animationWait)
                val expandedTop = drawerTop()
                publish(fixture.copy(snapshot = Snapshot(status = "disconnected")), wait = false)
                settle(40)
                val closingTop = drawerTop()
                if (animationScale > 0f) {
                    assertTrue("Disconnect closes from the visible position", closingTop >= expandedTop - 1f)
                    assertTrue("Disconnect must not snap to the collapsed position", closingTop < collapsedTop - 1f)
                }
                assertDrawer(expanded = false, enabled = false)
                publish(fixture, wait = false) // Reconnect while automatic closing is still running.
                settle(40)
                assertTrue("Reconnect must not jump to an anchor", drawerTop() >= closingTop - 1f)
                assertDrawer(expanded = false, enabled = true)
                click(context.localized(R.string.voice_drawer_expand))
                settle(animationWait)
                assertDrawer(expanded = true, enabled = true)
                assertTrue("A new user action supersedes the old close animation", drawerTop() < collapsedTop - 1f)
                for (status in listOf("disconnected", "connecting", "reconnecting")) {
                    publish(fixture.copy(snapshot = Snapshot(status = status)))
                    assertDrawer(expanded = false, enabled = false)
                }
                settle(animationWait)
                assertEquals(collapsedTop, drawerTop(), 1f)
                assertEquals("Closing keeps the list viewport", viewport, onMain { scrollNode().boundsInRoot })
                assertEquals("Disconnect keeps settings offset", settingsBottom, position())
                click(context.localized(R.string.tab_channels))
                publish(fixture)
                assertDrawer(expanded = false, enabled = true)
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

    @Test fun appPressFeedbackRestoresAfterCancelAcrossPagesAndFontScales() {
        ClientSession.initialize(context)
        assumeTrue(!ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")
        val handle = (field("handle\$delegate").get(ClientSession) as Lazy<*>).value as Long
        val poll = ClientSession.javaClass.getDeclaredMethod("scheduleCorePoll").apply { isAccessible = true }
        val saved = state.value
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        val localized = context.createConfigurationContext(configuration)
        fun label(id: Int) = localized.getString(id)
        val bookmark = Bookmark(title = "Feedback fixture", host = "example.invalid", port = 9987, password = "", automaticallyNamed = false)
        val channel = Channel(1, 0, 0, "Default Channel", false, true, "fixture", null)
        val member = Member(2, 1, "fixture-member", "MobileSpeakuser", null, emptyList(), emptyList(), null, false, false, false)
        val fixture = saved.copy(snapshot = Snapshot(status = "connected", server = "Test Server", serverId = "fixture", ownClient = 1,
            channels = listOf(channel), clients = listOf(member, member.copy(id = 1, uid = "fixture-own", name = "FeedbackVerification"))),
            bookmarks = listOf(bookmark), microphoneMuted = true, deafened = false, error = null,
            avatarPreviewPath = null, avatarStatus = "idle", avatarDetail = null, avatarClearingLocally = false,
            audioProcessingStatus = "ready", audioProcessingError = null, fileCachePending = false,
            fileCache = FileCacheState(bytes = 0), channelFiles = ChannelFilesState())
        try {
            NativeCore.setNotifier(handle, Runnable {})
            (field("coreExecutor").get(ClientSession) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
            for (scale in listOf(1f, 2f)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { current ->
                    activity = current
                    current.setContent {
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides localized,
                            androidx.activity.compose.LocalActivityResultRegistryOwner provides current,
                            androidx.compose.ui.platform.LocalConfiguration provides configuration,
                            androidx.compose.ui.platform.LocalResources provides localized.resources,
                            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(context.resources.displayMetrics.density, scale)) {
                            androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Palette.accent,
                                surface = Palette.card, background = Palette.background, onSurface = Palette.text, onPrimary = Palette.text)) {
                                androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif)) {
                                    androidx.compose.material3.Surface(color = Palette.background) { MobileSpeakApp({}) }
                                }
                            }
                        }
                    }
                }
                fun press(name: String, text: String, feedback: Boolean = true, enabled: Boolean = true) {
                    settle(600) // Native accessibility bounds lag page/scroll animations.
                    val target = waitForClickable(text)
                    assertEquals("$name enabled state", enabled, target.isEnabled)
                    val area = android.graphics.Rect().also(target::getBoundsInScreen)
                    android.util.Log.i("PressFeedbackBounds", "$name/$scale: $area")
                    val bounds = androidx.compose.ui.geometry.Rect(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat())
                    val before = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    val down = SystemClock.uptimeMillis()
                    fun send(action: Int) {
                        val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.center.x, bounds.center.y, 0)
                        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    send(android.view.MotionEvent.ACTION_DOWN); settle(250)
                    val held = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    send(android.view.MotionEvent.ACTION_CANCEL); settle(750)
                    val recovered = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    var changed = 0; var residue = 0; var sampled = 0; var backgroundChanged = 0; var backgroundSamples = 0
                    val background = before.getPixel(bounds.left.toInt() + 3, bounds.top.toInt() + 3)
                    for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(before.height) step 3)
                        for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(before.width) step 3) {
                            sampled++
                            if (before.getPixel(x,y) != held.getPixel(x,y)) changed++
                            if (before.getPixel(x,y) != recovered.getPixel(x,y)) residue++
                            if (before.getPixel(x,y) == background) {
                                backgroundSamples++
                                if (held.getPixel(x,y) != background) backgroundChanged++
                            }
                        }
                    for ((phase, bitmap) in listOf("before" to before, "held" to held, "cancelled" to recovered)) {
                        java.io.File(context.getExternalFilesDir(null), "press-$scale-$name-$phase.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    if (feedback) assertTrue("$name/$scale needs visible press feedback", changed > 8)
                    else assertTrue("$name/$scale must not draw a pressed circle ($changed/$sampled)", changed * 100 < sampled)
                    if (name.endsWith("-send")) assertTrue("Only the send icon dims, without a pressed circle", backgroundChanged * 100 < backgroundSamples)
                    assertTrue("$name/$scale must recover after cancellation ($residue/$sampled)", residue * 100 < sampled)
                }
                publish(fixture.copy(snapshot = Snapshot(status = "disconnected")))
                press("bookmark", bookmark.title); press("bookmark-add", label(R.string.bookmark_add_server))
                val manage = localized.getString(R.string.bookmark_manage, bookmark.title)
                press("bookmark-menu", manage, feedback = false)
                click(manage, nativeTouch = true)
                assertTrue(waitForClickable(label(R.string.action_edit)).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                settle(); assertTrue(waitForClickable(label(R.string.action_cancel)).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); settle()
                click(label(R.string.bookmark_add_server)); press("bookmark-save", label(R.string.action_save_and_connect)); assertTrue(waitForClickable(label(R.string.action_cancel)).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); settle()
                assertEquals("Cancel must not save a bookmark", listOf(bookmark), state.value.bookmarks)
                publish(fixture.copy(snapshot = Snapshot(status = "disconnected"), bookmarks = emptyList()))
                press("empty-connect", label(R.string.action_connect_server))
                publish(fixture.copy(error = "测试错误")); press("error-close", label(R.string.action_close))
                publish(fixture)
                press("disconnect", label(R.string.action_disconnect)); press("channel", channel.name)
                press("channel-chat", localized.getString(R.string.channel_chat_open, channel.name))
                fun chatSend(name: String, entry: String) {
                    click(entry, nativeTouch = true)
                    press("$name-send-empty", label(R.string.action_send), feedback = false, enabled = false)
                    fun input(value: String) {
                        onMain { assertTrue(nodes().first { it.config.contains(SemanticsActions.SetText) }.config[SemanticsActions.SetText].action!!(androidx.compose.ui.text.AnnotatedString(value))) }
                        settle()
                    }
                    input(" "); press("$name-send-whitespace", label(R.string.action_send), feedback = false, enabled = false)
                    input("1"); press("$name-send", label(R.string.action_send))
                    onMain { assertEquals("Cancelling must keep the draft", "1", nodes().first { it.config.contains(SemanticsProperties.EditableText) }.config[SemanticsProperties.EditableText].text) }
                    input("x".repeat(8193)); assertFalse(waitForClickable(label(R.string.action_send)).isEnabled)
                    input(""); click(label(R.string.action_back), nativeTouch = true)
                }
                chatSend("channel", localized.getString(R.string.channel_chat_open, channel.name))
                press("navigation", label(R.string.tab_members)); click(label(R.string.tab_members))
                press("member", member.name)
                chatSend("private", member.name)
                press("microphone", label(R.string.voice_enable_microphone)); press("speaker", label(R.string.voice_disable_listening))
                click(label(R.string.voice_drawer_expand)); press("handle", label(R.string.voice_drawer_collapse))
                press("tools", label(R.string.files_title))
                val fileFixture = fixture.copy(channelFiles = ChannelFilesState(open = true, server = "fixture", channel = 1,
                    channelName = channel.name, status = "ready", entries = listOf(ChannelFileEntry("Feedback.txt", 1024, 1700000000, false, "file-text", null))))
                publish(fileFixture)
                press("files-back", label(R.string.action_back)); press("files-upload", label(R.string.files_upload))
                press("files-refresh", label(R.string.files_reload)); press("file-row", "Feedback.txt")
                val more = label(R.string.files_info) + ": Feedback.txt"
                press("file-menu", more, feedback = false); click(more, nativeTouch = true)
                assertTrue(waitForClickable(label(R.string.files_info)).isEnabled)
                assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)); settle()
                press("files-sort", label(R.string.files_sort_name), feedback = false)
                click(label(R.string.files_sort_name), nativeTouch = true)
                assertTrue(waitForClickable(label(R.string.files_sort_newest)).isEnabled)
                assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)); settle()
                publish(fileFixture.copy(channelFiles = fileFixture.channelFiles.copy(status = "loading", entries = emptyList())))
                onMain {
                    val all = nodes()
                    val list = all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "channel-files-list" }.boundsInRoot
                    val loading = all.first { label(R.string.files_loading) in texts(it) }.boundsInRoot
                    val spinner = all.first { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.boundsInRoot
                    val tolerance = context.resources.displayMetrics.density
                    assertEquals("Loading label centers in the actual Home drawer", list.center.x, loading.center.x, tolerance)
                    assertEquals("Loading indicator centers in the actual Home drawer", list.center.x, spinner.center.x, tolerance)
                    assertEquals("Loading begins below the directory separator", list.top + 24 * tolerance, spinner.top, tolerance)
                }
                val loadingShot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.getExternalFilesDir(null), "home-files-loading-$scale.png").outputStream().use { loadingShot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                loadingShot.recycle()
                publish(fixture); click(label(R.string.voice_drawer_collapse)); click(label(R.string.tab_settings))
                press("avatar-choose", label(R.string.avatar_choose)); press("avatar-clear", label(R.string.avatar_remove))
                val noiseOffset = onMain { nodes().first { "RNNoise" in texts(it) }.positionInRoot.y - scrollNode().boundsInRoot.top }
                scroll(pixels = noiseOffset - 80 * context.resources.displayMetrics.density); press("noise", "RNNoise")
                scroll(pixels = 100000f)
                press("language-entry", label(R.string.settings_language))
                click(label(R.string.settings_language)); press("language-system", label(R.string.language_system)); click(label(R.string.action_back))
                assertEquals("Cancelled presses must not change the synthetic connection", "connected", state.value.snapshot.status)
                assertFalse(state.value.deafened)
            }
        } finally {
            instrumentation.runOnMainSync { state.value = saved }
            NativeCore.setNotifier(handle, Runnable { poll.invoke(ClientSession) }); poll.invoke(ClientSession)
        }
    }

    private data class Position(val axis: Float, val first: String?, val top: Float?)

    private fun checkPartiallyRevealedToolAndCapture() {
        val pane = onMain { nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "voice-drawer" } }
        val card = onMain { nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "channel-files-tool" } }
        val cardSize = card.size
        val distance = pane.size.height - (card.positionInRoot.y - pane.positionInRoot.y + cardSize.height / 2f)
        assertTrue(distance > 0)
        val origin = pane.positionOnScreen + androidx.compose.ui.geometry.Offset(pane.size.width / 2f, 24 * context.resources.displayMetrics.density)
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, dy: Float = 0f) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, origin.x, origin.y + dy, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN)
        try {
            for (step in 1..6) { SystemClock.sleep(30); send(android.view.MotionEvent.ACTION_MOVE, distance * step / 6) }
            settle(60)
            onMain {
                val revealed = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "channel-files-tool" }
                assertEquals("A real Home drawer crops the card without resizing it", cardSize, revealed.size)
                assertTrue(revealed.boundsInRoot.height > 0 && revealed.boundsInRoot.height < cardSize.height - 1)
            }
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.getExternalFilesDir(null), "home-drawer-partial.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { send(android.view.MotionEvent.ACTION_CANCEL, distance); settle() }
    }

    private fun drawerTop(): Float = onMain {
        val labels = listOf(context.localized(R.string.voice_drawer_expand), context.localized(R.string.voice_drawer_collapse))
        nodes().first { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it in labels } == true }.boundsInRoot.top
    }

    private fun assertDrawer(expanded: Boolean, enabled: Boolean) = onMain {
        val label = context.localized(if (expanded) R.string.voice_drawer_collapse else R.string.voice_drawer_expand)
        val handle = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
        assertEquals(!enabled, handle.config.contains(SemanticsProperties.Disabled))
        assertEquals(context.localized(if (expanded) R.string.voice_drawer_expanded else R.string.voice_drawer_collapsed),
            handle.config[SemanticsProperties.StateDescription])
    }

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

    private fun click(label: String, nativeTouch: Boolean = false) {
        var point = androidx.compose.ui.geometry.Offset.Zero
        onMain {
            val node = nodes().firstOrNull { node ->
                (label in texts(node) || node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) &&
                    node.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            assertNotNull("Clickable $label", node)
            if (nativeTouch) {
                requireNotNull(node)
                assertTrue(node.boundsInRoot.height > 0)
                point = node.boundsInRoot.center + node.positionOnScreen - node.positionInRoot
            } else assertTrue(node!!.config[SemanticsActions.OnClick].action!!())
        }
        if (nativeTouch) {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                SystemClock.sleep(30)
            }
        }
        settle()
    }

    private fun publish(ui: SessionUiState, wait: Boolean = true) { instrumentation.runOnMainSync { state.value = ui }; if (wait) settle() }
    private fun settle(delayMillis: Long = 500) { SystemClock.sleep(delayMillis); instrumentation.runOnMainSync { } }
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
