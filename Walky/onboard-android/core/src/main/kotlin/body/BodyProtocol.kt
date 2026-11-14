package com.vadymsidorov.walky.core.body

import com.vadymsidorov.walky.core.output.LegTargets
import com.vadymsidorov.walky.core.telemetry.WalkyJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Phone side of the "body v1" wire protocol. The Pico is the server and owns the definition:
 * see the module docstring of `Walky/firmware/pico/src/body.py`. Change that first.
 */
object BodyProtocol {
    /** The only constant shared with the firmware; the address comes from the Pico's network. */
    const val PORT = 8765
    const val VERSION = 1

    fun encode(command: BodyCommand, n: Long): String = buildJsonObject {
        when (command) {
            is BodyCommand.Drive -> {
                put("t", "legs")
                put("l", command.targets.leftDeg)
                put("r", command.targets.rightDeg)
            }
            BodyCommand.Limp -> put("t", "limp")
            is BodyCommand.Trim -> {
                put("t", "trim")
                putJsonArray("steps") { addAll(listOf(command.left, command.right)) }
            }
            BodyCommand.Save -> put("t", "save")
            BodyCommand.Ping -> put("t", "ping")
        }
        put("n", n)
    }.toString()

    /** The state in [line], or null if the line is something else. Throws on a malformed state. */
    fun decodeState(line: String): PicoState? {
        val fields = WalkyJson.parseToJsonElement(line) as? JsonObject ?: return null
        if ((fields["t"] as? JsonPrimitive)?.contentOrNull != "state") return null
        return WalkyJson.decodeFromJsonElement<PicoState>(fields)
    }
}

sealed interface BodyCommand {
    data class Drive(val targets: LegTargets) : BodyCommand
    data object Limp : BodyCommand
    data class Trim(val left: Int, val right: Int) : BodyCommand
    data object Save : BodyCommand
    data object Ping : BodyCommand
}

/** One state line from the Pico. Field meanings are in the firmware docstring. */
@Serializable
data class PicoState(
    val v: Int,
    val board: String,
    val mode: Mode,
    val target: List<Float>,
    val pulses: List<Int>? = null,
    val trim: List<Int>,
    val saved: List<Int>,
    val stored: Boolean,
    val slew: Int,
    val n: Long? = null,
    val error: PicoError? = null,
    @SerialName("up_ms") val upMs: Long,
) {
    @Serializable
    enum class Mode {
        @SerialName("limp") Limp,
        @SerialName("active") Active,
    }
}

@Serializable
data class PicoError(val n: Long? = null, val msg: String)

/** What the phone knows about the Pico. [pico] and [rttMs] are set only while connected. */
data class BodyLink(
    val phase: Phase = Phase.Connecting,
    val pico: PicoState? = null,
    val rttMs: Int? = null,
) {
    enum class Phase { Connecting, Connected }
}
