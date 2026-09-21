package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationTest {
    @Test
    fun stableCodesAndInvalidValuesResolveCorrectly() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("legacy-value"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromCode("zh-Hans"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
    }

    @Test
    fun systemLanguageSupportsSimplifiedChineseAndFallsBackToEnglish() {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-Hans-CN")))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("zh-CN")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("en-US")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.SYSTEM, listOf("fr-FR")))
    }

    @Test
    fun explicitSelectionIgnoresSystemAndSameSelectionIsANoOp() {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.resolve(AppLanguage.SIMPLIFIED_CHINESE, listOf("en-US")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.resolve(AppLanguage.ENGLISH, listOf("zh-CN")))
        assertFalse(AppLanguage.shouldApply(AppLanguage.ENGLISH, AppLanguage.ENGLISH))
        assertTrue(AppLanguage.shouldApply(AppLanguage.ENGLISH, AppLanguage.SYSTEM))
    }
}
