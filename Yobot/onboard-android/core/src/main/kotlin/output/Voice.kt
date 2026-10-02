package com.vadymsidorov.yobot.core.output

/**
 * Speech output. Implementations report progress as
 * [com.vadymsidorov.yobot.core.events.OutputFeedback] events tagged with [id].
 */
interface Voice {
    fun say(id: String, text: String)
    fun stop()
}
