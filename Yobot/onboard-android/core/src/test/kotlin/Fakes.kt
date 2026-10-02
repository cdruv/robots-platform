package com.vadymsidorov.yobot.core

import com.vadymsidorov.yobot.core.output.Face
import com.vadymsidorov.yobot.core.output.FaceState
import com.vadymsidorov.yobot.core.output.HapticPattern
import com.vadymsidorov.yobot.core.output.Haptics
import com.vadymsidorov.yobot.core.output.Output
import com.vadymsidorov.yobot.core.output.SoundName
import com.vadymsidorov.yobot.core.output.Sounds
import com.vadymsidorov.yobot.core.output.Voice

class FakeClock(var now: Long = 1_000_000) : Clock {
    override fun nowMs() = now
    override fun monoNs() = now * 1_000_000
}

class RecordingOutput {
    val calls = mutableListOf<String>()
    var face: FaceState? = null

    val output = Output(
        face = object : Face {
            override fun setTarget(state: FaceState) {
                face = state
                calls += "face:${state.expression}"
            }

            override fun glance(x: Float, y: Float, durationMs: Long) {
                calls += "glance:$x,$y"
            }
        },
        voice = object : Voice {
            override fun say(id: String, text: String) {
                calls += "say:$id:$text"
            }

            override fun stop() {
                calls += "stop"
            }
        },
        haptics = object : Haptics {
            override fun vibrate(pattern: HapticPattern) {
                calls += "vibrate:$pattern"
            }
        },
        sounds = object : Sounds {
            override fun play(name: SoundName) {
                calls += "sound:$name"
            }
        },
    )
}
