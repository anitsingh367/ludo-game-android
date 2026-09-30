package com.example.ludoduel.engine

/**
 * The only place where Ludo rules live. Every function is pure: it never mutates its input
 * and never throws for an illegal action (it returns a failed [Result] instead).
 */
object LudoEngine {

    /** Absolute track square (0..51) for a token of [color] at [progress] 0..50. */
    fun absoluteSquare(color: PlayerColor, progress: Int): Int =
        (color.startIndex + progress) % TRACK_SIZE

    /** Returns the indexes of [color]'s tokens that can legally move [dice] squares. */
    fun legalMoves(state: GameState, color: PlayerColor, dice: Int): List<Int> {
        if (dice !in 1..6) return emptyList()
        val tokens = state.tokensOf(color)
        return tokens.indices.filter { targetOf(tokens[it], dice) != null }
    }

    /**
     * When it is [color]'s turn to move and exactly one token has a legal move, that token (the app
     * moves it by itself). Null when there is nothing to move or the player has a choice (two or more
     * tokens can move, including two tokens on the same square or bringing out one of several yard
     * tokens with a 6).
     */
    fun onlyMovableToken(state: GameState, color: PlayerColor): Int? {
        if (state.phase != Phase.MOVE || state.turn != color) return null
        return legalMoves(state, color, checkNotNull(state.dice)).singleOrNull()
    }

    fun apply(state: GameState, action: Action, actor: PlayerColor): Result<GameState> {
        if (state.phase == Phase.OVER) return fail("The game is over")
        return when (action) {
            is Action.Roll -> roll(state, action, actor)
            is Action.Move -> move(state, action, actor)
            Action.Timeout -> timeout(state)
            Action.Forfeit -> Result.success(
                state.copy(
                    phase = Phase.OVER,
                    dice = null,
                    winner = actor.opponent,
                    winReason = WinReason.LEFT,
                    lastAction = LastAction(ActionType.LEFT, actor),
                )
            )
        }
    }

    /** Basic sanity check for a state that came over the network. */
    fun isValid(state: GameState): Boolean {
        val tokensOk = listOf(state.red, state.yellow).all { list ->
            list.size == TOKENS_PER_PLAYER && list.all { it in YARD..HOME }
        }
        val diceOk = when (state.phase) {
            // A MOVE state always has a legal move: with none, the engine passes the turn.
            Phase.MOVE -> state.dice?.let { it in 1..6 && legalMoves(state, state.turn, it).isNotEmpty() } == true
            else -> state.dice == null
        }
        val winnerOk = (state.phase == Phase.OVER) == (state.winner != null && state.winReason != null)
        return tokensOk && diceOk && winnerOk &&
            state.sixesInRow in 0..2 &&
            state.missedRed in 0..MAX_MISSED_TURNS && state.missedYellow in 0..MAX_MISSED_TURNS &&
            state.noSixRed >= 0 && state.noSixYellow >= 0
    }

    private fun roll(state: GameState, action: Action.Roll, actor: PlayerColor): Result<GameState> {
        if (actor != state.turn) return fail("Not your turn")
        if (state.phase != Phase.ROLL) return fail("You already rolled")
        if (action.value !in 1..6) return fail("Dice value must be 1..6")

        val missed = if (action.auto) state.missedOf(actor) + 1 else 0
        val record = LastAction(ActionType.ROLL, actor, dice = action.value, auto = action.auto)
        // Lucky Boost count: grows while the player has no token on the board and rolls no 6.
        val noSix = if (state.hasTokenOnBoard(actor) || action.value == 6) 0 else state.noSixOf(actor) + 1
        val counted = state.withMissed(actor, missed).withNoSix(actor, noSix).copy(lastAction = record)
        if (missed >= MAX_MISSED_TURNS) return Result.success(forfeitByTimeouts(counted, actor))

        val sixes = if (action.value == 6) state.sixesInRow + 1 else 0
        // Third six in a row: the roll is cancelled and the turn passes.
        if (sixes == 3) return Result.success(passTurn(counted))
        // No legal move: the turn passes (the UI shows the dice for a moment).
        if (legalMoves(state, actor, action.value).isEmpty()) return Result.success(passTurn(counted))

        return Result.success(counted.copy(phase = Phase.MOVE, dice = action.value, sixesInRow = sixes))
    }

