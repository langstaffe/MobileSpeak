package dev.mobilespeak.mobilespeak

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCoreTest {
    @Test
    fun jniStartsAndMovesUtf8AndExactAudioFrames() {
        val handle = NativeCore.create()
        assertTrue(handle != 0L)
        try {
            val initial = JSONObject(String(NativeCore.poll(handle), Charsets.UTF_8))
            assertEquals("disconnected", initial.getJSONObject("snapshot").getString("status"))
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
