package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPresentationTest {
    @Test fun networkPaletteDoesNotUseTheGlobalSpeakingColor() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFF3DBE78), NetworkGrade.GOOD.networkColor())
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE9B44C), NetworkGrade.FAIR.networkColor())
        assertEquals(androidx.compose.ui.graphics.Color(0xFFC15AB8), NetworkGrade.POOR.networkColor())
        assertEquals(Palette.muted, null.networkColor())
    }
    @Test fun voiceDrawerGeometryResistanceAndDistanceVelocitySettling() {
        val travel = voiceDrawerTravel(650f, 64f)
        assertEquals(391f, travel, .001f)
        assertEquals(7f / 3f, (64f + travel) / (650f - 64f - travel), .001f)
        assertEquals(320f * 7f / 10f - 64f, voiceDrawerTravel(320f, 64f), .001f)
        for (height in listOf(0f, 64f, 90f)) assertEquals(0f, voiceDrawerTravel(height, 64f), 0f)
        assertEquals(travel * 3f, voiceDrawerTravel(1950f, 192f), .001f)
        assertFalse(voiceDrawerShouldExpand(99f, 200f, 0f))
        assertFalse(voiceDrawerShouldExpand(100f, 200f, 0f))
        assertTrue(voiceDrawerShouldExpand(101f, 200f, 0f))
        assertTrue(voiceDrawerShouldExpand(20f, 200f, -500f))
        assertFalse(voiceDrawerShouldExpand(180f, 200f, 500f))
        assertFalse(voiceDrawerShouldExpand(600f, 200f, 600f))
        assertTrue(voiceDrawerShouldExpand(-400f, 200f, -600f))
        assertFalse(voiceDrawerShouldExpand(0f, 0f, -500f))
        assertTrue(voiceDrawerShouldExpand(60f, 600f, -1500f))
        for (raw in listOf(-10000f, -40f, 0f, 100f, 200f, 240f, 10000f)) {
            val offset = voiceDrawerOffset(raw, 200f, 16f)
            assertTrue(offset > -16f && offset < 216f)
            if (raw in -1000f..1000f) assertEquals(raw, voiceDrawerRawOffset(offset, 200f, 16f), .001f)
        }
        assertEquals(0f, voiceDrawerOffset(-40f, 200f, 0f), 0f)
        assertEquals(200f, voiceDrawerOffset(240f, 200f, 0f), 0f)
    }

    private fun channel(name: String, parent: Long = 0, permanent: Boolean = true) =
        Channel(1, parent, 0, name, false, permanent, "test", null)

    @Test fun spacerPresentationPreservesEmptyRowsAndIgnoresOrdinaryChannels() {
        assertEquals("", channelSpacer(channel("[spacer0]")))
        assertEquals("标题", channelSpacer(channel("[cspacer1]标题")))
        assertNull(channelSpacer(channel("普通频道")))
        assertNull(channelSpacer(channel("[spacer0]", parent = 1)))
        assertNull(channelSpacer(channel("[spacer0]", permanent = false)))
        assertNull(channelSpacer(channel("[spacer")))
        assertEquals("-".repeat(48), channelSpacerDisplayText(channel("[*spacer0]-"), "-"))
        assertEquals("", channelSpacerDisplayText(channel("[*spacer0]"), ""))
        assertEquals("长".repeat(60), channelSpacerDisplayText(channel("[*spacer0]"), "长".repeat(60)))
        assertEquals("标题", channelSpacerDisplayText(channel("[cspacer0]标题"), "标题"))
    }
}
