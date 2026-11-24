package dev.mobilespeak.mobilespeak

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class BridgeContractTest {
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun channelDescriptionLayoutAndEditorMenu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val channel = Channel(7, 0, 0, "乡岗井茶", true, false, "fixture", null,
            topic = "周末闲聊", description = "欢迎来这里聊天。", channelType = "semi_permanent",
            maxClients = ChannelClientLimit("limited", 4), canEdit = true)
        val parent = Channel(8, 0, 7, "休闲区", false, true, "parent", null)
        val child = Channel(9, 8, 0, "游戏大厅", false, true, "child", null)
        val extraChannels = (10L..16L).map { id ->
            val previous = if (id == 10L) parent.id else id - 1
            Channel(id, 0, previous, "测试频道 $id", false, true, "test-$id", null)
        }
        val member = Member(1, 7, "fixture", "MobileSpeakUser", null, emptyList(), emptyList(), null,
            muted = false, deafened = false, speaking = false)
        val ui = SessionUiState(snapshot = Snapshot(status = "connected", server = "祥和山庄", serverId = "fixture",
            connectionId = "fixture", ownClient = 1, channels = listOf(channel, parent, child) + extraChannels,
            clients = listOf(member)))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val config = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("zh-Hans")) }
                val localized = activity.createConfigurationContext(config)
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                        LocalResources provides localized.resources) {
                        MaterialTheme(colorScheme = darkColorScheme(background = Palette.background, surface = Palette.card,
                            onSurface = Palette.text, primary = Palette.accent)) {
                            Box(Modifier.fillMaxSize().statusBarsPadding()) { ChannelScreen(ui, {}, {}) }
                            val showDetail = remember { mutableStateOf(true) }
                            if (showDetail.value) ChannelDialog(channel, ui, onDismiss = { showDetail.value = false }, onPrivateChat = { _, _ -> })
                        }
                    }
                }
            }
            fun find(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                if (match(node)) return node
                return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let { child -> find(child, match) } }
            }
            fun <T : Any> awaitTree(read: (AccessibilityNodeInfo) -> T?): T {
                repeat(20) {
                    instrumentation.waitForIdleSync()
                    instrumentation.uiAutomation.rootInActiveWindow?.let(read)?.let { return it }
                    SystemClock.sleep(150)
                }
                error("Expected channel management state was not visible")
            }
            fun await(match: (AccessibilityNodeInfo) -> Boolean) = awaitTree { root ->
                find(root) { it.isVisibleToUser && match(it) }
            }
            fun awaitBelow(upper: String, lower: String) {
                awaitTree { root ->
                    fun bounds(label: String): Rect? = find(root) {
                        it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString()?.startsWith(label) == true)
                    }?.let { Rect().apply(it::getBoundsInScreen) }
                    val top = bounds(upper) ?: return@awaitTree null
                    val bottom = bounds(lower) ?: return@awaitTree null
                    true.takeIf { bottom.top >= top.bottom }
                }
            }
            fun capture(name: String) {
                SystemClock.sleep(300)
                val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(context.cacheDir, name).outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                shot.recycle()
            }
            fun tap(node: AccessibilityNodeInfo) {
                val button = generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isVisibleToUser }
                if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                val bounds = Rect().apply(node::getBoundsInScreen)
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                }
            }
            fun text(value: String) = await { it.text?.toString() == value || it.contentDescription?.toString() == value }
            fun visible(value: String) = instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                find(root) { it.text?.toString() == value || it.contentDescription?.toString() == value }
            } != null
            fun drag(handle: AccessibilityNodeInfo, after: AccessibilityNodeInfo, cancel: Boolean = false, whileHeld: () -> Unit) {
                val from = Rect().apply(handle::getBoundsInScreen)
                val to = Rect().apply(after::getBoundsInScreen)
                val x = from.exactCenterX()
                val startY = from.exactCenterY()
                val endY = to.exactCenterY() + from.height() / 2f
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, y: Float) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                }
                send(MotionEvent.ACTION_DOWN, startY)
                try {
                    for (step in 1..8) {
                        send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 8)
                        SystemClock.sleep(25)
                    }
                    whileHeld()
                } finally {
                    send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endY)
                }
            }
            awaitBelow(requireNotNull(channel.description), member.name)
            capture("android-channel-detail.png")
            val editTitles = setOf("编辑频道", context.getString(R.string.channel_edit_title))
            val menuTitles = setOf("管理频道", context.getString(R.string.channel_manage))
            tap(await { it.contentDescription?.toString() in menuTitles })
            tap(await { it.text?.toString() in editTitles })
            await { it.text?.toString()?.contains("周末闲聊") == true }
            await { it.text?.toString()?.contains(channel.name) == true }
            capture("android-channel-editor.png")
            fun limitChoice() = await { it.text?.toString()?.contains("人数上限") == true }
            fun isChecked(option: String): Boolean {
                fun checkable(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.firstOrNull { it.isCheckable }
                val label = await { it.text?.toString() == option && checkable(it) != null }
                return checkNotNull(checkable(label)).isChecked
            }
            tap(limitChoice())
            capture("android-channel-editor-limit-options.png")
            assertTrue(isChecked("设置上限"))
            assertFalse(isChecked("不限"))
            tap(text("不限"))
            capture("android-channel-editor-unlimited.png")
            tap(limitChoice())
            assertTrue(isChecked("不限"))
            assertFalse(isChecked("设置上限"))
            tap(text("不限"))
            tap(text("取消"))
            tap(await { it.contentDescription?.toString() in menuTitles })
            tap(text("新建子频道"))
            text("创建于：${channel.name}")
            assertFalse(visible("位置"))
            capture("android-channel-create-child.png")
            tap(text("取消"))
            tap(await { it.contentDescription?.toString() in menuTitles })
            tap(text("调整位置"))
            text("作为顶级频道")
            capture("android-channel-move-root.png")
            fun handle() = await { it.contentDescription?.toString() == "拖动蓝框频道调整顺序" }
            fun saveColor(): Int {
                val y = Rect().apply(text("保存位置")::getBoundsInScreen).centerY()
                val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                return try { shot.getPixel(shot.width / 5, y) } finally { shot.recycle() }
            }
            val originalParentTop = Rect().apply(text(parent.name)::getBoundsInScreen).top
            fun checkWhileHeld() {
                await { node ->
                    node.text?.toString() == parent.name && Rect().apply(node::getBoundsInScreen).top < originalParentTop
                }
                assertEquals(Palette.card.toArgb(), saveColor())
            }
            drag(handle(), text(parent.name), cancel = true) { checkWhileHeld() }
            awaitBelow(channel.name, parent.name)
            assertEquals(Palette.card.toArgb(), saveColor())
            drag(handle(), text(parent.name)) {
                checkWhileHeld()
                capture("android-channel-move-held.png")
            }
            awaitBelow(parent.name, channel.name)
            capture("android-channel-move-reordered.png")
            assertEquals(Palette.accent.toArgb(), saveColor())
            tap(text("作为顶级频道"))
            text("选择父频道")
            assertFalse(visible(channel.name))
            tap(text(parent.name))
            text(child.name)
            capture("android-channel-move-under-parent.png")
            tap(text("取消"))
            tap(text("关闭"))
            tap(text("新建频道"))
            text("位置")
            text("底部")
            capture("android-channel-create-root.png")
        }
    }

    @Test fun sharedRustOutputAndOptionalConnectionFields() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val samples = JSONArray(context.assets.open("bridge.json").bufferedReader().use { it.readText() })
        assertEquals(5, samples.length())
        for (i in 0 until samples.length()) {
            val envelope = samples.getJSONObject(i)
            val snapshot = envelope.getJSONObject("snapshot")
            assertEquals(i == 4, envelope.optBoolean("soundPending", false))
            if (i == 0) assertFalse(envelope.has("soundPending"))
            envelope.optJSONObject("speech")?.let {
                val speech = it.speech()
                assertEquals(it.getString("mode"), speech.mode)
                assertEquals(it.getString("token"), speech.token)
                assertEquals(it.getBoolean("available"), speech.available)
                assertEquals(it.getBoolean("transmitting"), speech.transmitting)
            }
            val decoded = snapshot.snapshot()
            assertEquals(listOf("disconnected", "connecting", "connected", "reconnecting", "disconnected")[i], decoded.status)
            assertEquals(if (i == 0) null else "fixture-connection", snapshot.stringOrNull("connectionId"))
            assertEquals(if (i == 2) ServerChatTarget("fixture-server", "fixture-connection") else null, decoded.serverChatTarget)
            if (i == 2) {
                val edited = decoded.channels.first()
                assertEquals("Fixture topic", edited.topic)
                assertEquals("", edited.description)
                assertEquals("semi_permanent", edited.channelType)
                assertEquals(ChannelClientLimit("limited", 0), edited.maxClients)
                assertEquals(false, edited.canEdit)
                assertEquals(true, edited.canDelete)
                assertEquals(null, decoded.channels.last().description)
                assertEquals(null, decoded.channels.last().canEdit)
                assertEquals(null, decoded.channels.last().canDelete)
            }
            assertEquals(if (i == 2) 3L else 0L, envelope.getJSONObject("unread").unread().serverCount)
            if (i > 0) assertEquals(ProfileState(), envelope.getJSONObject("profile").profile())
            if (!envelope.isNull("chats")) {
                val messages = envelope.getJSONArray("chats").objects().map(JSONObject::chatMessage)
                assertEquals(if (i == 2) 2 else 0, messages.size)
                decoded.serverChatTarget?.let { target ->
                    assertEquals("channel:id:1", messages.first().conversation)
                    assertEquals("sent", messages.first().status)
                    val server = messages.single { it.conversation == target.conversation }
                    assertEquals("Server", server.senderName)
                    assertEquals("", server.senderUid)
                    assertEquals(null, server.avatarPath)
                    assertEquals("received", server.status)
                }
            }
            for (event in envelope.getJSONArray("events").objects()) {
                when (event.getString("type")) {
                    "error" -> { assertEquals("fixture-connection", event.getString("connectionId")); assertEquals("fixture failure", event.getString("detail")) }
                    "audio_processing" -> { assertFalse(event.has("connectionId")); assertEquals("dpdfnet2", event.getJSONObject("requested").getString("noise")) }
                    "channel_command_result" -> {
                        assertEquals("fixture-connection", event.getString("connectionId"))
                        if (event.getString("operation") == "create") {
                            assertEquals("fixture-create", event.getString("requestId"))
                            assertEquals(2L, event.getLong("channel"))
                        } else {
                            assertEquals("edit", event.getString("operation"))
                            assertEquals("fixture-edit", event.getString("requestId"))
                            assertEquals("channel_permission_denied", event.getString("error"))
                        }
                    }
                }
            }
        }
    }
}

