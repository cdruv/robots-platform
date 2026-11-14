package com.vadymsidorov.walky.core.body

import com.vadymsidorov.walky.core.output.LegTargets
import com.vadymsidorov.walky.core.telemetry.WalkyJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Robot Relay's route to the body. Client lines in, each one command:
 *
 *     {"link":"body","legs":[l,r]}      degrees, as [LegTargets]
 *     {"link":"body","limp":true}
 *     {"link":"body","trim":[l,r]}      half-degree steps
 *     {"link":"body","save":true}
 *     {"link":"pair","ssid":"…","password":"…"}
 *
 * and one line out when the link changes, at most 10 and at least once a second:
 *
 *     {"link":"body","phase":"…","pico":{…},"rttMs":12}
 *
 * `pico` is the Pico's state line as received and is absent unless connected. Relay sends
 * leg targets as a stream and each line is forwarded once, so a Relay that stops also stops
 * the legs. Lines that do not fit are ignored.
 */
class BodyRelay(
    private val body: PicoBody,
    private val pair: (ssid: String, password: String) -> Unit,
    private val send: (JsonObject) -> Unit,
    private val scope: CoroutineScope,
    /** What to report as `phase`; the app names what it is waiting for while not connected. */
    private val phase: (BodyLink) -> String = { it.phase.name.lowercase() },
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            var sent: BodyLink? = null
            while (true) {
                val link = withTimeoutOrNull(MAX_INTERVAL_MS) { body.state.first { it != sent } } ?: body.state.value
                send(encode(link))
                sent = link
                delay(MIN_INTERVAL_MS)
            }
        }
    }

    fun onLine(line: JsonObject) {
        when (line.text("link")) {
            "body" -> {
                line.numbers("legs", JsonPrimitive::floatOrNull)?.let { (left, right) -> body.drive(LegTargets(left, right)) }
                line.numbers("trim", JsonPrimitive::intOrNull)?.let { (left, right) -> body.setTrim(left, right) }
                if (line.flag("limp")) body.limp()
                if (line.flag("save")) body.saveTrim()
            }
            "pair" -> {
                val ssid = line.text("ssid")
                val password = line.text("password")
                if (!ssid.isNullOrEmpty() && !password.isNullOrEmpty()) pair(ssid, password)
            }
        }
    }

    private fun encode(link: BodyLink): JsonObject = buildJsonObject {
        put("link", "body")
        put("phase", phase(link))
        link.pico?.let { put("pico", WalkyJson.encodeToJsonElement(PicoState.serializer(), it)) }
        link.rttMs?.let { put("rttMs", it) }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun <T : Any> JsonObject.numbers(key: String, read: (JsonPrimitive) -> T?): List<T>? {
        val values = (this[key] as? JsonArray)?.takeIf { it.size == 2 } ?: return null
        return values.map { value: JsonElement ->
            (value as? JsonPrimitive)?.takeIf { !it.isString }?.let(read) ?: return null
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 100L
        const val MAX_INTERVAL_MS = 1_000L
    }
}
