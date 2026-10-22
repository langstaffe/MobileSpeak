package dev.mobilespeak.mobilespeak

import org.junit.Assert.*
import org.junit.Test

class PushToTalkHoldTest {
    @Test fun startsImmediatelyStopsOnceAndRequiresANewDown() {
        val commands = mutableListOf<Pair<Boolean, String>>()
        val hold = PushToTalkHold { pressed, token -> commands += pressed to token }
        assertTrue(hold.down("press-1", true))
        assertEquals(listOf(true to "press-1"), commands)
        assertFalse(hold.down("press-1", true))
        hold.stop() // Up, slide outside, cancellation, lost focus and disposal share this path.
        hold.stop()
        assertEquals(listOf(true to "press-1", false to "press-1"), commands)
        assertFalse(hold.down("press-2", false))
        assertEquals(2, commands.size)
        assertTrue(hold.down("press-3", true))
        hold.stop()
        assertEquals(listOf(true to "press-3", false to "press-3"), commands.takeLast(2))
    }

    @Test fun interruptionReleasesOriginalTokenBeforeANewSession() {
        val commands = mutableListOf<Pair<Boolean, String>>()
        val hold = PushToTalkHold { pressed, token -> commands += pressed to token }
        assertFalse(hold.down("", true))
        hold.down("old-channel", true)
        hold.stop()
        // Recovery alone sends nothing; a new key down owns the new connection/channel token.
        assertEquals(false to "old-channel", commands.last())
        hold.down("new-channel", true)
        hold.stop()
        assertEquals(false to "new-channel", commands.last())
    }
}
