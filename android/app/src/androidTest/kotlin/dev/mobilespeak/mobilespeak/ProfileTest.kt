package dev.mobilespeak.mobilespeak

import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ProfileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun command(handle: Long, value: JSONObject) = assertEquals(0, NativeCore.command(handle, value.toString().toByteArray(Charsets.UTF_8)))
    private fun waitProfile(handle: Long, condition: (ProfileState) -> Boolean): ProfileState {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val envelope = JSONObject(String(NativeCore.poll(handle), Charsets.UTF_8))
            envelope.optJSONObject("profile")?.profile()?.let { if (condition(it)) return it }
            SystemClock.sleep(20)
        }
        throw AssertionError("Timed out waiting for core profile")
    }
    @Test fun jniProfileSavesRestartsAndProtectsFailedWritesAndReads() {
        val root = File(context.cacheDir, "profile-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val handles = mutableListOf<Long>()
        fun create() = NativeCore.create(context).also { handle ->
            handles += handle
            command(handle, JSONObject().put("type", "configure").put("storage", root.path))
        }
        try {
            var handle = create()
            assertEquals("MobileSpeakUser", waitProfile(handle) { it.requestId == 0L }.nickname)
            command(handle, JSONObject().put("type", "set_nickname").put("nickname", " 用户😀 ").put("request_id", 1))
            assertEquals("用户😀", waitProfile(handle) { it.requestId == 1L }.nickname)
            NativeCore.destroy(handle); handles.remove(handle)
            handle = create()
            assertEquals("用户😀", waitProfile(handle) { it.nickname == "用户😀" }.nickname)
            assertTrue(File(root, "profile.tmp").mkdir())
            command(handle, JSONObject().put("type", "set_nickname").put("nickname", "Failure").put("request_id", 1))
            val failed = waitProfile(handle) { it.requestId == 1L }
            assertEquals("用户😀", failed.nickname); assertEquals("profile_save_failed", failed.error)
            File(root, "profile.tmp").delete()
            listOf("A", "B", "C").forEachIndexed { index, name ->
                command(handle, JSONObject().put("type", "set_nickname").put("nickname", name).put("request_id", index + 2))
            }
            assertEquals("C", waitProfile(handle) { it.requestId == 4L }.nickname)
            command(handle, JSONObject().put("type", "set_nickname").put("nickname", "😀".repeat(32)).put("request_id", 5))
            assertNull(waitProfile(handle) { it.requestId == 5L }.error)
            command(handle, JSONObject().put("type", "set_nickname").put("nickname", "😀".repeat(32) + "a").put("request_id", 6))
            assertEquals("nickname_invalid", waitProfile(handle) { it.requestId == 6L }.error)
            NativeCore.destroy(handle); handles.remove(handle)
            File(root, "profile.json").writeText("corrupt")
            handle = create()
            assertEquals("profile_read_failed", waitProfile(handle) { it.error != null }.error)
            command(handle, JSONObject().put("type", "set_nickname").put("nickname", "Do not overwrite").put("request_id", 1))
            assertEquals("profile_read_failed", waitProfile(handle) { it.requestId == 1L }.error)
            assertEquals("corrupt", File(root, "profile.json").readText())
        } finally { handles.forEach(NativeCore::destroy); root.deleteRecursively() }
    }

    @Test fun oldNicknameFieldsAreIgnoredAndDuplicateBookmarksKeepIdsAndSecrets() {
        val rows = JSONArray("""[{"id":"one","title":"One","host":"EXAMPLE.com","port":9987,"nickname":"Legacy","password":"secret"},
            {"id":"two","title":"Two","host":"example.com","port":9987,"nickname":{"ignored":true},"password":"second"}]""")
        val bookmarks = rows.objects().map(Bookmark::from)
        assertEquals(listOf("one", "two"), bookmarks.map { it.id })
        assertEquals("secret", bookmarks.first().password)
        assertEquals(Bookmark.normalizedHost(bookmarks[0].host), Bookmark.normalizedHost(bookmarks[1].host))
        assertFalse(bookmarks.any { it.json().has("nickname") })
        assertEquals("second", bookmarks[1].copy(title = "Edited").json().getString("password"))
        assertEquals("MobileSpeakUser", ProfileState().nickname)
    }

    @Test fun controllerPreservesDuplicateEditsAndUsesConnectionIdForAutomaticTitle() {
        org.junit.Assume.assumeTrue("Use an isolated installation", InstrumentationRegistry.getArguments().getString("profileIsolated") == "1")
        ClientSession.initialize(context)
        fun field(name: String) = ClientSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        val store = field("store").get(ClientSession) as SecureStore
        val original = store.bookmarks()
        val state = field("mutableState").get(ClientSession) as kotlinx.coroutines.flow.MutableStateFlow<SessionUiState>
        val originalState = state.value
        val executor = field("coreExecutor").get(ClientSession) as java.util.concurrent.ExecutorService
        val gate = field("connectionGate").get(ClientSession) as ConnectionGate
        val originalId = gate.id
        val bookmarkField = field("activeBookmarkId")
        val originalBookmark = bookmarkField.get(ClientSession)
        val one = Bookmark("profile-one", "First", "example.invalid", 9987, "one", true)
        val two = Bookmark("profile-two", "Second", "EXAMPLE.invalid", 9987, "two", false)
        val other = Bookmark("profile-other", "Other", "other.invalid", 9987, "three", false)
        try {
            store.saveBookmarks(original + listOf(one, two, other))
            val edited = requireNotNull(ClientSession.saveBookmark(two.id, "Edited", two.host, "9987", "new password"))
            assertEquals(two.id, edited.id); assertEquals("new password", edited.password)
            assertEquals(original.size + 3, store.bookmarks().size)
            assertNull(ClientSession.saveBookmark(two.id, "Conflict", other.host, "9987", ""))
            assertEquals("Edited", store.bookmarks().single { it.id == two.id }.title)
            val existing = requireNotNull(ClientSession.saveBookmark(null, "", " EXAMPLE.invalid ", "9987", "one"))
            assertEquals(one.id, existing.id); assertEquals(original.size + 3, store.bookmarks().size)
            val update = ClientSession::class.java.getDeclaredMethod("updateAutomaticTitle", String::class.java).apply { isAccessible = true }
            executor.submit {
                gate.begin("profile-connection")
                bookmarkField.set(ClientSession, one.id)
                state.value = state.value.copy(snapshot = Snapshot(status = "connected", connectionId = gate.id), profile = ProfileState("Changed global nickname"))
                update.invoke(ClientSession, "Confirmed server")
                state.value = state.value.copy(snapshot = Snapshot(status = "connected", connectionId = "late old connection"))
                update.invoke(ClientSession, "Stale server")
            }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("Confirmed server", store.bookmarks().single { it.id == one.id }.title)
            assertEquals("Edited", store.bookmarks().single { it.id == two.id }.title)
        } finally {
            store.saveBookmarks(original)
            executor.submit {
                ConnectionGate::class.java.getDeclaredField("id").apply { isAccessible = true }.set(gate, originalId)
                bookmarkField.set(ClientSession, originalBookmark)
                state.value = originalState
            }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
