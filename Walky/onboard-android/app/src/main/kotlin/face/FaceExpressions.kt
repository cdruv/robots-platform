package com.vadymsidorov.walky.face

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.vadymsidorov.walky.core.output.Expression

/**
 * Everything about an eye that an expression changes, in design dp. The renderer interpolates
 * between poses, so every field must blend smoothly.
 */
internal data class EyePose(
    val width: Float = 111f,
    val height: Float = 132f,
    val topRadius: Float = 33f,
    val bottomRadius: Float = 33f,
    /** Horizontal shift applied to both eyes. */
    val offsetX: Float = 0f,
    val centerY: Float = 384f,
    val rim: Color = FaceSpec.EyeRim,
    val fill: Color = FaceSpec.EyeFill,
    val glow: Color = FaceSpec.EyeRim,
    /** Multiplier on the breathing glow: 0 turns it off. */
    val glowAlpha: Float = 1f,
    val pupilAlpha: Float = 1f,
    val pupilScale: Float = 1f,
    /** Share of the ambient gaze wander the pupil follows, 0..1. */
    val wander: Float = 1f,
    /** Fixed pupil offset from the eye centre, added to the wander. */
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    /** Share of the ambient blink the eye follows, 0..1. */
    val blink: Float = 1f,
)

/** The expression table from the design handoff. */
internal fun eyePose(expression: Expression): EyePose = when (expression) {
    Expression.Idle -> EyePose()
    Expression.Happy -> EyePose(
        height = 63f, topRadius = 55f, bottomRadius = 16f, centerY = 380f,
        pupilAlpha = 0f, wander = 0f,
    )
    Expression.Thinking -> EyePose(
        offsetX = 24f, centerY = 384f - 16f,
        wander = 0f, gazeX = 17f, gazeY = -6f,
    )
    Expression.Alert -> EyePose(
        height = 182f, rim = FaceSpec.AlertRim, glow = FaceSpec.AlertRim,
        pupilScale = 0.7f, wander = 0f,
    )
    Expression.Sleep -> EyePose(
        height = 24f, topRadius = 12f, bottomRadius = 12f, centerY = 392f,
        rim = FaceSpec.SleepRim, fill = FaceSpec.SleepFill, glowAlpha = 0f,
        pupilAlpha = 0f, wander = 0f, blink = 0f,
    )
}

internal fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

internal fun lerp(a: EyePose, b: EyePose, t: Float) = EyePose(
    width = mix(a.width, b.width, t),
    height = mix(a.height, b.height, t),
    topRadius = mix(a.topRadius, b.topRadius, t),
    bottomRadius = mix(a.bottomRadius, b.bottomRadius, t),
    offsetX = mix(a.offsetX, b.offsetX, t),
    centerY = mix(a.centerY, b.centerY, t),
    rim = lerp(a.rim, b.rim, t),
    fill = lerp(a.fill, b.fill, t),
    glow = lerp(a.glow, b.glow, t),
    glowAlpha = mix(a.glowAlpha, b.glowAlpha, t),
    pupilAlpha = mix(a.pupilAlpha, b.pupilAlpha, t),
    pupilScale = mix(a.pupilScale, b.pupilScale, t),
    wander = mix(a.wander, b.wander, t),
    gazeX = mix(a.gazeX, b.gazeX, t),
    gazeY = mix(a.gazeY, b.gazeY, t),
    blink = mix(a.blink, b.blink, t),
)