class ConnectionEnvelopeOrderingTest {
    @Test fun delayedOldDisconnectCannotSuppressNewStateOrGlobalEvents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ClientSession.initialize(context)
        fun field(name: String) = ClientSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        val executor = field("coreExecutor").get(ClientSession) as java.util.concurrent.ExecutorService
        val gate = field("connectionGate").get(ClientSession) as ConnectionGate
        val apply = ClientSession::class.java.getDeclaredMethod("applyEnvelope", JSONObject::class.java).apply { isAccessible = true }
        val handle = ClientSession::class.java.getDeclaredMethod("getHandle").apply { isAccessible = true }.invoke(ClientSession) as Long
        // Control notification order; do not connect a real protocol session in this replay.
        NativeCore.setNotifier(handle, Runnable {})
        val savedId = ConnectionGate::class.java.getDeclaredField("id").apply { isAccessible = true }
        val savedCancelled = ConnectionGate::class.java.getDeclaredField("cancelled").apply { isAccessible = true }
        val oldId = savedId.get(gate); val oldCancelled = savedCancelled.getBoolean(gate)
        val saved = ClientSession.state.value
        val savedPermission = field("microphonePermission").getBoolean(ClientSession)
        @Suppress("UNCHECKED_CAST")
        val state = field("mutableState").get(ClientSession) as kotlinx.coroutines.flow.MutableStateFlow<SessionUiState>
        val service = field("serviceRequested")
        fun envelope(id: String?, status: String, events: String = "[]") = JSONObject("""{
            "snapshot":{"connectionId":${id?.let { "\"$it\"" } ?: "null"},"status":"$status","channels":[],"clients":[]},
            "chats":[],"events":$events,"unread":{"serverId":null,"channel":null,"channelCount":0,"privateCounts":{}},
            "receiveAudio":{"agc":false},"speech":{"mode":"push_to_talk","token":"global","available":false,"transmitting":false}
        }""")
        try {
            executor.submit {
                gate.begin("new")
                state.value = saved.copy(snapshot = Snapshot(status = "connecting", connectionId = "new"))
                // The service is already requested by connect; avoid starting a service for synthetic data.
                service.setBoolean(ClientSession, true)
                apply.invoke(ClientSession, envelope(null, "disconnected", """[{"type":"error","scope":"global","code":"chat_history_save_failed","detail":"disk"}]"""))
                org.junit.Assert.assertEquals("connecting", ClientSession.state.value.snapshot.status)
                org.junit.Assert.assertFalse(ClientSession.state.value.receiveAgc)
                assertEquals("push_to_talk", ClientSession.state.value.speech.mode)
                assertEquals("global", ClientSession.state.value.speech.token)
                org.junit.Assert.assertNotNull(ClientSession.state.value.error)
                val error = ClientSession.state.value.error
                apply.invoke(ClientSession, envelope("old", "disconnected", """[{"type":"audio_muted","connectionId":"old","code":"legacy_codec"}]"""))
                org.junit.Assert.assertEquals(error, ClientSession.state.value.error)
                apply.invoke(ClientSession, envelope("new", "connected"))
                org.junit.Assert.assertEquals("connected", ClientSession.state.value.snapshot.status)
                org.junit.Assert.assertTrue(ClientSession.shouldRunService())
                apply.invoke(ClientSession, envelope("new", "reconnecting"))
                org.junit.Assert.assertTrue(ClientSession.shouldRunService())
                gate.cancel(); gate.begin("next")
                state.value = state.value.copy(snapshot = Snapshot(status = "connecting", connectionId = "next"))
                apply.invoke(ClientSession, envelope("new", "disconnected"))
                org.junit.Assert.assertEquals("connecting", ClientSession.state.value.snapshot.status)
                service.setBoolean(ClientSession, false) // model terminal service decision without OS effects
                apply.invoke(ClientSession, envelope("next", "disconnected", """[{"type":"error","connectionId":"next","code":"core_error","detail":"new failure"}]"""))
                org.junit.Assert.assertFalse(ClientSession.shouldRunService())
                org.junit.Assert.assertTrue(ClientSession.state.value.error.orEmpty().contains("new failure"))
            }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            executor.submit {
                field("microphonePermission").setBoolean(ClientSession, true)
                gate.begin("audio")
                state.value = saved.copy(snapshot = Snapshot(status = "connecting", connectionId = "audio"), microphoneMuted = false, deafened = false)
                service.setBoolean(ClientSession, true)
                ClientSession.setAudio(inputMuted = true)
                val connected = envelope("audio", "connected")
                connected.getJSONObject("snapshot").put("ownClient", 1).put("clients", JSONArray("""[{"id":1,"channel":1,"uid":"fixture","name":"User","avatarPath":null,"badges":[],"serverGroupIcons":[],"channelGroupIcon":null,"muted":false,"deafened":false,"speaking":false}]"""))
                apply.invoke(ClientSession, connected)
            }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            executor.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
            org.junit.Assert.assertTrue("Initial audio recovery must not overwrite an already queued mute", ClientSession.state.value.microphoneMuted)
        } finally {
            executor.submit { savedId.set(gate, oldId); savedCancelled.setBoolean(gate, oldCancelled); service.setBoolean(ClientSession, false); field("microphonePermission").setBoolean(ClientSession, savedPermission); state.value = saved }.get()
            val schedule = ClientSession::class.java.getDeclaredMethod("scheduleCorePoll").apply { isAccessible = true }
            NativeCore.setNotifier(handle, Runnable { schedule.invoke(ClientSession) })
        }
    }
}

