package dev.mobilespeak.mobilespeak

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class Channel(
    val id: Long,
    val parent: Long,
    val order: Long,
    val name: String,
    val password: Boolean,
    val permanent: Boolean,
    val key: String,
    val iconPath: String?,
) {
    val conversation get() = "channel:$key"
}

data class Badge(val id: String, val name: String, val description: String, val iconPath: String?)
data class GroupIcon(val id: Long, val name: String, val iconId: Long, val iconPath: String?)
data class Member(
    val id: Long,
    val channel: Long,
    val uid: String?,
    val name: String,
    val avatarPath: String?,
    val badges: List<Badge>,
    val serverGroupIcons: List<GroupIcon>,
    val channelGroupIcon: GroupIcon?,
    val muted: Boolean,
    val deafened: Boolean,
    val speaking: Boolean,
    val volumePercent: Int = 100,
    val volumeToken: String? = null,
    val away: Boolean = false,
) {
    val conversation get() = uid?.let { "client:$it" }
    val statusIcon get() = if (away) "afk" else if (deafened) "speaker-off" else if (muted) "mic-off" else null
}

data class Snapshot(
    val status: String = "disconnected",
    val server: String? = null,
    val serverId: String? = null,
    val ownClient: Long? = null,
    val canSend: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val clients: List<Member> = emptyList(),
    val networkQuality: NetworkQuality? = null,
    val connectionId: String? = null,
) {
    fun onlineMember(uid: String?): Member? =
        if (status == "connected" && !uid.isNullOrEmpty()) clients.firstOrNull { it.uid == uid } else null
}

enum class NetworkGrade { GOOD, FAIR, POOR }
data class NetworkSample(
    val second: Long, val rttMs: Double, val grade: NetworkGrade,
    val packetLossPercent: Double? = null, val packetLossGrade: NetworkGrade? = null,
) {
    val packetLossFraction: Double? get() = packetLossPercent
        ?.takeIf { it.isFinite() && it >= 0 && packetLossGrade != null }?.let { minOf(it, 100.0) / 100 }
}
data class NetworkQuality(
    val rttMs: Double? = null,
    val deviationMs: Double? = null,
    val packetLossPercent: Double? = null,
    val rttGrade: NetworkGrade? = null,
    val deviationGrade: NetworkGrade? = null,
    val packetLossGrade: NetworkGrade? = null,
    val iconGrade: NetworkGrade? = null,
    val axisMaxMs: Double = 10.0,
    val nowSecond: Long = 0,
    val samples: List<NetworkSample> = emptyList(),
) {
    val latencyText get() = number(rttMs, 0, 999.0)
    val deviationText get() = number(deviationMs, 1, 999.9)
    val lossText get() = number(packetLossPercent, 1)
    val axisText get() = axisNumber(axisMaxMs)
    val midAxisText get() = axisNumber(axisMaxMs / 2)
    companion object {
        fun axisNumber(value: Double, locale: Locale = Locale.getDefault()): String = number(value, if (value % 1.0 == 0.0) 0 else 1, locale = locale)
        fun number(value: Double?, decimals: Int, maximum: Double = Double.POSITIVE_INFINITY, locale: Locale = Locale.getDefault()): String =
            if (value == null || !value.isFinite()) "—"
            else {
                val scale = if (decimals == 0) 1.0 else 10.0
                String.format(locale, "%.${decimals}f", kotlin.math.floor(minOf(value, maximum) * scale + 0.5) / scale)
            }
    }
}

