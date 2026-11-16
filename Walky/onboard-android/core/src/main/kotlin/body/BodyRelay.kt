package com.vadymsidorov.walky.core.body

import com.vadymsidorov.walky.core.output.LegTargets
import com.vadymsidorov.walky.core.telemetry.BodyLinkMessage
import com.vadymsidorov.walky.core.telemetry.TelemetryChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Robot Relay's route to the body. Client lines in, each one command:
 *
 *     {"link":"body","legs":[l,r]}      degrees, as [LegTargets]
 *     {"link":"body","limp":true}
 *     {"link":"body","trim":[l,r]}      half-degree steps
 *     {"link":"body","save":true}
 *     {"link":"pair","ssid":"…","password":"…"}
 *
 * Relay sends leg targets as a stream and each line is forwarded once, so a Relay that stops
 * also stops the legs. Lines that do not fit are ignored.
 *
 * Out, the link's phase and RTT go on `/body/link` ([BodyLinkMessage]) within
 * [CHECK_INTERVAL_MS] of each change; the Pico's state lines are on `/body/state` from
 * [PicoBody]. Both are latched, so a client that joins later gets the latest of each.
 */
class BodyRelay(
    private val body: PicoBody,
    private val pair: (ssid: String, password: String) -> Unit,
    private val link: TelemetryChannel<BodyLinkMessage>,
    private val scope: CoroutineScope,
    /** What to report as `phase`; the app names what it is waiting for while not connected. */
    private val phase: (BodyLink) -> String = { it.phase.name.lowercase() },
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            var sent: BodyLinkMessage? = null
            while (true) {
                val state = body.state.value
                val message = BodyLinkMessage(phase(state), state.rttMs)
                if (message != sent) {
                    link.write(message)
                    sent = message
                }
                delay(CHECK_INTERVAL_MS)
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
        const val CHECK_INTERVAL_MS = 100L
    }
}
