package com.vadymsidorov.walky.core.telemetry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.vadymsidorov.walky.core.telemetry.mcap.Mcap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured observability: every event, log line and sensor stream goes through one of these.
 *
 * Events ([emit]) and logs ([logger]) are [TelemetryEvent]s on the `/events` channel; a log is
 * an event written through [logger], and [isLog] is the single definition. Log views (debug
 * overlay, logcat, Relay's Logs tab) show only logs. Streams and state go on their own
 * channels ([channel]), all defined in [Topics].
 */
interface Telemetry {
    fun start()
    fun stop()
    fun emit(source: String, kind: String, payload: JsonElement = JsonNull)
    fun <T> emit(source: String, kind: String, serializer: KSerializer<T>, value: T)
    fun logger(source: String): Logger
    fun <T> channel(topic: Topic<T>): TelemetryChannel<T>
}

interface Logger {
    fun info(message: String)
    fun warn(message: String, error: Throwable? = null)
    fun error(message: String, error: Throwable? = null)
}

@Serializable
data class TelemetryEvent(
    val seq: Long,
    val tsWallMs: Long,
    val tsMonoNs: Long,
    val source: String,
    val kind: String,
    val payload: JsonElement,
)

/**
 * A telemetry destination. Each sink runs on its own worker, so a slow or blocking [write]
 * only delays that sink. Exceptions thrown by [write] are caught and counted as drops.
 */
interface TelemetrySink {
    val name: String
    fun write(batch: List<TelemetryEvent>)

    /** Events this sink discarded since the last call. */
    fun takeDropped(): Long = 0
    fun close() {}
}

/** The one JSON configuration for telemetry lines and serialized events. */
val WalkyJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

val TelemetryEvent.isLog: Boolean get() = kind.startsWith("log.")

fun TelemetryEvent.toJsonLine(): String = WalkyJson.encodeToString(TelemetryEvent.serializer(), this)

/**
 * Nothing here blocks the caller. Each channel message, events included, is encoded once as
 * an MCAP record and handed to every [StreamSink] on the caller's thread. Events also enter a
 * bounded queue (oldest dropped on overflow) and one dispatcher coroutine batches them out to
 * a worker per [TelemetrySink]. Drops are reported once a second as a `telemetry/dropped`
 * event and accumulated in [droppedTotal]; drops in a lossless stream sink are logged as errors.
 *
 * [monoNs] is the clock of every timestamp; the phone passes boottime.
 */
class DefaultTelemetry(
    sinks: List<TelemetrySink>,
    private val scope: CoroutineScope,
    private val streams: List<StreamSink> = emptyList(),
    private val catalog: Catalog = Topics.catalog,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val monoNs: () -> Long = System::nanoTime,
    private val maxBatch: Int = 100,
    private val maxBatchDelayMs: Long = 250,
    queueCapacity: Int = 4096,
    sinkQueueCapacity: Int = 64,
) : Telemetry {
    private val seq = AtomicLong(0)
    private val queueDropped = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val queue = Channel<TelemetryEvent>(
        capacity = queueCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { queueDropped.incrementAndGet() },
    )
    private val workers = sinks.map { SinkWorker(it, sinkQueueCapacity) }
    private var jobs: List<Job> = emptyList()
    private val channels = ConcurrentHashMap<Topic<*>, TelemetryChannel<*>>()
    private val streamDropped = streams.associate { it.name to AtomicLong(0) }
    private val events = channel(Topics.Events)
    private val dropLog = logger(SOURCE)

    /** Events lost anywhere (queue or sinks) since start. */
    val droppedTotal: Long get() = dropped.get()

    override fun emit(source: String, kind: String, payload: JsonElement) {
        val event = TelemetryEvent(seq.incrementAndGet(), nowMs(), monoNs(), source, kind, payload)
        events.write(event.tsMonoNs, event)
        queue.trySend(event)
    }

    override fun <T> emit(source: String, kind: String, serializer: KSerializer<T>, value: T) {
        emit(source, kind, WalkyJson.encodeToJsonElement(serializer, value))
    }

    override fun logger(source: String): Logger = TelemetryLogger(this, source)

    @Suppress("UNCHECKED_CAST")
    override fun <T> channel(topic: Topic<T>): TelemetryChannel<T> =
        channels.getOrPut(topic) { StreamChannel(topic, catalog.id(topic)) } as TelemetryChannel<T>

    override fun start() {
        if (jobs.isNotEmpty()) return
        jobs = workers.map { worker -> scope.launch(dispatcher) { worker.run() } } +
            scope.launch(dispatcher) { dispatchBatches() } +
            scope.launch(dispatcher) { reportDrops() }
    }

    /** Stops accepting events; queued events are flushed and every sink is closed. */
    override fun stop() {
        queue.close()
    }

    private suspend fun dispatchBatches() {
        val batch = ArrayList<TelemetryEvent>(maxBatch)
        try {
            while (true) {
                batch += queue.receive()
                withTimeoutOrNull(maxBatchDelayMs) {
                    while (batch.size < maxBatch) batch += queue.receive()
                }
                flush(batch)
            }
        } catch (_: ClosedReceiveChannelException) {
            flush(batch)
        } finally {
            workers.forEach { it.close() }
        }
    }

    private fun flush(batch: MutableList<TelemetryEvent>) {
        if (batch.isEmpty()) return
        val snapshot = batch.toList()
        batch.clear()
        workers.forEach { it.offer(snapshot) }
    }

    private suspend fun reportDrops() {
        while (true) {
            delay(1_000)
            val queued = queueDropped.getAndSet(0)
            val perSink = (workers.associate { it.sink.name to it.takeDropped() } +
                streams.associate { it.name to it.takeDropped() + streamDropped.getValue(it.name).getAndSet(0) })
                .filterValues { it > 0 }
            streams.filter { it.lossless && (perSink[it.name] ?: 0) > 0 }.forEach {
                dropLog.error("${it.name} lost ${perSink[it.name]} messages")
            }
            if (queued > 0 || perSink.isNotEmpty()) {
                dropped.addAndGet(queued + perSink.values.sum())
                emit(SOURCE, "dropped", buildJsonObject {
                    put("queue", queued)
                    put("sinks", buildJsonObject { perSink.forEach { (name, n) -> put(name, n) } })
                })
                dropLog.warn("dropped events: queue=$queued" + perSink.entries.joinToString("") { ", ${it.key}=${it.value}" })
            }
        }
    }

    /** Owns one sink: its own queue and loop, so a failing or slow sink never blocks the others. */
    private inner class SinkWorker(val sink: TelemetrySink, capacity: Int) {
        private val dropped = AtomicLong(0)
        private val batches = Channel<List<TelemetryEvent>>(
            capacity = capacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { dropped.addAndGet(it.size.toLong()) },
        )

        fun offer(batch: List<TelemetryEvent>) {
            batches.trySend(batch)
        }

        fun close() {
            batches.close()
        }

        fun takeDropped(): Long = dropped.getAndSet(0) + sink.takeDropped()

        suspend fun run() {
            try {
                for (batch in batches) {
                    try {
                        sink.write(batch)
                    } catch (e: Exception) {
                        dropped.addAndGet(batch.size.toLong())
                    }
                }
            } finally {
                runCatching { sink.close() }
            }
        }
    }

    private inner class StreamChannel<T>(private val topic: Topic<T>, private val id: Int) : TelemetryChannel<T> {
        private val sequence = AtomicLong(0)

        override fun write(value: T) = write(monoNs(), value)

        override fun write(logTimeNs: Long, value: T) {
            if (streams.isEmpty()) return
            val record = try {
                StreamRecord(
                    channelId = id,
                    topic = topic,
                    logTimeNs = logTimeNs,
                    bytes = Mcap.message(id, sequence.incrementAndGet() and 0xFFFF_FFFFL, logTimeNs, monoNs(), topic.encode(value)),
                    keyframe = topic.keyframe?.invoke(value) ?: false,
                )
            } catch (e: Exception) {
                streams.forEach { streamDropped.getValue(it.name).incrementAndGet() }
                return
            }
            for (stream in streams) {
                try {
                    stream.write(record)
                } catch (e: Exception) {
                    streamDropped.getValue(stream.name).incrementAndGet()
                }
            }
        }
    }

    companion object {
        const val SOURCE = "telemetry"
    }
}

/** Free-form log lines routed through telemetry as `log.info|warn|error` events. */
private class TelemetryLogger(private val telemetry: Telemetry, private val source: String) : Logger {
    override fun info(message: String) = log("info", message, null)
    override fun warn(message: String, error: Throwable?) = log("warn", message, error)
    override fun error(message: String, error: Throwable?) = log("error", message, error)

    private fun log(level: String, message: String, error: Throwable?) {
        telemetry.emit(source, "log.$level", buildJsonObject {
            put("msg", message)
            if (error != null) put("error", error.toString())
        })
    }
}
