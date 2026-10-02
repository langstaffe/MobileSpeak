package dev.mobilespeak.mobilespeak

// Only AudioEngine's playback management thread owns these handles.
internal object NativeOutput {
    init { System.loadLibrary("mobilespeak_output") }
    external fun open(core: Long, channels: Int, communication: Boolean, notification: Runnable): Long
    external fun close(handle: Long)
    external fun failed(handle: Long): Boolean
    external fun deviceId(handle: Long): Int
}