internal fun JSONObject.networkQuality() = NetworkQuality(
    rttMs = if (isNull("rttMs")) null else getDouble("rttMs"),
    deviationMs = if (isNull("deviationMs")) null else getDouble("deviationMs"),
    packetLossPercent = if (isNull("packetLossPercent")) null else getDouble("packetLossPercent"),
    rttGrade = networkGrade("rttGrade"), deviationGrade = networkGrade("deviationGrade"),
    packetLossGrade = networkGrade("packetLossGrade"), iconGrade = networkGrade("iconGrade"),
    axisMaxMs = getDouble("axisMaxMs"), nowSecond = getLong("nowSecond"),
    samples = getJSONArray("samples").objects().map {
        NetworkSample(it.getLong("second"), it.getDouble("rttMs"), requireNotNull(it.networkGrade("grade")),
            if (it.isNull("packetLossPercent")) null else it.getDouble("packetLossPercent"), it.networkGrade("packetLossGrade"))
    },
)
private fun JSONObject.networkGrade(key: String): NetworkGrade? =
    stringOrNull(key)?.let { NetworkGrade.valueOf(it.uppercase(Locale.ROOT)) }

data class ChatMessage(
    val id: String,
    val conversation: String,
    val senderName: String,
    val avatarPath: String?,
    val own: Boolean,
    val text: String,
    val timestamp: Long,
    val status: String,
    val error: String?,
    val senderUid: String = "",
)

data class Unread(
    val serverId: String? = null,
    val channel: String? = null,
    val channelCount: Int = 0,
    val privateCounts: Map<String, Int> = emptyMap(),
)

internal fun JSONObject.chatMessage() = ChatMessage(
    getString("id"), getString("conversation"), getString("senderName"), stringOrNull("avatarPath"),
    getBoolean("own"), getString("text"), getLong("timestamp"), getString("status"), stringOrNull("error"), getString("senderUid"),
)

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val host: String,
    val port: Int,
    val password: String,
    val automaticallyNamed: Boolean,
) {
    val address get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    fun json() = JSONObject()
        .put("id", id).put("title", title).put("host", host).put("port", port)
        .put("password", password)
        .put("automaticallyNamed", automaticallyNamed)

    companion object {
        fun normalizedHost(host: String) = host.trim().trim('[', ']').lowercase(Locale.ROOT)
        fun create(id: String?, title: String, host: String, port: String, password: String): Bookmark {
            val cleanHost = normalizedHost(host)
            val parsedPort = port.toIntOrNull()
            require(cleanHost.isNotEmpty() && cleanHost.toByteArray().size <= 1000 &&
                cleanHost.none(Char::isWhitespace) && '/' !in cleanHost &&
                parsedPort != null && parsedPort in 1..65535 &&
                password.toByteArray().size <= 1024) { "invalid_bookmark" }
            val cleanTitle = title.trim()
            return Bookmark(
                id ?: UUID.randomUUID().toString(),
                cleanTitle.ifEmpty { cleanHost },
                cleanHost,
                parsedPort,
                password,
                cleanTitle.isEmpty(),
            )
        }

        fun from(json: JSONObject) = Bookmark(
            json.getString("id"), json.getString("title"), json.getString("host"),
            json.getInt("port"), json.optString("password"),
            json.optBoolean("automaticallyNamed"),
        )
    }
}

data class ProfileState(
    val nickname: String = "MobileSpeakUser",
    val requestId: Long = 0,
    val error: String? = null,
    val detail: String? = null,
)

internal fun JSONObject.profile() = ProfileState(getString("nickname"), getLong("requestId"), stringOrNull("error"), stringOrNull("detail"))

data class SpeechState(
    val mode: String = "automatic",
    val token: String = "",
    val available: Boolean = false,
    val transmitting: Boolean = false,
)

internal fun JSONObject.speech() = SpeechState(
    mode = optString("mode", "automatic").takeIf { it == "push_to_talk" } ?: "automatic",
    token = optString("token", ""),
    available = optBoolean("available", false),
    transmitting = optBoolean("transmitting", false),
)

