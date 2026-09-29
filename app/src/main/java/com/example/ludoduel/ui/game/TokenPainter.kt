package com.example.ludoduel.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
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
import com.example.ludoduel.ui.theme.SeatColors
import kotlin.math.acos
import kotlin.math.hypot

/** How a single pawn is drawn right now (all animation effects in one place). */
data class PawnLook(
    /** Size factor: 1 alone on a square, smaller in a stack or at home. Includes the hop grow. */
    val scale: Float = 1f,
    /** Height above the board in pixels (hops, bobbing). The shadow stays on the board and shrinks. */
    val liftPx: Float = 0f,
    /** 0..1: squashed flat on landing. */
    val squash: Float = 0f,
    /** 0..1: white flash when captured. */
    val flash: Float = 0f,
)

/**
 * Pawn measurements, in board squares, for a pawn at scale 1 standing on a square whose center is
 * (0, 0) (y grows downwards). Drawing, the "stays inside its square" test and the lift limits all use
 * these numbers, so they cannot drift apart.
 */
object PawnShape {
    const val HEAD_R = 0.28f
    const val HEAD_Y = -0.11f
    const val TIP_Y = 0.31f
    const val BASE_Y = 0.33f
    const val BASE_RX = 0.42f
    const val BASE_RY = 0.12f
    const val SHADOW_Y = 0.37f
    const val SHADOW_RX = 0.44f
    const val SHADOW_RY = 0.085f
    /** How far a movable pawn bobs up. Small, so the pawn stays inside its square. */
    const val BOB = 0.09f
    /** The glow ring under a movable pawn at its widest. */
    const val GLOW_RX = 0.46f
    const val GLOW_RY = 0.14f
    /** Half the outline width plus a little room for antialiasing. */
    private const val EDGE = 0.02f

    /**
     * Everything a pawn at [scale] may draw at rest or while bobbing (head, base, shadow, glow ring),
     * relative to its square's center, in squares.
     */
    fun envelope(scale: Float) = Rect(
        left = -GLOW_RX * scale,
        top = (HEAD_Y - HEAD_R - BOB - EDGE) * scale,
        right = GLOW_RX * scale,
        bottom = maxOf(BASE_Y + GLOW_RY, SHADOW_Y + SHADOW_RY) * scale,
    )
}

/**
 * A classic pin pawn standing on [base] (the square's center): a pearl-white pin with a glossy ball
 * in the player's color in its head, a white highlight, and a matching base disc with a thin dark
 * ring. The shadow stays on the board and shrinks while the pawn is in the air.
 * [letter] is drawn on the ball in colorblind mode.
 */
fun DrawScope.drawPawn(
    base: Offset,
    unit: Float,
    colors: SeatColors,
    look: PawnLook,
    letter: String?,
    textMeasurer: TextMeasurer,
) {
    val s = unit * look.scale
    val liftFraction = (look.liftPx / (unit * 0.45f)).coerceIn(0f, 1f)
    val ink = Color(0xFF2B2320)

    // Shadow and base disc stay on the board.
    val shadowW = 2 * PawnShape.SHADOW_RX * s * (1f - 0.45f * liftFraction)
    val shadowH = 2 * PawnShape.SHADOW_RY * s * (1f - 0.45f * liftFraction)
    drawOval(
        Color.Black.copy(alpha = 0.28f * (1f - 0.5f * liftFraction)),
        topLeft = base + Offset(-shadowW / 2, PawnShape.SHADOW_Y * s - shadowH / 2),
        size = Size(shadowW, shadowH),
    )
    val discTopLeft = base + Offset(-PawnShape.BASE_RX * s, (PawnShape.BASE_Y - PawnShape.BASE_RY) * s)
    val discSize = Size(2 * PawnShape.BASE_RX * s, 2 * PawnShape.BASE_RY * s)
    drawOval(colors.main, discTopLeft, discSize)
    drawOval(colors.light.copy(alpha = 0.6f), discTopLeft + Offset(discSize.width * 0.2f, discSize.height * 0.12f), Size(discSize.width * 0.6f, discSize.height * 0.45f))
    drawOval(ink.copy(alpha = 0.8f), discTopLeft, discSize, style = Stroke(s * 0.035f))

    val tip = base + Offset(0f, PawnShape.TIP_Y * s - look.liftPx)
    val r = PawnShape.HEAD_R * s
    val head = base + Offset(0f, PawnShape.HEAD_Y * s - look.liftPx)
    val sx = 1f + 0.14f * look.squash
    val sy = 1f - 0.18f * look.squash
    scale(sx, sy, pivot = tip) {
        // The pin: straight sides from the tip to the tangent points on the head circle.
        val d = hypot(tip.x - head.x, tip.y - head.y)
        val alpha = Math.toDegrees(acos((r / d).toDouble())).toFloat()
        val pin = Path().apply {
            moveTo(tip.x, tip.y)
            arcTo(Rect(head, r), 90f + alpha, 360f - 2 * alpha, false)
            close()
        }
        drawPath(pin, Brush.linearGradient(listOf(Color.White, Color(0xFFE6E2DA), Color(0xFFB9B3A8)), head - Offset(r, r), tip + Offset(r, 0f)))
        drawPath(pin, ink.copy(alpha = 0.85f), style = Stroke(s * 0.035f))
        // The glossy colored ball in the head.
        val ballR = r * 0.68f
        drawCircle(
            Brush.radialGradient(listOf(colors.light, colors.main, colors.dark), center = head - Offset(ballR * 0.35f, ballR * 0.4f), radius = ballR * 1.7f),
            ballR,
            head,
        )
        drawCircle(ink.copy(alpha = 0.5f), ballR, head, style = Stroke(s * 0.02f))
        // White highlight.
        rotate(-35f, head + Offset(-ballR * 0.4f, -ballR * 0.45f)) {
            drawOval(
                Color.White.copy(alpha = 0.9f),
                topLeft = head + Offset(-ballR * 0.4f - ballR * 0.3f, -ballR * 0.45f - ballR * 0.15f),
                size = Size(ballR * 0.6f, ballR * 0.3f),
            )
        }
        if (letter != null) {
            val layout = textMeasurer.measure(
                letter,
                TextStyle(color = Color.White, fontSize = (ballR * 1.2f).toSp(), fontWeight = FontWeight.ExtraBold, fontFamily = Baloo),
            )
            drawText(layout, topLeft = head - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
        if (look.flash > 0f) drawPath(pin, Color.White.copy(alpha = look.flash))
    }
}

/** The letter shown on tokens in colorblind mode. */
fun colorblindLetter(color: PlayerColor) = if (color == PlayerColor.RED) "R" else "Y"

@Preview(widthDp = 320, heightDp = 110)
@Composable
private fun PawnPreview() {
    LudoTheme {
        val palette = LocalLudoPalette.current
        val measurer = rememberTextMeasurer()
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(PlayerColor.RED, PawnLook(), null),
                Triple(PlayerColor.YELLOW, PawnLook(), null),
                Triple(PlayerColor.RED, PawnLook(liftPx = 20f, scale = 1.15f), null),
                Triple(PlayerColor.YELLOW, PawnLook(squash = 1f), null),
                Triple(PlayerColor.RED, PawnLook(), "R"),
                Triple(PlayerColor.YELLOW, PawnLook(), "Y"),
            ).forEach { (color, look, letter) ->
                Canvas(Modifier.size(44.dp, 90.dp)) {
                    drawRect(Color.White)
                    drawPawn(Offset(size.width / 2, size.height * 0.62f), size.width * 1.4f, palette.of(color), look, letter, measurer)
                }
            }
        }
    }
}
