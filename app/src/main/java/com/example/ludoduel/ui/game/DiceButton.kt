package com.example.ludoduel.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The dice. While [rolling] it shows random faces (the only optimistic UI in the app) until the
 * server-confirmed value arrives in [value].
 */
@Composable
fun DiceButton(
    value: Int?,
    rolling: Boolean,
    enabled: Boolean,
    pipColor: Color,
    description: String,
    onRoll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val face by produceState(value, value, rolling) {
        this.value = value
        while (rolling) {
            this.value = Random.nextInt(1, 7)
            delay(80)
        }
        this.value = value
    }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        tonalElevation = if (enabled) 6.dp else 1.dp,
        shadowElevation = if (enabled) 6.dp else 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .size(76.dp)
            .border(if (enabled) 3.dp else 1.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onRoll)
            .semantics { contentDescription = description },
    ) {
        Box(Modifier.padding(12.dp).alpha(if (enabled || rolling || face != null) 1f else 0.4f)) {
            Canvas(Modifier.fillMaxSize()) {
                val f = face ?: return@Canvas
                val r = size.minDimension * 0.1f
                val lo = size.minDimension * 0.2f
                val mid = size.minDimension * 0.5f
                val hi = size.minDimension * 0.8f
                val pips = when (f) {
                    1 -> listOf(mid to mid)
                    2 -> listOf(lo to lo, hi to hi)
                    3 -> listOf(lo to lo, mid to mid, hi to hi)
                    4 -> listOf(lo to lo, hi to lo, lo to hi, hi to hi)
                    5 -> listOf(lo to lo, hi to lo, mid to mid, lo to hi, hi to hi)
                    else -> listOf(lo to lo, hi to lo, lo to mid, hi to mid, lo to hi, hi to hi)
                }
                pips.forEach { (x, y) -> drawCircle(pipColor, r, Offset(x, y)) }
            }
        }
    }
}
