package com.vadymsidorov.walky.core.telemetry

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps everything in memory: log lines as `"<level> <message>"`, and channel writes in order. */
class FakeTelemetry : Telemetry {
    val lines = CopyOnWriteArrayList<String>()
    val written = CopyOnWriteArrayList<Pair<Topic<*>, Any?>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> values(topic: Topic<T>): List<T> = written.filter { it.first === topic }.map { it.second as T }

    override fun start() {}
    override fun stop() {}
    override fun emit(source: String, kind: String, payload: JsonElement) {}
    override fun <T> emit(source: String, kind: String, serializer: KSerializer<T>, value: T) {}

    override fun logger(source: String): Logger = object : Logger {
        override fun info(message: String) { lines += "info $message" }
        override fun warn(message: String, error: Throwable?) { lines += "warn $message" }
        override fun error(message: String, error: Throwable?) { lines += "error $message" }
    }

    override fun <T> channel(topic: Topic<T>): TelemetryChannel<T> = object : TelemetryChannel<T> {
        override fun write(logTimeNs: Long, value: T) { written += topic to value }
        override fun write(value: T) { written += topic to value }
    }
}
