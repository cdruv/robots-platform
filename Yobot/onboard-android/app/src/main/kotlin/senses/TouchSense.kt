package com.vadymsidorov.yobot.senses

import com.vadymsidorov.yobot.core.events.Percept
import com.vadymsidorov.yobot.core.events.Touch
import com.vadymsidorov.yobot.core.senses.Sense

/** Touches on the face, fed by `FaceScreen` pointer input. Ignored while stopped. */
class TouchSense : Sense {
    override val name = "touch"

    @Volatile
    private var sink: ((Percept) -> Unit)? = null

    override fun start(post: (Percept) -> Unit) {
        sink = post
    }

    override fun stop() {
        sink = null
    }

    fun post(touch: Touch) {
        sink?.invoke(touch)
    }
}
