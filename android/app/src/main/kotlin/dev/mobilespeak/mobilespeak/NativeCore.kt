package dev.mobilespeak.mobilespeak

internal object NativeCore {
    init {
        System.loadLibrary("mobilespeak_core")
    }

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun setNotifier(handle: Long, callback: Runnable)
    external fun command(handle: Long, json: ByteArray): Int
    external fun poll(handle: Long): ByteArray
    external fun capture(handle: Long, samples: ShortArray): Int
    external fun playback(handle: Long, samples: FloatArray): Int
}
