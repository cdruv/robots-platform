package com.vadymsidorov.yobot.output

import android.media.AudioManager
import android.media.ToneGenerator
import com.vadymsidorov.yobot.core.output.SoundName
import com.vadymsidorov.yobot.core.output.Sounds

/** Placeholder non-verbal sounds from the system tone generator; no asset files. */
class AndroidSounds : Sounds {
    private val tones = ToneGenerator(AudioManager.STREAM_MUSIC, 70)

    override fun play(name: SoundName) {
        when (name) {
            SoundName.Chirp -> tones.startTone(ToneGenerator.TONE_CDMA_PIP, 180)
            SoundName.Beep -> tones.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            SoundName.Purr -> tones.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L, 700)
        }
    }

    fun release() = tones.release()
}
