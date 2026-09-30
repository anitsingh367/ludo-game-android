package com.example.ludoduel.engine

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Dice rolls, with the optional Lucky Boost rule. All randomness comes from [SecureRandom].
 *
 * Lucky Boost only helps a player who has no token on the board (so they cannot play at all) and
 * has gone several rolls in a row without a 6:
 *
 * | rolls in a row without a 6 | chance of a 6 |
 * |---|---|
 * | 0-2 | 1 in 6 (normal) |
 * | 3 | 1 in 3 |
 * | 4 | 1 in 2 |
 * | 5 or more | certain |
 *
 * When the roll is not a 6, the other five numbers stay equally likely. With a token on the board,
 * or with the boost off, every roll is a normal fair roll.
 */
object Dice {
    private val secure: Random = SecureRandom().asKotlinRandom()

    /** A fair roll: 1..6, each 1 in 6. */
    fun roll(): Int = roll(streak = 0, boost = false)

    /** Chance of a 6 for a player stuck in the yard after [streak] rolls without a 6. */
    fun sixChance(streak: Int): Double = when {
        streak >= 5 -> 1.0
        streak == 4 -> 1.0 / 2
        streak == 3 -> 1.0 / 3
        else -> 1.0 / 6
    }

    /** Rolls for a player whose Lucky Boost count is [streak]; [boost] is the room setting. */
    fun roll(streak: Int, boost: Boolean, random: Random = secure): Int {
        if (!boost || streak < 3) return random.nextInt(1, 7)
        return if (random.nextDouble() < sixChance(streak)) 6 else random.nextInt(1, 6)
    }

    /**
     * The roll for [color]'s next turn in [state]. The boost counts only while the player has no
     * token on the board (tokens in the home column count as on the board).
     */
    fun rollFor(state: GameState, color: PlayerColor, boost: Boolean, random: Random = secure): Int =
        roll(luckyStreak(state, color), boost, random)

    /** The Lucky Boost count that applies to [color] right now (0 with a token on the board). */
    fun luckyStreak(state: GameState, color: PlayerColor): Int =
        if (state.hasTokenOnBoard(color)) 0 else state.noSixOf(color)
}
