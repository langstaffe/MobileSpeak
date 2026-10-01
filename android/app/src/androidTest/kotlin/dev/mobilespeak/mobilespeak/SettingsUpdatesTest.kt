package dev.mobilespeak.mobilespeak

import android.app.KeyguardManager
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SettingsUpdatesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun unlocked() = assumeTrue("Unlock the device for UI acceptance", !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)

    @Test fun languageNavigationSurvivesRecreationAndReturnsToSettingsScrollWithoutChangingCore() {
        unlocked()
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                instrumentation.waitForIdleSync()
                val core = ClientSession.javaClass.getDeclaredField("handle\$delegate").apply { isAccessible = true }.get(ClientSession)
                val before = ClientSession.state.value
                click(context.localized(R.string.tab_settings))
                scrollTo(context.localized(R.string.settings_language))
                val oldBounds = Rect().also(node(context.localized(R.string.settings_language))!!::getBoundsInScreen)
                click(context.localized(R.string.settings_language))
                click("English")
                waitFor { AppLanguage.selected() == AppLanguage.ENGLISH && node("System Default") != null }
                assertEquals("Language", node("Language", clickable = false)?.text?.toString())
                val selected = requireNotNull(node("English")) { describeTree() }
                assertTrue(selected.isSelected || selected.isChecked)
                click("English") // Same selection is a no-op, staying on page.
                assertFalse(AppLanguage.select(AppLanguage.ENGLISH))
                click(context.localized(R.string.language_simplified_chinese))
                waitFor { AppLanguage.selected() == AppLanguage.SIMPLIFIED_CHINESE && node(context.localized(R.string.language_system)) != null }
                scenario.recreate()
                waitFor { node(context.localized(R.string.language_system)) != null }
                assertTrue(node(context.localized(R.string.language_simplified_chinese))!!.let { it.isSelected || it.isChecked })
                click(context.localized(R.string.action_back))
                waitFor { node(context.localized(R.string.settings_language)) != null }
                assertNotNull(node(context.localized(R.string.settings_language)))
                val returned = Rect().also(node(context.localized(R.string.settings_language))!!::getBoundsInScreen)
                assertTrue("Settings keeps its previous scroll offset", kotlin.math.abs(oldBounds.top - returned.top) < 80)
                scrollTo(context.localized(R.string.settings_about))
                click(context.localized(R.string.settings_about))
                waitFor { node("v" + AppUpdates.installedVersion(), clickable = false) != null }
                assertNotNull(describeTree(), node("https://github.com/langstaffe/MobileSpeak"))
                waitFor { !AppUpdates.state.value.checking && node(context.localized(R.string.update_check)) != null }
                assertNotNull(node(context.localized(R.string.update_check)))
                click(context.localized(R.string.update_check))
                waitFor { !AppUpdates.state.value.checking && AppUpdates.state.value.result != null }
                val realCheck = requireNotNull(AppUpdates.state.value.result)
                assertNotNull(node(context.localized(realCheck.message, realCheck.version), clickable = false))
                java.io.File(context.cacheDir, "real-update-check.txt").writeText(context.localized(realCheck.message, realCheck.version) + " " + realCheck.detail)
                click("https://github.com/langstaffe/MobileSpeak")
                waitFor { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                waitFor { node("v" + AppUpdates.installedVersion(), clickable = false) != null }
                click(context.localized(R.string.action_back))
                assertNotNull(node(context.localized(R.string.settings_about)))
                assertSame(core, ClientSession.javaClass.getDeclaredField("handle\$delegate").apply { isAccessible = true }.get(ClientSession))
                assertEquals(before.microphoneMuted, ClientSession.state.value.microphoneMuted)
                assertEquals(before.deafened, ClientSession.state.value.deafened)
                assertEquals(before.snapshot.status, ClientSession.state.value.snapshot.status)
            } finally { instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(original) } }
        }
    }

    @Test fun largeTextPagesRetainAccessibleControlsInBothLanguages() {
        unlocked()
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                for (language in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
                    instrumentation.runOnMainSync { AppLanguage.select(language) }
                    instrumentation.waitForIdleSync()
                    for (page in listOf("language", "about")) {
                        scenario.onActivity { activity ->
                            activity.setContent {
                                MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(onSurface = Palette.text, onBackground = Palette.text)) {
                                    androidx.compose.material3.Surface(color = Palette.background, contentColor = Palette.text) {
                                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) { SettingsDetailPage(page, {}) }
                                    }
                                }
                            }
                        }
                        instrumentation.waitForIdleSync()
                        waitFor { node(context.localized(if (page == "language") R.string.settings_language else R.string.settings_about), clickable = false) != null }
                        waitFor { node(context.localized(R.string.action_back)) != null }
                        assertTrue(node(context.localized(R.string.action_back))!!.isFocusable)
                        if (page == "language") {
                            assertNotNull(node(context.localized(R.string.language_system)))
                            assertNotNull(describeTree(), node("English"))
                        } else {
                            assertNotNull(describeTree(), node("https://github.com/langstaffe/MobileSpeak"))
                            scrollTo("v" + AppUpdates.installedVersion(), clickable = false)
                            assertNotNull(node("v" + AppUpdates.installedVersion(), clickable = false))
                            scrollTo(context.localized(R.string.update_check))
                            assertTrue(node(context.localized(R.string.update_check))!!.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                        }
                        val shot = instrumentation.uiAutomation.takeScreenshot()
                        java.io.File(context.cacheDir, "secondary-$page-${language.name}.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        shot.recycle()
                    }
                }
            } finally { instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(original) } }
        }
    }

    @Test fun releaseParsingAndCoalescedManualDailyRequestsReportNetworkFailure() {
        AppUpdates.initialize(context)
        val prefs = context.getSharedPreferences("app_updates", 0)
        val attempted = prefs.getString("attempted_day", null)
        val prompted = prefs.getString("prompted_day", null)
        fun settle() = waitFor { !AppUpdates.state.value.checking }
        settle() // Finish any actual startup request before simulated responses.
        val json = JSONObject("""{"tag_name":"v0.10.0","draft":false,"prerelease":false,"assets":[]} """)
        assertEquals(AppVersion(0, 10, 0), AppUpdates.parseRelease(json).version)
        for (bad in listOf(json.toString().replace("\"draft\":false", "\"draft\":true"), json.toString().replace("\"prerelease\":false", "\"prerelease\":true"), "{}")) {
            assertTrue(runCatching { AppUpdates.parseRelease(JSONObject(bad)) }.isFailure)
        }
        try {
            instrumentation.runOnMainSync { AppUpdates.onBackground() }
            val requests = AtomicInteger()
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                AppUpdates.check(true) { requests.incrementAndGet(); latch.await(5, TimeUnit.SECONDS); null }
                AppUpdates.check(true) { requests.incrementAndGet(); null }
            }
            assertTrue(AppUpdates.state.value.checking)
            latch.countDown(); settle()
            assertEquals(1, requests.get())
            assertEquals(R.string.update_no_release, AppUpdates.state.value.result!!.message)
            assertNull(AppUpdates.state.value.prompt)
            instrumentation.runOnMainSync { AppUpdates.check(true) { throw java.io.IOException("fixture network failure") } }
            settle()
            assertEquals(R.string.update_check_failed, AppUpdates.state.value.result!!.message)
            prefs.edit().remove("attempted_day").commit()
            instrumentation.runOnMainSync { AppUpdates.check(false) { requests.incrementAndGet(); null } }
            settle()
            val stored = prefs.getString("attempted_day", null)
            assertNotNull(stored)
            assertEquals(stored, context.getSharedPreferences("app_updates", 0).getString("attempted_day", null))
            instrumentation.runOnMainSync { AppUpdates.check(false) { requests.incrementAndGet(); error("Repeated daily request") } }
            settle(); assertEquals(2, requests.get())
            assertNull(AppUpdates.state.value.result) // Automatic no-release is silent.
            instrumentation.runOnMainSync { AppUpdates.check(true) { AppRelease("v0.3.0", AppVersion(0, 3, 0), emptyList()) } }
            settle(); assertEquals(R.string.update_current, AppUpdates.state.value.result!!.message)
            instrumentation.runOnMainSync { AppUpdates.check(true) { AppRelease("v0.4.0", AppVersion(0, 4, 0), emptyList()) } }
            settle(); assertEquals(R.string.update_no_apk, AppUpdates.state.value.result!!.message)
            val asset = ReleaseAsset("MobileSpeak-v0.4.0-universal.apk", "https://github.com/langstaffe/MobileSpeak/releases/download/v0.4.0/MobileSpeak-v0.4.0-universal.apk", 100, null, true)
            val newer = AppRelease("v0.4.0", AppVersion(0, 4, 0), listOf(asset))
            prefs.edit().remove("attempted_day").remove("prompted_day").commit()
            instrumentation.runOnMainSync {
                AppUpdates.suitable = true
                AppUpdates.onForeground { requests.incrementAndGet(); newer }
            }
            settle()
            assertEquals(R.string.update_available, AppUpdates.state.value.prompt!!.message)
            assertNotNull(prefs.getString("prompted_day", null))
            val count = requests.get()
            instrumentation.runOnMainSync {
                AppUpdates.onBackground()
                AppUpdates.onForeground { requests.incrementAndGet(); newer }
            }
            settle(); assertEquals(count, requests.get()); assertNull(AppUpdates.state.value.prompt)
            instrumentation.runOnMainSync { AppUpdates.check(true) { throw UpdateFailure(R.string.update_rate_limited, "HTTP 429") } }
            settle(); assertEquals(R.string.update_rate_limited, AppUpdates.state.value.result!!.message)
            assertNull(AppUpdates.state.value.prompt)

        } finally {
            prefs.edit().putString("attempted_day", attempted).putString("prompted_day", prompted).commit()
            instrumentation.runOnMainSync { AppUpdates.onBackground(); AppUpdates.dismissPrompt() }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 28)
    @Test fun downloadedApkValidationRejectsCorruptionAndVersionMismatchAndReadsVerifiedSignatures() {
        AppUpdates.initialize(context)
        val source = java.io.File(context.applicationInfo.sourceDir)
        val info = requireNotNull(AppUpdates.archiveInfo(source))
        assertNotNull(info.signingInfo)
        assertTrue(requireNotNull(info.signingInfo).apkContentsSigners.isNotEmpty())
        val asset = ReleaseAsset("MobileSpeak-v0.4.0-universal.apk", "https://github.com/langstaffe/MobileSpeak/releases/download/v0.4.0/MobileSpeak-v0.4.0-universal.apk", source.length(), null, true)
        fun rejection(file: java.io.File, expected: ReleaseAsset): Int = try {
            AppUpdates.verifyDownloaded(file, "v0.4.0", expected)
            error("Unsafe APK passed validation")
        } catch (error: UpdateFailure) { error.messageId }
        assertEquals(R.string.update_wrong_version, rejection(source, asset))
        assertEquals(R.string.update_corrupt_apk, rejection(source, asset.copy(size = source.length() + 1)))
        assertEquals(R.string.update_corrupt_apk, rejection(source, asset.copy(digest = "sha256:" + "0".repeat(64))))
        val damaged = java.io.File(context.cacheDir, "damaged-fixture.apk")
        try {
            damaged.writeBytes(ByteArray(100))
            assertEquals(R.string.update_corrupt_apk, rejection(damaged, asset.copy(size = 100)))
        } finally { damaged.delete() }
        val missing = java.io.File(context.cacheDir, "missing-fixture.apk")
        assertEquals(R.string.update_file_missing, rejection(missing, asset))
    }

    @Test fun completedAndOlderUpdatesRemoveBothCopiesAndNotificationWithoutChangingUserData() {
        unlocked()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (tag in listOf("v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName, "v0.0.1")) {
                withDownloadRecord(tag) { id, downloaded, installer ->
                    val kept = File(context.filesDir, "core/update-cleanup-keep.txt")
                    assertFalse(kept.exists())
                    kept.parentFile!!.mkdirs(); kept.writeText("user data")
                    val coreBefore = ClientSession.state.value
                    val manager = context.getSystemService(NotificationManager::class.java)
                    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel("updates") == null)
                        manager.createNotificationChannel(NotificationChannel("updates", context.localized(R.string.update_notifications), NotificationManager.IMPORTANCE_DEFAULT))
                    @Suppress("DEPRECATION")
                    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, "updates") else Notification.Builder(context)
                    if (manager.areNotificationsEnabled()) manager.notify(300, builder.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Update cleanup fixture").build())
                    try {
                        instrumentation.runOnMainSync {
                            updateState.value = updateState.value.copy(result = CheckResult(message = R.string.update_current), download = DownloadStatus.FAILED,
                                downloadError = R.string.update_wrong_version, detail = "stale error")
                            AppUpdates.refreshDownload()
                            assertEquals(DownloadStatus.VERIFYING, AppUpdates.state.value.download)
                            AppUpdates.refreshDownload() // A late completion requests a refresh during cleanup.
                        }
                        waitForRefresh()
                        assertEquals(DownloadStatus.NONE, AppUpdates.state.value.download)
                        assertEquals("", AppUpdates.state.value.downloadVersion)
                        assertEquals("", AppUpdates.state.value.detail)
                        assertEquals(R.string.update_download_failed, AppUpdates.state.value.downloadError)
                        assertEquals(R.string.update_current, AppUpdates.state.value.result!!.message)
                        scenario.onActivity { activity -> activity.setContent { MaterialTheme { SettingsDetailPage("about", {}) } } }
                        waitFor { node(context.localized(R.string.update_current), clickable = false) != null }
                        assertNull(node(context.localized(R.string.update_wrong_version), clickable = false))
                        assertNull(updateField("record").get(AppUpdates))
                        assertFalse(downloaded.exists()); assertFalse(installer.exists())
                        val prefs = context.getSharedPreferences("app_updates", 0)
                        assertFalse(prefs.contains("download")); assertFalse(prefs.contains("notified_download"))
                        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)).use { assertFalse(it.moveToFirst()) }
                        assertFalse(manager.activeNotifications.any { it.id == 300 })
                        instrumentation.runOnMainSync { AppUpdates.refreshDownload() }
                        assertEquals(DownloadStatus.NONE, AppUpdates.state.value.download)
                        assertEquals("user data", kept.readText())
                        assertEquals(coreBefore, ClientSession.state.value)
                    } finally { kept.delete() }
                }
            }
        }
    }

    @Test fun obsoleteUpdateCleanupFailureKeepsRecordAndRetriesWithoutVersionError() {
        unlocked()
        ActivityScenario.launch(MainActivity::class.java).use {
            withDownloadRecord("v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName) { _, downloaded, installer ->
                assertTrue(installer.delete()); assertTrue(installer.mkdir())
                val blocked = File(installer, "blocked.txt").apply { writeText("fixture") }
                instrumentation.runOnMainSync { AppUpdates.refreshDownload() }
                waitForRefresh()
                assertEquals(DownloadStatus.FAILED, AppUpdates.state.value.download)
                assertEquals(R.string.update_storage_failed, AppUpdates.state.value.downloadError)
                assertNotNull(updateField("record").get(AppUpdates))
                assertTrue(context.getSharedPreferences("app_updates", 0).contains("download"))
                assertTrue(blocked.delete())
                instrumentation.runOnMainSync { AppUpdates.refreshDownload() }
                waitForRefresh()
                assertEquals(DownloadStatus.NONE, AppUpdates.state.value.download)
                assertFalse(downloaded.exists()); assertFalse(installer.exists())
                assertFalse(context.getSharedPreferences("app_updates", 0).contains("download"))
            }
        }
    }

    @Test fun newerUpdateAndCancelledInstallKeepBothCopies() {
        unlocked()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withDownloadRecord("v9.0.0") { _, downloaded, installer ->
                instrumentation.runOnMainSync { AppUpdates.refreshDownload() }
                waitForRefresh()
                assertNotEquals(DownloadStatus.NONE, AppUpdates.state.value.download)
                assertTrue(downloaded.isFile); assertTrue(installer.isFile)
                assertNotNull(updateField("record").get(AppUpdates))
                scenario.onActivity { activity ->
                    activity.setContent { MaterialTheme { SettingsDetailPage("about", {}) } }
                    updateState.value = updateState.value.copy(download = DownloadStatus.READY)
                }
                click(context.localized(R.string.update_install))
                click(context.localized(R.string.action_cancel))
                assertEquals(DownloadStatus.READY, AppUpdates.state.value.download)
                assertFalse(AppUpdates.state.value.installing)
                assertTrue(downloaded.isFile); assertTrue(installer.isFile)
                instrumentation.runOnMainSync { AppUpdates.onBackground(); AppUpdates.onForeground { null } }
                waitForRefresh()
                assertTrue(downloaded.isFile); assertTrue(installer.isFile)
                assertFalse(AppUpdates.state.value.installing)
            }
        }
    }

    @Test fun replacementIsBlockedDuringInstallAndCleanupFailureKeepsPreviousRecord() {
        unlocked()
        ActivityScenario.launch(MainActivity::class.java).use {
            withDownloadRecord("v9.0.0") { _, downloaded, installer ->
                val previous = updateField("record").get(AppUpdates)
                val saved = context.getSharedPreferences("app_updates", 0).getString("download", null)
                val tag = "v9.0.1"
                val name = "MobileSpeak-$tag-universal.apk"
                val asset = ReleaseAsset(name, "https://github.com/langstaffe/MobileSpeak/releases/download/$tag/$name", 100, null, true)
                val next = CheckResult(AppRelease(tag, AppVersion(9, 0, 1), listOf(asset)), asset, R.string.update_available, "9.0.1")
                instrumentation.runOnMainSync {
                    updateState.value = updateState.value.copy(download = DownloadStatus.READY, installing = true)
                    AppUpdates.download(next)
                    assertSame(previous, updateField("record").get(AppUpdates))
                    assertEquals(DownloadStatus.READY, AppUpdates.state.value.download)
                }
                assertTrue(downloaded.isFile); assertTrue(installer.isFile)
                assertTrue(installer.delete()); assertTrue(installer.mkdir())
                File(installer, "blocked.txt").writeText("fixture")
                instrumentation.runOnMainSync {
                    updateState.value = updateState.value.copy(installing = false)
                    AppUpdates.download(next)
                }
                waitFor { AppUpdates.state.value.download == DownloadStatus.FAILED }
                assertEquals(R.string.update_storage_failed, AppUpdates.state.value.downloadError)
                assertSame(previous, updateField("record").get(AppUpdates))
                assertEquals(saved, context.getSharedPreferences("app_updates", 0).getString("download", null))
            }
        }
    }

    private fun updateField(name: String) = AppUpdates.javaClass.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private val updateState get() = updateField("mutable").get(AppUpdates) as MutableStateFlow<UpdateState>
    private fun waitForRefresh() = waitFor { !updateField("refreshing").getBoolean(AppUpdates) && !AppUpdates.state.value.checking }

    private fun withDownloadRecord(tag: String, block: (Long, File, File) -> Unit) {
        AppUpdates.initialize(context)
        waitForRefresh()
        waitFor { ClientSession.state.value.audioProcessingStatus != "switching" }
        val prefs = context.getSharedPreferences("app_updates", 0)
        val saved = prefs.getString("download", null)
        val notified = prefs.getLong("notified_download", -1)
        val originalRecord = updateField("record").get(AppUpdates)
        val originalState = AppUpdates.state.value
        val manager = context.getSystemService(NotificationManager::class.java)
        val originalNotification = manager.activeNotifications.firstOrNull { it.id == 300 }
        val name = "MobileSpeak-$tag-universal.apk"
        val downloaded = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)), "updates/$name")
        val installer = File(context.cacheDir, "updates/$name")
        assertFalse("Fixture must not overwrite an existing download", downloaded.exists())
        assertFalse("Fixture must not overwrite an existing installer copy", installer.exists())
        downloaded.parentFile!!.mkdirs(); installer.parentFile!!.mkdirs()
        val asset = ReleaseAsset(name, "https://github.com/langstaffe/MobileSpeak/releases/download/$tag/$name", 100, null, true)
        val downloads = context.getSystemService(DownloadManager::class.java)
        var id = -1L
        try {
            downloaded.writeBytes(ByteArray(100)); installer.writeBytes(ByteArray(100))
            @Suppress("DEPRECATION")
            val completed = try {
                downloads.addCompletedDownload(name, "Update cleanup fixture", false, "application/vnd.android.package-archive", downloaded.path, downloaded.length(), true)
            } catch (error: IllegalStateException) {
                // Older MIUI providers can insert the row before rejecting their service start.
                downloads.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)).use { cursor ->
                    var found = -1L
                    while (cursor.moveToNext()) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) == Uri.fromFile(downloaded).toString())
                            found = maxOf(found, cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
                    }
                    if (found < 0) throw error
                    found
                }
            }
            id = completed
            assertTrue(id > 0)
            assertTrue(prefs.edit().putString("download", JSONObject().put("id", id).put("tag", tag).put("name", name).put("url", asset.url).put("size", 100).put("digest", "").toString()).putLong("notified_download", id).commit())
            val constructor = Class.forName("dev.mobilespeak.mobilespeak.DownloadRecord").getDeclaredConstructor(Long::class.javaPrimitiveType, String::class.java, ReleaseAsset::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync {
                updateField("record").set(AppUpdates, constructor.newInstance(id, tag, asset))
                updateState.value = originalState.copy(download = DownloadStatus.DOWNLOADING, downloadVersion = tag.removePrefix("v"), installing = false)
            }
            block(id, downloaded, installer)
        } finally {
            waitForRefresh()
            if (id > 0) downloads.remove(id)
            downloaded.delete(); installer.deleteRecursively()
            val edit = prefs.edit().putString("download", saved)
            if (notified == -1L) edit.remove("notified_download") else edit.putLong("notified_download", notified)
            assertTrue(edit.commit())
            manager.cancel(300)
            originalNotification?.let { manager.notify(it.tag, it.id, it.notification) }
            instrumentation.runOnMainSync { updateField("record").set(AppUpdates, originalRecord); updateState.value = originalState }
        }
    }

    private fun node(text: String, clickable: Boolean = true): AccessibilityNodeInfo? {
        fun find(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.isVisibleToUser && (root.text?.toString()?.contains(text) == true || root.contentDescription?.toString() == text)) {
                if (!clickable) return root
                generateSequence(root) { it.parent }.firstOrNull { it.isClickable || it.isCheckable || it.isSelected }?.let { return it }
            }
            for (index in 0 until root.childCount) find(root.getChild(index))?.let { return it }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun describeTree(): String {
        val result = StringBuilder()
        fun append(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result.append("\n").append(node.text).append(" / ").append(node.contentDescription).append(" click=").append(node.isClickable).append(" visible=").append(node.isVisibleToUser)
            for (i in 0 until node.childCount) append(node.getChild(i))
        }
        append(instrumentation.uiAutomation.rootInActiveWindow)
        return result.toString()
    }
    private fun waitFor(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; SystemClock.sleep(100) }
        assertTrue("Timed out waiting for native UI/update state", condition())
    }
    private fun click(text: String) {
        waitFor { node(text) != null }
        val target = node(text)!!
        if (target.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        else assertTrue("Only an already selected radio option may omit duplicate activation", target.isChecked || target.isSelected)
        instrumentation.waitForIdleSync(); SystemClock.sleep(400)
    }
    private fun scrollTo(text: String, clickable: Boolean = true) {
        fun scrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (root == null) return null
            if (root.isScrollable) return root
            for (index in 0 until root.childCount) scrollable(root.getChild(index))?.let { return it }
            return null
        }
        repeat(8) {
            if (node(text, clickable) != null) return
            scrollable(instrumentation.uiAutomation.rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        }
        assertNotNull("Visible $text", node(text, clickable))
    }
}
