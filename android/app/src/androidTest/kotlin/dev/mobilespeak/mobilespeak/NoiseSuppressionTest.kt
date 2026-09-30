package dev.mobilespeak.mobilespeak

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoiseSuppressionTest {
    @Test fun audioPreferencesMigrateAndPersistWithoutTouchingOtherSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("audio-migration-test", android.content.Context.MODE_PRIVATE)
        try {
            for ((legacy, expected) in listOf("dpdfnet8" to "rnnoise", "dpdfnet2" to "dpdfnet2", "rnnoise" to "rnnoise", "none" to "none", "unknown" to "rnnoise")) {
                preferences.edit().putString("noise", legacy).putString("vad", "silero").putString("bookmark", "preserved").commit()
                assertEquals(expected, restoreNoiseSuppression(preferences))
                assertFalse(preferences.contains("vad"))
                assertEquals(expected, preferences.getString("noise", null))
                assertEquals(expected, restoreNoiseSuppression(preferences))
                assertEquals("preserved", preferences.getString("bookmark", null))
            }
        } finally { preferences.edit().clear().commit() }
    }
}
