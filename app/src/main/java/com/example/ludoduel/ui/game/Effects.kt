package com.example.ludoduel.ui.game

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import com.example.ludoduel.ui.theme.starPath
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class BurstKind(val lifetimeMillis: Long, val count: Int) {
    /** Paper confetti falling from the center when a token reaches home. */
    CONFETTI(1400, 28),
    /** Little stars bursting out of a captured token. */
    STARS(650, 9),
    /** A quick sparkle when a token leaves the yard. */
    SPARKLE(500, 10),
}

/** One effect burst at a board point (grid units, viewer's orientation). */
data class Burst(val kind: BurstKind, val origin: Offset, val color: Color, val startNanos: Long, val seed: Int)

/**
 * Short particle effects on the board. Every particle's position is a function of time, so nothing
 * is updated per particle: the board just redraws while bursts are alive.
 */
@Stable
class BoardEffects {
    val bursts = mutableStateListOf<Burst>()

    fun add(kind: BurstKind, origin: Offset, color: Color) {
        bursts += Burst(kind, origin, color, System.nanoTime(), Random.nextInt())
    }

    fun prune(nowNanos: Long) {
        bursts.removeAll { (nowNanos - it.startNanos) / 1_000_000 > it.kind.lifetimeMillis }
    }
}

private val confettiColors = listOf(Color(0xFFFFD54F), Color(0xFF4FC3F7), Color(0xFFE57373), Color(0xFF81C784), Color(0xFFBA68C8), Color.White)

fun DrawScope.drawBursts(bursts: List<Burst>, nowNanos: Long, unit: Float) {
    for (burst in bursts) {
        val t = ((nowNanos - burst.startNanos) / 1_000_000f) / burst.kind.lifetimeMillis
        if (t !in 0f..1f) continue
        val random = Random(burst.seed)
        val origin = burst.origin * unit
        val fade = if (t < 0.7f) 1f else (1f - t) / 0.3f
        repeat(burst.kind.count) { i ->
            val angle = (i.toFloat() / burst.kind.count) * 2 * Math.PI.toFloat() + random.nextFloat() * 0.6f
            val speed = 0.6f + random.nextFloat() * 0.8f
            when (burst.kind) {
                BurstKind.CONFETTI -> {
                    // Thrown up and out, then falling with gravity while spinning.
                    val vx = cos(angle) * speed * 2.2f
                    val vy = sin(angle) * speed * 1.6f - 2.4f
                    val p = origin + Offset(vx * t * unit * 1.6f, (vy * t + 4.5f * t * t) * unit * 1.6f)
                    val color = if (i % 3 == 0) burst.color else confettiColors[i % confettiColors.size]
                    rotate(t * 720f * (if (i % 2 == 0) 1 else -1) + i * 40f, p) {
                        drawRect(color.copy(alpha = fade), p - Offset(unit * 0.09f, unit * 0.05f), Size(unit * 0.18f, unit * 0.1f))
                    }
                }
                BurstKind.STARS -> {
                    val dist = unit * 1.3f * speed * (1f - (1f - t) * (1f - t))
                    val p = origin + Offset(cos(angle) * dist, sin(angle) * dist)
                    drawPath(starPath(p, unit * 0.2f * (1f - 0.5f * t)), Color(0xFFFFEB3B).copy(alpha = fade))
                }
                BurstKind.SPARKLE -> {
                    val dist = unit * 0.8f * speed * t
                    val p = origin + Offset(cos(angle) * dist, sin(angle) * dist)
                    drawCircle(Color.White.copy(alpha = fade), unit * 0.08f * (1f - t), p)
                    drawCircle(burst.color.copy(alpha = fade * 0.8f), unit * 0.05f * (1f - t), p)
                }
            }
        }
    }
}
