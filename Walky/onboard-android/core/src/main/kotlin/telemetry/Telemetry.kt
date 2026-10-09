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
import java.util.concurrent.atomic.AtomicLong

/** Structured observability: every event and log line goes through one of these. */
interface Telemetry {
    fun start()
    fun stop()
    fun emit(source: String, kind: String, payload: JsonElement = JsonNull)
    fun <T> emit(source: String, kind: String, serializer: KSerializer<T>, value: T)
    fun logger(source: String): Logger
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

fun TelemetryEvent.toJsonLine(): String = WalkyJson.encodeToString(TelemetryEvent.serializer(), this)

/**
 * [emit] never blocks: events enter a bounded queue (oldest dropped on overflow) and one
 * dispatcher coroutine batches them out to a worker per sink. Drops are reported once a
 * second as a `telemetry/dropped` event and accumulated in [droppedTotal].
 */
class DefaultTelemetry(
    sinks: List<TelemetrySink>,
    private val scope: CoroutineScope,
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

    /** Events lost anywhere (queue or sinks) since start. */
    val droppedTotal: Long get() = dropped.get()

    override fun emit(source: String, kind: String, payload: JsonElement) {
        queue.trySend(TelemetryEvent(seq.incrementAndGet(), nowMs(), monoNs(), source, kind, payload))
    }

    override fun <T> emit(source: String, kind: String, serializer: KSerializer<T>, value: T) {
        emit(source, kind, WalkyJson.encodeToJsonElement(serializer, value))
    }

    override fun logger(source: String): Logger = TelemetryLogger(this, source)

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
            val perSink = workers.associate { it.sink.name to it.takeDropped() }.filterValues { it > 0 }
            if (queued > 0 || perSink.isNotEmpty()) {
                dropped.addAndGet(queued + perSink.values.sum())
                emit(SOURCE, "dropped", buildJsonObject {
                    put("queue", queued)
                    put("sinks", buildJsonObject { perSink.forEach { (name, n) -> put(name, n) } })
                })
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
