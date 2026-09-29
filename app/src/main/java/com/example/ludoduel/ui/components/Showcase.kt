package com.example.ludoduel.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ludoduel.ui.game.LocalGameFx
import com.example.ludoduel.ui.game.Sfx
import com.example.ludoduel.ui.game.drawDieBody
import com.example.ludoduel.ui.theme.Baloo
import com.example.ludoduel.ui.theme.LocalLudoPalette
import com.example.ludoduel.ui.theme.LudoBackground
import com.example.ludoduel.ui.theme.LudoTheme
import com.example.ludoduel.ui.theme.starPath
import kotlin.math.sin
import kotlin.random.Random

/** Colors for a glossy button: top and bottom of the gradient. */
data class GlossColors(val top: Color, val bottom: Color)

val GoldGloss = GlossColors(Color(0xFFFFD54F), Color(0xFFFF8F00))
val GreenGloss = GlossColors(Color(0xFF69F0AE), Color(0xFF00897B))
val BlueGloss = GlossColors(Color(0xFF64B5F6), Color(0xFF1565C0))
val RedGloss = GlossColors(Color(0xFFFF8A80), Color(0xFFD32F2F))

/**
 * A big, glossy pill button: gradient body, shine on the top half, shadow. It presses down to 0.95
 * while touched and plays the click sound.
 */
@Composable
fun GlossyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: GlossColors = GoldGloss,
    enabled: Boolean = true,
    height: Dp = 60.dp,
    content: (@Composable () -> Unit)? = null,
) {
    val fx = LocalGameFx.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, tween(90), label = "press")
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .shadow(if (pressed) 2.dp else 8.dp, shape)
            .background(Brush.verticalGradient(listOf(colors.top, colors.bottom)), shape)
            .border(2.dp, Color.White.copy(alpha = 0.55f), shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                fx.play(Sfx.CLICK)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        // Shine on the upper half.
        Canvas(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 5.dp)) {
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.05f)), 0f, size.height * 0.5f),
                size = Size(size.width, size.height * 0.45f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
            )
        }
        if (content != null) {
            content()
        } else {
            Text(
                text,
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleLarge.copy(shadow = Shadow(Color.Black.copy(alpha = 0.3f), Offset(0f, 3f), 4f)),
            )
        }
    }
}

/** A translucent rounded card for grouping content on the gradient background. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.14f), shape)
            .border(1.5.dp, Color.White.copy(alpha = 0.35f), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** The game title in big playful gold letters with a shadow. */
@Composable
fun GameTitle(text: String, modifier: Modifier = Modifier, fontSize: Int = 56) {
    Text(
        text,
        modifier = modifier,
        style = TextStyle(
            brush = Brush.verticalGradient(listOf(Color(0xFFFFF59D), Color(0xFFFFC107), Color(0xFFFF8F00))),
            fontFamily = Baloo,
            fontWeight = FontWeight.ExtraBold,
            fontSize = fontSize.sp,
            shadow = Shadow(Color(0xFF311B92).copy(alpha = 0.7f), Offset(0f, 8f), 10f),
        ),
    )
}

/** Two dice bouncing and spinning: a small illustration for the home and waiting screens. */
@Composable
fun BouncingDice(modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val palette = LocalLudoPalette.current
    val loop = rememberInfiniteTransition(label = "dice")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "t")
    val faces = remember { listOf(6, 3, 5, 2, 4, 1) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0..1) {
            val phase = (t + i * 0.5f) % 1f
            val bounce = kotlin.math.abs(sin(phase * Math.PI)).toFloat()
            val face = faces[((t * 3 + i * 2).toInt()) % faces.size]
            Canvas(Modifier.size(size, size * 1.6f).graphicsLayer()) {
                val dieSize = this.size.width
                translate(top = (this.size.height - dieSize) * (1f - bounce)) {
                    rotate(if (i == 0) -20f * bounce else 18f * bounce, Offset(dieSize / 2, dieSize / 2)) {
                        drawDieBody(face, if (i == 0) palette.red.main else palette.blue.main, area = Rect(0f, 0f, dieSize, dieSize))
                    }
                }
                // Shadow on the floor, smaller while the die is up.
                val w = dieSize * (0.8f - 0.35f * bounce)
                drawOval(Color.Black.copy(alpha = 0.25f), Offset((dieSize - w) / 2, this.size.height - dieSize * 0.08f), Size(w, dieSize * 0.12f))
            }
        }
    }
}

