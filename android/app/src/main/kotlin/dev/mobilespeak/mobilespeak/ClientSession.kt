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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("StaticFieldLeak") // The stored context is applicationContext and owns the process session.
internal object ClientSession {
    private val handle by lazy { NativeCore.create().also { check(it != 0L) } }
    private val mutableState = MutableStateFlow(SessionUiState())
    private val mutableAppActive = MutableStateFlow(false)
    private val coreExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MobileSpeakCore")
    }
    private val coreDirty = AtomicBoolean()
    private val coreScheduled = AtomicBoolean()
    private val avatarWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "MobileSpeakAvatar") }
    private val avatarSelection = AtomicLong()
    private val avatarLock = Any()
    private val mutableAvatarSave = MutableStateFlow<Pair<Long, Boolean?>?>(null)
    val avatarSave = mutableAvatarSave.asStateFlow()
    val state = mutableState.asStateFlow()
    private lateinit var context: Context
    private lateinit var store: SecureStore
    private var initialized = false
    private var activeBookmarkId: String? = null
    @Volatile private var acceptingConnection = false
    val appActive = mutableAppActive.asStateFlow()
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
            val root = File(context.filesDir, "core")
            val preview = File(root, AvatarImages.previewName)
            val upload = File(root, AvatarImages.uploadName)
            val currentPreview = activeAvatarDirectory(root)?.let { File(it, "preview.jpg") }
            microphonePermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            mutableState.value = mutableState.value.copy(
                bookmarks = runCatching { store.bookmarks() }.getOrDefault(emptyList()),
                noiseSuppression = noise,
                microphoneMuted = !microphonePermission,
                error = store.readError,
                avatarPreviewPath = if (currentPreview?.isFile == true) currentPreview.absolutePath
                else if (!File(root, AvatarImages.currentName).exists() && preview.isFile && upload.isFile) {
                    (if (preview.lastModified() > upload.lastModified()) upload else preview).absolutePath
                } else null,
            )
            NativeCore.setNotifier(handle, Runnable(::scheduleCorePoll))
            scheduleCorePoll()
            root.mkdirs()
            send(JSONObject().put("type", "configure").put("storage", root.absolutePath))
            send(JSONObject().put("type", "set_noise_suppression").put("mode", noise))
            initialized = true
        }
    }

    fun beginAvatarSelection(): Long = synchronized(avatarLock) {
        mutableAvatarSave.value = null
        avatarSelection.incrementAndGet().also { send(JSONObject().put("type", "avatar_select").put("selection", it)) }
    }
    fun isCurrentAvatarSelection(selection: Long) = avatarSelection.get() == selection
    fun cancelAvatarSelection(selection: Long) = synchronized(avatarLock) {
        if (selection == avatarSelection.get()) {
            avatarSelection.incrementAndGet().also { send(JSONObject().put("type", "avatar_select").put("selection", it)) }
            mutableAvatarSave.value = null
        }
    }

    fun saveAvatar(image: android.graphics.Bitmap, crop: AvatarCrop, selection: Long) {
        synchronized(avatarLock) {
            if (selection != avatarSelection.get() || mutableAvatarSave.value?.let { it.first == selection && it.second != false } == true) return
            mutableAvatarSave.value = selection to null
        }
        avatarWorker.execute {
            if (selection != avatarSelection.get()) return@execute
            var directory: File? = null
            val result = runCatching {
                val (preview, uploads) = AvatarImages.prepare(image, crop)
                if (selection != avatarSelection.get()) return@execute
                val root = File(context.filesDir, "core")
                val next = File(root, "avatars/${UUID.randomUUID()}").apply { check(mkdirs()) }
                directory = next
                writeAvatar(File(next, "preview.jpg"), preview)
                uploads.forEachIndexed { index, bytes -> writeAvatar(File(next, "upload-$index.jpg"), bytes) }
                if (selection != avatarSelection.get()) return@runCatching
                check(send(JSONObject().put("type", "avatar_set").put("selection", selection).put("image", next.name))) { "Unable to save avatar intent" }
                directory = null // Core committed this version; stale callbacks must never remove it.
                synchronized(avatarLock) {
                    if (selection == avatarSelection.get()) mutableAvatarSave.value = selection to true
                }

            }
            directory?.let { staged ->
                // A failed rollback must not remove files still referenced by the current pointer.
                if (activeAvatarDirectory(File(context.filesDir, "core")) != staged) runCatching { staged.deleteRecursively() }
            }
            synchronized(avatarLock) {
                if (selection == avatarSelection.get() && result.isFailure) {
                    avatarFailure()
                    mutableAvatarSave.value = selection to false
                }
            }
        }
    }

    fun clearAvatar() {
        val selection = synchronized(avatarLock) {
            if (state.value.avatarClearingLocally) return
            mutableState.update { it.copy(avatarClearingLocally = true) }
            beginAvatarSelection()
        }
        avatarWorker.execute {
            val saved = send(JSONObject().put("type", "avatar_set").put("selection", selection).put("image", JSONObject.NULL))
            synchronized(avatarLock) {
                mutableState.update { it.copy(avatarClearingLocally = false) }
                if (selection == avatarSelection.get()) {
                    if (saved) mutableState.update { it.copy(avatarPreviewPath = null, avatarRevision = it.avatarRevision + 1, avatarIntent = "clear") }
                    else mutableState.update { it.copy(avatarStatus = "clear_save_failed", avatarDetail = null) }
                }
            }
        }
    }

    private fun activeAvatarDirectory(root: File): File? {
        val current = File(root, AvatarImages.currentName)
        if (!current.isFile || current.length() !in 1..64) return null
        val id = runCatching { current.readText() }.getOrNull() ?: return null
        return if (id.matches(Regex("[0-9a-fA-F-]{1,64}"))) File(root, "avatars/$id") else null
    }

    private fun writeAvatar(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.outputStream().use { it.write(bytes) }
        check(temp.renameTo(file)) { "Unable to save avatar" }
    }

    private fun avatarFailure() {
        mutableState.update { it.copy(avatarStatus = "failed", avatarDetail = context.localized(R.string.avatar_image_error)) }
    }

    fun connect(bookmark: Bookmark) {
        if (state.value.snapshot.status != "disconnected") return
        val identity = runCatching { store.identity() }.getOrElse {
            fail(context.localized(R.string.error_with_detail, context.localized(R.string.error_identity_read), it.message.orEmpty()))
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
        val intent = Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_START_CALL)
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
        mutableAppActive.value = active
        if (initialized) send(JSONObject().put("type", "set_app_active").put("active", active))
    }

    fun setMicrophonePermission(granted: Boolean) {
        val wasGranted = microphonePermission
        microphonePermission = granted
        if (!granted) setAudio(inputMuted = true)
        else if (!wasGranted) setAudio(inputMuted = false)
    }

    fun setAudio(inputMuted: Boolean? = null, deafened: Boolean? = null) {
        val old = state.value
        val nextInput = inputMuted ?: old.microphoneMuted
        val nextOutput = deafened ?: old.deafened
        if (!nextInput && !microphonePermission) {
            fail(context.localized(R.string.error_microphone_permission))
            return
        }
        mutableState.update { it.copy(microphoneMuted = nextInput, deafened = nextOutput) }
        if (old.snapshot.status == "connected") {
            send(JSONObject().put("type", "mute").put("input", nextInput || nextOutput).put("output", nextOutput))
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
            fail(context.localized(R.string.error_bookmark_invalid))
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
            fail(context.localized(R.string.error_bookmark_duplicate))
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
            fail(context.localized(R.string.error_with_detail, context.localized(R.string.error_bookmark_save), it.message.orEmpty()))
            null
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        val next = state.value.bookmarks.filterNot { it.id == bookmark.id }
        runCatching { store.saveBookmarks(next) }
            .onSuccess { mutableState.update { current -> current.copy(bookmarks = next) } }
            .onFailure { fail(context.localized(R.string.error_with_detail, context.localized(R.string.error_bookmark_delete), it.message.orEmpty())) }
    }

    fun join(channel: Channel, password: String) =
        send(JSONObject().put("type", "join").put("channel", channel.id).put("password", password))

    fun sendChannelMessage(channel: Channel, text: String) =
        send(JSONObject().put("type", "send_channel_message").put("request_id", UUID.randomUUID().toString())
            .put("channel", channel.id).put("message", text))

    fun sendPrivateMessage(member: Member, text: String): Boolean {
        val uid = member.uid ?: return false.also { fail(context.localized(R.string.error_user_identity_unavailable)) }
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

    private fun scheduleCorePoll() {
        coreDirty.set(true)
        if (coreScheduled.compareAndSet(false, true)) coreExecutor.execute(::drainCore)
    }

    private fun drainCore() {
        do {
            coreDirty.set(false)
            pollCore()
        } while (coreDirty.get())
        coreScheduled.set(false)
        if (coreDirty.get() && coreScheduled.compareAndSet(false, true)) coreExecutor.execute(::drainCore)
    }

    private fun pollCore() {
        val envelope = runCatching {
            val bytes = NativeCore.poll(handle)
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrElse {
            fail(context.localized(R.string.error_with_detail, context.localized(R.string.error_core_read), it.message.orEmpty()))
            return
        }
        runCatching { applyEnvelope(envelope) }.onFailure {
            fail(context.localized(R.string.error_with_detail, context.localized(R.string.error_core_parse), it.message.orEmpty()))
        }
    }

    private fun avatarSyncDetail(event: JSONObject): String? {
        val detail = event.stringOrNull("detail")
        if (event.optString("status") !in listOf("failed", "clear_failed")) return detail
        val phase = when (event.stringOrNull("phase")) {
            "local_read" -> R.string.avatar_stage_local_read
            "server_self" -> R.string.avatar_stage_server_self
            "upload_connection" -> R.string.avatar_stage_upload_connection
            "upload_write" -> R.string.avatar_stage_upload_write
            "hash_command" -> R.string.avatar_stage_hash_command
            "server_hash" -> R.string.avatar_stage_server_hash
            "verify_connection" -> R.string.avatar_stage_verify_connection
            "verify_file" -> R.string.avatar_stage_verify_file
            "synced" -> R.string.avatar_stage_synced
            "delete_command" -> R.string.avatar_stage_delete_command
            "clear_hash" -> R.string.avatar_stage_clear_hash
            "cleared" -> R.string.avatar_stage_cleared
            "previous_transfer_query" -> R.string.avatar_stage_previous_transfer_query
            "previous_transfer_stop" -> R.string.avatar_stage_previous_transfer_stop
            else -> return detail
        }
        return context.localized(R.string.error_with_detail, context.localized(phase), detail.orEmpty())
    }

    private fun applyEnvelope(envelope: JSONObject) {
        val wasConnected = state.value.snapshot.status == "connected"
        var eventError: String? = null
        var audioMuted = false
        val events = envelope.getJSONArray("events")
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            when (event.getString("type")) {
                "identity" -> {
                    val identity = event.getJSONObject("value")
                    runCatching { store.saveIdentity(identity) }.onFailure {
                        fail(context.localized(R.string.error_with_detail, context.localized(R.string.error_identity_save), it.message.orEmpty()))
                        disconnect()
                        return
                    }
                }
                "error" -> eventError = context.localizedCoreError(
                    event.stringOrNull("code"),
                    event.stringOrNull("detail") ?: event.stringOrNull("message"),
                )
                "audio_muted" -> {
                    audioMuted = true
                    eventError = context.localizedCoreError(
                        event.stringOrNull("code"),
                        event.stringOrNull("detail") ?: event.stringOrNull("message"),
                    )
                }
                "avatar_local" -> mutableState.update { it.copy(
                    avatarIntent = event.getString("intent"), avatarCleanupDetail = null,
                    avatarPreviewPath = if (event.getString("intent") == "clear") null else event.stringOrNull("preview") ?: it.avatarPreviewPath,
                    avatarRevision = it.avatarRevision + 1,
                ) }
                "avatar_cleanup_failed" -> mutableState.update { it.copy(avatarCleanupDetail = context.localized(R.string.error_with_detail, context.localized(R.string.avatar_cleanup_error), event.optString("detail"))) }
                "avatar_sync" -> mutableState.update { it.copy(
                    avatarStatus = event.getString("status"), avatarDetail = avatarSyncDetail(event),
                ) }
            }
        }
        val nextSnapshot = envelope.getJSONObject("snapshot").snapshot()
        if (!acceptingConnection && nextSnapshot.status != "disconnected") return
        val chats = if (envelope.get("chats") == JSONObject.NULL) null else envelope.getJSONArray("chats").objects().map {
            ChatMessage(
                it.getString("id"), it.getString("conversation"), it.getString("senderName"),
                it.stringOrNull("avatarPath"), it.getBoolean("own"), it.getString("text"),
                it.getLong("timestamp"), it.getString("status"), it.stringOrNull("error"),
            )
        }
        val unreadJson = envelope.getJSONObject("unread")
        val unread = Unread(
            unreadJson.stringOrNull("serverId"), unreadJson.stringOrNull("channel"),
            unreadJson.getInt("channelCount"), unreadJson.getJSONObject("privateCounts").toIntMap(),
        )
        mutableState.update { latest ->
            latest.copy(
                snapshot = nextSnapshot,
                messages = chats ?: latest.messages,
                unread = unread,
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

    fun captureStopped() { if (initialized) send(JSONObject().put("type", "capture_stopped")) }

    fun capture(samples: ShortArray): Int = NativeCore.capture(handle, samples)
    fun playback(samples: FloatArray): Int = NativeCore.playback(handle, samples)
    fun shouldRunService() = acceptingConnection || state.value.snapshot.status in setOf("connected", "connecting", "reconnecting")

    private fun send(command: JSONObject): Boolean {
        val result = runCatching {
            NativeCore.command(handle, command.toString().toByteArray(Charsets.UTF_8))
        }.getOrElse {
            fail(context.localized(R.string.error_with_detail, context.localized(R.string.error_operation_submit), it.message.orEmpty()))
            return false
        }
        if (result != 0) fail(context.localized(R.string.error_operation_submit))
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
            .onFailure { fail(context.localized(R.string.error_with_detail, context.localized(R.string.error_bookmark_title_update), it.message.orEmpty())) }
    }

    fun refreshLanguage() {
        if (!initialized || !shouldRunService()) return
        context.startService(Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_REFRESH_LANGUAGE))
    }

    private fun fail(message: String) {
        mutableState.update { it.copy(error = message) }
    }
}

private fun JSONObject?.toIntMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { getInt(it) }
}
