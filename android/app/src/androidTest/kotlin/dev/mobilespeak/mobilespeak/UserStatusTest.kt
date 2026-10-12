package dev.mobilespeak.mobilespeak

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as PixelColor
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
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class UserStatusTest {
    private fun member(path: String? = null, muted: Boolean = false, deafened: Boolean = false, away: Boolean = false) =
        Member(7, 9, "uid-7", "User 7", path, emptyList(), emptyList(), null, muted, deafened, false, volumeToken = "token-7", away = away)

    @Test fun bridgeDecodesOptionalAwayAndExistingMessageUid() {
        val json = JSONObject("""{"id":7,"channel":9,"uid":"uid-7","name":"User 7","avatarPath":null,"badges":[],"serverGroupIcons":[],"channelGroupIcon":null,"muted":false,"deafened":false,"speaking":false}""")
        fun decode() = JSONObject().put("status", "connected").put("channels", org.json.JSONArray()).put("clients", org.json.JSONArray().put(json)).snapshot().clients.single()
        assertFalse(decode().away)
        json.put("away", true).put("deafened", true).put("muted", true)
        assertEquals("afk", decode().statusIcon)
        val message = JSONObject("""{"id":"message","conversation":"client:uid-7","senderUid":"uid-7","senderName":"Old nickname","avatarPath":null,"own":false,"text":"Hello","timestamp":1,"status":"received","error":null}""").chatMessage()
        assertEquals("uid-7", message.senderUid)
        assertEquals("afk", Snapshot(status = "connected", clients = listOf(decode())).onlineMember(message.senderUid)?.statusIcon)
    }
    @Test fun nativeAvatarsAndChatUpdateWithoutKeepingSavedStatus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        ClientSession.initialize(context)
        org.junit.Assume.assumeTrue("Requires an offline fixture profile", !ClientSession.shouldRunService())
        var page by mutableIntStateOf(0); var language by mutableStateOf("en"); var font by mutableFloatStateOf(1f)
        var snapshot by mutableStateOf(Snapshot(status = "connected", ownClient = 1, clients = listOf(member())))
        val paths = mutableListOf<String?>(null)
        for ((index, color) in listOf(0xFFFFD159.toInt(), 0xFF1F2B40.toInt(), PixelColor.WHITE).withIndex()) {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
            if (index == 2) {
                val canvas = Canvas(bitmap); val paint = android.graphics.Paint()
                val colors = listOf(0xFF007AFF.toInt(), 0xFFFF3B30.toInt(), PixelColor.WHITE, 0xFF34C759.toInt())
                for (y in 0 until 8) for (x in 0 until 8) { paint.color = colors[(x+y)%4]; canvas.drawRect(x*12f, y*12f, (x+1)*12f, (y+1)*12f, paint) }
            }
            val file = File(context.cacheDir, "status-source-$index.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle(); paths += file.path
        }
        val originals = paths.filterNotNull().map { File(it).readBytes() }
        val saved = member(muted = true, deafened = true, away = true)
        val message = ChatMessage("message", "client:uid-7", "Old nickname", paths[1], false, "Hello", 1, "received", null, "uid-7")
        val channel = Channel(9, 0, 0, "Lobby", false, true, "lobby", null); var clicks = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { current ->
                activity = current
                current.setContent {
                    val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
                    val localized = context.createConfigurationContext(configuration); val density = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, LocalDensity provides Density(density.density, font)) {
                        MaterialTheme(colorScheme = darkColorScheme(background = Palette.background, surface = Palette.card, onSurface = Palette.text)) {
                            ProvideTextStyle(TextStyle(color = Palette.text, fontSize = 17.sp, lineHeight = 20.sp)) {
                                Box(Modifier.fillMaxSize().background(Palette.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                                    when (page) {
                                        0 -> Column(Modifier.width(390.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Normal / Mic off / Listening off / AFK", fontSize = 14.sp)
                                            for (size in listOf(24, 30, 34)) for ((index, path) in paths.withIndex()) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                                                    Text("$size", Modifier.width(24.dp), fontSize = 12.sp)
                                                    for (state in 0..3) {
                                                        val m = member(path, muted = state == 1 || state == 3, deafened = state == 2 || state == 3, away = state == 3).copy(speaking = state == 1)
                                                        Box(Modifier.testTag("$size-$index-$state")) { Avatar(m.name, m.avatarPath, m.speaking, size, m) }
                                                    }
                                                }
                                            }
                                        }
                                        1 -> ChatScreen(ChatTarget("client:uid-7", saved.name, member = saved), SessionUiState(snapshot = snapshot, messages = listOf(message, message.copy(id = "self", own = true, senderUid = "uid-1"))), false) {}
                                        2 -> MemberScreen(SessionUiState(snapshot = snapshot, unread = Unread(privateCounts = mapOf("uid-7" to 99)))) { clicks++ }
                                        3 -> ChannelScreen(SessionUiState(snapshot = snapshot.copy(channels = listOf(channel))), {}, {})
                                        4 -> UserVolumesDrawer(SessionUiState(snapshot = snapshot, userVolumesOpen = true))
                                        5 -> ChatScreen(ChatTarget("channel:lobby", "Lobby", channel = channel), SessionUiState(snapshot = snapshot, messages = listOf(message.copy(conversation = "channel:lobby"), message.copy(id = "self", conversation = "channel:lobby", own = true, senderUid = "uid-1"))), false) {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
            fun root(view: View): androidx.compose.ui.node.RootForTest? =
                (view as? androidx.compose.ui.node.RootForTest) ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
            fun nodes(): List<SemanticsNode> {
                fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                var result = emptyList<SemanticsNode>()
                instrumentation.runOnMainSync { result = collect(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode) }; return result
            }
            fun descriptions() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(450) }
            fun screenshot(name: String): Bitmap {
                // Semantics can update before the new composition reaches the screen.
                val frame = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { current ->
                    val view = current.window.decorView
                    if (android.os.Build.VERSION.SDK_INT >= 29) view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
                    else view.postOnAnimation { view.postOnAnimation { frame.countDown() } }
                    view.invalidate()
                }
                assertTrue("Fixture frame must be drawn before sampling pixels", frame.await(3, java.util.concurrent.TimeUnit.SECONDS))
                val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.getExternalFilesDir(null), "user-status-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; return shot
            }
            for (lang in listOf("en", "zh-Hans")) {
                instrumentation.runOnMainSync { language = lang; page = 0 }; settle(); val shot = screenshot("grid-$lang")
                for (size in listOf(24, 30, 34)) for (index in paths.indices) {
                    val avatars = (0..3).map { state -> nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "$size-$index-$state" }.boundsInWindow }
                    fun base(state: Int): Int { val rect = avatars[state]; return shot.getPixel(rect.center.x.toInt(), (rect.top + rect.height * .82f).toInt()) }
                    for (state in 1..2) for (component in listOf(PixelColor::red, PixelColor::green, PixelColor::blue)) {
                        assertTrue("Mute/deafen keep base pixels within raster rounding", kotlin.math.abs(component(base(0)) - component(base(state))) <= 2)
                    }
                    val gray = base(3); assertTrue(kotlin.math.abs(PixelColor.red(gray) - PixelColor.green(gray)) <= 1); assertTrue(kotlin.math.abs(PixelColor.green(gray) - PixelColor.blue(gray)) <= 1); assertTrue("AFK dims only base", PixelColor.red(gray) <= 65)
                    val rect = avatars[3]; var red = false
                    for (y in rect.top.toInt() until rect.bottom.toInt()) for (x in rect.left.toInt() until rect.right.toInt()) { if (shot.getPixel(x,y) == 0xFFC83F4A.toInt()) red = true }
                    assertTrue("AFK glyph remains original red at $size", red)
                }; shot.recycle()
            }
            for (state in listOf(member(away = true, deafened = true, muted = true), member(deafened = true), member(muted = true), member())) {
                instrumentation.runOnMainSync { page = 1; snapshot = snapshot.copy(clients = listOf(state, member().copy(id = 1, uid = "uid-1", away = true))) }; settle()
                assertTrue(descriptions().contains(state.accessibilityLabel(context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })))); assertTrue(descriptions().contains("User 7, 离开"))
                screenshot("chat-${state.statusIcon ?: "normal"}").recycle()
            }
            instrumentation.runOnMainSync { snapshot = snapshot.copy(clients = emptyList()) }; settle()
            assertTrue(descriptions().contains("User 7")); assertTrue(descriptions().contains("Old nickname")); assertFalse(descriptions().any { it.contains("离开") || it.contains("已静音") || it.contains("关闭收听") }); screenshot("chat-offline").recycle()
            val badge = Badge("badge", "Badge", "Badge", paths[1]); val group = GroupIcon(1, "Server Admin", 300, null)
            val long = member(away = true, deafened = true, muted = true).copy(name = "Long username 完整用户名不能因离开而变暗", badges = listOf(badge), serverGroupIcons = listOf(group), channelGroupIcon = group.copy(id = 2, name = "Channel Admin", iconId = 100))
            for (scale in listOf(1f, 2f)) for (target in listOf(2, 3, 4, 5)) {
                instrumentation.runOnMainSync { font = scale; page = target; snapshot = snapshot.copy(clients = listOf(long, member().copy(id = 1, uid = "uid-1"))) }; settle(); screenshot("page-$target-font-$scale").recycle()
                assertTrue(descriptions().any { it.contains("离开") && it.contains("关闭收听") && it.contains("已静音") })
                if (target == 4) assertFalse(nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "user-volume-7" }.config.contains(SemanticsProperties.Disabled))
                if (target == 2) {
                    val row = nodes().single { it.config.getOrNull(SemanticsActions.OnClick) != null }
                    instrumentation.runOnMainSync { row.config[SemanticsActions.OnClick].action!!.invoke() }; assertTrue(clicks > 0); assertFalse(row.config.contains(SemanticsProperties.Disabled))
                    assertTrue(descriptions().contains("Badge")); assertTrue(descriptions().contains("Server Admin")); assertTrue(descriptions().contains("Channel Admin"))
                    assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "99" } == true })
                }
            }
        }
        paths.filterNotNull().forEachIndexed { index, path -> assertArrayEquals(originals[index], File(path).readBytes()); File(path).delete() }
    }
}
