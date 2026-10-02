package com.vadymsidorov.yobot.core.output

import kotlinx.serialization.Serializable

@Serializable
enum class SoundName { Chirp, Beep, Purr }

interface Sounds {
    fun play(name: SoundName)
}
