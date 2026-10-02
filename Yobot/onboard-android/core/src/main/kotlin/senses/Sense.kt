package com.vadymsidorov.yobot.core.senses

import com.vadymsidorov.yobot.core.events.Percept

/**
 * A source of percepts running on its own loop. [start] must not block; percepts are
 * delivered through [post] from any thread. Percept types live in `events`.
 */
interface Sense {
    val name: String
    fun start(post: (Percept) -> Unit)
    fun stop()
}

/** Lets the Executive mute hearing, e.g. while the robot is speaking. */
fun interface HearingControl {
    fun setEnabled(enabled: Boolean)
}
