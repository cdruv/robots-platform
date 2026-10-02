package com.vadymsidorov.yobot.core.cognition

import com.vadymsidorov.yobot.core.state.RobotState
import com.vadymsidorov.yobot.core.state.ThinkTrigger
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Compact snapshot of the robot's circumstances, sent as the user message of each request. */
@Serializable
data class Situation(
    val trigger: String,
    val detail: String? = null,
    val time: String,
    val heard: List<Heard> = emptyList(),
    val motion: String? = null,
    val tilt: String? = null,
    val vision: VisionInfo? = null,
    val touch: String? = null,
    val battery: String? = null,
    val expression: String,
) {
    @Serializable
    data class Heard(val text: String, val secondsAgo: Int)

    @Serializable
    data class VisionInfo(val brightness: String, val movement: String)

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("EEEE HH:mm")

        fun of(state: RobotState, trigger: ThinkTrigger, nowMs: Long, zone: ZoneId): Situation = Situation(
            trigger = trigger.kind.name,
            detail = trigger.detail,
            time = TIME.format(Instant.ofEpochMilli(nowMs).atZone(zone)),
            heard = state.recentUtterances.map { Heard(it.text, ((nowMs - it.atMs) / 1000).toInt()) },
            motion = state.latestMotion?.kind?.name,
            tilt = state.latestMotion?.let { "pitch ${it.pitch.roundToInt()}°, roll ${it.roll.roundToInt()}°" },
            vision = state.latestVision?.let {
                VisionInfo(brightness = level(it.brightness, "dark", "dim", "bright"), movement = level(it.motionScore, "none", "some", "lots"))
            },
            touch = state.latestTouch?.let { "${it.kind} on ${it.region}" },
            battery = state.system?.let { "${it.batteryPct}%" + if (it.charging) " charging" else "" },
            expression = (state.reaction ?: state.expression).name,
        )

        private fun level(v: Float, low: String, mid: String, high: String) = when {
            v < 0.15f -> low
            v < 0.5f -> mid
            else -> high
        }
    }
}
