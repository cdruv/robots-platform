package com.vadymsidorov.yobot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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

// Palette and metrics from the face design handoff. Green, amber and red are status colours
// the handoff does not cover.
private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp)
private val Dim = Color(0xFF9A5A9C)
private val Timestamp = Color(0xFF8A3A8C)
private val Bright = Color(0xFFFDE8FF)
private val Cyan = Color(0xFF5FE3FF)
private val Amber = Color(1f, 0.75f, 0.25f)
private val Red = Color(1f, 0.4f, 0.4f)
private val Green = Color(0.45f, 0.9f, 0.5f)
private val FabFill = Color(0xFF1B1522)
private val FabDots = Color(0xFF6B6B7A)

/** Round three-dot button in the bottom-right corner; tapping toggles [DebugOverlay]. */
@Composable
fun DebugToggle(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(18.dp)
            .size(38.dp)
            .clip(CircleShape)
            .background(FabFill)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(if (active) Cyan else FabDots))
        }
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 23.dp, top = 23.dp, end = 23.dp, bottom = 81.dp),
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
            style = Mono.copy(color = if (hearing.partial.isNotEmpty()) Cyan else Bright),
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
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 5.5.dp)) {
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Timestamp)) { append(time.format(Date(event.tsWallMs))) }
                append("  ${summary.label}  ${event.source}  ${if (expanded) "−" else "+"}")
            },
            style = Mono.copy(color = color),
        )
        BasicText(
            summary.message,
            style = Mono.copy(color = color),
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
