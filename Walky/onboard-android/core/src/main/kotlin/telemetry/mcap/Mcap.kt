package com.vadymsidorov.walky.core.telemetry.mcap

import java.util.zip.CRC32

/**
 * MCAP record encoding (https://mcap.dev/spec). Each function returns one whole record:
 * opcode, uint64 content length, content; all integers little-endian.
 */
object Mcap {
    val MAGIC = byteArrayOf(0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 0x30, '\r'.code.toByte(), '\n'.code.toByte())

    const val HEADER: Int = 0x01
    const val FOOTER: Int = 0x02
    const val SCHEMA: Int = 0x03
    const val CHANNEL: Int = 0x04
    const val MESSAGE: Int = 0x05
    const val CHUNK: Int = 0x06
    const val MESSAGE_INDEX: Int = 0x07
    const val CHUNK_INDEX: Int = 0x08
    const val STATISTICS: Int = 0x0B
    const val METADATA: Int = 0x0C
    const val METADATA_INDEX: Int = 0x0D
    const val SUMMARY_OFFSET: Int = 0x0E
    const val DATA_END: Int = 0x0F

    /** Opcode and length prefix. */
    const val RECORD_PREFIX = 9
    /** Bytes in a Message record before its data. */
    const val MESSAGE_PREFIX = RECORD_PREFIX + 2 + 4 + 8 + 8

    fun header(profile: String, library: String) = record(HEADER) { string(profile); string(library) }

    fun schema(id: Int, name: String, encoding: String, data: ByteArray) = record(SCHEMA) {
        u16(id); string(name); string(encoding); u32(data.size.toLong()); bytes(data)
    }

    fun channel(id: Int, schemaId: Int, topic: String, messageEncoding: String, metadata: Map<String, String>) = record(CHANNEL) {
        u16(id); u16(schemaId); string(topic); string(messageEncoding); stringMap(metadata)
    }

    fun message(channelId: Int, sequence: Long, logTime: Long, publishTime: Long, data: ByteArray): ByteArray {
        val out = RecordBuffer(MESSAGE_PREFIX + data.size)
        out.u8(MESSAGE); out.u64((MESSAGE_PREFIX - RECORD_PREFIX + data.size).toLong())
        out.u16(channelId); out.u32(sequence); out.u64(logTime); out.u64(publishTime); out.bytes(data)
        return out.array
    }

    fun metadata(name: String, metadata: Map<String, String>) = record(METADATA) { string(name); stringMap(metadata) }

    fun chunk(startTime: Long, endTime: Long, records: ByteArray, recordsLength: Int): ByteArray {
        val crc = CRC32().apply { update(records, 0, recordsLength) }.value
        return record(CHUNK) {
            u64(startTime); u64(endTime); u64(recordsLength.toLong()); u32(crc); string("")
            u64(recordsLength.toLong()); bytes(records, recordsLength)
        }
    }

    /** [entries]: log time and offset in the chunk's records, per message. */
    fun messageIndex(channelId: Int, entries: List<LongArray>) = record(MESSAGE_INDEX) {
        u16(channelId); u32(entries.size * 16L)
        entries.forEach { u64(it[0]); u64(it[1]) }
    }

    fun chunkIndex(
        startTime: Long, endTime: Long, chunkOffset: Long, chunkLength: Long,
        messageIndexOffsets: Map<Int, Long>, messageIndexLength: Long, recordsSize: Long,
    ) = record(CHUNK_INDEX) {
        u64(startTime); u64(endTime); u64(chunkOffset); u64(chunkLength)
        u32(messageIndexOffsets.size * 10L)
        messageIndexOffsets.forEach { (channel, offset) -> u16(channel); u64(offset) }
        u64(messageIndexLength); string(""); u64(recordsSize); u64(recordsSize)
    }

    fun statistics(
        messageCount: Long, schemaCount: Int, channelCount: Int, metadataCount: Int, chunkCount: Int,
        startTime: Long, endTime: Long, channelCounts: Map<Int, Long>,
    ) = record(STATISTICS) {
        u64(messageCount); u16(schemaCount); u32(channelCount.toLong()); u32(0); u32(metadataCount.toLong())
        u32(chunkCount.toLong()); u64(startTime); u64(endTime)
        u32(channelCounts.size * 10L)
        channelCounts.forEach { (channel, count) -> u16(channel); u64(count) }
    }

    fun metadataIndex(offset: Long, length: Long, name: String) = record(METADATA_INDEX) { u64(offset); u64(length); string(name) }

    fun summaryOffset(opcode: Int, start: Long, length: Long) = record(SUMMARY_OFFSET) { u8(opcode); u64(start); u64(length) }

    fun dataEnd(crc: Long) = record(DATA_END) { u32(crc) }

    /** Without its CRC field: the summary CRC covers the footer up to that field. */
    fun footerPrefix(summaryStart: Long, summaryOffsetStart: Long) = RecordBuffer(25).apply {
        u8(FOOTER); u64(20); u64(summaryStart); u64(summaryOffsetStart)
    }.array

    fun crcBytes(crc: Long) = RecordBuffer(4).apply { u32(crc) }.array

    private inline fun record(opcode: Int, content: RecordBuffer.() -> Unit): ByteArray {
        val body = RecordBuffer(64).apply(content)
        val out = RecordBuffer(RECORD_PREFIX + body.size)
        out.u8(opcode); out.u64(body.size.toLong()); out.bytes(body.array, body.size)
        return out.array
    }
}

/** A growable little-endian byte buffer. [array] is trimmed to [size]. */
class RecordBuffer(capacity: Int) {
    private var buffer = ByteArray(maxOf(capacity, 16))
    var size = 0
        private set

    val array: ByteArray get() = if (size == buffer.size) buffer else buffer.copyOf(size)

    /** Empties the buffer and keeps its capacity. */
    fun reset() {
        size = 0
    }

    fun u8(value: Int) {
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun u16(value: Int) = le(value.toLong(), 2)
    fun u32(value: Long) = le(value, 4)
    fun u64(value: Long) = le(value, 8)

    fun string(value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        u32(utf8.size.toLong())
        bytes(utf8)
    }

    fun stringMap(map: Map<String, String>) {
        val entries = RecordBuffer(64)
        map.forEach { (key, value) -> entries.string(key); entries.string(value) }
        u32(entries.size.toLong())
        bytes(entries.buffer, entries.size)
    }

    fun bytes(value: ByteArray, length: Int = value.size) {
        ensure(length)
        System.arraycopy(value, 0, buffer, size, length)
        size += length
    }

    private fun le(value: Long, width: Int) {
        ensure(width)
        for (i in 0 until width) buffer[size++] = (value ushr (8 * i)).toByte()
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }
}
