package com.example.ludoduel.ui.game

/**
 * My own die, as a small finite state machine (pure Kotlin, so every path is unit-tested).
 *
 *     IDLE --tap--> WAITING --confirmed--> SHOWING --animation done--> IDLE
 *                     |  \--failed / 5 s timeout--> IDLE (+ "Couldn't roll — tap again")
 *
 * - A tap is accepted only in IDLE; the die is disabled from that moment.
 * - The dice animation starts only for a confirmed roll (a server state whose last action is my
 *   roll), plays once with a fixed length, and ends the SHOWING state. Nothing loops.
 * - A roll that is confirmed without a tap (the turn timer rolled for me) is shown the same way.
 * - Every state leads back to IDLE, so the die can never stay stuck.
 */
class RollMachine(private val timeoutMillis: Long = TIMEOUT_MILLIS) {

    sealed interface State {
        data object Idle : State
        data class Waiting(val sinceMillis: Long) : State
        data class Showing(val version: Long) : State
    }

    var state: State = State.Idle
        private set

    /** Highest game version already shown, so a repeated update never plays twice. */
    private var shownVersion = Long.MIN_VALUE

    /** The die can be tapped (the screen also requires that it is my turn to roll). */
    val canTap: Boolean get() = state == State.Idle

    /** A tap on the die. Returns true when the roll should be sent. */
    fun tap(nowMillis: Long): Boolean {
        if (state != State.Idle) return false
        state = State.Waiting(nowMillis)
        return true
    }

    /**
     * A server state arrived whose last action is my roll, at [version]. Returns true when this roll
     * should be animated now (false for an update that was already shown).
     */
    fun rollConfirmed(version: Long): Boolean {
        if (version <= shownVersion) return false
        shownVersion = version
        state = State.Showing(version)
        return true
    }

    /** The dice animation for [version] has finished. */
    fun animationFinished(version: Long) {
        if (state == State.Showing(version)) state = State.Idle
    }

    /** The roll could not be sent (the write failed or was refused). Returns true to show "Couldn't roll". */
    fun sendFailed(): Boolean {
        if (state !is State.Waiting) return false
        state = State.Idle
        return true
    }

    /**
     * Checks the safety net: waiting longer than the timeout gives up. Also call it when the app
     * comes back to the foreground. Returns true to show "Couldn't roll".
     */
    fun tick(nowMillis: Long): Boolean {
        val waiting = state as? State.Waiting ?: return false
        if (nowMillis - waiting.sinceMillis < timeoutMillis) return false
        state = State.Idle
        return true
    }

    /**
     * The board jumped to a later state without animating (for example after reconnecting). Nothing
     * of mine is animating or waiting any more: a roll of mine that arrives later is still shown.
     */
    fun snapped(version: Long) {
        shownVersion = maxOf(shownVersion, version)
        state = State.Idle
    }

    companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
