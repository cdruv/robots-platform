package com.vadymsidorov.yobot.core.output

import kotlinx.serialization.Serializable

/**
 * Everything the robot can express or actuate. All calls must return quickly;
 * implementations do their own work off the caller's thread. Locomotion joins here later.
 */
data class Output(
    val face: Face,
    val voice: Voice,
    val haptics: Haptics,
    val sounds: Sounds,
)

/**
 * Speech output. Implementations report progress as
 * [com.vadymsidorov.yobot.core.events.OutputFeedback] events tagged with [id].
 */
interface Voice {
    fun say(id: String, text: String)
    fun stop()
}

@Serializable
enum class HapticPattern { Short, Double, Long }

interface Haptics {
    fun vibrate(pattern: HapticPattern)
}

@Serializable
enum class SoundName { Chirp, Beep, Purr }

interface Sounds {
    fun play(name: SoundName)
}
