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
import androidx.compose.ui.graphics.drawscope.rotate
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

private val Red = SeatColors(Color(0xFFE53935), Color(0xFF9E1B17), Color(0xFFFFCDD2))
private val Green = SeatColors(Color(0xFF43A047), Color(0xFF1B5E20), Color(0xFFC8E6C9))
private val Yellow = SeatColors(Color(0xFFFDD835), Color(0xFFB08A00), Color(0xFFFFF59D))
private val Blue = SeatColors(Color(0xFF1E88E5), Color(0xFF0D47A1), Color(0xFFBBDEFB))

private val LightPalette = LudoPalette(
    red = Red, green = Green, yellow = Yellow, blue = Blue,
    track = Color.White, trackLine = Color(0xFFD5D8DC),
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

/** TEMPORARY (redesign in progress): colors for the old board drawing, removed once it is redrawn. */
@Immutable
data class BoardColors(
    val red: Color, val redDark: Color, val yellow: Color, val yellowDark: Color, val neutral: Color,
    val cell: Color, val grid: Color, val board: Color, val star: Color, val glow: Color,
) {
    fun of(color: PlayerColor) = if (color == PlayerColor.RED) red else yellow
    fun darkOf(color: PlayerColor) = if (color == PlayerColor.RED) redDark else yellowDark
}

val LocalBoardColors = staticCompositionLocalOf {
    BoardColors(
        red = Red.main, redDark = Red.dark, yellow = Yellow.main, yellowDark = Yellow.dark,
        neutral = Color(0xFFCFD2D6), cell = Color.White, grid = Color(0xFF9EA3A8),
        board = Color(0xFFF1EFEA), star = Color(0xFF7A7F85), glow = Color.White,
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
 */
@Composable
fun LudoBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalLudoPalette.current
    Box(
        modifier.drawWithCache {
            val gradient = Brush.verticalGradient(listOf(palette.backgroundTop, palette.backgroundBottom))
            val step = 64.dp.toPx()
            onDrawBehind {
                drawRect(gradient)
                drawPattern(step, Color.White.copy(alpha = 0.06f))
            }
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

/** A staggered grid of small dice, stars and diamonds. */
private fun DrawScope.drawPattern(step: Float, color: Color) {
    val cols = (size.width / step).toInt() + 2
    val rows = (size.height / step).toInt() + 2
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val x = c * step + if (r % 2 == 0) 0f else step / 2
            val y = r * step
            val center = Offset(x, y)
            when ((r * 7 + c * 3) % 3) {
                0 -> drawPatternDie(center, step * 0.22f, color)
                1 -> drawPatternStar(center, step * 0.16f, color)
                else -> drawPatternDiamond(center, step * 0.12f, color)
            }
        }
    }
}

private fun DrawScope.drawPatternDie(center: Offset, half: Float, color: Color) {
    rotate(15f, center) {
        drawRoundRect(
            color, center - Offset(half, half), Size(half * 2, half * 2), CornerRadius(half * 0.35f),
            style = Stroke(half * 0.18f),
        )
        drawCircle(color, half * 0.16f, center)
        drawCircle(color, half * 0.16f, center + Offset(-half * 0.5f, -half * 0.5f))
        drawCircle(color, half * 0.16f, center + Offset(half * 0.5f, half * 0.5f))
    }
}

fun DrawScope.drawPatternStar(center: Offset, radius: Float, color: Color, style: Stroke? = null) {
    val path = starPath(center, radius)
    if (style == null) drawPath(path, color) else drawPath(path, color, style = style)
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

private fun DrawScope.drawPatternDiamond(center: Offset, radius: Float, color: Color) {
    drawPath(
        Path().apply {
            moveTo(center.x, center.y - radius)
            lineTo(center.x + radius * 0.7f, center.y)
            lineTo(center.x, center.y + radius)
            lineTo(center.x - radius * 0.7f, center.y)
            close()
        },
        color,
    )
}
