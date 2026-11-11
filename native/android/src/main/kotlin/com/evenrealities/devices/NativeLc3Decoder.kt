package com.evenrealities.devices

/** One G2 stream's predictor history. Close before a replacement owner receives audio. */
internal class NativeLc3Decoder : AutoCloseable {
    private var handle = nativeCreate()

    /** Fixed 40-byte LC3 frames produce 160 little-endian signed 16-bit samples. */
    @Synchronized fun decode(frame: ByteArray): ByteArray? =
        if (handle == 0L || frame.size != 40) null else nativeDecode(handle, frame)

    /** Serialization prevents teardown racing a native decode on the same history. */
    @Synchronized override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(): Long
    private external fun nativeDecode(handle: Long, frame: ByteArray): ByteArray?
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("even_lc3") }
    }
}
