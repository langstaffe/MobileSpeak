package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationTest {
    @Test
    fun stableCodesAndInvalidValuesResolveCorrectly() {
        assertEquals(listOf("system", "zh-Hans", "zh-Hant", "en"), AppLanguage.entries.map(AppLanguage::code))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("legacy-value"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromCode("zh-Hans"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromCode("zh-Hant"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
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
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("fr-FR", "zh-HK", "en-US")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("fr-FR", "en-US", "zh-TW")))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-MY", "zh-MO")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-MY")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("en-US")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("fr-FR")))
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
