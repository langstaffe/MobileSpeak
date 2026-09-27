package dev.mobilespeak.mobilespeak

import android.app.KeyguardManager
import android.graphics.Rect
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
                waitFor { node("v0.3.0", clickable = false) != null }
                assertNotNull(describeTree(), node("https://github.com/langstaffe/MobileSpeak"))
                assertNotNull(node(context.localized(R.string.update_check)))
                click(context.localized(R.string.update_check))
                waitFor { !AppUpdates.state.value.checking && AppUpdates.state.value.result != null }
                val realCheck = requireNotNull(AppUpdates.state.value.result)
                assertNotNull(node(context.localized(realCheck.message, realCheck.version), clickable = false))
                java.io.File(context.cacheDir, "real-update-check.txt").writeText(context.localized(realCheck.message, realCheck.version) + " " + realCheck.detail)
                click("https://github.com/langstaffe/MobileSpeak")
                waitFor { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                waitFor { node("v0.3.0", clickable = false) != null }
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
                            scrollTo("v0.3.0", clickable = false)
                            assertNotNull(node("v0.3.0", clickable = false))
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
