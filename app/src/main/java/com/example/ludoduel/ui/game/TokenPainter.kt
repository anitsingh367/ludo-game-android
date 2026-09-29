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
 * A 3D-looking map-pin pawn standing on [base] (the square's center): a round head with a pointed
 * foot, a radial gradient in the player's color, a shine on the top left, a dark outline and an
 * oval shadow on the board. [letter] is drawn on the head in colorblind mode.
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
    val maxLift = unit * 0.45f
    val liftFraction = (look.liftPx / maxLift).coerceIn(0f, 1f)

    // Shadow on the board: smaller and lighter while the pawn is in the air.
    val shadowW = s * 0.62f * (1f - 0.45f * liftFraction)
    val shadowH = s * 0.2f * (1f - 0.45f * liftFraction)
    drawOval(
        Color.Black.copy(alpha = 0.32f * (1f - 0.5f * liftFraction)),
        topLeft = base + Offset(-shadowW / 2, s * 0.36f - shadowH / 2),
        size = Size(shadowW, shadowH),
    )

    val tip = base + Offset(0f, s * 0.38f - look.liftPx)
    val r = s * 0.34f
    val head = base + Offset(0f, -s * 0.16f - look.liftPx)
    val sx = 1f + 0.14f * look.squash
    val sy = 1f - 0.18f * look.squash
    scale(sx, sy, pivot = tip) {
        // Pin outline: straight sides from the tip to the tangent points on the head.
        val d = hypot(tip.x - head.x, tip.y - head.y)
        val alpha = Math.toDegrees(acos((r / d).toDouble())).toFloat()
        val pin = Path().apply {
            moveTo(tip.x, tip.y)
            arcTo(Rect(head, r), 90f + alpha, 360f - 2 * alpha, false)
            close()
        }
        drawPath(
            pin,
            Brush.radialGradient(
                listOf(colors.light, colors.main, colors.dark),
                center = head + Offset(-r * 0.35f, -r * 0.45f),
                radius = r * 2.6f,
            ),
        )
        drawPath(pin, colors.dark, style = Stroke(s * 0.055f))
        // A lighter cap on the head, like a game piece.
        drawCircle(Brush.radialGradient(listOf(Color.White, colors.light), center = head - Offset(r * 0.15f, r * 0.2f), radius = r * 0.7f), r * 0.5f, head)
        drawCircle(colors.dark.copy(alpha = 0.35f), r * 0.5f, head, style = Stroke(s * 0.03f))
        // Shine on the top left.
        rotate(-35f, head + Offset(-r * 0.45f, -r * 0.52f)) {
            drawOval(
                Color.White.copy(alpha = 0.85f),
                topLeft = head + Offset(-r * 0.45f - r * 0.24f, -r * 0.52f - r * 0.12f),
                size = Size(r * 0.48f, r * 0.24f),
            )
        }
        if (letter != null) {
            val layout = textMeasurer.measure(
                letter,
                TextStyle(color = colors.dark, fontSize = (r * 0.95f).toSp(), fontWeight = FontWeight.ExtraBold, fontFamily = Baloo),
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
