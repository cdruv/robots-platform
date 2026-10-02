package com.vadymsidorov.yobot.core.skills

import com.vadymsidorov.yobot.core.output.HapticPattern
import com.vadymsidorov.yobot.core.output.SoundName

/** Skills available with the phone alone. Locomotion skills will be added alongside. */
object BuiltinSkills {
    private const val GLANCE_MS = 1_500L

    val vibrate = Skill(
        name = "vibrate",
        description = "Buzz your body briefly, like a shiver or a giggle.",
        paramsSchema = enumParams("pattern" to listOf("short", "double", "long")),
    ) { args, output ->
        output.haptics.vibrate(HapticPattern.valueOf(args.string("pattern").replaceFirstChar(Char::uppercase)))
    }

    val playSound = Skill(
        name = "play_sound",
        description = "Make a short non-verbal sound.",
        paramsSchema = enumParams("name" to listOf("chirp", "beep", "purr")),
    ) { args, output ->
        output.sounds.play(SoundName.valueOf(args.string("name").replaceFirstChar(Char::uppercase)))
    }

    val look = Skill(
        name = "look",
        description = "Glance in a direction for a moment.",
        paramsSchema = enumParams("direction" to listOf("left", "right", "up", "down", "center")),
    ) { args, output ->
        val (x, y) = when (args.string("direction")) {
            "left" -> -1f to 0f
            "right" -> 1f to 0f
            "up" -> 0f to -1f
            "down" -> 0f to 1f
            else -> 0f to 0f
        }
        output.face.glance(x, y, GLANCE_MS)
    }

    val all: List<Skill> = listOf(vibrate, playSound, look)
}
