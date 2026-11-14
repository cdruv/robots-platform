package com.vadymsidorov.walky.core.output

import com.vadymsidorov.walky.core.body.BodyLink
import kotlinx.coroutines.flow.StateFlow

/**
 * Leg angles in degrees from horizontal, before trim. The same sign is the same physical
 * direction on both legs. The Pico clamps each to ±90.
 */
data class LegTargets(val leftDeg: Float, val rightDeg: Float)

/** The legs, for any driver: Robot Relay today, the locomotion policy later. */
interface Legs {
    val state: StateFlow<BodyLink>

    /**
     * Sends these targets to the Pico once; a newer call replaces one still waiting to be sent.
     * Nothing is resent. The Pico lets the legs go limp 500 ms after the last target, so a
     * driver holds a pose by calling this steadily and a stalled driver stops the legs.
     * Does nothing while the link is down or if a target is not a finite number.
     */
    fun drive(targets: LegTargets)

    /** Servo signals off now. Targets still waiting to be sent are discarded. */
    fun limp()
}
