package com.vadymsidorov.walky.core.telemetry

import com.vadymsidorov.walky.core.telemetry.mcap.RecordBuffer

/**
 * One encoded video frame, sent as Foxglove's `foxglove.CompressedVideo` protobuf: for h264,
 * [data] is one access unit in Annex B, and a [keyframe] carries SPS and PPS so it decodes on
 * its own.
 */
class VideoFrame(
    val timestampNs: Long,
    val data: ByteArray,
    val keyframe: Boolean,
    val frameId: String,
    val format: String = "h264",
) {
    /** Hand-written protobuf: the four fields of `CompressedVideo.proto` in `resources/foxglove`. */
    fun encode(): ByteArray {
        val timestamp = RecordBuffer(16).apply {
            val seconds = Math.floorDiv(timestampNs, 1_000_000_000L)
            val nanos = Math.floorMod(timestampNs, 1_000_000_000L)
            if (seconds != 0L) { tag(1, VARINT); varint(seconds) }
            if (nanos != 0L) { tag(2, VARINT); varint(nanos) }
        }.array
        val frameId = frameId.toByteArray(Charsets.UTF_8)
        val format = format.toByteArray(Charsets.UTF_8)
        return RecordBuffer(data.size + frameId.size + format.size + 40).apply {
            field(1, timestamp)
            if (frameId.isNotEmpty()) field(2, frameId)
            if (data.isNotEmpty()) field(3, data)
            if (format.isNotEmpty()) field(4, format)
        }.array
    }

    private companion object {
        const val VARINT = 0
        const val LENGTH_DELIMITED = 2

        fun RecordBuffer.tag(field: Int, wireType: Int) = varint((field shl 3 or wireType).toLong())

        fun RecordBuffer.varint(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                u8(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            u8(v.toInt())
        }

        fun RecordBuffer.field(number: Int, bytes: ByteArray) {
            tag(number, LENGTH_DELIMITED)
            varint(bytes.size.toLong())
            bytes(bytes)
        }
    }
}
