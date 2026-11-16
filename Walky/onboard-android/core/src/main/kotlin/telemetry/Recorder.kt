package com.vadymsidorov.walky.core.telemetry

import com.vadymsidorov.walky.core.telemetry.mcap.McapFileWriter
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Records every channel message of one session into one MCAP file: the replay source of
 * truth. [write] only queues; one writer thread opens the file with [open], writes schemas,
 * channels and a `session` Metadata record holding [metadata], then chunks of messages.
 *
 * The queue holds up to [budgetBytes]. It should never fill; if it does, or the file fails,
 * messages are counted as drops, which [DefaultTelemetry] logs as errors. Chunks are written
 * when full or [flushIntervalMs] after the last one, so a crash loses at most that much.
 * [close] finishes the file with its index on the writer thread and returns at once.
 */
class Recorder(
    private val open: () -> OutputStream,
    private val metadata: Map<String, String>,
    private val log: () -> Logger,
    private val catalog: Catalog = Topics.catalog,
    private val budgetBytes: Long = 64L shl 20,
    private val chunkBytes: Int = 4 shl 20,
    private val flushIntervalMs: Long = 2_000,
) : StreamSink {
    override val name = "recording"
    override val lossless = true

    private val queue = LinkedBlockingQueue<StreamRecord>()
    private val queuedBytes = AtomicLong(0)
    private val dropped = AtomicLong(0)
    @Volatile private var closed = false
    @Volatile private var failed = false
    private var writer: Thread? = null

    @Synchronized
    fun start() {
        if (writer != null || closed) return
        writer = thread(name = "walky-recorder") { run() }
    }

    override fun write(record: StreamRecord) {
        if (closed) return
        val size = record.bytes.size.toLong()
        if (failed || queuedBytes.addAndGet(size) > budgetBytes) {
            if (!failed) queuedBytes.addAndGet(-size)
            dropped.incrementAndGet()
            return
        }
        queue.add(record)
    }

    override fun takeDropped(): Long = dropped.getAndSet(0)

    /** Stops taking messages; those queued are still written, then the file is finished and closed. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        if (writer == null) queue.clear() else queue.add(END)
    }

    /** Waits for the file to be finished, for tests and shutdown. */
    fun join(timeoutMs: Long): Boolean {
        val w = synchronized(this) { writer } ?: return true
        w.join(timeoutMs)
        return !w.isAlive
    }

    private fun run() {
        var stream: OutputStream? = null
        try {
            stream = BufferedOutputStream(open(), 1 shl 16)
            val file = McapFileWriter(stream, library = "walky", chunkBytes = chunkBytes)
            catalog.schemaRecords.forEach(file::schema)
            catalog.channelRecords.forEach(file::channel)
            file.metadata("session", metadata)
            var lastFlush = System.nanoTime()
            while (true) {
                val record = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS)
                if (record === END) break
                if (record != null) {
                    queuedBytes.addAndGet(-record.bytes.size.toLong())
                    file.message(record.channelId, record.logTimeNs, record.bytes)
                }
                if (System.nanoTime() - lastFlush >= flushIntervalMs * 1_000_000) {
                    file.flushChunk()
                    lastFlush = System.nanoTime()
                }
            }
            file.finish()
        } catch (e: Exception) {
            fail(e)
        } finally {
            runCatching { stream?.close() }
        }
    }

    private fun fail(error: Exception) {
        failed = true
        val lost = queue.size.toLong()
        queue.clear()
        dropped.addAndGet(lost)
        log().error("recording stopped", error)
    }

    private companion object {
        val END = StreamRecord(0, Topics.Events, 0, ByteArray(0))
    }
}