class SecureStoreFailureTest {
    @Test fun failedCommitRestoresSharedPreferencesCacheBeforeReopeningStore() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        var cached: String? = null
        var persisted: String? = null
        var fail = false
        val preferences = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getString" -> cached
                "edit" -> {
                    var next: String? = cached
                    lateinit var editor: android.content.SharedPreferences.Editor
                    editor = java.lang.reflect.Proxy.newProxyInstance(
                        android.content.SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { _, edit, args ->
                        when (edit.name) {
                            "putString" -> { next = args!![1] as String?; editor }
                            "commit" -> { cached = next; if (!fail) persisted = next; !fail }
                            else -> error("Unexpected editor call ${edit.name}")
                        }
                    } as android.content.SharedPreferences.Editor
                    editor
                }
                else -> error("Unexpected preference call ${method.name}")
            }
        } as android.content.SharedPreferences
        val context = object : android.content.ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        val store = SecureStore(context)
        val bookmark = Bookmark.create("failure-test", "Manual", "example.invalid", "9987", "secret")
        store.saveBookmarks(listOf(bookmark))
        val original = persisted
        fail = true
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { store.saveBookmarks(emptyList()) }
        assertEquals(listOf(bookmark), store.bookmarks())
        assertEquals(original, persisted)
        assertEquals(original, cached)
        assertEquals(listOf(bookmark), SecureStore(context).bookmarks())
    }
}