    private fun move(state: GameState, action: Action.Move, actor: PlayerColor): Result<GameState> {
        if (actor != state.turn) return fail("Not your turn")
        if (state.phase != Phase.MOVE) return fail("Roll first")
        val dice = state.dice ?: return fail("No dice value")
        val tokens = state.tokensOf(actor)
        if (action.token !in tokens.indices) return fail("No such token")
        val from = tokens[action.token]
        val to = targetOf(from, dice) ?: return fail("That token cannot move")

        var next = state.withTokens(actor, tokens.toMutableList().also { it[action.token] = to })

        // Capture, only when landing: a single opponent token on a normal (not safe) square goes back
        // to the yard. Two or more opponent tokens are a safe pair: the square is shared.
        var captured: Int? = null
        if (to <= LAST_TRACK) {
            val square = absoluteSquare(actor, to)
            if (square !in SAFE_SQUARES) {
                val opp = actor.opponent
                val oppTokens = next.tokensOf(opp)
                val hits = oppTokens.indices.filter { isOnSquare(opp, oppTokens[it], square) }
                if (hits.size == 1) {
                    captured = hits[0]
                    next = next.withTokens(opp, oppTokens.toMutableList().also { it[hits[0]] = YARD })
                }
            }
        }

        val record = LastAction(
            ActionType.MOVE, actor, dice = dice, token = action.token,
            from = from, to = to, captured = captured, auto = action.auto,
        )
        next = next.copy(lastAction = record)
        if (!action.auto) next = next.withMissed(actor, 0)

        // Last token home: the game ends at once, no extra turn.
        if (next.tokensOf(actor).all { it == HOME }) {
            return Result.success(
                next.copy(phase = Phase.OVER, dice = null, winner = actor, winReason = WinReason.ALL_HOME)
            )
        }

        // One extra turn at most, even when several reasons apply.
        val extraTurn = dice == 6 || captured != null || to == HOME
        return Result.success(
            if (extraTurn) next.copy(phase = Phase.ROLL, dice = null) else passTurn(next)
        )
    }

    private fun timeout(state: GameState): Result<GameState> {
        val late = state.turn
        val missed = state.missedOf(late) + 1
        val next = state.withMissed(late, missed).copy(lastAction = LastAction(ActionType.TIMEOUT, late))
        return Result.success(if (missed >= MAX_MISSED_TURNS) forfeitByTimeouts(next, late) else passTurn(next))
    }

    private fun forfeitByTimeouts(state: GameState, loser: PlayerColor): GameState =
        state.copy(phase = Phase.OVER, dice = null, winner = loser.opponent, winReason = WinReason.FORFEIT)

    private fun passTurn(state: GameState): GameState =
        state.copy(turn = state.turn.opponent, phase = Phase.ROLL, dice = null, sixesInRow = 0)

    /**
     * Where a token at [from] ends up after moving [dice] squares, or null if that move is illegal:
     * leaving the yard needs a 6 and finishing needs the exact number. Nothing on the track stops a
     * token (safe pairs can be passed and shared).
     */
    private fun targetOf(from: Int, dice: Int): Int? {
        if (from == HOME) return null
        if (from == YARD) return if (dice == 6) 0 else null
        val to = from + dice
        return if (to > HOME) null else to
    }

    private fun isOnSquare(color: PlayerColor, progress: Int, square: Int): Boolean =
        progress in 0..LAST_TRACK && absoluteSquare(color, progress) == square

    private fun fail(message: String): Result<GameState> = Result.failure(IllegalActionException(message))
}
