package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiPresentationTest {
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
