package dev.mobilespeak.mobilespeak

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
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
            } finally {
                instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
                instrumentation.waitForIdleSync()
            }
        }
    }
}
