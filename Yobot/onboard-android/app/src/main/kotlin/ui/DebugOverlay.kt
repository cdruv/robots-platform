package com.vadymsidorov.yobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vadymsidorov.yobot.Brain
import com.vadymsidorov.yobot.core.telemetry.TelemetryEvent
import com.vadymsidorov.yobot.core.telemetry.presentation
import com.vadymsidorov.yobot.core.telemetry.toJsonLine
import com.vadymsidorov.yobot.senses.HearingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
private val Dim = Color(0.65f, 0.7f, 0.75f)
private val Bright = Color(0.92f, 0.95f, 1f)
private val Cyan = Color(0.35f, 0.85f, 1f)
private val Amber = Color(1f, 0.75f, 0.25f)
private val Red = Color(1f, 0.4f, 0.4f)
private val Green = Color(0.45f, 0.9f, 0.5f)

/** Small translucent button in a corner; tapping toggles [DebugOverlay]. */
@Composable
fun DebugToggle(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(14.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (active) 0.3f else 0.1f))
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("⋮", style = Mono.copy(fontSize = 18.sp, color = Color.White.copy(alpha = 0.8f)))
    }
}

/** Hearing status, telemetry stream status, and the most recent telemetry events. */
@Composable
fun DebugOverlay(brain: Brain, modifier: Modifier = Modifier) {
    val hearing by brain.hearing.status.collectAsStateWithLifecycle()
    val server by brain.telemetryServer.status.collectAsStateWithLifecycle()
    val events by brain.recentEvents.events.collectAsStateWithLifecycle()
    var showPartial by remember { mutableStateOf(false) }
    val visibleEvents = remember(events, showPartial) {
        events.asReversed().filter { showPartial || !it.presentation().partial }
    }
    val time = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.78f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 72.dp),
    ) {
        val stateColor = when (hearing.state) {
            HearingState.Listening -> Green
            HearingState.Speech -> Cyan
            HearingState.Starting -> Dim
            HearingState.Muted -> Amber
            HearingState.Stopped, HearingState.NoPermission, HearingState.Error -> Red
        }
        val muted = hearing.muted
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("hearing ", style = Mono.copy(color = Dim))
            BasicText(hearing.state.name.lowercase(), style = Mono.copy(color = stateColor))
            BasicText(
                "  ${hearing.engine}  #${hearing.sessions}  ${hearing.rmsDb.coerceAtLeast(0f).toInt().let { "▮".repeat(it / 2) }}",
                style = Mono.copy(color = Dim),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                if (muted) "[unmute]" else "[mute]",
                style = Mono.copy(color = Amber),
                modifier = Modifier.clickable(interactionSource = null, indication = null) {
                    brain.hearing.setEnabled(muted)
                },
            )
        }
        hearing.lastError?.let { BasicText("error $it", style = Mono.copy(color = Red)) }
        BasicText(
            if (hearing.partial.isNotEmpty()) "› ${hearing.partial}" else "  ${hearing.lastFinal}",
            style = Mono.copy(color = if (hearing.partial.isNotEmpty()) Cyan else Bright, fontSize = 13.sp, lineHeight = 17.sp),
            maxLines = 3,
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            buildString {
                append("stream ")
                when {
                    server.error != null -> append("bind failed: ${server.error}")
                    server.port == null -> append("not started")
                    else -> {
                        append("port ${server.port}  clients ${server.clients}")
                        if (server.addresses.isNotEmpty()) append("  ${server.addresses.joinToString(" ")}")
                    }
                }
                val dropped = brain.telemetry.droppedTotal
                if (dropped > 0) append("  dropped $dropped")
            },
            style = Mono.copy(color = if (server.error != null) Red else Dim),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            if (showPartial) "[hide partials] · tap event for details" else "[show partials] · tap event for details",
            style = Mono.copy(color = Dim),
            modifier = Modifier.padding(bottom = 6.dp).clickable { showPartial = !showPartial },
        )
        LazyColumn(Modifier.weight(1f), reverseLayout = true) {
            items(visibleEvents, key = { it.seq }) { event -> EventRow(event, time) }
        }
    }
}

@Composable
private fun EventRow(event: TelemetryEvent, time: SimpleDateFormat) {
    var expanded by remember(event.seq) { mutableStateOf(false) }
    val summary = remember(event) { event.presentation() }
    val color = when (summary.label) {
        "ERROR" -> Red
        "WARN" -> Amber
        "HEARD" -> Cyan
        "PARTIAL" -> Dim
        else -> Bright
    }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 5.dp)) {
        BasicText(
            "${time.format(Date(event.tsWallMs))}  ${summary.label}  ${event.source}  ${if (expanded) "−" else "+"}",
            style = Mono.copy(color = color),
        )
        BasicText(
            summary.message,
            style = Mono.copy(color = color, fontSize = 13.sp, lineHeight = 17.sp),
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            SelectionContainer {
                BasicText(event.toJsonLine(), style = Mono.copy(color = Dim))
            }
        }
    }
}
