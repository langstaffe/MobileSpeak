package dev.mobilespeak.mobilespeak

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BridgeContractTest {
    @Test fun sharedRustOutputAndOptionalConnectionFields() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val samples = JSONArray(context.assets.open("bridge.json").bufferedReader().use { it.readText() })
        assertEquals(5, samples.length())
        for (i in 0 until samples.length()) {
            val envelope = samples.getJSONObject(i)
            val snapshot = envelope.getJSONObject("snapshot")
            envelope.optJSONObject("speech")?.let {
                val speech = it.speech()
                assertEquals(it.getString("mode"), speech.mode)
                assertEquals(it.getString("token"), speech.token)
                assertEquals(it.getBoolean("available"), speech.available)
                assertEquals(it.getBoolean("transmitting"), speech.transmitting)
            }
            assertEquals(listOf("disconnected", "connecting", "connected", "reconnecting", "disconnected")[i], snapshot.snapshot().status)
            assertEquals(if (i == 0) null else "fixture-connection", snapshot.stringOrNull("connectionId"))
            if (i > 0) assertEquals(ProfileState(), envelope.getJSONObject("profile").profile())
            if (!envelope.isNull("chats")) {
                val messages = envelope.getJSONArray("chats").objects().map(JSONObject::chatMessage)
                assertEquals(if (i == 2) 1 else 0, messages.size)
                if (i == 2) assertEquals("sent", messages.first().status)
            }
            for (event in envelope.getJSONArray("events").objects()) {
                when (event.getString("type")) {
                    "error" -> { assertEquals("fixture-connection", event.getString("connectionId")); assertEquals("fixture failure", event.getString("detail")) }
                    "audio_processing" -> { assertFalse(event.has("connectionId")); assertEquals("dpdfnet2", event.getJSONObject("requested").getString("noise")) }
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
