package com.vadymsidorov.walky.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vadymsidorov.walky.Settings
import com.vadymsidorov.walky.core.cognition.BrainMode
import kotlin.math.cos
import kotlin.math.sin

/** Gear-shaped settings toggle, next to the debug toggle. */
@Composable
fun SettingsToggle(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CornerButton(if (active) "Close settings" else "Open settings", active, onClick, modifier) { color ->
        val u = size.width / 24f
        val c = Offset(12*u, 12*u)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val d = Offset(cos(a).toFloat(), sin(a).toFloat())
            drawLine(color, c + d * (6*u), c + d * (10*u), 3*u, StrokeCap.Round)
        }
        drawCircle(color, 6.5f*u, c, style = Stroke(2.5f*u))
    }
}

/** User settings: microphone mute and the active brain. Changes persist and apply immediately. */
@Composable
fun SettingsOverlay(settings: Settings, modifier: Modifier = Modifier) {
    val muted by settings.muted.collectAsStateWithLifecycle()
    val brain by settings.brain.collectAsStateWithLifecycle()
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.10f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 23.dp, top = 23.dp, end = 23.dp, bottom = 78.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().background(FabFill.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText("SETTINGS", style = Mono.copy(color = Dim))
            BasicText("Microphone", style = Mono.copy(color = Bright))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton("On", !muted) { settings.setMuted(false) }
                ChoiceButton("Muted", muted) { settings.setMuted(true) }
            }
            BasicText("Brain", style = Mono.copy(color = Bright), modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrainMode.entries.forEach { mode ->
                    ChoiceButton(mode.name, mode == brain) { settings.setBrain(mode) }
                }
            }
            BasicText(brain.summary, style = Mono.copy(color = Dim))
        }
    }
}
