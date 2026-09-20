package dev.mobilespeak.mobilespeak

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeCoreTest {
    @Test
    fun jniStartsAndMovesUtf8AndExactAudioFrames() {
        val handle = NativeCore.create()
        assertTrue(handle != 0L)
        try {
            val initial = JSONObject(String(NativeCore.poll(handle), Charsets.UTF_8))
            val snapshot = initial.getJSONObject("snapshot")
            assertEquals("disconnected", snapshot.getString("status"))
            assertTrue(snapshot.getJSONArray("channels").length() == 0)
            assertTrue(snapshot.getJSONArray("clients").length() == 0)
            assertTrue(initial.getJSONArray("events").length() == 0)
            assertTrue(initial.isNull("chats"))
            val notified = CountDownLatch(1)
            NativeCore.setNotifier(handle, Runnable { notified.countDown() })
            assertEquals(-1, NativeCore.command(handle, "not json".toByteArray()))
            assertTrue(notified.await(1, TimeUnit.SECONDS))
            val command = """{"type":"connect","address":"127.0.0.1:1","name":"测试😀","password":"","identity":null}"""
            assertEquals(0, NativeCore.command(handle, command.toByteArray(Charsets.UTF_8)))
            assertEquals(0, NativeCore.capture(handle, ShortArray(960)))
            assertTrue(NativeCore.playback(handle, FloatArray(1920)) in 0..1920)
            NativeCore.command(handle, """{"type":"disconnect"}""".toByteArray())
        } finally {
            NativeCore.destroy(handle)
        }
    }
}
