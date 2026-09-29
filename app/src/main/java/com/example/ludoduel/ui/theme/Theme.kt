package com.example.ludoduel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ludoduel.R
import com.example.ludoduel.engine.PlayerColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The four board colors. Only Red and Yellow play; Green and Blue are drawn so the board looks complete. */
enum class Seat { RED, GREEN, YELLOW, BLUE }

fun PlayerColor.seat(): Seat = if (this == PlayerColor.RED) Seat.RED else Seat.YELLOW

/** One board color: the main color, a darker shade for outlines/shadows and a light tint for yard slots. */
@Immutable
data class SeatColors(val main: Color, val dark: Color, val light: Color)

/** Colors used to draw the board, tokens and panels. Bright in both light and dark mode. */
@Immutable
data class LudoPalette(
    val red: SeatColors,
    val green: SeatColors,
    val yellow: SeatColors,
    val blue: SeatColors,
    val track: Color,
    val trackLine: Color,
    val frameLight: Color,
    val frameDark: Color,
    val gold: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val amber: Color,
) {
    fun of(seat: Seat): SeatColors = when (seat) {
        Seat.RED -> red
        Seat.GREEN -> green
        Seat.YELLOW -> yellow
        Seat.BLUE -> blue
    }

    fun of(color: PlayerColor): SeatColors = of(color.seat())
}

// Warm, printed-looking colors (flat on the board).
private val Red = SeatColors(Color(0xFFD9443A), Color(0xFF9A2A22), Color(0xFFF4B7B1))
private val Green = SeatColors(Color(0xFF3F9B4F), Color(0xFF276A34), Color(0xFFB5DDBB))
private val Yellow = SeatColors(Color(0xFFEDBE2E), Color(0xFFA8821A), Color(0xFFF8E2A0))
private val Blue = SeatColors(Color(0xFF2F7FCF), Color(0xFF1D5A99), Color(0xFFB2D2F0))

private val LightPalette = LudoPalette(
    red = Red, green = Green, yellow = Yellow, blue = Blue,
    track = Color(0xFFFBF6EA), trackLine = Color(0xFF3B2F2A).copy(alpha = 0.55f),
    frameLight = Color(0xFFF3C969), frameDark = Color(0xFFA86B22), gold = Color(0xFFFFC107),
    backgroundTop = Color(0xFF3F51B5), backgroundBottom = Color(0xFF7B1FA2),
    amber = Color(0xFFEF7C00),
)

/** Dark mode keeps the bright board and uses a deeper background. */
private val DarkPalette = LightPalette.copy(
    backgroundTop = Color(0xFF1A237E), backgroundBottom = Color(0xFF4A148C),
)

val LocalLudoPalette = staticCompositionLocalOf { LightPalette }

/** Baloo 2, a rounded playful font (SIL Open Font License, see ASSETS.md), bundled as one variable font. */
val Baloo = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.baloo2, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private val BalooTypography: Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = Baloo, fontWeight = FontWeight.ExtraBold),
        displayMedium = displayMedium.copy(fontFamily = Baloo, fontWeight = FontWeight.ExtraBold),
        displaySmall = displaySmall.copy(fontFamily = Baloo, fontWeight = FontWeight.ExtraBold),
        headlineLarge = headlineLarge.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.copy(fontFamily = Baloo, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = Baloo, fontWeight = FontWeight.Medium),
        bodyMedium = bodyMedium.copy(fontFamily = Baloo, fontWeight = FontWeight.Medium),
        bodySmall = bodySmall.copy(fontFamily = Baloo, fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontFamily = Baloo, fontWeight = FontWeight.Bold),
        labelMedium = labelMedium.copy(fontFamily = Baloo, fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = Baloo, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun LudoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Sheets and dialogs use Material surfaces; the screens themselves sit on the gradient background.
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFFFFCA28), onPrimary = Color(0xFF3E2723), secondary = Color(0xFF80DEEA))
    } else {
        lightColorScheme(primary = Color(0xFF5E35B1), secondary = Color(0xFFFFB300))
    }
    CompositionLocalProvider(LocalLudoPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = scheme, typography = BalooTypography, content = content)
    }
}

/**
 * The game background: a vertical royal-blue to purple gradient with a faint (6%) pattern of dice,
 * stars and diamonds. Content on top is white by default.
 *
 * The background has its own graphics layer and its shapes are built once, so it is never redrawn
 * when something on top of it animates.
 */
@Composable
fun LudoBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalLudoPalette.current
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer()
                .drawWithCache {
                    val gradient = Brush.verticalGradient(listOf(palette.backgroundTop, palette.backgroundBottom))
                    val pattern = buildPattern(size, 64.dp.toPx())
                    val color = Color.White.copy(alpha = 0.06f)
                    onDrawBehind {
                        drawRect(gradient)
                        drawPath(pattern.filled, color)
                        drawPath(pattern.outlined, color, style = Stroke(pattern.stroke))
                    }
                },
        )
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

private class Pattern(val filled: Path, val outlined: Path, val stroke: Float)

/** A staggered grid of small dice, stars and diamonds, built as two paths. */
private fun buildPattern(size: Size, step: Float): Pattern {
    val filled = Path()
    val outlined = Path()
    val cols = (size.width / step).toInt() + 2
    val rows = (size.height / step).toInt() + 2
    val half = step * 0.22f
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val center = Offset(c * step + if (r % 2 == 0) 0f else step / 2, r * step)
            when ((r * 7 + c * 3) % 3) {
                0 -> {
                    // A die: rounded outline with three pips.
                    outlined.addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            center.x - half, center.y - half, center.x + half, center.y + half,
                            CornerRadius(half * 0.35f),
                        ),
                    )
                    for (k in -1..1) {
                        filled.addOval(androidx.compose.ui.geometry.Rect(center + Offset(k * half * 0.5f, k * half * 0.5f), half * 0.16f))
                    }
                }
                1 -> filled.addPath(starPath(center, step * 0.16f))
                else -> {
                    val d = step * 0.12f
                    filled.moveTo(center.x, center.y - d)
                    filled.lineTo(center.x + d * 0.7f, center.y)
                    filled.lineTo(center.x, center.y + d)
                    filled.lineTo(center.x - d * 0.7f, center.y)
                    filled.close()
                }
            }
        }
    }
    return Pattern(filled, outlined, half * 0.18f)
}

/** A five-pointed star centered on [center]. */
fun starPath(center: Offset, radius: Float, innerRatio: Float = 0.45f): Path = Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else radius * innerRatio
        val angle = -PI / 2 + i * PI / 5
        val x = center.x + (r * cos(angle)).toFloat()
        val y = center.y + (r * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

