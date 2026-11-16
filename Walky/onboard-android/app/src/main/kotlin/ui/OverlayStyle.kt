package com.vadymsidorov.walky.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette and metrics from the face design handoff. Green, amber and red are status colours
// the handoff does not cover.
internal val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 17.sp)
internal val Dim = Color(0xFF9A5A9C)
internal val Timestamp = Color(0xFF8A3A8C)
internal val Bright = Color(0xFFFDE8FF)
internal val Cyan = Color(0xFF5FE3FF)
internal val Amber = Color(1f, 0.75f, 0.25f)
internal val Red = Color(1f, 0.4f, 0.4f)
internal val Green = Color(0.45f, 0.9f, 0.5f)
internal val FabFill = Color(0xFF1B1522)
internal val FabDots = Color(0xFF6B6B7A)

/** Round corner button that toggles an overlay. [icon] draws on a 24 dp canvas in the given colour. */
@Composable
internal fun CornerButton(
    description: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: DrawScope.(color: Color) -> Unit,
) {
    Box(
        modifier.size(44.dp).clip(CircleShape).background(FabFill)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) { icon(if (active) Cyan else FabDots) }
    }
}

/** One option of a choice; the [selected] one is highlighted. */
@Composable
internal fun ChoiceButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    BasicText(label, style = Mono.copy(color = if (selected) Cyan else Bright),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Dim.copy(alpha = 0.15f))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 14.dp))
}
