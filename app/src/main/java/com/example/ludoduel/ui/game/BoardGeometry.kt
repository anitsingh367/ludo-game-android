package com.example.ludoduel.ui.game

import com.example.ludoduel.engine.GameState
import com.example.ludoduel.engine.HOME
import com.example.ludoduel.engine.LAST_TRACK
import com.example.ludoduel.engine.LudoEngine
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.SAFE_SQUARES
import com.example.ludoduel.engine.TOKENS_PER_PLAYER
import com.example.ludoduel.engine.YARD
import com.example.ludoduel.ui.theme.Seat
import com.example.ludoduel.ui.theme.seat

data class Cell(val col: Int, val row: Int)

/** A point on the board in grid units (0..15 on both axes). */
data class GridPoint(val x: Float, val y: Float)

data class TokenKey(val color: PlayerColor, val index: Int)

/** Where to draw a token: its point and a size factor (smaller when several share a square). */
data class TokenSpot(val point: GridPoint, val scale: Float)

/**
 * Maps game positions to a 15x15 grid. Coordinates are for Red's view (Red yard bottom-left,
 * Yellow yard top-right). [forViewer] rotates the board 180° for Yellow so each player sees
 * their own corner at the bottom. Movement stays clockwise in both views.
 */
object BoardGeometry {
    const val SIZE = 15

    /** The 52 shared squares, clockwise, starting at Red's start square (absolute index 0). */
    val track: List<Cell> = buildList {
        for (r in 13 downTo 9) add(Cell(6, r))       // 0..4   up the bottom arm
        for (c in 5 downTo 0) add(Cell(c, 8))        // 5..10  left along the left arm
        add(Cell(0, 7))                              // 11
        for (c in 0..5) add(Cell(c, 6))              // 12..17 right along the left arm
        for (r in 5 downTo 0) add(Cell(6, r))        // 18..23 up the top arm
        add(Cell(7, 0))                              // 24
        for (r in 0..5) add(Cell(8, r))              // 25..30 down the top arm (26 = Yellow start)
        for (c in 9..14) add(Cell(c, 6))             // 31..36 right along the right arm
        add(Cell(14, 7))                             // 37
        for (c in 14 downTo 9) add(Cell(c, 8))       // 38..43 left along the right arm
        for (r in 9..14) add(Cell(8, r))             // 44..49 down the bottom arm
        add(Cell(7, 14))                             // 50
        add(Cell(6, 14))                             // 51
    }

    /** Top-left cell of each 6x6 yard, in Red's view. Clockwise order: Red, Green, Yellow, Blue. */
    fun yardOrigin(seat: Seat): Cell = when (seat) {
        Seat.RED -> Cell(0, 9)
        Seat.GREEN -> Cell(0, 0)
        Seat.YELLOW -> Cell(9, 0)
        Seat.BLUE -> Cell(9, 9)
    }

    /** Absolute track index of each seat's start square. */
    fun startIndex(seat: Seat): Int = seat.ordinal * 13

    /** The five home-column squares of a seat, from the entry to the center. */
    fun homeColumn(seat: Seat): List<Cell> = (0 until 5).map { i ->
        when (seat) {
            Seat.RED -> Cell(7, 13 - i)
            Seat.GREEN -> Cell(1 + i, 7)
            Seat.YELLOW -> Cell(7, 1 + i)
            Seat.BLUE -> Cell(13 - i, 7)
        }
    }

    /** Safe squares that are not start squares (they get a star; start squares get an arrow). */
    val starSquares: List<Int> = SAFE_SQUARES.filter { it % 13 != 0 }.sorted()

    /**
     * The corner points of each seat's center triangle (two outer corners on the center square's
     * edge next to its home column), in Red's view. The third point is the board center.
     */
    fun centerTriangle(seat: Seat): Pair<GridPoint, GridPoint> = when (seat) {
        Seat.RED -> GridPoint(6f, 9f) to GridPoint(9f, 9f)
        Seat.GREEN -> GridPoint(6f, 6f) to GridPoint(6f, 9f)
        Seat.YELLOW -> GridPoint(6f, 6f) to GridPoint(9f, 6f)
        Seat.BLUE -> GridPoint(9f, 6f) to GridPoint(9f, 9f)
    }

    /** Home column square for progress 51..55. */
    fun homeColumnCell(color: PlayerColor, progress: Int): Cell = homeColumn(color.seat())[progress - (LAST_TRACK + 1)]

