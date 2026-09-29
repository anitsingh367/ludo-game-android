package com.example.ludoduel.engine

/**
 * Pure Kotlin model of one Ludo game. No Android or Firebase types here, so both phones
 * compute identical results and the rules can be unit-tested on the JVM.
 *
 * Token positions are "progress" values from the token's own start square:
 *  -1 = yard, 0..50 = shared track, 51..55 = own home column, 56 = home (finished).
 */
enum class PlayerColor(val startIndex: Int) {
    RED(0),
    YELLOW(26);

    val opponent: PlayerColor
        get() = if (this == RED) YELLOW else RED
}

enum class Phase { ROLL, MOVE, OVER }

enum class WinReason { ALL_HOME, FORFEIT, LEFT }

enum class ActionType { ROLL, MOVE, TIMEOUT, LEFT }

/** What the last applied action did. Used by the UI to animate and to show messages. */
data class LastAction(
    val type: ActionType,
    val by: PlayerColor,
    val dice: Int? = null,
    val token: Int? = null,
    val from: Int? = null,
    val to: Int? = null,
    /** Index of the opponent token that was sent back to the yard, if any. */
    val captured: Int? = null,
    /** True when the app played this action automatically because the turn timer ran out. */
    val auto: Boolean = false,
)

data class GameState(
    val red: List<Int>,
    val yellow: List<Int>,
    val turn: PlayerColor,
    val phase: Phase,
    /** The rolled value while in MOVE phase; null in ROLL and OVER phases. */
    val dice: Int?,
    val sixesInRow: Int,
    val missedRed: Int,
    val missedYellow: Int,
    /**
     * Lucky Boost counters: rolls in a row without a 6 while the player had no token on the board
     * (all in the yard, or finished). Any 6, or a token on the board, resets it to 0.
     */
    val noSixRed: Int,
    val noSixYellow: Int,
    val winner: PlayerColor?,
    val winReason: WinReason?,
    val lastAction: LastAction?,
) {
    fun tokensOf(color: PlayerColor): List<Int> = if (color == PlayerColor.RED) red else yellow

    fun missedOf(color: PlayerColor): Int = if (color == PlayerColor.RED) missedRed else missedYellow

    fun noSixOf(color: PlayerColor): Int = if (color == PlayerColor.RED) noSixRed else noSixYellow

    /** True when [color] has a token on the board (shared track or home column). */
    fun hasTokenOnBoard(color: PlayerColor): Boolean = tokensOf(color).any { it in 0 until HOME }

    internal fun withTokens(color: PlayerColor, tokens: List<Int>): GameState =
        if (color == PlayerColor.RED) copy(red = tokens) else copy(yellow = tokens)

    internal fun withMissed(color: PlayerColor, missed: Int): GameState =
        if (color == PlayerColor.RED) copy(missedRed = missed) else copy(missedYellow = missed)

    internal fun withNoSix(color: PlayerColor, streak: Int): GameState =
        if (color == PlayerColor.RED) copy(noSixRed = streak) else copy(noSixYellow = streak)

    companion object {
        fun initial(first: PlayerColor): GameState = GameState(
            red = List(TOKENS_PER_PLAYER) { YARD },
            yellow = List(TOKENS_PER_PLAYER) { YARD },
            turn = first,
            phase = Phase.ROLL,
            dice = null,
            sixesInRow = 0,
            missedRed = 0,
            missedYellow = 0,
            noSixRed = 0,
            noSixYellow = 0,
            winner = null,
            winReason = null,
            lastAction = null,
        )
    }
}

sealed interface Action {
    data class Roll(val value: Int, val auto: Boolean = false) : Action
    data class Move(val token: Int, val auto: Boolean = false) : Action
    /** The current player's turn timer ran out and their app did not act. */
    data object Timeout : Action
    /** The actor leaves the game and loses. */
    data object Forfeit : Action
}

class IllegalActionException(message: String) : Exception(message)

const val TOKENS_PER_PLAYER = 4
const val YARD = -1
const val LAST_TRACK = 50
const val HOME = 56
const val TRACK_SIZE = 52
const val MAX_MISSED_TURNS = 3
val SAFE_SQUARES: Set<Int> = setOf(0, 8, 13, 21, 26, 34, 39, 47)
