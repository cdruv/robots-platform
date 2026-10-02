package com.vadymsidorov.yobot.core.executive

import com.vadymsidorov.yobot.core.cognition.Intent
import com.vadymsidorov.yobot.core.events.RequestId
import com.vadymsidorov.yobot.core.events.UtteranceId
import com.vadymsidorov.yobot.core.output.FaceState
import com.vadymsidorov.yobot.core.output.HapticPattern
import com.vadymsidorov.yobot.core.output.SoundName
import com.vadymsidorov.yobot.core.skills.SkillCall
import com.vadymsidorov.yobot.core.state.ThinkTrigger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Side effects requested by [ExecutiveLogic]; the [Executive] runtime carries them out. */
@Serializable
sealed interface Effect {
    @Serializable
    @SerialName("Think")
    data class Think(val requestId: RequestId, val trigger: ThinkTrigger) : Effect

    /** Store the exchange for [requestId] in conversation memory. */
    @Serializable
    @SerialName("Remember")
    data class Remember(val requestId: RequestId, val intent: Intent) : Effect

    @Serializable
    @SerialName("SetFace")
    data class SetFace(val face: FaceState) : Effect

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