/** Big white letter tiles for the room code. */
@Composable
fun LetterTiles(code: String, modifier: Modifier = Modifier, tileWidth: Dp = 46.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        code.forEach { ch ->
            Box(
                Modifier
                    .size(tileWidth, tileWidth * 1.25f)
                    .shadow(6.dp, RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE8EAF6))), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(ch.toString(), color = Color(0xFF311B92), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

/** Confetti falling over the whole area, forever (for the win screen). */
@Composable
fun ConfettiRain(modifier: Modifier = Modifier, count: Int = 70) {
    val time by produceState(0L) { while (true) withFrameMillis { value = it } }
    val pieces = remember {
        val r = Random(7)
        List(count) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    val colors = listOf(Color(0xFFFFD54F), Color(0xFF4FC3F7), Color(0xFFE57373), Color(0xFF81C784), Color(0xFFBA68C8), Color.White)
    Canvas(modifier.graphicsLayer()) {
        val seconds = time / 1000f
        pieces.forEachIndexed { i, (x0, speed, phase) ->
            val fall = ((seconds * (0.12f + speed * 0.18f) + phase) % 1f)
            val x = (x0 + 0.04f * sin((seconds * 2 + i).toDouble()).toFloat()) * size.width
            val y = fall * (size.height + 40f) - 20f
            rotate(seconds * 200f * (if (i % 2 == 0) 1 else -1) + i * 30f, Offset(x, y)) {
                drawRect(colors[i % colors.size], Offset(x - 8f, y - 5f), Size(16f, 10f))
            }
        }
    }
}

/** A golden trophy with a crown on top, drawn in code. */
@Composable
fun Trophy(modifier: Modifier = Modifier) {
    val gold = Brush.verticalGradient(listOf(Color(0xFFFFF176), Color(0xFFFFC107), Color(0xFFE65100)))
    val shine by rememberInfiniteTransition(label = "trophy")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "shine")
    Canvas(modifier.graphicsLayer()) {
        val w = size.width
        val h = size.height
        // Crown.
        val crown = Path().apply {
            moveTo(w * 0.3f, h * 0.2f)
            lineTo(w * 0.3f, h * 0.05f); lineTo(w * 0.4f, h * 0.13f); lineTo(w * 0.5f, h * 0.0f)
            lineTo(w * 0.6f, h * 0.13f); lineTo(w * 0.7f, h * 0.05f); lineTo(w * 0.7f, h * 0.2f); close()
        }
        drawPath(crown, gold)
        drawPath(crown, Color(0xFFBF6A00), style = Stroke(w * 0.012f))
        listOf(0.3f, 0.5f, 0.7f).forEachIndexed { i, x ->
            drawCircle(listOf(Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047))[i], w * 0.022f, Offset(w * x, h * (if (i == 1) 0.02f else 0.07f)))
        }
        // Cup with handles.
        drawArc(Color(0xFFFFB300), 90f, 180f, false, Offset(w * 0.1f, h * 0.27f), Size(w * 0.3f, h * 0.24f), style = Stroke(w * 0.045f))
        drawArc(Color(0xFFFFB300), -90f, 180f, false, Offset(w * 0.6f, h * 0.27f), Size(w * 0.3f, h * 0.24f), style = Stroke(w * 0.045f))
        val cup = Path().apply {
            moveTo(w * 0.22f, h * 0.24f)
            lineTo(w * 0.78f, h * 0.24f)
            cubicTo(w * 0.78f, h * 0.55f, w * 0.62f, h * 0.64f, w * 0.55f, h * 0.66f)
            lineTo(w * 0.55f, h * 0.76f)
            lineTo(w * 0.45f, h * 0.76f)
            lineTo(w * 0.45f, h * 0.66f)
            cubicTo(w * 0.38f, h * 0.64f, w * 0.22f, h * 0.55f, w * 0.22f, h * 0.24f)
            close()
        }
        drawPath(cup, gold)
        drawPath(cup, Color(0xFFBF6A00), style = Stroke(w * 0.014f))
        // Shine and a star on the cup.
        drawPath(starPath(Offset(w * 0.5f, h * 0.42f), w * 0.1f), Color.White.copy(alpha = 0.85f))
        drawOval(Color.White.copy(alpha = 0.25f + 0.35f * shine), Offset(w * 0.3f, h * 0.28f), Size(w * 0.08f, h * 0.22f))
        // Base.
        drawRoundRect(Color(0xFF6D4C41), Offset(w * 0.3f, h * 0.76f), Size(w * 0.4f, h * 0.1f), androidx.compose.ui.geometry.CornerRadius(w * 0.02f))
        drawRoundRect(Color(0xFF4E342E), Offset(w * 0.24f, h * 0.86f), Size(w * 0.52f, h * 0.12f), androidx.compose.ui.geometry.CornerRadius(w * 0.03f))
        drawRoundRect(gold, Offset(w * 0.38f, h * 0.885f), Size(w * 0.24f, h * 0.06f), androidx.compose.ui.geometry.CornerRadius(w * 0.01f))
    }
}

@Preview(widthDp = 380, heightDp = 520)
@Composable
private fun ShowcasePreview() {
    LudoTheme {
        LudoBackground(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GameTitle("Ludo Duel")
                BouncingDice()
                LetterTiles("ABC234")
                GlossyButton("Create Room", {}, Modifier.fillMaxWidth())
                GlossyButton("Join Room", {}, Modifier.fillMaxWidth(), colors = GreenGloss)
                Trophy(Modifier.size(120.dp))
            }
        }
    }
}

/** Places [content] over a full-screen dim scrim. */
@Composable
fun Scrim(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Color(0xCC1A0B3D)), contentAlignment = Alignment.Center, content = content)
}
