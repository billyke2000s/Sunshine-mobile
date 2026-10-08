package dev.sunshinemobile

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Boundary between MediaCodec capture and a future authenticated GameStream session.
 * No network socket is opened by this class.
 */
class EncodedVideoPipeline {
    data class AccessUnit(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val keyFrame: Boolean,
        val codecConfig: Boolean
    )

    @Volatile var sink: ((AccessUnit) -> Unit)? = null
    val framesEncoded = AtomicLong(0)
    val bytesEncoded = AtomicLong(0)
    @Volatile var outputFormat: MediaFormat? = null
        private set

    fun onFormatChanged(format: MediaFormat) {
        outputFormat = format
    }

    fun onEncodedBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        val view = buffer.duplicate()
        view.position(info.offset)
        view.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        view.get(bytes)
        val unit = AccessUnit(
            bytes,
            info.presentationTimeUs,
            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        )
        bytesEncoded.addAndGet(bytes.size.toLong())
        if (!unit.codecConfig) framesEncoded.incrementAndGet()
        sink?.invoke(unit)
    }
}
