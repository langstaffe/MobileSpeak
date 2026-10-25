package dev.mobilespeak.mobilespeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test fun avatarPriorityAndLiveIdentityNeverUseSavedStateOrNicknames() {
        val saved = Member(7, 9, "stable", "Same name", null, emptyList(), emptyList(), null, true, true, false, away = true)
        for (away in listOf(false, true)) for (deafened in listOf(false, true)) for (muted in listOf(false, true)) {
            val member = saved.copy(away = away, deafened = deafened, muted = muted)
            assertEquals(if (away) "afk" else if (deafened) "speaker-off" else if (muted) "mic-off" else null, member.statusIcon)
        }
        var snapshot = Snapshot(status = "connected", clients = listOf(saved, saved.copy(id = 8, uid = "another")))
        assertEquals(saved, snapshot.onlineMember("stable"))
        snapshot = snapshot.copy(clients = listOf(saved.copy(away = false, deafened = false, muted = false)))
        assertEquals(null, snapshot.onlineMember("stable")?.statusIcon)
        assertEquals(null, snapshot.copy(clients = emptyList()).onlineMember(saved.uid))
        assertEquals(null, snapshot.onlineMember("unknown"))
        assertEquals(null, snapshot.onlineMember(null))
        assertEquals(null, snapshot.onlineMember(""))
        assertEquals(null, snapshot.copy(status = "reconnecting").onlineMember(saved.uid))
        assertEquals(null, snapshot.copy(status = "disconnected").onlineMember(saved.uid))
    }
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
            java.util.Locale.setDefault(java.util.Locale.ROOT)
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
        val bookmark = Bookmark.create(null, "", " [2001:DB8::1] ", "9987", "secret")
        assertEquals("2001:db8::1", bookmark.host)
        assertEquals("[2001:db8::1]:9987", bookmark.address)
        assertThrows(IllegalArgumentException::class.java) {
            Bookmark.create(null, "", "bad host", "9987", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Bookmark.create(null, "", "host", "0", "")
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

class SessionOrderingTest {
    @Test fun connectionGateRejectsOldQueuedAndCancelledOutputs() {
        val gate = ConnectionGate()
        assertEquals(true, gate.accepts(null, "disconnected"))
        gate.begin("first")
        assertEquals(false, gate.accepts(null, "disconnected")) // command queued, Rust not processing yet
        assertEquals(true, gate.accepts("first", "connecting"))
        gate.cancel()
        assertEquals(false, gate.accepts("first", "connected"))
        gate.begin("second")
        for (status in listOf("disconnected", "connecting", "connected")) assertEquals(false, gate.accepts("first", status))
        for (status in listOf("connecting", "connected", "reconnecting", "disconnected")) assertEquals(true, gate.accepts("second", status))
        assertEquals(false, gate.accepts("first")) // old error
        assertEquals(true, gate.accepts("second"))
    }

    @Test fun bookmarkTransactionsSerializeReadComputeSavePublishAndKeepFailuresAtomic() {
        val original = Bookmark.create("a", "", "host", "9987", "password")
        var disk = listOf(original)
        var memory = disk
        val saving = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var blockSave = true
        var failSave = false
        val transactions = BookmarkTransactions({ disk }, { next ->
            if (blockSave) { saving.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); blockSave = false }
            check(!failSave) { "disk failure" }
            disk = next
        }, { memory = it })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val automatic = pool.submit { transactions.change { latest -> latest.map { it.copy(title = "Server") } to Unit } }
            check(saving.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deletionStarted = java.util.concurrent.CountDownLatch(1)
            val deletion = pool.submit { deletionStarted.countDown(); transactions.change { latest -> latest.filterNot { it.id == "a" } to Unit } }
            check(deletionStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown(); automatic.get(); deletion.get()
            assertEquals(emptyList<Bookmark>(), disk); assertEquals(disk, memory)
            transactions.change { latest -> (latest + original) to Unit }
            transactions.change { latest -> latest.map { it.copy(title = "Manual", automaticallyNamed = false, password = "edited") } to Unit }
            transactions.change { latest -> latest.map { if (it.automaticallyNamed) it.copy(title = "Auto") else it } to Unit }
            val second = original.copy(id = "b", host = "second")
            transactions.change { latest -> (latest + second) to Unit }
            assertEquals(listOf("Manual", original.title), disk.map { it.title })
            assertEquals("edited", disk.first().password)
            val before = disk
            failSave = true
            assertThrows(IllegalStateException::class.java) { transactions.change { emptyList<Bookmark>() to Unit } }
            assertEquals(before, disk); assertEquals(before, memory)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
