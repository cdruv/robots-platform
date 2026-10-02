package com.vadymsidorov.yobot.core.output

import kotlinx.serialization.Serializable

@Serializable
enum class HapticPattern { Short, Double, Long }

interface Haptics {
    fun vibrate(pattern: HapticPattern)
}
