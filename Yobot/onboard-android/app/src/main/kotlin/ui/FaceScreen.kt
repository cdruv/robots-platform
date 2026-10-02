package com.vadymsidorov.yobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vadymsidorov.yobot.core.events.Touch
import com.vadymsidorov.yobot.core.events.TouchKind
import com.vadymsidorov.yobot.core.events.TouchRegion
import com.vadymsidorov.yobot.core.output.FaceState
import com.vadymsidorov.yobot.face.FaceGeometry
import com.vadymsidorov.yobot.face.FaceRenderer
import com.vadymsidorov.yobot.output.Glance
import kotlinx.coroutines.flow.StateFlow

/**
 * The whole UI: the face, touch input mapped to face regions, and an [overlay] slot for the
 * future debug overlay. A long press in the top-left corner is reserved to open it.
 */
@Composable
fun FaceScreen(
    faceState: StateFlow<FaceState>,
    glance: StateFlow<Glance?>,
    onTouch: (Touch) -> Unit,
    onDebugGesture: () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val target by faceState.collectAsStateWithLifecycle()
    val currentGlance by glance.collectAsStateWithLifecycle()
    val post by rememberUpdatedState(onTouch)
    val debug by rememberUpdatedState(onDebugGesture)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { post(touchAt(TouchKind.Tap, it, size)) },
                    onLongPress = {
                        if (it.x < size.width * CORNER && it.y < size.height * CORNER) debug()
                        else post(touchAt(TouchKind.LongPress, it, size))
                    },
                )
            }
            .pointerInput(Unit) {
                var last = Offset.Zero
                detectDragGestures(
                    onDragStart = { last = it },
                    onDragEnd = { post(touchAt(TouchKind.Stroke, last, size)) },
                    onDrag = { change, _ -> last = change.position },
                )
            },
    ) {
        FaceRenderer(target, glance = currentGlance)
        overlay()
    }
}

private const val CORNER = 0.15f

private fun touchAt(kind: TouchKind, at: Offset, size: IntSize): Touch {
    val region = if (size.width == 0) TouchRegion.Other
    else FaceGeometry(size.width.toFloat(), size.height.toFloat()).regionAt(at.x, at.y)
    return Touch(kind, at.x / size.width.coerceAtLeast(1), at.y / size.height.coerceAtLeast(1), region)
}
