package com.vadymsidorov.yobot.core.telemetry

import com.vadymsidorov.yobot.core.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured event bus for observability. [emit] never blocks: events go into a bounded
 * queue (oldest dropped on overflow) and one worker batches them out to every sink.
 */
class Telemetry(
    sinks: List<TelemetrySink>,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBatch: Int = 100,
    private val maxBatchDelayMs: Long = 250,
    queueCapacity: Int = 2048,
    sinkQueueCapacity: Int = 64,
) {
    private val seq = AtomicLong(0)
    private val queueDropped = AtomicLong(0)
    private val queue = Channel<TelemetryEvent>(
        capacity = queueCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { queueDropped.incrementAndGet() },
    )
    private val workers = sinks.map { SinkWorker(it, sinkQueueCapacity) }

    fun emit(source: String, kind: String, payload: JsonElement = JsonNull) {
        queue.trySend(TelemetryEvent(seq.incrementAndGet(), clock.nowMs(), clock.monoNs(), source, kind, payload))
    }

    fun <T> emit(source: String, kind: String, serializer: KSerializer<T>, value: T) {
        emit(source, kind, YobotJson.encodeToJsonElement(serializer, value))
    }

    fun logger(source: String) = Logger(this, source)

    fun start() {
        workers.forEach { worker -> scope.launch(dispatcher) { worker.run() } }
        scope.launch(dispatcher) { dispatchBatches() }
        scope.launch(dispatcher) { reportDrops() }
    }

    private suspend fun dispatchBatches() {
        val batch = ArrayList<TelemetryEvent>(maxBatch)
        while (scope.isActive) {
            batch += queue.receive()
            withTimeoutOrNull(maxBatchDelayMs) {
                while (batch.size < maxBatch) batch += queue.receive()
            }
            val snapshot = batch.toList()
            batch.clear()
            workers.forEach { it.offer(snapshot) }
        }
    }

    private suspend fun reportDrops() {
        while (scope.isActive) {
            delay(1_000)
            val queued = queueDropped.getAndSet(0)
            val perSink = workers.associate { it.sink.name to it.takeDropped() }.filterValues { it > 0 }
            if (queued > 0 || perSink.isNotEmpty()) {
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

/** Free-form log lines, routed through telemetry so there is one logging path. */
class Logger(private val telemetry: Telemetry, private val source: String) {
    fun info(message: String) = log("info", message, null)
    fun warn(message: String, error: Throwable? = null) = log("warn", message, error)
    fun error(message: String, error: Throwable? = null) = log("error", message, error)

    private fun log(level: String, message: String, error: Throwable?) {
        telemetry.emit(source, "log.$level", buildJsonObject {
            put("msg", message)
            if (error != null) put("error", JsonPrimitive(error.toString()))
        })
    }
}
