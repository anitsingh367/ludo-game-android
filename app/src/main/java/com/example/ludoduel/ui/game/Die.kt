package com.example.ludoduel.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.theme.Baloo
import com.example.ludoduel.ui.theme.LocalLudoPalette
import com.example.ludoduel.ui.theme.LudoTheme
import kotlinx.coroutines.delay

/** Everything that changes about one player's die while it animates. Written by [GameAnimator]. */
@Stable
class DieVisual {
    /** 1..6, or null for the blank "tap" face. */
    var face by mutableStateOf<Int?>(null)
    var tumbling by mutableStateOf(false)
    var angle by mutableFloatStateOf(0f)
    var shakeX by mutableFloatStateOf(0f)
    /** Landing "pop": 1.0 -> 1.2 -> 1.0. */
    val pop = Animatable(1f)
    /** Golden glow burst for a six, 0..1. */
    val glow = Animatable(0f)
    /** Red flash for a cancelled third six, 0..1. */
    val redFlash = Animatable(0f)
    /** Slide-in when the die moves to this player's box, 0..1. */
    val enter = Animatable(1f)
    /** "+1 turn!" label floating up from the die; progress 0..1 (1 = hidden). */
    val floater = Animatable(1f)
    var floaterText by mutableStateOf("")
}

/**
 * A white 3D die with rounded corners, black pips and the 1-pip in the player's color. Tapping it
 * rolls when [enabled]. While it waits for a tap it wiggles every 2 seconds to invite one.
 */
@Composable
fun Die(
    visual: DieVisual,
    color: PlayerColor,
    enabled: Boolean,
    description: String,
    onRoll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLudoPalette.current.of(color)
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        while (enabled) {
            delay(2_000)
            for (target in listOf(12f, -10f, 7f, -4f, 0f)) wiggle.animateTo(target, tween(70))
        }
        wiggle.snapTo(0f)
    }
    val measurer = rememberTextMeasurer()
    Box(
        modifier
            .graphicsLayer {
                val pop = visual.pop.value
                scaleX = pop
                scaleY = pop
                rotationZ = visual.angle + wiggle.value
                translationX = visual.shakeX + (1f - visual.enter.value) * 40.dp.toPx()
                alpha = visual.enter.value
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRoll,
            )
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawGlow(visual.glow.value)
            drawDieBody(visual.face, colors.main, visual.redFlash.value)
            if (visual.face == null && enabled) {
                val layout = measurer.measure(
                    "TAP",
                    TextStyle(color = Color(0xFF9E9E9E), fontSize = (size.width * 0.22f).toSp(), fontWeight = FontWeight.ExtraBold, fontFamily = Baloo),
                )
                drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }
        }
    }
}

private fun DrawScope.drawGlow(glow: Float) {
    if (glow <= 0f) return
    val radius = size.minDimension * (0.55f + 0.5f * glow)
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE082).copy(alpha = glow), Color(0xFFFFC107).copy(alpha = glow * 0.6f), Color.Transparent), center, radius),
        radius,
        center,
    )
}

/**
 * The die body and its pips, filling the square [area] (the whole canvas by default). Also used by
 * previews and the bouncing dice on the home and waiting screens.
 */
fun DrawScope.drawDieBody(face: Int?, onePipColor: Color, redFlash: Float = 0f, area: Rect = Rect(Offset.Zero, size)) {
    val side = area.minDimension * 0.84f
    val topLeft = area.center - Offset(side / 2, side / 2)
    val corner = CornerRadius(side * 0.22f)
    // Soft shadow under the die.
    drawRoundRect(Color.Black.copy(alpha = 0.28f), topLeft + Offset(side * 0.03f, side * 0.07f), Size(side, side), corner)
    drawRoundRect(Brush.verticalGradient(listOf(Color.White, Color(0xFFE3E3E3)), topLeft.y, topLeft.y + side), topLeft, Size(side, side), corner)
    drawRoundRect(Color(0xFFBDBDBD), topLeft, Size(side, side), corner, style = Stroke(side * 0.03f))
    if (redFlash > 0f) drawRoundRect(Color(0xFFFF1744).copy(alpha = 0.75f * redFlash), topLeft, Size(side, side), corner)
    val f = face ?: return
    val lo = 0.25f
    val hi = 0.75f
    val mid = 0.5f
    val pips = when (f) {
        1 -> listOf(mid to mid)
        2 -> listOf(lo to lo, hi to hi)
        3 -> listOf(lo to lo, mid to mid, hi to hi)
        4 -> listOf(lo to lo, hi to lo, lo to hi, hi to hi)
        5 -> listOf(lo to lo, hi to lo, mid to mid, lo to hi, hi to hi)
        else -> listOf(lo to lo, hi to lo, lo to mid, hi to mid, lo to hi, hi to hi)
    }
    val r = side * if (f == 1) 0.13f else 0.09f
    for ((x, y) in pips) {
        val p = topLeft + Offset(side * x, side * y)
        drawCircle(if (f == 1) onePipColor else Color(0xFF212121), r, p)
        drawCircle(Color.White.copy(alpha = 0.35f), r * 0.35f, p - Offset(r * 0.3f, r * 0.3f))
    }
}

@Preview(widthDp = 420, heightDp = 80)
@Composable
private fun DieFacesPreview() {
    LudoTheme {
        val palette = LocalLudoPalette.current
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(null, 1, 2, 3, 4, 5, 6).forEach { face ->
                Canvas(Modifier.size(52.dp)) { drawDieBody(face, palette.red.main) }
            }
        }
    }
}

@Preview(widthDp = 200, heightDp = 80)
@Composable
private fun DieEffectsPreview() {
    LudoTheme {
        val palette = LocalLudoPalette.current
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Canvas(Modifier.size(56.dp)) { drawGlow(0.8f); drawDieBody(6, palette.yellow.main) }
            Canvas(Modifier.size(56.dp)) { drawDieBody(6, palette.red.main, redFlash = 1f) }
        }
    }
}
