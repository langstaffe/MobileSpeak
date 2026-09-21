package dev.mobilespeak.mobilespeak

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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
        val original = AppCompatDelegate.getApplicationLocales()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            assertTrue(AppLanguage.select(AppLanguage.ENGLISH))
            assertEquals(AppLanguage.ENGLISH, AppLanguage.selected())
            assertEquals("Settings", context.localized(R.string.tab_settings))
            assertFalse(AppLanguage.select(AppLanguage.ENGLISH))

            assertTrue(AppLanguage.select(AppLanguage.SIMPLIFIED_CHINESE))
            assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.selected())
            assertEquals("设置", context.localized(R.string.tab_settings))
        } finally {
            AppCompatDelegate.setApplicationLocales(original)
        }
    }
}
