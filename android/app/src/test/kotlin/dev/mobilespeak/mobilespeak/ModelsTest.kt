package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test fun fileCacheRequiresKnownIdleSizeAndAllowsZeroByteFiles() {
        assertEquals(false, FileCacheState().canClear)
        assertEquals(false, FileCacheState(bytes = 0, status = "ready").canClear)
        val cache = FileCacheState(bytes = 0, status = "ready", items = 1)
        assertEquals(true, cache.canClear)
        assertEquals(false, cache.copy(busy = true).canClear)
        for (status in listOf("loading", "clearing")) {
            assertEquals(false, cache.copy(status = status).canClear)
        }
        assertEquals(false, cache.copy(bytes = null).canClear)
        assertEquals(true, cache.copy(bytes = 6, status = "failed", error = "partial cleanup").canClear)
    }
    @Test fun networkNumbersKeepPrecisionAndDoNotOverflowAtLargeValues() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val q = NetworkQuality(rttMs = 1234.5, deviationMs = 123.4, packetLossPercent = 0.8)
            assertEquals("999", q.latencyText)
            assertEquals("123.4", q.deviationText)
            assertEquals("0.8", q.lossText)
            assertEquals("43", NetworkQuality.number(42.5, 0))
            assertEquals("6.1", NetworkQuality.number(6.05, 1))
            assertEquals("0.0", NetworkQuality.number(0.0, 1))
            assertEquals("—", NetworkQuality().latencyText)
            assertEquals("—", NetworkQuality.number(Double.NaN, 1))
            assertEquals("10000000000000000000000", NetworkQuality.number(1e22, 0))
            for ((value, expected) in listOf(998.49 to "998", 998.5 to "999", 999.5 to "999", 1234.0 to "999", 1e22 to "999")) {
                assertEquals(expected, NetworkQuality(rttMs = value).latencyText)
            }
            for ((value, expected) in listOf(999.84 to "999.8", 999.85 to "999.9", 999.95 to "999.9", 1234.0 to "999.9", 1e22 to "999.9")) {
                assertEquals(expected, NetworkQuality(deviationMs = value).deviationText)
            }
            assertEquals("—", NetworkQuality(rttMs = Double.POSITIVE_INFINITY).latencyText)
            assertEquals("—", NetworkQuality(deviationMs = Double.NaN).deviationText)
            assertEquals(1234.5, q.rttMs!!, 0.0)
            assertEquals(10.0, NetworkQuality().axisMaxMs, 0.0)
            for ((axis, middle) in listOf(10.0 to "5", 50.0 to "25", 100.0 to "50", 750.0 to "375", 1359.0 to "679.5")) {
                val quality = NetworkQuality(axisMaxMs = axis)
                assertEquals(NetworkQuality.number(axis, 0), quality.axisText)
                assertEquals(middle, quality.midAxisText)
            }
        } finally { java.util.Locale.setDefault(previous) }
    }
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
