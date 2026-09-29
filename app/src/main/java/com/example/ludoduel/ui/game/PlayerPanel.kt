package com.example.ludoduel.ui.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ludoduel.data.GameReducer
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.theme.LocalLudoPalette
import com.example.ludoduel.ui.theme.LudoBackground
import com.example.ludoduel.ui.theme.LudoTheme
import kotlin.math.sin

/**
 * A player's card, placed next to their own corner of the board: avatar with the turn timer ring,
 * name, and the dice box. The active player's card is full brightness, slightly larger and glowing;
 * the waiting player's card is dimmed.
 *
 * [mirrored] puts the dice box on the left and the avatar on the right (for the top-right card).
 */
@Composable
fun PlayerPanel(
    name: String,
    subtitle: String,
    color: PlayerColor,
    active: Boolean,
    deadline: Long,
    now: () -> Long,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
    diceBox: @Composable () -> Unit,
) {
    val colors = LocalLudoPalette.current.of(color)
    val scale by animateFloatAsState(if (active) 1.05f else 1f, tween(250), label = "panelScale")
    val alpha by animateFloatAsState(if (active) 1f else 0.55f, tween(250), label = "panelAlpha")
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .shadow(if (active) 18.dp else 4.dp, shape, ambientColor = colors.main, spotColor = colors.main)
            .background(Brush.linearGradient(listOf(colors.main, colors.dark)), shape)
            .border(2.dp, Color.White.copy(alpha = if (active) 0.7f else 0.25f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val avatar = @Composable { Avatar(name, color, active, deadline, now) }
        val label = @Composable {
            Column(
                Modifier.width(118.dp),
                horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start,
            ) {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (mirrored) TextAlign.End else TextAlign.Start,
                )
                Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1)
            }
        }
        if (mirrored) {
            diceBox(); label(); avatar()
        } else {
            avatar(); label(); diceBox()
        }
    }
}

/** Circular avatar with the player's initial, surrounded by the turn timer ring when active. */
@Composable
private fun Avatar(name: String, color: PlayerColor, active: Boolean, deadline: Long, now: () -> Long) {
    val colors = LocalLudoPalette.current.of(color)
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        if (active) TimerRing(deadline, now, Modifier.fillMaxSize())
        Box(
            Modifier
                .size(50.dp)
                .shadow(4.dp, CircleShape)
                .background(Brush.radialGradient(listOf(colors.light, colors.main)), CircleShape)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = colors.dark,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

/**
 * A ring that empties smoothly over the 30-second turn. Orange from 10 s, red from 5 s, and it
 * pulses gently in the last 5 s. The frame clock is only read while drawing, so it never recomposes.
 */
@Composable
fun TimerRing(deadline: Long, now: () -> Long, modifier: Modifier = Modifier) {
    val frame by produceState(0L) { while (true) withFrameMillis { value = it } }
    Canvas(modifier) {
        frame // Redraw every frame.
        val remaining = (deadline - now()).coerceAtLeast(0)
        val fraction = (remaining.toFloat() / GameReducer.TURN_MILLIS).coerceIn(0f, 1f)
        val color = when {
            remaining <= 5_000 -> Color(0xFFFF1744)
            remaining <= 10_000 -> Color(0xFFFF9100)
            else -> Color.White
        }
        val pulse = if (remaining in 1..5_000) 1f + 0.12f * sin(now() / 120.0).toFloat() else 1f
        val stroke = size.minDimension * 0.08f * pulse
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Color.Black.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * The slot where a player's die sits. Only the player whose turn it is has a die in their box; the
 * other box is empty and greyed out.
 */
@Composable
fun DiceBox(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(64.dp)
            .background(if (active) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.18f), shape)
            .border(2.dp, Color.White.copy(alpha = if (active) 0.6f else 0.2f), shape)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Preview(widthDp = 400, heightDp = 260)
@Composable
private fun PlayerPanelsPreview() {
    LudoTheme {
        LudoBackground(Modifier.fillMaxSize()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                PlayerPanel("Ben", "Yellow", PlayerColor.YELLOW, active = false, deadline = 0, now = { 0 }, mirrored = true) {
                    DiceBox(active = false) {}
                }
                PlayerPanel("Anna", "You", PlayerColor.RED, active = true, deadline = 22_000, now = { 0 }, mirrored = false) {
                    DiceBox(active = true) {}
                }
            }
        }
    }
}
