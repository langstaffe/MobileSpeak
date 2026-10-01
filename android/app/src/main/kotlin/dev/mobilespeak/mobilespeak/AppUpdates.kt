package dev.mobilespeak.mobilespeak

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Calendar
import javax.net.ssl.HttpsURLConnection

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(text: String): AppVersion? {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(text) ?: return null
            val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return AppVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String?, val uploaded: Boolean)
internal data class AppRelease(val tag: String, val version: AppVersion, val assets: List<ReleaseAsset>)
internal object UpdatePolicy {
    const val repository = "langstaffe/MobileSpeak"
    fun asset(release: AppRelease, abis: List<String>): ReleaseAsset? {
        val names = listOf("MobileSpeak-${release.tag}-universal.apk") + abis.map { "MobileSpeak-${release.tag}-$it.apk" }
        return names.firstNotNullOfOrNull { name ->
            release.assets.singleOrNull { it.name == name && validAsset(it, release.tag) }
        }
    }
    fun validAsset(asset: ReleaseAsset, tag: String): Boolean {
        if (!asset.uploaded || asset.size !in 1..512L * 1024 * 1024 || AppVersion.parse(tag) == null || !tag.startsWith("v")) return false
        if (asset.digest != null && !Regex("sha256:[0-9a-fA-F]{64}").matches(asset.digest)) return false
        val uri = runCatching { URI(asset.url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.query == null &&
            uri.path == "/$repository/releases/download/$tag/${asset.name}" && Regex("MobileSpeak-v[0-9]+\\.[0-9]+\\.[0-9]+-(universal|arm64-v8a|armeabi-v7a|x86_64|x86)\\.apk").matches(asset.name)
    }
    fun signaturesCompatible(installed: Set<String>, incoming: Set<String>, history: Set<String>): Boolean =
        installed.isNotEmpty() && incoming.isNotEmpty() && if (installed.size > 1 || incoming.size > 1) installed == incoming else installed.single() in history
    fun automaticAllowed(today: String, attemptedDay: String?, checking: Boolean) = !checking && today != attemptedDay
    fun obsoleteDownload(tag: String, installedVersion: String) = AppVersion.parse(tag)?.let { target ->
        AppVersion.parse(installedVersion)?.let { target <= it }
    } == true
    fun downloadAllowed(status: DownloadStatus, currentVersion: String = "", targetVersion: String = "") =
        status in listOf(DownloadStatus.NONE, DownloadStatus.FAILED) || (status == DownloadStatus.READY && targetVersion.isNotEmpty() && targetVersion != currentVersion)
    fun classify(release: AppRelease?, installed: AppVersion, abis: List<String>): CheckResult = when {
        release == null -> CheckResult(message = R.string.update_no_release)
        release.version <= installed -> CheckResult(message = R.string.update_current)
        asset(release, abis) == null -> CheckResult(message = R.string.update_no_apk, version = release.version.toString())
        else -> CheckResult(release, asset(release, abis), R.string.update_available, release.version.toString())
    }
    fun downloadStatus(status: Int) = when (status) {
        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> DownloadStatus.DOWNLOADING
        DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.VERIFYING
        else -> DownloadStatus.FAILED
    }
}

internal data class CheckResult(val release: AppRelease? = null, val asset: ReleaseAsset? = null, val message: Int, val version: String = "", val detail: String = "")
internal enum class DownloadStatus { NONE, DOWNLOADING, VERIFYING, READY, FAILED }
internal data class UpdateState(
    val checking: Boolean = false, val result: CheckResult? = null, val prompt: CheckResult? = null, val manual: Boolean = false,
    val installing: Boolean = false, val download: DownloadStatus = DownloadStatus.NONE, val downloadVersion: String = "", val downloadError: Int = R.string.update_download_failed, val detail: String = "",
)
private data class DownloadRecord(val id: Long, val tag: String, val asset: ReleaseAsset)
internal class UpdateFailure(val messageId: Int, val diagnostic: String = "") : Exception(diagnostic)

// One app-owned request/download. No voice/session state and no background scheduling.
@SuppressLint("StaticFieldLeak", "UseKtx") // Only app context is retained; commit() results must be checked for persistence failures.
internal object AppUpdates {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val preferences get() = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val downloads get() = context.getSystemService(DownloadManager::class.java)
    private var record: DownloadRecord? = null
    private var refreshing = false
    private var refreshRequested = false
    private var foreground = false
    private var manualRequest = false
    var suitable = false
    private var initialized = false

    fun initialize(applicationContext: Context) {
        if (initialized) return
        context = applicationContext.applicationContext
        initialized = true
        val saved = preferences.getString("download", null)
        if (saved != null) {
            record = runCatching {
                val json = JSONObject(saved)
                val asset = ReleaseAsset(json.getString("name"), json.getString("url"), json.getLong("size"), json.optString("digest").takeIf { it.isNotEmpty() }, true)
                val tag = json.getString("tag")
                require(UpdatePolicy.validAsset(asset, tag))
                DownloadRecord(json.getLong("id").also { require(it > 0) }, tag, asset)
            }.getOrNull()
            mutable.value = mutable.value.copy(download = if (record == null) DownloadStatus.FAILED else DownloadStatus.DOWNLOADING,
                downloadVersion = record?.tag?.removePrefix("v").orEmpty(), downloadError = R.string.update_task_missing)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE && intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == record?.id) refreshDownload()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    fun installedVersion(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    fun onForeground(query: () -> AppRelease? = ::fetchRelease) {
        foreground = true
        refreshDownload()
        check(manual = false, query = query)
    }
    fun onBackground() {
        foreground = false
        mutable.value = mutable.value.copy(prompt = null)
    }
    private fun today(): String {
        val date = Calendar.getInstance()
        return "${date.get(Calendar.YEAR)}-${date.get(Calendar.MONTH) + 1}-${date.get(Calendar.DAY_OF_MONTH)}"
    }
    fun check(manual: Boolean, query: () -> AppRelease? = ::fetchRelease) {
        if (mutable.value.checking) {
            if (manual) manualRequest = true // A manual tap joins an in-flight automatic request.
            return
        }
        val day = today()
        if (!manual) {
            if (!UpdatePolicy.automaticAllowed(day, preferences.getString("attempted_day", null), false)) return
            // Commit before the request; a failed check is still today's attempt.
            if (!preferences.edit().putString("attempted_day", day).commit()) return
        }
        manualRequest = manual
        mutable.value = mutable.value.copy(checking = true, result = null, prompt = null)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val installed = AppVersion.parse(installedVersion()) ?: throw UpdateFailure(R.string.update_invalid_version)
                    UpdatePolicy.classify(query(), installed, Build.SUPPORTED_ABIS.toList())
                } catch (error: UpdateFailure) {
                    CheckResult(message = error.messageId, detail = error.diagnostic)
                } catch (_: Exception) { CheckResult(message = R.string.update_check_failed) }
            }
            val wasManual = manualRequest
            val canShow = foreground && (wasManual || (day == today() && (suitable && preferences.getString("prompted_day", null) != day)))
            val prompt = result.takeIf { it.asset != null && canShow }
            if (prompt != null && !wasManual && !preferences.edit().putString("prompted_day", day).commit()) {
                mutable.value = mutable.value.copy(checking = false, result = null)
            } else mutable.value = mutable.value.copy(checking = false, result = result.takeIf { wasManual }, prompt = prompt, manual = wasManual)
        }
    }

    internal fun parseRelease(json: JSONObject): AppRelease {
        if (json.getBoolean("draft") || json.getBoolean("prerelease")) throw UpdateFailure(R.string.update_invalid_version)
        val tag = json.getString("tag_name")
        val version = AppVersion.parse(tag)?.takeIf { tag.startsWith("v") } ?: throw UpdateFailure(R.string.update_invalid_version)
        val assets = json.getJSONArray("assets")
        return AppRelease(tag, version, (0 until assets.length()).map { index ->
            val item = assets.getJSONObject(index)
            ReleaseAsset(item.getString("name"), item.getString("browser_download_url"), item.getLong("size"), item.optString("digest").takeIf { it.isNotEmpty() && it != "null" }, item.getString("state") == "uploaded")
        })
    }
    private fun fetchRelease(): AppRelease? {
        val connection = URL("https://api.github.com/repos/${UpdatePolicy.repository}/releases/latest").openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "MobileSpeak/${installedVersion()}")
        try {
            val code = connection.responseCode
            if (code == 404) return null
            if (code == 429 || (code == 403 && (connection.getHeaderField("X-RateLimit-Remaining") == "0" || connection.getHeaderField("Retry-After") != null))) throw UpdateFailure(R.string.update_rate_limited, "HTTP $code")
            if (code != 200) throw UpdateFailure(R.string.update_check_failed, "HTTP $code")
            val bytes = connection.inputStream.use { it.readBytesLimited(2 * 1024 * 1024) }
            return parseRelease(JSONObject(bytes.toString(Charsets.UTF_8)))
        } finally { connection.disconnect() }
    }

