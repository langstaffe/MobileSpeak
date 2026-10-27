package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationTest {
    @Test
    fun stableCodesAndInvalidValuesResolveCorrectly() {
        assertEquals(listOf("system", "zh-Hans", "zh-Hant", "en", "de", "ru", "pl", "es-MX", "fr", "pt-BR", "es", "ja", "ko"), AppLanguage.entries.map(AppLanguage::code))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("legacy-value"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromCode("zh-Hans"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromCode("zh-Hant"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        for (language in AppLanguage.entries) assertEquals(language, AppLanguage.fromCode(language.code))
    }

    @Test
    fun systemLanguageFollowsFirstSupportedLocaleAndScriptBeforeRegion() {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-Hans-CN")))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-Hans-TW")))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-Hant-CN")))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-CN")))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-SG")))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh")))
        for (region in listOf("TW", "HK", "MO")) {
            assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-$region")))
        }
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("it-IT", "zh-HK", "en-US")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("it-IT", "en-US", "zh-TW")))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-MY", "zh-MO")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-MY")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("en-US")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("it-IT")))
    }

    @Test fun regionalMatchingPreferenceOrderAndPerAppSelection() {
        for ((tag, expected) in mapOf("es-MX" to AppLanguage.SPANISH_MEXICO,
            "es-ES" to AppLanguage.SPANISH, "es-AR" to AppLanguage.SPANISH,
            "pt-BR" to AppLanguage.PORTUGUESE_BRAZIL, "pt-PT" to AppLanguage.ENGLISH,
            "pt" to AppLanguage.ENGLISH, "de-AT" to AppLanguage.GERMAN,
            "fr-CA" to AppLanguage.FRENCH, "ru-BY" to AppLanguage.RUSSIAN, "pl-PL" to AppLanguage.POLISH,
            "ja" to AppLanguage.JAPANESE, "ja-JP" to AppLanguage.JAPANESE,
            "ko" to AppLanguage.KOREAN, "ko-KR" to AppLanguage.KOREAN)) {
            assertEquals(tag, expected, AppLanguage.resolve(AppLanguage.SYSTEM, listOf(tag)))
            assertEquals(tag, expected, AppLanguage.selected(listOf(tag)))
        }
        assertEquals(AppLanguage.SYSTEM, AppLanguage.selected(emptyList()))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.selected(listOf("it-IT")))
        assertEquals(AppLanguage.GERMAN, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("pt-PT", "de-AT", "fr-CA")))
        assertEquals(AppLanguage.FRENCH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("it-IT", "fr-CA", "de-AT")))
        assertEquals(AppLanguage.SPANISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("es-AR", "es-MX")))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("it-IT", "ja-JP", "ko-KR", "en-US")))
        assertEquals(AppLanguage.KOREAN, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("ko-KR", "ja-JP")))
        assertEquals(AppLanguage.FRENCH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("fr-CA", "ja-JP", "ko-KR")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, emptyList()))
        for (language in AppLanguage.entries.filter { it != AppLanguage.SYSTEM }) {
            assertEquals(language, AppLanguage.resolve(language, listOf("en-US")))
            assertFalse(AppLanguage.shouldApply(language, language))
        }
    }

    @Test fun displayNumbersUseRequestedLocaleWithoutChangingPrecisionOrRawValues() {
        for (tag in listOf("en", "zh-Hans", "zh-Hant", "de", "ru", "pl", "es-MX", "fr", "pt-BR", "es", "ja", "ko")) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val decimal = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
            assertEquals("6${decimal}1", NetworkQuality.number(6.05, 1, locale = locale))
            assertEquals("999${decimal}9", NetworkQuality.number(1234.5, 1, 999.9, locale))
            assertEquals("999", NetworkQuality.number(1234.5, 0, 999.0, locale))
            assertEquals("0${decimal}0", NetworkQuality.number(0.0, 1, locale = locale))
            assertEquals("679${decimal}5", NetworkQuality.axisNumber(679.5, locale))
            assertEquals("—", NetworkQuality.number(Double.NaN, 1, locale = locale))
            assertEquals("10000000000000000000000", NetworkQuality.number(1e22, 0, locale = locale))
            assertEquals(java.text.NumberFormat.getPercentInstance(locale).format(1.25), localizedPercent(125, locale))
        }
        assertEquals("example.com", Bookmark.normalizedHost("EXAMPLE.COM"))
        assertEquals("1.2.3", AppVersion(1, 2, 3).toString())
    }

    @Test
    fun explicitSelectionIgnoresSystemAndSameSelectionIsANoOp() {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SIMPLIFIED_CHINESE, listOf("en-US")))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.resolve(AppLanguage.TRADITIONAL_CHINESE, listOf("zh-CN")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.ENGLISH, listOf("zh-CN")))
        assertFalse(AppLanguage.shouldApply(AppLanguage.ENGLISH, AppLanguage.ENGLISH))
        assertTrue(AppLanguage.shouldApply(AppLanguage.ENGLISH, AppLanguage.SYSTEM))
    }
}
