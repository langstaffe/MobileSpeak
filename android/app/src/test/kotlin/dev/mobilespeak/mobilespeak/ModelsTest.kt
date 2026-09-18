package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test
    fun bookmarkValidationNormalizesAddressAndProtectsLimits() {
        val bookmark = Bookmark.create(null, "", " [2001:DB8::1] ", "9987", " 用户😀 ", "secret")
        assertEquals("2001:db8::1", bookmark.host)
        assertEquals("[2001:db8::1]:9987", bookmark.address)
        assertEquals("用户😀", bookmark.nickname)
        assertThrows(IllegalArgumentException::class.java) {
            Bookmark.create(null, "", "bad host", "9987", "name", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Bookmark.create(null, "", "host", "0", "name", "")
        }
    }

    @Test
    fun channelOrderingFollowsServerOrderAndKeepsOrphans() {
        fun channel(id: Long, parent: Long, order: Long) =
            Channel(id, parent, order, "c$id", false, true, "$id", null)
        val result = orderedChannels(
            listOf(channel(2, 0, 1), channel(1, 0, 0), channel(3, 1, 0), channel(4, 99, 0)),
        )
        assertEquals(listOf(1L, 3L, 2L, 4L), result.map { it.first.id })
        assertEquals(listOf(0, 1, 0, 0), result.map { it.second })
    }
}
