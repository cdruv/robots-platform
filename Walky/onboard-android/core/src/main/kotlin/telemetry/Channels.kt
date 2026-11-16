package com.vadymsidorov.walky.core.telemetry

import com.vadymsidorov.walky.core.telemetry.mcap.Mcap
import kotlinx.serialization.KSerializer

/**
 * One MCAP channel: its topic, schema and how a value becomes message bytes. Every topic is
 * defined in [Topics]. [latched] topics hold state: a new live client gets their latest
 * message. [keyframe] is set only for video, which the live stream may skip up to a keyframe.
 */
class Topic<in T>(
    val name: String,
    val schemaName: String,
    val schemaEncoding: String,
    val schema: ByteArray,
    val messageEncoding: String,
    /** How often messages come, for people reading the recording: `400 Hz`, `on change`. */
    val rate: String,
    val latched: Boolean = false,
    val encode: (T) -> ByteArray,
    val keyframe: ((T) -> Boolean)? = null,
) {
    val isVideo: Boolean get() = keyframe != null

    companion object {
        fun <T> json(name: String, serializer: KSerializer<T>, schema: String, rate: String, latched: Boolean = false) = Topic<T>(
            name = name,
            schemaName = name.removePrefix("/").replace('/', '.'),
            schemaEncoding = "jsonschema",
            schema = schema.trimIndent().toByteArray(Charsets.UTF_8),
            messageEncoding = "json",
            rate = rate,
            latched = latched,
            encode = { WalkyJson.encodeToString(serializer, it).toByteArray(Charsets.UTF_8) },
        )
    }
}

/**
 * The fixed set of channels in one session. Topic `i` has schema and channel id `i + 1`, so
 * the recording and every live client agree without negotiating.
 */
class Catalog(val topics: List<Topic<*>>) {
    private val ids = topics.withIndex().associate { (i, topic) -> topic to i + 1 }

    init {
        require(topics.map { it.name }.toSet().size == topics.size) { "duplicate topic" }
    }

    fun id(topic: Topic<*>): Int = ids[topic] ?: error("${topic.name} is not in the catalog")

    fun topic(id: Int): Topic<*> = topics[id - 1]

    val schemaRecords: List<ByteArray> = topics.mapIndexed { i, topic ->
        Mcap.schema(i + 1, topic.schemaName, topic.schemaEncoding, topic.schema)
    }

    val channelRecords: List<ByteArray> = topics.mapIndexed { i, topic ->
        Mcap.channel(i + 1, i + 1, topic.name, topic.messageEncoding, buildMap {
            put("rate", topic.rate)
            if (topic.latched) put("latched", "true")
        })
    }
}

/** Writes messages to one topic. Thread-safe, never blocks. */
interface TelemetryChannel<in T> {
    /** [logTimeNs]: when it happened, on the telemetry clock (boottime on the phone). */
    fun write(logTimeNs: Long, value: T)

    /** Logged at the time of the call. */
    fun write(value: T)
}

/** One encoded MCAP Message record, shared by every [StreamSink]. */
class StreamRecord(
    val channelId: Int,
    val topic: Topic<*>,
    val logTimeNs: Long,
    /** The whole record: opcode, length and content. */
    val bytes: ByteArray,
    val keyframe: Boolean = false,
)

/**
 * A destination for channel messages. [write] is called on the writer's thread and must not
 * block. A [lossless] sink reports drops as errors.
 */
interface StreamSink {
    val name: String
    val lossless: Boolean get() = false
    fun write(record: StreamRecord)

    /** Messages this sink discarded since the last call. */
    fun takeDropped(): Long = 0
}
