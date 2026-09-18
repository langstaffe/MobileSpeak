package dev.mobilespeak.mobilespeak

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

@SuppressLint("StaticFieldLeak") // The stored context is applicationContext and owns the process session.
internal object ClientSession {
    private val nativeLock = Any()
    private val handle by lazy { NativeCore.create().also { check(it != 0L) } }
    private val mutableState = MutableStateFlow(SessionUiState())
    val state = mutableState.asStateFlow()
    private lateinit var context: Context
    private lateinit var store: SecureStore
    private var initialized = false
    private var activeBookmarkId: String? = null
    @Volatile private var acceptingConnection = false
    @Volatile var appActive = false
        private set
    @Volatile var microphonePermission = false
        private set

    fun initialize(applicationContext: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            context = applicationContext.applicationContext
            store = SecureStore(context)
            val noise = context.getSharedPreferences("mobilespeak.settings", Context.MODE_PRIVATE)
                .getString("noise", "rnnoise") ?: "rnnoise"
            mutableState.value = mutableState.value.copy(
                bookmarks = runCatching { store.bookmarks() }.getOrDefault(emptyList()),
                noiseSuppression = noise,
                error = store.readError,
            )
            val root = File(context.filesDir, "core").apply { mkdirs() }
            send(JSONObject().put("type", "configure").put("storage", root.absolutePath))
            send(JSONObject().put("type", "set_noise_suppression").put("mode", noise))
            microphonePermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            initialized = true
        }
    }

    fun connect(bookmark: Bookmark) {
        if (state.value.snapshot.status != "disconnected") return
        val identity = runCatching { store.identity() }.getOrElse {
            fail("无法安全读取 TS 身份：${it.message}")
            return
        }
        val command = JSONObject()
            .put("type", "connect")
            .put("address", bookmark.address)
            .put("name", bookmark.nickname)
            .put("password", bookmark.password)
            .put("identity", identity ?: JSONObject.NULL)
        if (!send(command)) return
        activeBookmarkId = bookmark.id
        acceptingConnection = true
        mutableState.update {
            it.copy(
                snapshot = Snapshot(status = "connecting"),
                microphoneMuted = it.microphoneMuted || !microphonePermission,
                error = null,
            )
        }
        val intent = Intent(context, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun disconnect() {
        acceptingConnection = false
        activeBookmarkId = null
        send(JSONObject().put("type", "disconnect"))
        mutableState.update { it.copy(snapshot = Snapshot(), messages = emptyList(), unread = Unread()) }
        context.stopService(Intent(context, VoiceService::class.java))
    }

    fun setAppActive(active: Boolean) {
        appActive = active
        if (initialized) send(JSONObject().put("type", "set_app_active").put("active", active))
    }

    fun setMicrophonePermission(granted: Boolean) {
        microphonePermission = granted
        if (!granted) setAudio(inputMuted = true)
        else setAudio(inputMuted = false)
    }

    fun setAudio(inputMuted: Boolean? = null, deafened: Boolean? = null) {
        val old = state.value
        val nextInput = inputMuted ?: old.microphoneMuted
        val nextOutput = deafened ?: old.deafened
        if (!nextInput && !microphonePermission) {
            fail("请在系统设置中允许麦克风权限")
            return
        }
        mutableState.update { it.copy(microphoneMuted = nextInput, deafened = nextOutput) }
        if (old.snapshot.status == "connected") {
            send(JSONObject().put("type", "mute").put("input", nextInput || nextOutput).put("output", nextOutput))
        }
    }

    fun setAudioInterrupted(interrupted: Boolean) {
        val current = state.value
        if (current.snapshot.status == "connected") {
            send(JSONObject().put("type", "mute")
                .put("input", interrupted || current.microphoneMuted || current.deafened)
                .put("output", current.deafened))
        }
    }

    fun setNoiseSuppression(mode: String) {
        if (mode !in setOf("rnnoise", "none")) return
        if (!send(JSONObject().put("type", "set_noise_suppression").put("mode", mode))) return
        context.getSharedPreferences("mobilespeak.settings", Context.MODE_PRIVATE)
            .edit { putString("noise", mode) }
        mutableState.update { it.copy(noiseSuppression = mode) }
    }

    fun saveBookmark(id: String?, title: String, host: String, port: String, nickname: String, password: String): Bookmark? {
        val bookmark = runCatching { Bookmark.create(id, title, host, port, nickname, password) }.getOrElse {
            fail(it.message ?: "书签无效")
            return null
        }
        val existing = state.value.bookmarks
        val original = existing.firstOrNull { it.id == id }
        val resolvedAutomaticName = title.trim().isEmpty() ||
            (original?.automaticallyNamed == true && title.trim() == original.title)
        val normalized = bookmark.copy(automaticallyNamed = resolvedAutomaticName)
        val duplicate = existing.firstOrNull {
            it.id != id && it.host == normalized.host && it.port == normalized.port && it.nickname == normalized.nickname
        }
        if (id != null && duplicate != null) {
            fail("已有相同地址、端口和昵称的书签")
            return null
        }
        val resolved = if (id == null && duplicate != null) normalized.copy(id = duplicate.id) else normalized
        val next = existing.toMutableList().apply {
            val index = indexOfFirst { it.id == resolved.id }
            if (index >= 0) set(index, resolved) else add(resolved)
        }
        return runCatching {
            store.saveBookmarks(next)
            mutableState.update { it.copy(bookmarks = next, error = null) }
            resolved
        }.getOrElse {
            fail("无法安全保存书签：${it.message}")
            null
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        val next = state.value.bookmarks.filterNot { it.id == bookmark.id }
        runCatching { store.saveBookmarks(next) }
            .onSuccess { mutableState.update { current -> current.copy(bookmarks = next) } }
            .onFailure { fail("无法安全删除书签：${it.message}") }
    }

    fun join(channel: Channel, password: String) =
        send(JSONObject().put("type", "join").put("channel", channel.id).put("password", password))

    fun sendChannelMessage(channel: Channel, text: String) =
        send(JSONObject().put("type", "send_channel_message").put("request_id", UUID.randomUUID().toString())
            .put("channel", channel.id).put("message", text))

    fun sendPrivateMessage(member: Member, text: String): Boolean {
        val uid = member.uid ?: return false.also { fail("该用户没有可用的 TeamSpeak UID") }
        return send(JSONObject().put("type", "send_private_message").put("request_id", UUID.randomUUID().toString())
            .put("client", member.id).put("uid", uid).put("message", text))
    }

    fun setChatVisible(conversation: String, token: String, visible: Boolean) {
        val server = state.value.snapshot.serverId ?: return
        send(JSONObject().put("type", "set_chat_visible").put("server", server)
            .put("conversation", conversation).put("token", token).put("visible", visible))
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    fun reportError(message: String) = fail(message)

    fun pollCore() {
        val wasConnected = state.value.snapshot.status == "connected"
        val envelope = runCatching {
            val bytes = synchronized(nativeLock) { NativeCore.poll(handle) }
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrElse {
            fail("核心状态读取失败：${it.message}")
            return
        }
        var eventError: String? = null
        var audioMuted = false
        val events = envelope.optJSONArray("events")
        if (events != null) for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            when (event.optString("type")) {
                "identity" -> {
                    val identity = event.optJSONObject("value") ?: continue
                    runCatching { store.saveIdentity(identity) }.onFailure {
                        fail("无法安全保存 TS 身份：${it.message}")
                        disconnect()
                        return
                    }
                }
                "error" -> eventError = event.optString("message", "连接错误")
                "audio_muted" -> {
                    audioMuted = true
                    eventError = event.optString("message", "服务器已关闭麦克风")
                }
            }
        }
        val nextSnapshot = envelope.getJSONObject("snapshot").snapshot()
        if (!acceptingConnection && nextSnapshot.status != "disconnected") return
        val chats = envelope.optJSONArray("chats")?.objects()?.map {
            ChatMessage(
                it.getString("id"), it.getString("conversation"), it.getString("senderName"),
                it.stringOrNull("avatarPath"), it.optBoolean("own"), it.getString("text"),
                it.getLong("timestamp"), it.getString("status"), it.stringOrNull("error"),
            )
        }
        val unreadJson = envelope.optJSONObject("unread")
        val unread = if (unreadJson == null) null else Unread(
            unreadJson.stringOrNull("serverId"), unreadJson.stringOrNull("channel"),
            unreadJson.optInt("channelCount"), unreadJson.optJSONObject("privateCounts").toIntMap(),
        )
        mutableState.update { latest ->
            latest.copy(
                snapshot = nextSnapshot,
                messages = chats ?: latest.messages,
                unread = unread ?: latest.unread,
                error = eventError ?: latest.error,
                microphoneMuted = if (audioMuted) true else latest.microphoneMuted,
            )
        }
        if (nextSnapshot.status == "connected") {
            updateAutomaticTitle(nextSnapshot.server)
            if (!wasConnected) {
                val audio = state.value
                setAudio(inputMuted = audio.microphoneMuted, deafened = audio.deafened)
            }
        } else if (nextSnapshot.status == "disconnected") {
            acceptingConnection = false
        }
    }

    fun capture(samples: ShortArray): Int = synchronized(nativeLock) { NativeCore.capture(handle, samples) }
    fun playback(samples: FloatArray): Int = synchronized(nativeLock) { NativeCore.playback(handle, samples) }
    fun shouldRunService() = acceptingConnection || state.value.snapshot.status in setOf("connected", "connecting", "reconnecting")

    private fun send(command: JSONObject): Boolean {
        val result = runCatching {
            synchronized(nativeLock) { NativeCore.command(handle, command.toString().toByteArray(Charsets.UTF_8)) }
        }.getOrElse {
            fail("操作未能提交：${it.message}")
            return false
        }
        if (result != 0) fail("操作未能提交")
        return result == 0
    }

    private fun updateAutomaticTitle(serverName: String?) {
        val name = serverName?.trim().orEmpty()
        if (name.isEmpty()) return
        val connectedBookmark = activeBookmarkId ?: return
        val next = state.value.bookmarks.map {
            if (it.id == connectedBookmark && it.automaticallyNamed && it.title != name) it.copy(title = name) else it
        }
        if (next == state.value.bookmarks) return
        runCatching { store.saveBookmarks(next) }
            .onSuccess { mutableState.update { current -> current.copy(bookmarks = next) } }
            .onFailure { fail("无法更新书签名称：${it.message}") }
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(error = message) }
    }
}

private fun JSONObject?.toIntMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { getInt(it) }
}
