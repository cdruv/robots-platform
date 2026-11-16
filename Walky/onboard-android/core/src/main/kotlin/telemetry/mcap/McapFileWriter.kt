package com.vadymsidorov.walky.core.telemetry.mcap

import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Writes an indexed MCAP file: Magic, Header, then a data section of Schema, Channel and
 * Metadata records and uncompressed Chunks, each followed by its Message Indexes. [finish]
 * adds DataEnd, the summary (Schemas, Channels, Statistics, Chunk Indexes, Metadata Indexes),
 * the Summary Offsets, Footer and Magic.
 *
 * A file that is never finished still holds every chunk written so far; `mcap recover`
 * rebuilds its index. Not thread-safe.
 */
class McapFileWriter(
    output: OutputStream,
    library: String,
    private val chunkBytes: Int = 4 shl 20,
) {
    private val out = CountingOutput(output)
    private val schemas = ArrayList<ByteArray>()
    private val channels = ArrayList<ByteArray>()
    private val chunkIndexes = ArrayList<ByteArray>()
    private val metadataIndexes = ArrayList<ByteArray>()
    private val channelCounts = sortedMapOf<Int, Long>()
    private var messageCount = 0L
    private var startTime = Long.MAX_VALUE
    private var endTime = Long.MIN_VALUE

    private val chunk = RecordBuffer(chunkBytes + (chunkBytes shr 2))
    private val chunkIndex = sortedMapOf<Int, MutableList<LongArray>>()
    private var chunkStart = Long.MAX_VALUE
    private var chunkEnd = Long.MIN_VALUE
    private var finished = false

    init {
        out.write(Mcap.MAGIC)
        out.write(Mcap.header(profile = "", library = library))
    }

    /** Before any message on channels that use it. */
    fun schema(record: ByteArray) {
        out.write(record)
        schemas += record
    }

    /** Before any message on it. */
    fun channel(record: ByteArray) {
        out.write(record)
        channels += record
    }

    fun metadata(name: String, values: Map<String, String>) {
        val record = Mcap.metadata(name, values)
        metadataIndexes += Mcap.metadataIndex(out.count, record.size.toLong(), name)
        out.write(record)
    }

    /** [record] is a whole Message record ([Mcap.message]). */
    fun message(channelId: Int, logTime: Long, record: ByteArray) {
        chunkIndex.getOrPut(channelId) { ArrayList() } += longArrayOf(logTime, chunk.size.toLong())
        chunk.bytes(record)
        chunkStart = minOf(chunkStart, logTime)
        chunkEnd = maxOf(chunkEnd, logTime)
        messageCount++
        channelCounts[channelId] = (channelCounts[channelId] ?: 0) + 1
        startTime = minOf(startTime, logTime)
        endTime = maxOf(endTime, logTime)
        if (chunk.size >= chunkBytes) flushChunk()
    }

    /** Writes the messages buffered so far as one chunk, so they survive a crash. */
    fun flushChunk() {
        if (chunk.size == 0) return
        val chunkOffset = out.count
        val records = chunk.array
        val chunkRecord = Mcap.chunk(chunkStart, chunkEnd, records, records.size)
        out.write(chunkRecord)
        val indexOffsets = LinkedHashMap<Int, Long>()
        val indexStart = out.count
        for ((channel, entries) in chunkIndex) {
            indexOffsets[channel] = out.count
            // Readers expect each index in log-time order; messages may arrive slightly out of it.
            out.write(Mcap.messageIndex(channel, entries.sortedBy { it[0] }))
        }
        chunkIndexes += Mcap.chunkIndex(
            chunkStart, chunkEnd, chunkOffset, chunkRecord.size.toLong(),
            indexOffsets, out.count - indexStart, records.size.toLong(),
        )
        out.flush()
        chunk.reset()
        chunkIndex.clear()
        chunkStart = Long.MAX_VALUE
        chunkEnd = Long.MIN_VALUE
    }

    /** Completes the file and flushes it; the stream stays open. */
    fun finish() {
        if (finished) return
        finished = true
        flushChunk()
        out.write(Mcap.dataEnd(out.crc.value))

        val summaryStart = out.count
        out.crc.reset()
        val groups = ArrayList<ByteArray>()
        fun group(opcode: Int, records: List<ByteArray>) {
            if (records.isEmpty()) return
            val start = out.count
            records.forEach { out.write(it) }
            groups += Mcap.summaryOffset(opcode, start, out.count - start)
        }
        group(Mcap.SCHEMA, schemas)
        group(Mcap.CHANNEL, channels)
        group(Mcap.STATISTICS, listOf(statistics()))
        group(Mcap.CHUNK_INDEX, chunkIndexes)
        group(Mcap.METADATA_INDEX, metadataIndexes)
        val summaryOffsetStart = out.count
        groups.forEach { out.write(it) }
        out.write(Mcap.footerPrefix(summaryStart, summaryOffsetStart))
        out.write(Mcap.crcBytes(out.crc.value))
        out.write(Mcap.MAGIC)
        out.flush()
    }

    private fun statistics() = Mcap.statistics(
        messageCount = messageCount,
        schemaCount = schemas.size,
        channelCount = channels.size,
        metadataCount = metadataIndexes.size,
        chunkCount = chunkIndexes.size,
        startTime = if (messageCount == 0L) 0 else startTime,
        endTime = if (messageCount == 0L) 0 else endTime,
        channelCounts = channelCounts,
    )

    private class CountingOutput(private val out: OutputStream) {
        val crc = CRC32()
        var count = 0L
            private set

        fun write(bytes: ByteArray) {
            out.write(bytes)
            crc.update(bytes)
            count += bytes.size
        }

        fun flush() = out.flush()
    }
}