    fun dismissPrompt() { mutable.value = mutable.value.copy(prompt = null) }
    fun download(result: CheckResult) {
        val release = result.release ?: return
        val asset = result.asset ?: return
        if (mutable.value.installing || !UpdatePolicy.downloadAllowed(mutable.value.download, mutable.value.downloadVersion, release.version.toString()) || !UpdatePolicy.validAsset(asset, release.tag)) return
        val previous = record
        record = null
        dismissPrompt()
        mutable.value = mutable.value.copy(download = DownloadStatus.DOWNLOADING, downloadVersion = release.version.toString(), detail = "")
        scope.launch {
            try {
                record = withContext(Dispatchers.IO) {
                    previous?.let(::removeDownloadFiles)
                    val file = downloadFile(asset.name)
                    if (file.exists() && !file.delete()) throw UpdateFailure(R.string.update_storage_failed)
                    if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw UpdateFailure(R.string.update_storage_failed)
                    val request = DownloadManager.Request(asset.url.toUri())
                        .setTitle(context.localized(R.string.update_download_title, release.version.toString()))
                        .setDescription(context.localized(R.string.update_download_notice))
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationUri(Uri.fromFile(file))
                    val id = downloads.enqueue(request)
                    val value = DownloadRecord(id, release.tag, asset)
                    if (!preferences.edit().remove("notified_download").putString("download", JSONObject().put("id", id).put("tag", release.tag).put("name", asset.name).put("url", asset.url).put("size", asset.size).put("digest", asset.digest ?: "").toString()).commit()) {
                        downloads.remove(id)
                        throw UpdateFailure(R.string.update_storage_failed)
                    }
                    value
                }
                refreshDownload() // Handles an unusually fast completion before receiver registration of the ID.
            } catch (error: Exception) {
                if (record == null) record = previous
                mutable.value = mutable.value.copy(download = DownloadStatus.FAILED, downloadError = (error as? UpdateFailure)?.messageId ?: R.string.update_download_failed)
            }
        }
    }
    private fun downloadFile(name: String): File {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw UpdateFailure(R.string.update_storage_failed)
        return File(File(directory, "updates"), name)
    }
    private fun removeDownloadFiles(value: DownloadRecord) {
        downloads.remove(value.id)
        for (file in listOf(downloadFile(value.asset.name), File(context.cacheDir, "updates/${value.asset.name}"))) {
            if (file.exists() && !file.delete()) throw UpdateFailure(R.string.update_storage_failed)
        }
        context.getSystemService(NotificationManager::class.java).cancel(300)
    }
    fun refreshDownload() {
        val value = record ?: return
        if (refreshing) { refreshRequested = true; return }
        val obsolete = UpdatePolicy.obsoleteDownload(value.tag, installedVersion())
        refreshing = true
        refreshRequested = false
        // Block replacement/installation while removing a completed upgrade.
        if (obsolete) mutable.value = mutable.value.copy(download = DownloadStatus.VERIFYING, detail = "")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (obsolete) {
                        removeDownloadFiles(value)
                        if (!preferences.edit().remove("download").remove("notified_download").commit()) throw UpdateFailure(R.string.update_storage_failed)
                        DownloadStatus.NONE
                    } else downloads.query(DownloadManager.Query().setFilterById(value.id)).use { cursor ->
                        if (!cursor.moveToFirst()) throw UpdateFailure(R.string.update_task_missing)
                        val status = UpdatePolicy.downloadStatus(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
                        if (status == DownloadStatus.FAILED) {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            throw UpdateFailure(if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) R.string.update_no_space else R.string.update_download_failed, "DownloadManager $reason")
                        }
                        if (status == DownloadStatus.VERIFYING) { verify(value); DownloadStatus.READY } else status
                    }
                } catch (error: Exception) {
                    error
                }
            }
            if (record?.id == value.id) {
                mutable.value = when (result) {
                    DownloadStatus.NONE -> {
                        record = null
                        mutable.value.copy(download = DownloadStatus.NONE, downloadVersion = "", downloadError = R.string.update_download_failed, detail = "", installing = false)
                    }
                    is DownloadStatus -> mutable.value.copy(download = result, downloadVersion = value.tag.removePrefix("v"), detail = "")
                    else -> mutable.value.copy(download = DownloadStatus.FAILED, downloadError = (result as? UpdateFailure)?.messageId ?: R.string.update_download_failed, detail = (result as? UpdateFailure)?.diagnostic.orEmpty())
                }
            }
            if (result == DownloadStatus.READY && record?.id == value.id) notifyReady(value)
            refreshing = false
            // A completion broadcast during the query must not be lost. Only replay an actual request.
            if (record != null && (refreshRequested || record?.id != value.id)) refreshDownload()
        }
    }

    private fun verify(value: DownloadRecord, file: File = downloadFile(value.asset.name)) = verifyDownloaded(file, value.tag, value.asset)

    // Older Android archive parsers require GET_SIGNATURES to collect/verify certificates.
    @Suppress("DEPRECATION")
    private val packageFlags get() = PackageManager.GET_SIGNATURES or (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)
    internal fun archiveInfo(file: File) = context.packageManager.getPackageArchiveInfo(file.path, packageFlags)
    internal fun verifyDownloaded(file: File, tag: String, asset: ReleaseAsset) {
        if (!file.isFile) throw UpdateFailure(R.string.update_file_missing)
        if (file.length() != asset.size) throw UpdateFailure(R.string.update_corrupt_apk)
        asset.digest?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream -> val buffer = ByteArray(64 * 1024); while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            if (!digest.digest().joinToString("") { "%02x".format(it) }.equals(expected.removePrefix("sha256:"), ignoreCase = true)) throw UpdateFailure(R.string.update_corrupt_apk)
        }
        val abis = runCatching {
            java.util.zip.ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet() }
        }.getOrElse { throw UpdateFailure(R.string.update_corrupt_apk) }
        if (abis.isNotEmpty() && abis.intersect(Build.SUPPORTED_ABIS.toSet()).isEmpty()) throw UpdateFailure(R.string.update_wrong_abi)
        val manager = context.packageManager
        val archive = archiveInfo(file) ?: throw UpdateFailure(R.string.update_corrupt_apk)
        val installed = manager.getPackageInfo(context.packageName, packageFlags)
        val code = PackageInfoCompat.getLongVersionCode(archive)
        val currentCode = PackageInfoCompat.getLongVersionCode(installed)
        if (archive.packageName != context.packageName) throw UpdateFailure(R.string.update_wrong_package)
        if (archive.versionName != tag.removePrefix("v") || code <= currentCode || AppVersion.parse(archive.versionName.orEmpty())?.let { it > (AppVersion.parse(installed.versionName.orEmpty()) ?: return@let false) } != true) throw UpdateFailure(R.string.update_wrong_version)
        val app = archive.applicationInfo ?: throw UpdateFailure(R.string.update_corrupt_apk)
        if (app.minSdkVersion > Build.VERSION.SDK_INT) throw UpdateFailure(R.string.update_wrong_android)
        if (app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) throw UpdateFailure(R.string.update_debug_apk)
        @Suppress("DEPRECATION")
        val compatible = if (Build.VERSION.SDK_INT >= 28) {
            val old = installed.signingInfo ?: throw UpdateFailure(R.string.update_wrong_signature)
            val new = archive.signingInfo ?: throw UpdateFailure(R.string.update_wrong_signature)
            UpdatePolicy.signaturesCompatible(old.apkContentsSigners.map { it.toCharsString() }.toSet(), new.apkContentsSigners.map { it.toCharsString() }.toSet(),
                (new.signingCertificateHistory ?: new.apkContentsSigners).map { it.toCharsString() }.toSet())
        } else UpdatePolicy.signaturesCompatible(installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty(),
            archive.signatures?.map { it.toCharsString() }?.toSet().orEmpty(), archive.signatures?.map { it.toCharsString() }?.toSet().orEmpty())
        if (!compatible) throw UpdateFailure(R.string.update_wrong_signature)
    }

    private fun notifyReady(value: DownloadRecord) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (preferences.getLong("notified_download", -1) == value.id || !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("updates", context.localized(R.string.update_notifications), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("show_about", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 300, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!preferences.edit().putLong("notified_download", value.id).commit()) return
        manager.notify(300, NotificationCompat.Builder(context, "updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.localized(R.string.update_downloaded, value.tag.removePrefix("v")))
            .setContentText(context.localized(R.string.update_download_notice)).setContentIntent(pending).setAutoCancel(true).build())
    }

    // Called only by the foreground Install confirmation. Authorization never starts an installation on resume.
    fun install(activity: Context) {
        val value = record ?: return
        if (!foreground || mutable.value.installing || mutable.value.download != DownloadStatus.READY) return
        mutable.value = mutable.value.copy(installing = true)
        scope.launch {
            try {
                val installerCopy = withContext(Dispatchers.IO) {
                    // Use a private snapshot for the installer, preventing external-storage modification after verification.
                    val directory = File(context.cacheDir, "updates")
                    if (!directory.isDirectory && !directory.mkdirs()) throw UpdateFailure(R.string.update_storage_failed)
                    val temporary = File.createTempFile("pending-", ".apk", directory)
                    try {
                        downloadFile(value.asset.name).inputStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var size = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                if (size > value.asset.size) throw UpdateFailure(R.string.update_corrupt_apk)
                                output.write(buffer, 0, count)
                            }
                        } }
                        verify(value, temporary)
                        val copy = File(directory, value.asset.name)
                        if (!temporary.renameTo(copy)) throw UpdateFailure(R.string.update_storage_failed)
                        copy
                    } finally { temporary.delete() }
                }
                if (!foreground || record?.id != value.id || (activity is android.app.Activity && (activity.isDestroyed || activity.isFinishing))) return@launch
                if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", installerCopy)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            } catch (error: Exception) {
                if (record?.id == value.id) mutable.value = mutable.value.copy(download = DownloadStatus.FAILED, downloadError = (error as? UpdateFailure)?.messageId ?: R.string.update_install_failed)
            } finally { if (record?.id == value.id) mutable.value = mutable.value.copy(installing = false) }
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        val buffer = ByteArray(8192)
        while (true) { if (android.os.SystemClock.elapsedRealtime() > deadline) throw UpdateFailure(R.string.update_check_failed); val count = read(buffer); if (count < 0) break; if (output.size() + count > limit) throw UpdateFailure(R.string.update_check_failed); output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}
