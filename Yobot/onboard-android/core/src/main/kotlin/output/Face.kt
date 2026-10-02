package com.vadymsidorov.yobot.core.output

interface Face {
    fun setTarget(state: FaceState)

    /** Brief gaze override on top of the target, e.g. for the `look` skill. */
    fun glance(x: Float, y: Float, durationMs: Long)
}
