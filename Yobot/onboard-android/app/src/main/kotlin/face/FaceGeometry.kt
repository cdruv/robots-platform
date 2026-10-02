package com.vadymsidorov.yobot.face

import com.vadymsidorov.yobot.core.events.TouchRegion
import kotlin.math.abs

/**
 * Face layout for a canvas size, shared by the renderer and touch hit-testing so touch
 * regions always match what is drawn. Left/right refer to the screen, not the robot.
 */
class FaceGeometry(val width: Float, val height: Float) {
    val centerX = width / 2f
    val eyesY = height * 0.42f
    val eyeSpacing = width * 0.25f
    val eyeWidth = width * 0.27f
    val eyeHeight = width * 0.32f
    val mouthY = height * 0.60f
    val mouthWidth = width * 0.24f
    val strokeWidth = width * 0.022f
    val gazeShiftX = width * 0.09f
    val gazeShiftY = height * 0.035f

    fun eyeCenterX(side: Int) = centerX + side * eyeSpacing

    fun regionAt(x: Float, y: Float): TouchRegion {
        val eyeHalfW = eyeWidth * 0.65f
        val eyeHalfH = eyeHeight * 0.65f
        return when {
            abs(y - eyesY) < eyeHalfH && abs(x - eyeCenterX(-1)) < eyeHalfW -> TouchRegion.LeftEye
            abs(y - eyesY) < eyeHalfH && abs(x - eyeCenterX(1)) < eyeHalfW -> TouchRegion.RightEye
            abs(y - mouthY) < height * 0.06f && abs(x - centerX) < mouthWidth -> TouchRegion.Mouth
            y < eyesY - eyeHalfH -> TouchRegion.Forehead
            else -> TouchRegion.Other
        }
    }
}
