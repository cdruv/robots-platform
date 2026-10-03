package com.vadymsidorov.yobot.ui

import android.view.Window
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import com.vadymsidorov.yobot.core.telemetry.DebugLogFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
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

/** Bug-shaped debug toggle, kept available when the overlay is closed. */
@Composable
fun DebugToggle(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(18.dp)
            .size(44.dp).clip(CircleShape).background(FabFill)
            .semantics { contentDescription = if (active) "Close debug overlay" else "Open debug overlay" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val color = if (active) Cyan else FabDots
            val u = size.width / 24f
            drawRoundRect(color, Offset(7*u, 6*u), Size(10*u, 15*u), androidx.compose.ui.geometry.CornerRadius(5*u))
            for (y in listOf(9f, 14f, 18f)) {
                drawLine(color, Offset(3*u, y*u), Offset(21*u, y*u), 2*u)
            }
            drawLine(color, Offset(8*u, 3*u), Offset(11*u, 7*u), 2*u)
            drawLine(color, Offset(16*u, 3*u), Offset(13*u, 7*u), 2*u)
            drawLine(FabFill, Offset(12*u, 11*u), Offset(12*u, 19*u), u)
        }
    }
}

/** Hearing status, the IMU reflex feed, telemetry stream status, and the most recent telemetry events. */
@Composable
fun DebugOverlay(brain: Brain, window: Window, modifier: Modifier = Modifier) {
    val hearing by brain.hearing.status.collectAsStateWithLifecycle()
    val body by brain.faceMotionReflex.bodyMotion.collectAsStateWithLifecycle()
    val server by brain.telemetryServer.status.collectAsStateWithLifecycle()
    val events by brain.recentEvents.events.collectAsStateWithLifecycle()
    var showPartial by rememberSaveable { mutableStateOf(false) }
    var showMotion by rememberSaveable { mutableStateOf(true) }
    var showSpeech by rememberSaveable { mutableStateOf(true) }
    var showSystem by rememberSaveable { mutableStateOf(true) }
    var fontSize by rememberSaveable { mutableIntStateOf(13) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val filter = DebugLogFilter(showPartial, showMotion, showSpeech, showSystem)
    val visibleEvents = remember(events, filter) { events.asReversed().filter(filter::includes) }
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    var userScrolling by remember { mutableStateOf(false) }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    following = false
                    userScrolling = true
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && userScrolling) {
                following = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                userScrolling = false
            }
        }
    }
    // Key on sequence, not count: the ring buffer stays at 300 entries when full.
    LaunchedEffect(visibleEvents.firstOrNull()?.seq, following, settings, listState.isScrollInProgress) {
        if (following && !settings && !listState.isScrollInProgress) listState.scrollToItem(0)
    }
    val time = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.10f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 23.dp, top = 23.dp, end = 23.dp, bottom = 78.dp),
    ) {
        Column(Modifier.fillMaxWidth().background(FabFill.copy(alpha = 0.8f), RoundedCornerShape(8.dp)).padding(10.dp)) {
            ResourceTickers(window)
            Spacer(Modifier.height(6.dp))
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
                "imu → face (reflex, no executive)  roll %.0f°  pitch %.0f°  accel %.1f"
                    .format(Locale.US, body.rollDegrees, body.pitchDegrees, body.accelMagnitude),
                style = Mono.copy(color = Dim),
            )
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
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Dim.copy(alpha = 0.45f)))
        BasicText(
            if (settings) "DEBUG SETTINGS" else "LOGS · ${visibleEvents.size} · ${if (following) "live" else "paused"} · tap for details",
            style = Mono.copy(color = Dim), modifier = Modifier.padding(vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            if (settings) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterButton("Partials", showPartial) { showPartial = !showPartial }
                    FilterButton("Motion", showMotion) { showMotion = !showMotion }
                    FilterButton("Speech", showSpeech) { showSpeech = !showSpeech }
                    FilterButton("System / other", showSystem) { showSystem = !showSystem }
                    BasicText("Warnings and errors always visible", style = Mono.copy(color = Dim))
                    Spacer(Modifier.height(8.dp))
                    BasicText("Log font size", style = Mono.copy(color = Bright))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(11, 13, 16, 19).forEach { size ->
                            DebugButton("$size", fontSize == size) { fontSize = size }
                        }
                    }
                    BasicText("CPU: app usage; 100% = one core.\nMemory: app PSS.\nGPU: average render time per frame over the last second; not utilization. — means no supported samples.", style = Mono.copy(color = Dim))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().nestedScroll(scrollConnection), state = listState, reverseLayout = true) {
                    items(visibleEvents, key = { it.seq }) { event -> EventRow(event, time, fontSize) }
                }
                if (visibleEvents.isEmpty()) BasicText("No logs to show", style = Mono.copy(color = Dim))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Dim.copy(alpha = 0.45f)))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                .background(FabFill.copy(alpha = 0.95f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            FooterButton("Clear logs", FooterIcon.Clear) {
                brain.recentEvents.clear()
                following = true
            }
            FooterButton(
                if (settings) "Logs" else "Settings",
                if (settings) FooterIcon.Back else FooterIcon.Settings,
                selected = settings,
            ) { settings = !settings }
            Spacer(Modifier.weight(1f))
            if (!settings && !following) FooterButton("Latest", FooterIcon.Latest) { following = true }
        }
    }
}

