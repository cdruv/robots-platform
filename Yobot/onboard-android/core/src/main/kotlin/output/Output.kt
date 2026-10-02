package com.vadymsidorov.yobot.core.output

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
