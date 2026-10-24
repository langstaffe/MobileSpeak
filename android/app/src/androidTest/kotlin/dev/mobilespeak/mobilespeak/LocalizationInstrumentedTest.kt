package dev.mobilespeak.mobilespeak

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
    @Test fun channelSummaryUsesTotalCountAndLocalizedPlural() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((language, expected) in listOf("en" to listOf("· 1 member online", "· 5 members online", "· 100 members online"),
            "zh-Hans" to listOf("· 1 人在线", "· 5 人在线", "· 100 人在线"),
            "zh-Hant" to listOf("· 1 人線上", "· 5 人線上", "· 100 人線上"))) {
            val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }).resources
            listOf(1, 5, 100).zip(expected).forEach { (count, text) ->
                assertEquals(text, resources.getQuantityString(R.plurals.channel_members_summary, count, count))
            }
        }
    }
    @Test
    fun appLocalePersistsUsesLocalizedResourcesAndSkipsSameSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Android's locale manager is available to AppCompat through an active delegate.
        ActivityScenario.launch(MainActivity::class.java).use {
            val original = AppCompatDelegate.getApplicationLocales()
            fun select(language: AppLanguage): Boolean {
                var changed = false
                instrumentation.runOnMainSync { changed = AppLanguage.select(language) }
                instrumentation.waitForIdleSync()
                return changed
            }
            try {
                instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
                instrumentation.waitForIdleSync()
                assertTrue(select(AppLanguage.ENGLISH))
                assertEquals(AppLanguage.ENGLISH, AppLanguage.selected())
                assertEquals("Settings", context.localized(R.string.tab_settings))
                assertEquals("Automatic gain control", context.localized(R.string.settings_receive_agc))
                assertEquals("Automatically adjusts other users' volume", context.localized(R.string.settings_receive_agc_help))
                assertFalse(select(AppLanguage.ENGLISH))

                assertTrue(select(AppLanguage.SIMPLIFIED_CHINESE))
                assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.selected())
                assertEquals("设置", context.localized(R.string.tab_settings))
                assertEquals("调节用户音量", context.localized(R.string.volume_title))
                assertEquals("自动调整其他用户的音量", context.localized(R.string.settings_receive_agc_help))

                assertTrue(select(AppLanguage.TRADITIONAL_CHINESE))
                assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.selected())
                assertEquals("設定", context.localized(R.string.tab_settings))
                assertEquals("調整使用者音量", context.localized(R.string.volume_title))
                assertEquals("中文（简体）", context.localized(R.string.language_simplified_chinese))
                assertEquals("中文（繁體）", context.localized(R.string.language_traditional_chinese))
                assertEquals("English", context.localized(R.string.language_english))
                assertEquals("中文（繁體）", context.languageContext().getString(R.string.language_traditional_chinese))
                assertFalse(select(AppLanguage.TRADITIONAL_CHINESE))
            } finally {
                instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
                instrumentation.waitForIdleSync()
            }
        }
    }

    @Test fun previouslyRenderedErrorChangesLanguageButKeepsDiagnosticDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rawDetail = "server_name_测试_測試"
        val source = LocalizedMessage(R.string.error_bookmark_save, detail = rawDetail)
        val english = source.render(context.contextForLanguage(AppLanguage.ENGLISH))
        assertEquals("Couldn’t securely save the bookmark. $rawDetail", english)
        assertEquals("無法安全儲存書籤。 $rawDetail",
            source.render(context.contextForLanguage(AppLanguage.TRADITIONAL_CHINESE)))
        val literalOldText = "Couldn’t securely save the bookmark."
        assertEquals("無法安全儲存書籤。 $literalOldText",
            LocalizedMessage(R.string.error_bookmark_save, detail = literalOldText)
                .render(context.contextForLanguage(AppLanguage.TRADITIONAL_CHINESE)))
        assertEquals("無法安全儲存書籤。 相同位址和連接埠的書籤已存在。",
            LocalizedMessage(R.string.error_bookmark_save, detailSource = LocalizedMessage(R.string.error_bookmark_duplicate))
                .render(context.contextForLanguage(AppLanguage.TRADITIONAL_CHINESE)))
    }

    @Test fun activeErrorAndRawFileDetailSurviveLanguageSwitchWithoutResettingSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = AppCompatDelegate.getApplicationLocales()
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                instrumentation.runOnMainSync { AppLanguage.select(AppLanguage.ENGLISH) }
                instrumentation.waitForIdleSync()
                val before = ClientSession.state.value
                val detail = "server_name_測試"
                ClientSession.reportError(LocalizedMessage(R.string.error_bookmark_save, detail))
                ClientSession.fileLocalError("raw_$detail")
                instrumentation.runOnMainSync { AppLanguage.select(AppLanguage.TRADITIONAL_CHINESE) }
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                while (ClientSession.state.value.error != "無法安全儲存書籤。 $detail" &&
                    android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
                val after = ClientSession.state.value
                assertEquals("無法安全儲存書籤。 $detail", after.error)
                assertEquals("raw_$detail", after.channelFilesLocalError)
                assertEquals(before.snapshot.connectionId, after.snapshot.connectionId)
                assertEquals(before.microphoneMuted, after.microphoneMuted)
                assertEquals(before.deafened, after.deafened)
            } finally {
                ClientSession.clearError()
                ClientSession.fileLocalError(null)
                instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }
}