@Composable
private fun EventRow(event: TelemetryEvent, time: SimpleDateFormat, fontSize: Int) {
    val logStyle = Mono.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 4).sp)
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
            style = logStyle.copy(color = color),
        )
        BasicText(
            summary.message,
            style = logStyle.copy(color = color),
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (expanded) {
            SelectionContainer {
                BasicText(event.toJsonLine(), style = logStyle.copy(color = Dim))
            }
        }
    }
}

@Composable
private fun DebugButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    BasicText(label, style = Mono.copy(color = if (selected) Cyan else Bright),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Dim.copy(alpha = 0.15f))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 14.dp))
}

@Composable
private fun FilterButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    DebugButton("${if (enabled) "✓" else "–"} $label", enabled, onClick)
}

private enum class FooterIcon { Clear, Settings, Back, Latest }

@Composable
private fun FooterButton(
    label: String,
    icon: FooterIcon,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (selected) Cyan else Bright
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Dim.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val u = size.width / 24f
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(color, Offset(x1*u, y1*u), Offset(x2*u, y2*u), 1.6f*u, StrokeCap.Round)
            }
            when (icon) {
                FooterIcon.Clear -> {
                    line(4f, 6f, 20f, 6f)
                    line(9f, 3f, 15f, 3f)
                    drawRoundRect(color, Offset(6*u, 6*u), Size(12*u, 15*u),
                        androidx.compose.ui.geometry.CornerRadius(2*u), style = Stroke(1.6f*u))
                    line(10f, 10f, 10f, 17f)
                    line(14f, 10f, 14f, 17f)
                }
                FooterIcon.Settings -> {
                    // Sliders remain legible at this compact size.
                    for ((y, knob) in listOf(6f to 9f, 12f to 16f, 18f to 8f)) {
                        line(3f, y, knob - 2f, y)
                        line(knob + 2f, y, 21f, y)
                        drawCircle(color, 2*u, Offset(knob*u, y*u), style = Stroke(1.6f*u))
                    }
                }
                FooterIcon.Back -> {
                    line(20f, 12f, 4f, 12f)
                    line(10f, 6f, 4f, 12f)
                    line(10f, 18f, 4f, 12f)
                }
                FooterIcon.Latest -> {
                    line(12f, 3f, 12f, 16f)
                    line(6f, 11f, 12f, 17f)
                    line(18f, 11f, 12f, 17f)
                    line(5f, 21f, 19f, 21f)
                }
            }
        }
        BasicText(label, style = Mono.copy(color = color, fontSize = 11.sp, lineHeight = 14.sp), maxLines = 1)
    }
}
