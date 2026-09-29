package com.example.ludoduel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.ludoduel.engine.PlayerColor

/** Colors used to draw the board and tokens, per light/dark theme. */
@Immutable
data class BoardColors(
    val red: Color,
    val redDark: Color,
    val yellow: Color,
    val yellowDark: Color,
    val neutral: Color,
    val cell: Color,
    val grid: Color,
    val board: Color,
    val star: Color,
    val glow: Color,
) {
    fun of(color: PlayerColor) = if (color == PlayerColor.RED) red else yellow
    fun darkOf(color: PlayerColor) = if (color == PlayerColor.RED) redDark else yellowDark
}

private val LightBoard = BoardColors(
    red = Color(0xFFD32F2F), redDark = Color(0xFF8E1B1B),
    yellow = Color(0xFFF9B90F), yellowDark = Color(0xFF8A6100),
    neutral = Color(0xFFCFD2D6), cell = Color.White, grid = Color(0xFF9EA3A8),
    board = Color(0xFFF1EFEA), star = Color(0xFF7A7F85), glow = Color(0xFF1565C0),
)

private val DarkBoard = BoardColors(
    red = Color(0xFFE57373), redDark = Color(0xFF7F1D1D),
    yellow = Color(0xFFFFD54F), yellowDark = Color(0xFF7A5A00),
    neutral = Color(0xFF3C4046), cell = Color(0xFF24272B), grid = Color(0xFF5B6168),
    board = Color(0xFF1A1C1F), star = Color(0xFFA7ADB4), glow = Color(0xFF90CAF9),
)

val LocalBoardColors = staticCompositionLocalOf { LightBoard }

@Composable
fun LudoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFFEF9A9A), secondary = Color(0xFFFFD54F))
    } else {
        lightColorScheme(primary = Color(0xFFC62828), secondary = Color(0xFFF9A825))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalBoardColors provides if (dark) DarkBoard else LightBoard) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