    fun cellOf(color: PlayerColor, progress: Int): Cell? = when (progress) {
        in 0..LAST_TRACK -> track[LudoEngine.absoluteSquare(color, progress)]
        in LAST_TRACK + 1 until HOME -> homeColumnCell(color, progress)
        else -> null
    }

    fun center(cell: Cell) = GridPoint(cell.col + 0.5f, cell.row + 0.5f)

    fun yardSpot(color: PlayerColor, index: Int): GridPoint {
        val (x0, y0) = if (color == PlayerColor.RED) 2f to 11f else 11f to 2f
        return GridPoint(x0 + (index % 2) * 2f, y0 + (index / 2) * 2f)
    }

    /** Finished tokens sit in their color's triangle in the middle of the board. */
    fun finishSpot(color: PlayerColor): GridPoint =
        if (color == PlayerColor.RED) GridPoint(7.5f, 8.45f) else GridPoint(7.5f, 6.55f)

    /** Base point (before stacking offsets) of a token at [progress], in Red's view. */
    fun point(color: PlayerColor, index: Int, progress: Int): GridPoint = when (progress) {
        YARD -> yardSpot(color, index)
        HOME -> finishSpot(color)
        else -> center(checkNotNull(cellOf(color, progress)))
    }

    fun forViewer(p: GridPoint, viewer: PlayerColor): GridPoint =
        if (viewer == PlayerColor.RED) p else GridPoint(SIZE - p.x, SIZE - p.y)

    fun forViewer(cell: Cell, viewer: PlayerColor): Cell =
        if (viewer == PlayerColor.RED) cell else Cell(SIZE - 1 - cell.col, SIZE - 1 - cell.row)

    /** The squares a token passes through when moving from [from] to [to], ending at [to]. */
    fun path(color: PlayerColor, index: Int, from: Int, to: Int): List<GridPoint> =
        if (from == YARD) listOf(point(color, index, to))
        else (from + 1..to).map { point(color, index, it) }

    /** Scale of a finished pawn in its color's center triangle. */
    const val FINISHED_SCALE = 0.8f

    /** Where every token is drawn when the whole [state] is at rest. */
    fun layout(state: GameState, viewer: PlayerColor): Map<TokenKey, TokenSpot> =
        layout(
            PlayerColor.entries.flatMap { c -> (0 until TOKENS_PER_PLAYER).map { i -> TokenKey(c, i) to state.tokensOf(c)[i] } }.toMap(),
            viewer,
        )

    /**
     * Where each of the given tokens (key -> progress) is drawn, in the viewer's orientation.
     * Only tokens at rest should be passed in: a token in the middle of a move is not part of any
     * stack. Tokens sharing a square are shrunk and arranged so each one is fully visible and stays
     * inside the square: 2 side by side (60%), 3-4 in a 2x2 (50%), 5-8 in a 3x3 (33%, only possible
     * on a safe square holding both colors). Finished tokens sit on their color's finish spot.
     */
    fun layout(positions: Map<TokenKey, Int>, viewer: PlayerColor): Map<TokenKey, TokenSpot> {
        val result = mutableMapOf<TokenKey, TokenSpot>()
        val onBoard = mutableListOf<Pair<TokenKey, GridPoint>>()
        for ((key, progress) in positions) {
            val p = forViewer(point(key.color, key.index, progress), viewer)
            if (progress == HOME) result[key] = TokenSpot(p, FINISHED_SCALE) else onBoard += key to p
        }
        onBoard.groupBy({ it.second }, { it.first }).forEach { (point, keys) ->
            // Red before Yellow and lower index first, so the arrangement never jumps around.
            val sorted = keys.sortedWith(compareBy({ it.color }, { it.index }))
            sorted.forEachIndexed { i, key ->
                val (dx, dy, scale) = stackSlot(sorted.size, i)
                result[key] = TokenSpot(GridPoint(point.x + dx, point.y + dy), scale)
            }
        }
        return result
    }

    /** Offset (in squares) and scale of the [i]-th of [n] tokens on one square. */
    internal fun stackSlot(n: Int, i: Int): Triple<Float, Float, Float> = when {
        n == 1 -> Triple(0f, 0f, 1f)
        n == 2 -> Triple(if (i == 0) -0.22f else 0.22f, 0f, 0.6f)
        n <= 4 -> Triple(if (i % 2 == 0) -0.25f else 0.25f, if (i < 2) -0.25f else 0.25f, 0.5f)
        else -> Triple(((i % 3) - 1) * 0.33f, ((i / 3) - 1) * 0.33f, 0.33f)
    }
}
