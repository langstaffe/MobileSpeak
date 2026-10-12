package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelMemberPreviewTest {
    @Test fun countReservesTextAndOnlyIncludesCompleteAvatars() {
        val text = 70
        assertEquals(0, channelPreviewAvatarCount(1000, text, 0, 29))
        assertEquals(1, channelPreviewAvatarCount(text + 29, text, 1, 29))
        assertEquals(5, channelPreviewAvatarCount(text + 5 * 29, text, 5, 29))
        assertEquals(4, channelPreviewAvatarCount(text + 4 * 29, text, 5, 29))
        assertEquals(3, channelPreviewAvatarCount(text + 4 * 29 - 1, text, 5, 29))
        assertEquals(0, channelPreviewAvatarCount(text + 28, text, 5, 29))
        assertEquals(0, channelPreviewAvatarCount(text - 1, text, 5, 29))
        assertEquals(1, channelPreviewAvatarCount(1000, text, 1, 29))
        // A fractional display density rounds avatar and gap independently in native layout.
        assertEquals(4, channelPreviewAvatarCount(217, 65, 5, 38))
        assertEquals(3, channelPreviewAvatarCount(216, 65, 5, 38))
    }
}
