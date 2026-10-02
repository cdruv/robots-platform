package com.vadymsidorov.yobot.output

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import com.vadymsidorov.yobot.core.output.HapticPattern
import com.vadymsidorov.yobot.core.output.Haptics

class AndroidHaptics(context: Context) : Haptics {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator

    private val effects = mapOf(
        HapticPattern.Short to VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
        HapticPattern.Double to VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
        HapticPattern.Long to VibrationEffect.createWaveform(
            longArrayOf(0, 80, 60, 300),
            intArrayOf(0, 160, 0, 255),
            -1,
        ),
    )

    override fun vibrate(pattern: HapticPattern) {
        if (vibrator.hasVibrator()) vibrator.vibrate(effects.getValue(pattern))
    }
}
