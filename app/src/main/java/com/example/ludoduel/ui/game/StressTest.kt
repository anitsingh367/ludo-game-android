package com.example.ludoduel.ui.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the stress test can do next. */
private enum class StressStep { WAIT, ROLL, MOVE, OVER }

/**
 * Debug builds only: rolls and moves by itself [rolls] times in a row through exactly the same path
 * as a player's taps, and reports if it ever gets stuck (nothing it can do for [stuckMillis], which
 * is longer than an opponent's turn timeout). Run it on both phones for a fast game.
 */
@Composable
fun StressTestEffect(
    running: Boolean,
    rolls: Int,
    stuckMillis: Long,
    canRoll: Boolean,
    movable: List<Int>,
    autoMoving: Boolean,
    over: Boolean,
    onRoll: () -> Unit,
    onMove: (Int) -> Unit,
    onRematch: () -> Unit,
    onFinished: (String) -> Unit,
) {
    val step by rememberUpdatedState(
        when {
            over -> StressStep.OVER
            canRoll -> StressStep.ROLL
            movable.isNotEmpty() && !autoMoving -> StressStep.MOVE
            else -> StressStep.WAIT
        },
    )
    val currentMovable by rememberUpdatedState(movable)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var done = 0
        while (done < rolls) {
            val next = withTimeoutOrNull(stuckMillis) { snapshotFlow { step }.first { it != StressStep.WAIT } }
            when (next) {
                null -> {
                    onFinished("Stress test STUCK after $done rolls")
                    return@LaunchedEffect
                }
                // A finished game: ask for a rematch and keep counting in the next game.
                StressStep.OVER -> {
                    onRematch()
                    val restarted = withTimeoutOrNull(stuckMillis) { snapshotFlow { step }.first { it != StressStep.OVER } }
                    if (restarted == null) {
                        onFinished("Stress test: no rematch after $done rolls (is the other phone still there?)")
                        return@LaunchedEffect
                    }
                    continue
                }
                StressStep.ROLL -> {
                    onRoll()
                    done++
                }
                StressStep.MOVE -> onMove(currentMovable.first())
                StressStep.WAIT -> Unit
            }
            // Wait until that action has been taken up before looking again.
            val takenUp = withTimeoutOrNull(stuckMillis) { snapshotFlow { step }.first { it == StressStep.WAIT || it == StressStep.OVER } }
            if (takenUp == null) {
                onFinished("Stress test STUCK after $done rolls")
                return@LaunchedEffect
            }
        }
        onFinished("Stress test: $done rolls, never stuck")
    }
}