data class SessionUiState(
    val snapshot: Snapshot = Snapshot(),
    val soundPending: Boolean = false,
    val channelFiles: ChannelFilesState = ChannelFilesState(),
    val channelFilesLocalError: String? = null,
    val channelFilesLocalErrorSource: LocalizedMessage? = null,
    val fileCache: FileCacheState = FileCacheState(),
    val fileImporting: Boolean = false,
    val fileCacheClearing: Boolean = false,
    val fileCachePending: Boolean = false,
    val fileCacheMessage: String? = null,
    val fileCacheMessageSource: LocalizedMessage? = null,
    val messages: List<ChatMessage> = emptyList(),
    val unread: Unread = Unread(),
    val bookmarks: List<Bookmark> = emptyList(),
    val error: String? = null,
    val errorSource: LocalizedMessage? = null,
    val profile: ProfileState = ProfileState(),
    val nicknameSaving: Boolean = false,
    val nicknameSaveError: String? = null,
    val nicknameSaveErrorSource: LocalizedMessage? = null,
    val microphoneMuted: Boolean = true,
    val deafened: Boolean = false,
    val receiveAgc: Boolean = true,
    val speech: SpeechState = SpeechState(),
    val microphonePermission: Boolean = false,
    val userVolumesOpen: Boolean = false,
    val noiseSuppression: String = "rnnoise",
    val appliedNoise: String = "rnnoise",
    val audioProcessingStatus: String = "ready",
    val audioProcessingError: String? = null,
    val audioProcessingErrorSource: LocalizedMessage? = null,
    val avatarPreviewPath: String? = null,
    val avatarRevision: Long = 0,
    val avatarIntent: String = "unset",
    val avatarClearingLocally: Boolean = false,
    val avatarCleanupDetail: String? = null,
    val avatarCleanupDetailSource: LocalizedMessage? = null,
    val avatarStatus: String = "idle",
    val avatarDetail: String? = null,
    val avatarDetailSource: LocalizedMessage? = null,
)

internal fun orderedChannels(channels: List<Channel>): List<Pair<Channel, Int>> {
    val result = mutableListOf<Pair<Channel, Int>>()
    val visited = mutableSetOf<Long>()
    fun visit(parent: Long, depth: Int) {
        val remaining = channels.filter { it.parent == parent }.sortedBy { it.id }.toMutableList()
        var previous = 0L
        while (remaining.isNotEmpty()) {
            val index = remaining.indexOfFirst { it.order == previous }.let { if (it < 0) 0 else it }
            val channel = remaining.removeAt(index)
            previous = channel.id
            if (visited.add(channel.id)) {
                result += channel to depth
                visit(channel.id, depth + 1)
            }
        }
    }
    visit(0, 0)
    channels.filterNot { it.id in visited }.forEach {
        if (visited.add(it.id)) {
            result += it to 0
            visit(it.id, 1)
        }
    }
    return result
}

internal fun JSONObject.snapshot() = Snapshot(
    status = getString("status"),
    connectionId = stringOrNull("connectionId"),
    server = stringOrNull("server"),
    serverId = stringOrNull("serverId"),
    ownClient = if (has("ownClient") && !isNull("ownClient")) getLong("ownClient") else null,
    canSend = optBoolean("canSend"),
    networkQuality = optJSONObject("networkQuality")?.networkQuality(),
    channels = getJSONArray("channels").objects().map {
        Channel(it.getLong("id"), it.getLong("parent"), it.getLong("order"), it.getString("name"),
            it.getBoolean("password"), it.getBoolean("permanent"), it.getString("key"), it.stringOrNull("iconPath"))
    },
    clients = getJSONArray("clients").objects().map { client ->
        Member(
            client.getLong("id"), client.getLong("channel"), client.stringOrNull("uid"),
            client.getString("name"), client.stringOrNull("avatarPath"),
            client.getJSONArray("badges").objects().map {
                Badge(it.getString("id"), it.getString("name"), it.getString("description"), it.stringOrNull("iconPath"))
            },
            client.getJSONArray("serverGroupIcons").objects().map(JSONObject::groupIcon),
            client.optJSONObject("channelGroupIcon")?.groupIcon(),
            client.getBoolean("muted"), client.getBoolean("deafened"), client.getBoolean("speaking"),
            client.optInt("volumePercent", 100), client.stringOrNull("volumeToken"),
            client.optBoolean("away"),
        )
    },
)

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONObject.groupIcon() =
    GroupIcon(getLong("id"), getString("name"), getLong("iconId"), stringOrNull("iconPath"))

internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
