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

    @Test fun russianAndPolishCountsUseNativePluralRules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val counts = listOf(0, 1, 2, 5, 11, 21, 22, 25)
        val expected = mapOf(
            AppLanguage.RUSSIAN to listOf("участников", "участник", "участника", "участников", "участников", "участник", "участника", "участников"),
            AppLanguage.POLISH to listOf("osób", "osoba", "osoby", "osób", "osób", "osób", "osoby", "osób"),
        )
        for ((language, nouns) in expected) {
            val resources = context.contextForLanguage(language).resources
            counts.zip(nouns).forEach { (count, noun) ->
                val text = if (language == AppLanguage.RUSSIAN) "· $count $noun в сети" else "· $count $noun online"
                assertEquals(text, resources.getQuantityString(R.plurals.channel_members_summary, count, count))
            }
        }
    }

    @Test fun everyLanguageFormatsDisplayDataVariablesAndRetainedErrorsAfterSwitchAndRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = AppCompatDelegate.getApplicationLocales()
        val rawName = "Server_Тест_測試_á"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val before = ClientSession.state.value
                val source = LocalizedMessage(R.string.error_bookmark_save, detail = rawName)
                ClientSession.reportError(source)
                for (language in AppLanguage.entries.filter { it != AppLanguage.SYSTEM }) {
                    instrumentation.runOnMainSync { AppLanguage.select(language) }
                    instrumentation.waitForIdleSync()
                    scenario.recreate()
                    instrumentation.waitForIdleSync()
                    assertEquals(language, AppLanguage.selected())
                    assertEquals(language, AppLanguage.selected(context))
                    assertEquals(language.tag, AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    val localized = context.languageContext()
                    val locale = localized.resources.configuration.locales[0]
                    val decimal = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
                    assertEquals("6${decimal}1", NetworkQuality.number(6.05, 1, locale = locale))
                    assertEquals("999", NetworkQuality.number(1500.0, 0, 999.0, locale))
                    assertEquals("999${decimal}9", NetworkQuality.number(1500.0, 1, 999.9, locale))
                    val percentFormat = java.text.NumberFormat.getPercentInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
                    val (percentPrefix, percentSuffix) = percentAffixes(locale)
                    assertEquals(percentFormat.format(.061), percentPrefix + "6${decimal}1" + percentSuffix)
                    assertEquals(java.text.NumberFormat.getPercentInstance(locale).format(1.25), localizedPercent(125, locale))
                    val (unitPrefix, unitSuffix) = millisecondAffixes(locale)
                    val measureFormat = android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.SHORT,
                        android.icu.text.NumberFormat.getInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 })
                    assertEquals(measureFormat.formatMeasures(android.icu.util.Measure(6.1, android.icu.util.MeasureUnit.MILLISECOND)),
                        unitPrefix + "6${decimal}1" + unitSuffix)
                    assertEquals(android.text.format.Formatter.formatFileSize(localized, 1536000), context.localizedFileSize(1536000))
                    val timestamp = 1735689600L
                    assertEquals(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale)
                        .format(java.util.Date(timestamp * 1000)), context.localizedFileDate(timestamp))
                    for (id in listOf(R.string.voice_target_channel, R.string.profile_edit_nickname, R.string.volume_accessibility)) {
                        val text = localized.getString(id, rawName)
                        assertTrue(text, text.contains(rawName))
                        assertFalse(text, text.contains("%1\$s"))
                    }
                    val deadline = android.os.SystemClock.uptimeMillis() + 5000
                    while (ClientSession.state.value.error != source.render(localized) && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
                    assertEquals(source.render(localized), ClientSession.state.value.error)
                    assertEquals(before.snapshot.connectionId, ClientSession.state.value.snapshot.connectionId)
                    assertEquals(before.microphoneMuted, ClientSession.state.value.microphoneMuted)
                    assertEquals(before.deafened, ClientSession.state.value.deafened)
                    scenario.onActivity { activity -> assertEquals(localized.getString(R.string.tab_settings), activity.getString(R.string.tab_settings)) }
                    val labels = mutableListOf<String>()
                    fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                        if (node == null || !node.refresh()) return
                        node.text?.toString()?.let(labels::add)
                        node.contentDescription?.toString()?.let(labels::add)
                        for (index in 0 until node.childCount) collect(node.getChild(index))
                    }
                    val accessibilityDeadline = android.os.SystemClock.uptimeMillis() + 5000
                    do {
                        labels.clear()
                        collect(instrumentation.uiAutomation.rootInActiveWindow)
                        if (labels.any { source.render(localized) in it }) break
                        Thread.sleep(50)
                    } while (android.os.SystemClock.uptimeMillis() < accessibilityDeadline)
                    assertTrue(labels.toString(), labels.any { source.render(localized) in it })
                    Thread.sleep(150)
                    val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    java.io.File(context.getExternalFilesDir(null), "localization-error-${language.code}.png").outputStream().use {
                        shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    shot.recycle()
                    android.util.Log.i("LocalizationCheck", "${language.code}: date=${context.localizedFileDate(timestamp)}, size=${context.localizedFileSize(1536000)}, number=6${decimal}1, percent=${localizedPercent(125, locale)}")
                }
            } finally {
                ClientSession.clearError()
                instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
            }
        }
    }

    @Test fun systemPerAppLanguageIsRecognizedAndBackgroundContextKeepsItWithoutActivity() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val original = manager.applicationLocales
        try {
            for (tag in listOf("de", "fr", "es-MX", "es", "pt-BR")) {
                instrumentation.runOnMainSync { manager.applicationLocales = android.os.LocaleList.forLanguageTags(tag) }
                instrumentation.waitForIdleSync()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    assertEquals(AppLanguage.fromCode(tag), AppLanguage.selected(context))
                    scenario.onActivity { activity -> assertEquals(context.localized(R.string.tab_settings), activity.getString(R.string.tab_settings)) }
                }
                assertEquals(AppLanguage.fromCode(tag), AppLanguage.selected(context))
            }
            assertEquals(AppLanguage.PORTUGUESE_BRAZIL, AppLanguage.selected(context))
            assertEquals("pt-BR", context.languageContext().resources.configuration.locales[0].toLanguageTag())
        } finally { instrumentation.runOnMainSync { manager.applicationLocales = original } }
    }

}
