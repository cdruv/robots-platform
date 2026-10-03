package com.vadymsidorov.yobot.core.telemetry

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Future observability contract. No telemetry implementation or sinks are connected. */
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

/** Contract for a future telemetry destination. No sink is connected yet. */
interface TelemetrySink {
    val name: String
    fun write(batch: List<TelemetryEvent>)
    fun takeDropped(): Long = 0
    fun close() {}
}
