package com.vadymsidorov.walky.core.reflex

import com.vadymsidorov.walky.core.senses.BodyMotion
import kotlinx.coroutines.flow.StateFlow

/*
 * REFLEXES: the sense-to-output paths that do NOT go through the Executive.
 *
 * Everything else a sense perceives reaches an output only as
 * sense -> Percept -> Executive -> Effect -> output. A reflex is the deliberate exception:
 * an output reads a continuous, high-rate signal straight from a sense, because routing
 * 50 Hz samples through the sequential event loop would add latency and load for no decision.
 *
 * Rules that keep the exception small:
 * - A reflex only shapes presentation (how the face moves). It never changes RobotState,
 *   triggers thinking or drives actuators; anything the robot should decide on is also
 *   posted as a coarse Percept through the normal path.
 * - Every reflex is an interface in this package named `*Reflex`, and is connected in one
 *   place: the composition root (`Runtime`), which also announces it
 *   in the session resume telemetry event.
 */

/** Reflex: live body motion for outputs that animate with it. Bypasses the Executive; see the file header. */
interface MotionReflex {
    val bodyMotion: StateFlow<BodyMotion>
}
