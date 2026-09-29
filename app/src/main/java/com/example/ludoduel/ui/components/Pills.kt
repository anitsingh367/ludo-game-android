package com.example.ludoduel.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** A small rounded message. [spinner] shows a spinning ring (used while someone reconnects). */
data class Pill(val text: String, val color: Color, val spinner: Boolean = false)

/**
 * Short messages that slide down from the top, stay about 2 seconds and slide away, one at a time.
 * Call [run] from a LaunchedEffect.
 */
@Stable
class PillState {
    var current by mutableStateOf<Pill?>(null)
        private set
    private val queue = Channel<Pill>(Channel.UNLIMITED)
    private var waiting = 0

    fun show(text: String, color: Color) {
        waiting++
        queue.trySend(Pill(text, color))
    }

    suspend fun run() {
        for (pill in queue) {
            waiting--
            current = pill
            // Shorter when more messages are waiting, so they never pile up.
            delay(if (waiting > 0) 1_200 else 2_000)
            current = null
            delay(280)
        }
    }
}

/** Shows the persistent pill (if any) and the current short message below it. */
@Composable
fun PillHost(state: PillState, persistent: Pill?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SlidingPill(persistent)
        SlidingPill(state.current)
    }
}

@Composable
private fun SlidingPill(pill: Pill?) {
    // Keep the last pill while it slides away.
    var last by remember { mutableStateOf(pill) }
    if (pill != null) last = pill
    AnimatedVisibility(
        visible = pill != null,
        enter = slideInVertically(tween(250)) { -it * 2 } + fadeIn(tween(250)),
        exit = slideOutVertically(tween(250)) { -it * 2 } + fadeOut(tween(250)),
    ) {
        last?.let { PillView(it) }
    }
}

@Composable
fun PillView(pill: Pill, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .shadow(6.dp, shape)
            .background(pill.color, shape)
            .border(1.5.dp, Color.White.copy(alpha = 0.6f), shape)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pill.spinner) Spinner()
        Text(pill.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun Spinner() {
    val angle by rememberInfiniteTransition(label = "spin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "angle")
    Canvas(Modifier.size(14.dp)) {
        rotate(angle) {
            drawArc(Color.White, 0f, 270f, false, style = Stroke(size.width * 0.18f, cap = StrokeCap.Round))
        }
    }
}
