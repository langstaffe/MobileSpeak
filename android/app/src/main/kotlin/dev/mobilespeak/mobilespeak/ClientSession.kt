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
    private val handle by lazy { NativeCore.create(context).also { check(it != 0L) } }
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
    private var displayedLanguage = AppLanguage.ENGLISH
    private val nicknameRequest = AtomicLong()
    private val audioRequest = AtomicLong()
    private val audioLock = Any()
    private val fileCacheLock = Any()
    private var fileCacheRequest = 0L
    private var fileCacheClearRequest: Long? = null
    private var activeBookmarkId: String? = null
    private val connectionGate = ConnectionGate()
    private var pendingConnect: JSONObject? = null
    private var tailPollScheduled = false
    private val serviceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val bookmarks by lazy { BookmarkTransactions(store::bookmarks, store::saveBookmarks) { next ->
        mutableState.update { it.copy(bookmarks = next) }
    } }
    val appActive = mutableAppActive.asStateFlow()
    @Volatile var microphonePermission = false
        private set

    fun initialize(applicationContext: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            context = applicationContext.applicationContext
            displayedLanguage = AppLanguage.resolve(AppLanguage.selected(context), systemLanguageTags())
            store = SecureStore(context)
            val noise = restoreNoiseSuppression(context.getSharedPreferences("mobilespeak.settings", Context.MODE_PRIVATE))
            val root = File(context.filesDir, "core")
            val preview = File(root, AvatarImages.previewName)
            val upload = File(root, AvatarImages.uploadName)
            val currentPreview = activeAvatarDirectory(root)?.let { File(it, "preview.jpg") }
            microphonePermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            mutableState.value = mutableState.value.copy(
                bookmarks = runCatching { store.bookmarks() }.getOrDefault(emptyList()),
                noiseSuppression = noise, audioProcessingStatus = "switching",
                microphoneMuted = !microphonePermission, microphonePermission = microphonePermission,
                error = store.readError,
                errorSource = if (store.readError != null) LocalizedMessage(R.string.error_secure_store_read) else null,
                avatarPreviewPath = if (currentPreview?.isFile == true) currentPreview.absolutePath
                else if (!File(root, AvatarImages.currentName).exists() && preview.isFile && upload.isFile) {
                    (if (preview.lastModified() > upload.lastModified()) upload else preview).absolutePath
                } else null,
            )
            NativeCore.setNotifier(handle, Runnable(::scheduleCorePoll))
            scheduleCorePoll()
            root.mkdirs()
            send(JSONObject().put("type", "configure").put("storage", root.absolutePath))
            setNoiseSuppression(noise)
            initialized = true
            context.registerComponentCallbacks(object : android.content.ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { refreshLanguage() }
                @Suppress("OVERRIDE_DEPRECATION") override fun onLowMemory() = Unit
            })
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
                    else mutableState.update { it.copy(avatarStatus = "clear_save_failed", avatarDetail = null, avatarDetailSource = null) }
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
        val source = LocalizedMessage(R.string.avatar_image_error)
        mutableState.update { it.copy(avatarStatus = "failed", avatarDetail = source.render(context.languageContext()), avatarDetailSource = source) }
    }

    fun connect(bookmark: Bookmark) = coreExecutor.execute {
        if (state.value.snapshot.status != "disconnected") return@execute
        val identity = runCatching { store.identity() }.getOrElse {
            fail(localizedFailureSource(R.string.error_identity_read, it))
            return@execute
        }
        val connectionId = UUID.randomUUID().toString()
        val command = JSONObject().put("connectionId", connectionId)
            .put("type", "connect")
            .put("address", bookmark.address)
            .put("password", bookmark.password)
            .put("identity", identity ?: JSONObject.NULL)
        pendingConnect = command
        activeBookmarkId = bookmark.id
        connectionGate.begin(connectionId)
        mutableState.update {
            it.copy(
                snapshot = Snapshot(status = "connecting", connectionId = connectionId),
                soundPending = false,
                microphoneMuted = it.microphoneMuted || !microphonePermission,
                error = null, errorSource = null,
            )
        }
        updateService()
    }

    // Output preparation runs without microphone authorization. Old HAL callbacks cannot submit a new request.
    fun audioOutputPrepared(connectionId: String?) = coreExecutor.execute {
        if (!connectionGate.accepts(connectionId) || state.value.snapshot.status != "connecting") return@execute
        val command = pendingConnect ?: return@execute
        pendingConnect = null
        if (!send(command)) {
            connectionGate.cancel()
            mutableState.update { it.copy(snapshot = Snapshot(connectionId = connectionGate.id)) }
            updateService()
        }
    }

    fun disconnect() = coreExecutor.execute(::disconnectCore)

    private fun disconnectCore() {
        val submitted = pendingConnect == null
        pendingConnect = null
        if (submitted && !send(JSONObject().put("type", "disconnect"))) { updateService(); return }
        connectionGate.cancel()
        activeBookmarkId = null
        mutableState.update { it.copy(snapshot = Snapshot(connectionId = connectionGate.id),
            soundPending = submitted && (it.snapshot.status == "connected" || it.soundPending),
            messages = emptyList(), unread = Unread()) }
        updateService()
    }

    fun setAppActive(active: Boolean) {
        mutableAppActive.value = active
        if (initialized) send(JSONObject().put("type", "set_app_active").put("active", active))
    }

    fun setMicrophonePermission(granted: Boolean, enableMicrophone: Boolean = true) {
        val wasGranted = microphonePermission
        microphonePermission = granted
        mutableState.update { it.copy(microphonePermission = granted) }
        if (!granted) setAudio(inputMuted = true)
        else if (!wasGranted && enableMicrophone) setAudio(inputMuted = false)
    }

    fun setAudio(inputMuted: Boolean? = null, deafened: Boolean? = null) {
        if (deafened == true) setPlaybackActive(false)
        if (inputMuted == true || deafened == true) cancelPushToTalk()
        coreExecutor.execute { setAudioCore(inputMuted, deafened) }
    }

    private fun setAudioCore(inputMuted: Boolean? = null, deafened: Boolean? = null) {
        val old = state.value
        val nextInput = inputMuted ?: old.microphoneMuted
        val nextOutput = deafened ?: old.deafened
        if (!nextInput && !microphonePermission) {
            fail(LocalizedMessage(R.string.error_microphone_permission))
            return
        }
        mutableState.update { it.copy(microphoneMuted = nextInput, deafened = nextOutput) }
        // A rapid off/on can be conflated by StateFlow; still reapply the final output gate.
        if (inputMuted != null || deafened != null) {
            voiceService?.let { service -> serviceHandler.post { service.startCall() } }
        }
        if (old.snapshot.canApplyAudioState()) {
            send(JSONObject().put("type", "mute").put("input", nextInput || nextOutput).put("output", nextOutput))
        }
    }

    fun setSpeechMode(mode: String) {
        if (mode != "automatic" && mode != "push_to_talk") return
        send(JSONObject().put("type", "set_speech_mode").put("mode", mode))
    }
    private val pushToTalkLock = Any()
    private var heldSpeechToken: String? = null
    private var captureSegment = 0L
    fun pushToTalk(pressed: Boolean, token: String) = synchronized(pushToTalkLock) {
        captureSegment++
        if (pressed) heldSpeechToken = token
        else if (heldSpeechToken == token) heldSpeechToken = null
        send(JSONObject().put("type", "push_to_talk").put("pressed", pressed).put("token", token))
        Unit
    }
    fun cancelPushToTalk() = synchronized(pushToTalkLock) {
        heldSpeechToken?.let { pushToTalk(false, it) }
    }
    fun setCaptureReady(ready: Boolean) {
        if (initialized) send(JSONObject().put("type", "set_capture_ready").put("ready", ready))
    }

    fun setReceiveAgc(enabled: Boolean) { send(JSONObject().put("type", "set_receive_agc").put("enabled", enabled)) }
    fun openUserVolumes() { mutableState.update { it.copy(userVolumesOpen = true) } }
    fun closeUserVolumes() { mutableState.update { it.copy(userVolumesOpen = false) } }
    fun setUserVolume(server: String, member: Member, percent: Int) {
        val token = member.volumeToken ?: return
        send(JSONObject().put("type", "set_user_volume").put("server", server).put("client", member.id)
            .put("uid", member.uid ?: JSONObject.NULL).put("token", token).put("percent", percent))
    }
    fun setNoiseSuppression(mode: String) {
        if (mode !in setOf("dpdfnet2", "rnnoise", "none")) return
        // Core notifications arrive on another executor, including very fast RNNoise/None loads.
        synchronized(audioLock) {
            if (!send(JSONObject().put("type", "set_noise_suppression").put("mode", mode).put("request_id", audioRequest.incrementAndGet()))) return
            mutableState.update { it.copy(noiseSuppression = mode, audioProcessingStatus = "switching",
                audioProcessingError = null, audioProcessingErrorSource = null) }
        }
    }

    fun saveNickname(nickname: String): Long {
        val request = nicknameRequest.incrementAndGet()
        coreExecutor.execute {
            if (request != nicknameRequest.get()) return@execute
            mutableState.update { it.copy(nicknameSaving = true, nicknameSaveError = null, nicknameSaveErrorSource = null) }
            if (!send(JSONObject().put("type", "set_nickname").put("nickname", nickname).put("request_id", request))) {
                mutableState.update { it.copy(nicknameSaving = false, nicknameSaveError = it.error,
                    nicknameSaveErrorSource = it.errorSource) }
            }
        }
        return request
    }

    fun saveBookmark(id: String?, title: String, host: String, port: String, password: String): Bookmark? {
        val bookmark = runCatching { Bookmark.create(id, title, host, port, password) }.getOrElse {
            fail(LocalizedMessage(R.string.error_bookmark_invalid))
            return null
        }
        return runCatching {
            bookmarks.change { existing ->
                val original = existing.firstOrNull { it.id == id }
                val automatic = title.trim().isEmpty() ||
                    (original?.automaticallyNamed == true && title.trim() == original.title)
                val normalized = bookmark.copy(automaticallyNamed = automatic)
                val duplicate = existing.firstOrNull {
                    it.id != id && Bookmark.normalizedHost(it.host) == normalized.host && it.port == normalized.port
                }
                val endpointChanged = original == null || Bookmark.normalizedHost(original.host) != normalized.host || original.port != normalized.port
                if (id != null && endpointChanged && duplicate != null)
                    throw LocalizedFailure(LocalizedMessage(R.string.error_bookmark_duplicate), context)
                val resolved = if (id == null && duplicate != null) normalized.copy(id = duplicate.id) else normalized
                val next = existing.toMutableList().apply {
                    val index = indexOfFirst { it.id == resolved.id }
                    if (index >= 0) set(index, resolved) else add(resolved)
                }
                next to resolved
            }.also { mutableState.update { it.copy(error = null, errorSource = null) } }
        }.getOrElse {
            fail(localizedFailureSource(R.string.error_bookmark_save, it))
            null
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        runCatching { bookmarks.change { latest -> latest.filterNot { it.id == bookmark.id } to Unit } }
            .onFailure { fail(localizedFailureSource(R.string.error_bookmark_delete, it)) }
    }

    fun join(channel: Channel, password: String) =
        send(JSONObject().put("type", "join").put("channel", channel.id).put("password", password))

    fun sendChannelMessage(channel: Channel, text: String) =
        send(JSONObject().put("type", "send_channel_message").put("request_id", UUID.randomUUID().toString())
            .put("channel", channel.id).put("message", text))

    fun sendPrivateMessage(member: Member, text: String): Boolean {
        val uid = member.uid ?: return false.also { fail(LocalizedMessage(R.string.error_user_identity_unavailable)) }
        return send(JSONObject().put("type", "send_private_message").put("request_id", UUID.randomUUID().toString())
            .put("client", member.id).put("uid", uid).put("message", text))
    }

    fun setChatVisible(conversation: String, token: String, visible: Boolean) {
        val server = state.value.snapshot.serverId ?: return
        send(JSONObject().put("type", "set_chat_visible").put("server", server)
            .put("conversation", conversation).put("token", token).put("visible", visible))
    }

    fun clearError() {
        mutableState.update { it.copy(error = null, errorSource = null) }
    }

    fun reportError(source: LocalizedMessage) = fail(source)

    private fun scheduleCorePoll() {
        coreDirty.set(true)
        if (coreScheduled.compareAndSet(false, true)) coreExecutor.execute(::drainCore)
    }

    private fun drainCore() {
        // Yield between envelopes so continuous notifications cannot starve cancel/connect.
        coreDirty.set(false)
        pollCore()
        coreScheduled.set(false)
        if (coreDirty.get() && coreScheduled.compareAndSet(false, true)) coreExecutor.execute(::drainCore)
    }

    private fun pollCore() {
        val envelope = runCatching {
            val bytes = NativeCore.poll(handle)
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrElse {
            fail(LocalizedMessage(R.string.error_core_read, it.message.orEmpty()))
            return
        }
        runCatching { applyEnvelope(envelope) }.onFailure {
            fail(LocalizedMessage(R.string.error_core_parse, it.message.orEmpty()))
        }
    }

    private fun avatarSyncDetail(event: JSONObject): Pair<String?, LocalizedMessage?> {
        val detail = event.stringOrNull("detail")
        if (event.optString("status") !in listOf("failed", "clear_failed")) return detail to null
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
            else -> return detail to null
        }
        val source = LocalizedMessage(phase, detail)
        return source.render(context.languageContext()) to source
    }

    private fun applyEnvelope(envelope: JSONObject) {
        envelope.optJSONObject("profile")?.let { value ->
            val profile = value.profile()
            val completed = profile.requestId == nicknameRequest.get()
            val source = if (completed) profile.error?.let { code -> coreErrorSource(code, profile.detail) } else null
            mutableState.update { it.copy(profile = profile,
                nicknameSaving = if (completed) false else it.nicknameSaving,
                nicknameSaveError = if (completed) source?.render(context.languageContext()) else it.nicknameSaveError,
                nicknameSaveErrorSource = if (completed) source else it.nicknameSaveErrorSource) }
        }
        val wasAudioReady = state.value.snapshot.canApplyAudioState()
        var eventError: LocalizedMessage? = null
        var audioMuted = false
        val events = envelope.getJSONArray("events")
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            if (event.has("connectionId") && !connectionGate.accepts(event.stringOrNull("connectionId"))) continue
            when (event.getString("type")) {
                "file_cache_result" -> synchronized(fileCacheLock) {
                    if (event.optLong("requestId") == fileCacheRequest) {
                        val clearing = fileCacheClearRequest == fileCacheRequest
                        if (clearing) fileCacheClearRequest = null
                        val error = event.stringOrNull("error")
                        val fileSource = error?.let(::fileErrorSource)
                        val source = if (error != null) LocalizedMessage(
                            if (clearing) R.string.files_cache_clear_failed else R.string.files_cache_read_failed,
                            detail = if (fileSource == null) error else null, detailSource = fileSource,
                        ) else if (clearing) LocalizedMessage(R.string.files_cache_cleared, sizeBytes = event.optLong("removedBytes")) else null
                        mutableState.update { it.copy(fileCachePending = false, fileCacheClearing = if (clearing) false else it.fileCacheClearing,
                            fileCacheMessage = source?.render(context.languageContext()) ?: it.fileCacheMessage,
                            fileCacheMessageSource = source ?: it.fileCacheMessageSource) }
                    }
                }
                "identity" -> {
                    val identity = event.getJSONObject("value")
                    runCatching { store.saveIdentity(identity) }.onFailure {
                        fail(localizedFailureSource(R.string.error_identity_save, it))
                        disconnect()
                        return
                    }
                }
                "error" -> eventError = coreErrorSource(
                    event.stringOrNull("code"),
                    event.stringOrNull("detail") ?: event.stringOrNull("message"),
                )
                "audio_processing" -> synchronized(audioLock) {
                    if (event.optLong("requestId") < audioRequest.get()) return@synchronized
                    val applied = event.getJSONObject("applied")
                    val requested = event.getJSONObject("requested")
                    val status = event.getString("status")
                    val source = if (status == "failed") LocalizedMessage(R.string.error_noise_suppression, event.optString("detail")) else null
                    mutableState.update { it.copy(
                        noiseSuppression = requested.getString("noise"),
                        appliedNoise = applied.getString("noise"),
                        audioProcessingStatus = status,
                        audioProcessingError = source?.render(context.languageContext()), audioProcessingErrorSource = source,
                    ) }
                    if (status != "switching") {
                        context.getSharedPreferences("mobilespeak.settings", Context.MODE_PRIVATE).edit {
                            putString("noise", applied.getString("noise"))
                        }
                    }
                }
                "audio_muted" -> {
                    audioMuted = true
                    eventError = coreErrorSource(
                        event.stringOrNull("code"),
                        event.stringOrNull("detail") ?: event.stringOrNull("message"),
                    )
                }
                "avatar_local" -> mutableState.update { it.copy(
                    avatarIntent = event.getString("intent"), avatarCleanupDetail = null, avatarCleanupDetailSource = null,
                    avatarPreviewPath = if (event.getString("intent") == "clear") null else event.stringOrNull("preview") ?: it.avatarPreviewPath,
                    avatarRevision = it.avatarRevision + 1,
                ) }
                "avatar_cleanup_failed" -> {
                    val source = LocalizedMessage(R.string.avatar_cleanup_error, event.optString("detail"))
                    mutableState.update { it.copy(avatarCleanupDetail = source.render(context.languageContext()), avatarCleanupDetailSource = source) }
                }
                "avatar_sync" -> {
                    val (detail, source) = avatarSyncDetail(event)
                    mutableState.update { it.copy(avatarStatus = event.getString("status"), avatarDetail = detail, avatarDetailSource = source) }
                }
            }
        }
        if (eventError != null || audioMuted) mutableState.update { it.copy(
            error = eventError?.render(context.languageContext()) ?: it.error,
            errorSource = eventError ?: it.errorSource, microphoneMuted = audioMuted || it.microphoneMuted) }
        envelope.optJSONObject("fileCache")?.let { cache -> mutableState.update { it.copy(fileCache = cache.fileCache()) } }
        envelope.optJSONObject("receiveAudio")?.let { audio -> mutableState.update { it.copy(receiveAgc = audio.optBoolean("agc", true)) } }
        envelope.optJSONObject("speech")?.let { speech -> mutableState.update { it.copy(speech = speech.speech()) } }
        // Global events above remain valid even while Rust has not consumed Connect yet.
        val snapshotJson = envelope.getJSONObject("snapshot")
        val nextSnapshot = snapshotJson.snapshot()
        if (!connectionGate.accepts(nextSnapshot.connectionId, nextSnapshot.status)) return
        val chats = if (envelope.get("chats") == JSONObject.NULL) null else envelope.getJSONArray("chats").objects().map(JSONObject::chatMessage)
        val unreadJson = envelope.getJSONObject("unread")
        val unread = Unread(
            unreadJson.stringOrNull("serverId"), unreadJson.stringOrNull("channel"),
            unreadJson.getInt("channelCount"), unreadJson.getJSONObject("privateCounts").toIntMap(),
        )
        mutableState.update { latest ->
            latest.copy(
                snapshot = nextSnapshot,
                soundPending = envelope.optBoolean("soundPending", false),
                receiveAgc = envelope.optJSONObject("receiveAudio")?.optBoolean("agc", true) ?: latest.receiveAgc,
                userVolumesOpen = latest.userVolumesOpen && nextSnapshot.status == "connected",
                channelFiles = envelope.optJSONObject("channelFiles")?.channelFiles() ?: latest.channelFiles,
                messages = chats ?: latest.messages,
                unread = unread,
                error = eventError?.render(context.languageContext()) ?: latest.error,
                errorSource = eventError ?: latest.errorSource,
                microphoneMuted = if (audioMuted) true else latest.microphoneMuted,
            )
        }
        if (nextSnapshot.status == "connected") {
            updateAutomaticTitle(nextSnapshot.server)
            if (!wasAudioReady && nextSnapshot.canApplyAudioState()) {
                setAudioCore()
            }
        }
        updateService()
        // Poll only while a terminal clip drains; no extra service or background lifetime.
        if (nextSnapshot.status == "disconnected" && state.value.soundPending && !tailPollScheduled) {
            tailPollScheduled = true
            serviceHandler.postDelayed({
                coreExecutor.execute { tailPollScheduled = false; pollCore() }
            }, 20)
        }
    }

    fun fileLocalError(message: String?) { mutableState.update { it.copy(channelFilesLocalError = message, channelFilesLocalErrorSource = null) } }
    fun fileLocalError(source: LocalizedMessage) { mutableState.update { it.copy(
        channelFilesLocalError = source.render(context.languageContext()), channelFilesLocalErrorSource = source) } }
    fun refreshFileCache() = synchronized(fileCacheLock) {
        val ui = state.value
        if (ui.fileCachePending || ui.fileCache.working || ui.fileImporting || ui.fileCache.busy) return@synchronized
        fileCacheRequest += 1
        mutableState.update { it.copy(fileCachePending = true, fileCacheMessage = null, fileCacheMessageSource = null) }
        if (!send(JSONObject().put("type", "file_cache_inspect").put("request_id", fileCacheRequest))) {
            val source = LocalizedMessage(R.string.files_cache_read_failed)
            mutableState.update { it.copy(fileCachePending = false, fileCacheMessage = source.render(context.languageContext()), fileCacheMessageSource = source) }
        }
    }
    fun clearFileCache() = synchronized(fileCacheLock) {
        val ui = state.value
        if (!ui.fileCache.canClear || ui.fileImporting || ui.fileCachePending) return@synchronized
        fileCacheRequest += 1
        fileCacheClearRequest = fileCacheRequest
        mutableState.update { it.copy(fileCachePending = true, fileCacheClearing = true, fileCacheMessage = null, fileCacheMessageSource = null) }
        if (!send(JSONObject().put("type", "file_cache_clear").put("request_id", fileCacheRequest))) {
            fileCacheClearRequest = null
            val source = LocalizedMessage(R.string.files_cache_clear_failed)
            mutableState.update { it.copy(fileCachePending = false, fileCacheClearing = false,
                fileCacheMessage = source.render(context.languageContext()), fileCacheMessageSource = source) }
        }
    }
    fun fileCommand(type: String, fields: JSONObject = JSONObject()): Boolean {
        if (state.value.snapshot.status != "connected") { fileLocalError(LocalizedMessage(R.string.files_disconnected)); return false }
        fileLocalError(null)
        val files = state.value.channelFiles
        if (!fields.has("server")) fields.put("server", files.server)
        if (!fields.has("channel")) fields.put("channel", files.channel)
        if (!fields.has("directory")) fields.put("directory", files.path)
        val sent = send(fields.put("type", type))
        if (!sent) fileLocalError(LocalizedMessage(R.string.error_operation_submit))
        return sent
    }
    fun openChannelFiles() {
        val snapshot = state.value.snapshot
        val own = snapshot.clients.firstOrNull { it.id == snapshot.ownClient } ?: return
        if (snapshot.status != "connected") return
        val server = snapshot.serverId ?: return
        fileCommand("files_open", JSONObject().put("server", server).put("channel", own.channel))
    }
    fun downloadChannelFile(name: String, target: ChannelFileTarget) {
        fileCommand("files_download", target.command().put("name", name))
    }
    suspend fun uploadChannelFile(uri: android.net.Uri, target: ChannelFileTarget) {
        val importing = synchronized(fileCacheLock) {
            val ui = state.value
            if (ui.fileImporting || ui.fileCacheClearing || ui.fileCache.status == "clearing") false
            else { mutableState.update { it.copy(fileImporting = true) }; true }
        }
        if (!importing) { fileLocalError(LocalizedMessage(R.string.files_cache_busy)); return }
        try {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (!it.moveToFirst()) throw LocalizedFailure(LocalizedMessage(R.string.files_invalid_path), context)
                        it.getString(0)
                    } ?: throw LocalizedFailure(LocalizedMessage(R.string.files_invalid_path), context)
                    if (name.isEmpty() || name == "." || name == ".." || name.toByteArray().size > 255 || name.any { it == '/' || it == '\\' || it.isISOControl() }) {
                        throw LocalizedFailure(LocalizedMessage(R.string.files_invalid_path), context)
                    }
                    val root = File(context.filesDir, "core/file-uploads").apply {
                        if (!isDirectory && !mkdirs()) throw LocalizedFailure(LocalizedMessage(R.string.files_storage_failed), context)
                    }
                    val staged = File(root, UUID.randomUUID().toString())
                    try {
                        context.contentResolver.openInputStream(uri).use { input ->
                            if (input == null) throw LocalizedFailure(LocalizedMessage(R.string.files_choose_failed), context)
                            staged.outputStream().use { input.copyTo(it, 64 * 1024) }
                        }
                        if (!fileCommand("files_upload", target.command().put("name", name).put("source", staged.absolutePath))) staged.delete()
                    } catch (error: Exception) { staged.delete(); throw error }
                }
            }.onFailure { fileLocalError((it as? LocalizedFailure)?.source ?: localizedFailureSource(R.string.files_choose_failed, it)) }
        } finally { synchronized(fileCacheLock) { mutableState.update { it.copy(fileImporting = false) } } }
    }

    fun captureStopped() { if (initialized) send(JSONObject().put("type", "capture_stopped")) }

    fun captureSegment(): Long = synchronized(pushToTalkLock) { captureSegment }
    fun capture(samples: ShortArray, segment: Long = captureSegment()): Int = synchronized(pushToTalkLock) {
        // A read started before this press must never enter the core under its new capture epoch.
        if (segment != captureSegment) 0 else NativeCore.capture(handle, samples)
    }
    fun openOutput(channels: Int, communication: Boolean, notification: Runnable): Long =
        NativeOutput.open(handle, channels, communication, notification)
    fun setPlaybackActive(active: Boolean) { if (initialized) NativeOutput.setPlaybackActive(handle, active) }
    fun shouldRunService() = shouldKeepAudioOutput(state.value.snapshot.status, state.value.soundPending)

    // Intent/request bookkeeping is separate from the actual, instance-scoped lifecycle.
    private var serviceRequested = false
    private var voiceService: VoiceService? = null // Owned by coreExecutor; cleared only by this instance.
    private val serviceRevision = AtomicLong()

    fun voiceServiceCreated(service: VoiceService) = coreExecutor.execute {
        voiceService = service
        updateService()
    }

    fun voiceServiceStarted(service: VoiceService, disconnect: Boolean, connectionId: String?) = coreExecutor.execute {
        if (voiceService !== service) return@execute
        if (disconnect && connectionId == state.value.snapshot.connectionId) disconnectCore()
        else updateService()
    }

    fun voiceServiceDestroyed(service: VoiceService) = coreExecutor.execute {
        if (voiceService !== service) return@execute
        voiceService = null
        serviceRequested = false
        serviceRevision.incrementAndGet()
        // A stop accepted before a new Connect may finish destroying after it.
        updateService()
    }

    fun voiceServiceFailed(service: VoiceService, connectionId: String?, error: Throwable) = coreExecutor.execute {
        if (voiceService === service && connectionId == state.value.snapshot.connectionId) {
            serviceStartFailed(error)
        }
    }

    private fun serviceStartFailed(error: Throwable) {
        serviceRequested = false
        android.util.Log.e("MobileSpeakVoice", "Voice service start failed", error)
        fail(LocalizedMessage(R.string.error_background_microphone_enable))
        disconnectCore()
    }

    private fun updateService() {
        val running = shouldRunService()
        val instance = voiceService
        if (running == serviceRequested && (running || instance == null)) return
        serviceRequested = running
        val revision = serviceRevision.incrementAndGet()
        val requestId = state.value.snapshot.connectionId
        serviceHandler.post {
            if (revision != serviceRevision.get() || requestId != state.value.snapshot.connectionId || running != shouldRunService()) return@post
            if (running) {
                if (instance != null) {
                    instance.startCall()
                    return@post
                }
                val intent = Intent(context, VoiceService::class.java).setAction(VoiceService.ACTION_START_CALL)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                }.onFailure { error -> coreExecutor.execute {
                    if (revision == serviceRevision.get() && connectionGate.id == requestId) serviceStartFailed(error)
                } }
            } else instance?.stopSelf() // Never let an old stop target a newer service instance.
        }
    }

    private fun send(command: JSONObject): Boolean {
        val result = runCatching {
            NativeCore.command(handle, command.toString().toByteArray(Charsets.UTF_8))
        }.getOrElse {
            fail(localizedFailureSource(R.string.error_operation_submit, it))
            return false
        }
        if (result != 0) fail(LocalizedMessage(R.string.error_operation_submit))
        return result == 0
    }

    private fun updateAutomaticTitle(serverName: String?) {
        val snapshot = state.value.snapshot
        if (snapshot.status != "connected" || !connectionGate.accepts(snapshot.connectionId)) return
        val name = serverName?.trim().orEmpty()
        if (name.isEmpty()) return
        val connectedBookmark = activeBookmarkId ?: return
        runCatching { bookmarks.change { latest ->
            latest.map {
                if (it.id == connectedBookmark && it.automaticallyNamed && it.title != name) it.copy(title = name) else it
            } to Unit
        } }.onFailure { fail(localizedFailureSource(R.string.error_bookmark_title_update, it)) }
    }

    fun refreshLanguage(requestedLanguage: AppLanguage? = null) = coreExecutor.execute {
        if (!initialized) return@execute
        val language = requestedLanguage ?: AppLanguage.resolve(AppLanguage.selected(context), systemLanguageTags())
        VoiceService.refreshChannelName(context)
        if (displayedLanguage != language) {
            val localizedContext = context.contextForLanguage(language)
            mutableState.update { ui -> ui.copy(
                error = ui.errorSource?.render(localizedContext) ?: ui.error,
                nicknameSaveError = ui.nicknameSaveErrorSource?.render(localizedContext) ?: ui.nicknameSaveError,
                audioProcessingError = ui.audioProcessingErrorSource?.render(localizedContext) ?: ui.audioProcessingError,
                avatarDetail = ui.avatarDetailSource?.render(localizedContext) ?: ui.avatarDetail,
                avatarCleanupDetail = ui.avatarCleanupDetailSource?.render(localizedContext) ?: ui.avatarCleanupDetail,
                channelFilesLocalError = ui.channelFilesLocalErrorSource?.render(localizedContext) ?: ui.channelFilesLocalError,
                fileCacheMessage = ui.fileCacheMessageSource?.render(localizedContext) ?: ui.fileCacheMessage,
            ) }
            displayedLanguage = language
        }
        updateService()
        val instance = voiceService ?: return@execute
        val requestId = state.value.snapshot.connectionId
        serviceHandler.post {
            if (requestId == state.value.snapshot.connectionId && shouldRunService()) instance.refreshLanguage()
        }
    }

    private fun fail(source: LocalizedMessage) {
        mutableState.update { it.copy(error = source.render(context.languageContext()), errorSource = source) }
    }
}

private fun JSONObject?.toIntMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { getInt(it) }
}

// A connected snapshot can precede our initial member record. Applying mute then
// lets that record restore the old input-muted value inside tsclientlib.
internal fun Snapshot.canApplyAudioState() =
    status == "connected" && ownClient != null && clients.any { it.id == ownClient }

// Migrate only audio preferences; identity and bookmarks use their existing store.
internal fun restoreNoiseSuppression(preferences: android.content.SharedPreferences): String {
    val mode = preferences.getString("noise", "rnnoise")
        .takeIf { it in setOf("dpdfnet2", "rnnoise", "none") } ?: "rnnoise"
    preferences.edit { putString("noise", mode); remove("vad") }
    return mode
}
