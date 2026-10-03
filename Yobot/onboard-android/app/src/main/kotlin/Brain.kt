package com.vadymsidorov.yobot

import com.vadymsidorov.yobot.core.inference.Inference
import com.vadymsidorov.yobot.output.ComposeFace

/** Composition root. Add subsystem implementations here when their features are ready. */
class Brain {
    val face = ComposeFace()
    val inference = Inference()
}
