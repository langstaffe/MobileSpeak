package dev.mobilespeak.mobilespeak

import android.content.Context

internal object NativeCore {
    init {
        System.loadLibrary("mobilespeak_core")
    }

    external fun create(context: Context): Long
    external fun destroy(handle: Long)
    external fun setNotifier(handle: Long, callback: Runnable)
    external fun command(handle: Long, json: ByteArray): Int
    external fun poll(handle: Long): ByteArray
    external fun capture(handle: Long, samples: ShortArray): Int
}
