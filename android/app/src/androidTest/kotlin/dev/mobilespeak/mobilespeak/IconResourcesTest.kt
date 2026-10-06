package dev.mobilespeak.mobilespeak

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class IconResourcesTest {
    @Test fun vectorResourcesRenderAndAcceptStateTintExceptOff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icons = listOf(R.drawable.ic_avatar_placeholder, R.drawable.ic_channel_chat,
            R.drawable.ic_channels, R.drawable.ic_checkmark, R.drawable.ic_chevron_right,
            R.drawable.ic_error, R.drawable.ic_headphones, R.drawable.ic_lock,
            R.drawable.ic_members, R.drawable.ic_mic_off, R.drawable.ic_mic_on,
            R.drawable.ic_more, R.drawable.ic_off, R.drawable.ic_plus, R.drawable.ic_send,
            R.drawable.ic_settings, R.drawable.ic_speaker_off, R.drawable.ic_speaker_on,
            R.drawable.ic_waveform, R.drawable.ic_pencil, R.drawable.ic_trash,
            R.drawable.ic_folder, R.drawable.ic_file, R.drawable.ic_file_text, R.drawable.ic_file_image,
            R.drawable.ic_file_audio, R.drawable.ic_file_archive, R.drawable.ic_upload, R.drawable.ic_download,
            R.drawable.ic_sort, R.drawable.ic_refresh, R.drawable.ic_info, R.drawable.ic_share)
        for (id in icons) {
            val off = id == R.drawable.ic_off
            val size = if (off) 34 else 24
            for (tint in listOf(0xFF23A559.toInt(), 0xFF949BA4.toInt(), 0xFFC83F4A.toInt())) {
                val drawable = requireNotNull(context.getDrawable(id)).mutate()
                if (!off) drawable.setTint(tint)
                val bitmap = Bitmap.createBitmap(size * 4, size * 4, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val solid = pixels.filter { Color.alpha(it) == 255 }.toSet()
                if (off) {
                    assertTrue(solid.contains(0xFFC83F4A.toInt()))
                    assertTrue(solid.contains(0xFFF2F3F5.toInt()))
                } else {
                    assertEquals("Resource $id must use the supplied tint", setOf(tint), solid)
                    assertTrue((0 until bitmap.width).all { x ->
                        Color.alpha(bitmap.getPixel(x, 0)) == 0 && Color.alpha(bitmap.getPixel(x, bitmap.height - 1)) == 0
                    })
                    assertTrue((0 until bitmap.height).all { y ->
                        Color.alpha(bitmap.getPixel(0, y)) == 0 && Color.alpha(bitmap.getPixel(bitmap.width - 1, y)) == 0
                    })
                }
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                bitmap.recycle()
            }
        }
    }
}
