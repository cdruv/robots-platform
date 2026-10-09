package com.vadymsidorov.walky.core.executive

import com.vadymsidorov.walky.core.events.ExecutiveEvent
import com.vadymsidorov.walky.core.events.RequestId
import com.vadymsidorov.walky.core.events.UtteranceId
import com.vadymsidorov.walky.core.output.FaceState
import com.vadymsidorov.walky.core.output.HapticPattern
import com.vadymsidorov.walky.core.output.Reaction
import com.vadymsidorov.walky.core.output.SoundName
import com.vadymsidorov.walky.core.skills.SkillCall
import com.vadymsidorov.walky.core.state.RobotState
import com.vadymsidorov.walky.core.state.ThinkTrigger
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Future state owner and event loop. No implementation is created by the app yet. */
interface Executive {
    val state: StateFlow<RobotState>
    fun start()
    fun stop()
    fun post(event: ExecutiveEvent)
}

/** Future pure policy contract, separate from hardware and network work. */
fun interface ExecutiveLogic {
    fun step(state: RobotState, event: ExecutiveEvent, nowMs: Long): Step
}

data class Step(val state: RobotState, val effects: List<Effect> = emptyList())

/** Future effect contracts. No effect producer or executor is connected yet. */
@Serializable
sealed interface Effect {
    @Serializable
    @SerialName("Think")
    data class Think(val requestId: RequestId, val trigger: ThinkTrigger) : Effect

    @Serializable
    @SerialName("SetFace")
    data class SetFace(val face: FaceState) : Effect

    @Serializable
    @SerialName("React")
    data class React(val reaction: Reaction) : Effect

    @Serializable
    @SerialName("Say")
    data class Say(val utteranceId: UtteranceId, val text: String) : Effect

    @Serializable
    @SerialName("StopSpeaking")
    data object StopSpeaking : Effect

    @Serializable
    @SerialName("Vibrate")
    data class Vibrate(val pattern: HapticPattern) : Effect

    @Serializable
    @SerialName("PlaySound")
    data class PlaySound(val name: SoundName) : Effect

    @Serializable
    @SerialName("RunSkill")
    data class RunSkill(val call: SkillCall) : Effect

    @Serializable
    @SerialName("SetHearing")
    data class SetHearing(val enabled: Boolean) : Effect

    @Serializable
    @SerialName("Log")
    data class Log(val kind: String, val payload: JsonElement = JsonNull) : Effect
}
