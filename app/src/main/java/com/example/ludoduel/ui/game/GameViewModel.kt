package com.example.ludoduel.ui.game

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ludoduel.AppContainer
import com.example.ludoduel.data.GameReducer
import com.example.ludoduel.data.RoomEvent
import com.example.ludoduel.data.RoomGame
import com.example.ludoduel.data.RoomStatus
import com.example.ludoduel.data.SCHEMA_VERSION
import com.example.ludoduel.data.Seats
import com.example.ludoduel.engine.Action
import com.example.ludoduel.engine.ActionType
import com.example.ludoduel.engine.Dice
import com.example.ludoduel.engine.LudoEngine
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GameStatus { LOADING, READY, CORRUPT, UPDATE_REQUIRED, GONE }

enum class NoticeKind { NO_MOVE, THREE_SIXES, TIMEOUT }

/** A short message shown after a turn passed without a move. */
data class Notice(val kind: NoticeKind, val by: PlayerColor, val dice: Int?)

data class GameUi(
    val status: GameStatus = GameStatus.LOADING,
    val me: PlayerColor = PlayerColor.RED,
    val myName: String = "",
    val opponentName: String = "",
    val game: RoomGame? = null,
    val connected: Boolean = true,
    val opponentConnected: Boolean = true,
    val opponentLastSeen: Long = 0L,
    val canRoll: Boolean = false,
    /** My roll is being written: the dice animates until the server state arrives. */
    val rolling: Boolean = false,
    /** Tokens I may tap right now. Empty whenever input is not allowed. */
    val movable: List<Int> = emptyList(),
    val notice: Notice? = null,
    val opponentLeft: Boolean = false,
    val iWantRematch: Boolean = false,
    val opponentWantsRematch: Boolean = false,
)

/**
 * Drives the game screen. The UI renders only from the room state received from Firebase;
 * every action goes through a transaction in [com.example.ludoduel.data.RoomRepository.submit].
 */
class GameViewModel(private val c: AppContainer, saved: SavedStateHandle) : ViewModel() {
    /** From the navigation arguments, which are kept in the SavedStateHandle across process death. */
    val code: String = checkNotNull(saved["code"])

    private val uid = MutableStateFlow<String?>(null)
    /** True while one of my transactions is running. Blocks further input (double taps). */
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<Notice?>(null)
    private var presenceAcquired = false

    private val room: StateFlow<RoomEvent?> =
        c.rooms.observeRoom(code).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val muted: StateFlow<Boolean> = c.settings.muted.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val ui: StateFlow<GameUi> = combine(room, uid, c.connection.connected, busy, notice, ::buildUi)
        .stateIn(viewModelScope, SharingStarted.Eagerly, GameUi())

    init {
        viewModelScope.launch {
            val id = c.auth.signIn()
            uid.value = id
            c.presence.acquire(code, id)
            presenceAcquired = true
            c.settings.setActiveRoom(code)
        }
        watchNotices()
        runTimers()
        startRematchWhenAgreed()
    }

    override fun onCleared() {
        if (presenceAcquired) c.presence.release(code)
    }

    fun serverNow(): Long = c.clock.now()

    fun roll() {
        val ui = ui.value
        val game = ui.game ?: return
        if (!ui.canRoll) return
        viewModelScope.launch { act(game, Action.Roll(Dice.roll())) }
    }

    fun move(token: Int) {
        val ui = ui.value
        val game = ui.game ?: return
        // Taps on tokens that are not legal moves are ignored.
        if (token !in ui.movable) return
        viewModelScope.launch { act(game, Action.Move(token)) }
    }

    fun requestRematch() {
        val id = uid.value ?: return
        val game = ui.value.game ?: return
        c.rooms.voteRematch(code, id, game.gameNumber)
    }

    fun toggleMute() {
        viewModelScope.launch { c.settings.setMuted(!muted.value) }
    }

    /**
     * Leaves the room: forfeits if the game is still running (reason "left") and marks the
     * room abandoned. Runs in the app scope so it finishes after this screen closes.
     */
    fun leave() {
        val loaded = (room.value as? RoomEvent.Loaded)?.room
        val id = uid.value
        c.appScope.launch {
            c.settings.setActiveRoom(null)
            if (loaded != null && id != null && ui.value.status == GameStatus.READY) {
                c.rooms.leave(code, loaded.seats, id)
            }
        }
    }

    private fun seats(): Seats? = (room.value as? RoomEvent.Loaded)?.room?.seats

    private suspend fun act(game: RoomGame, action: Action): Boolean {
        val seats = seats() ?: return false
        val id = uid.value ?: return false
        if (busy.value) return false
        busy.value = true
        return try {
            c.rooms.submit(code, seats, game.version, action, id)
        } finally {
            busy.value = false
        }
    }

    private fun buildUi(event: RoomEvent?, id: String?, connected: Boolean, busy: Boolean, notice: Notice?): GameUi {
        val base = GameUi(connected = connected)
        if (event == null || id == null) return base
        val r = when (event) {
            is RoomEvent.Loaded -> event.room
            RoomEvent.Missing, RoomEvent.Denied -> return base.copy(status = GameStatus.GONE)
        }
        if (r.schemaVersion != SCHEMA_VERSION) return base.copy(status = GameStatus.UPDATE_REQUIRED)
        if (r.gameCorrupt) return base.copy(status = GameStatus.CORRUPT)
        // Before the guest's join finishes there is no game yet: keep showing "Loading".
        val seats = r.seats ?: return base
        val game = r.game ?: return base
        val me = seats.colorOf(id) ?: return base.copy(status = GameStatus.GONE)
        val opponentUid = seats.uidOf(me.opponent)
        val opponent = r.players[opponentUid]
        val s = game.state
        val myTurn = s.turn == me && s.phase != Phase.OVER
        // No input while offline (moves are not queued) or while a transaction is running.
        val inputOpen = connected && !busy && myTurn
        return base.copy(
            status = GameStatus.READY,
            me = me,
            myName = r.players[id]?.name.orEmpty(),
            opponentName = opponent?.name.orEmpty(),
            game = game,
            opponentConnected = opponent?.connected == true,
            opponentLastSeen = opponent?.lastSeen ?: 0L,
            canRoll = inputOpen && s.phase == Phase.ROLL && notice == null,
            rolling = busy && myTurn && s.phase == Phase.ROLL,
            movable = if (inputOpen && s.phase == Phase.MOVE) {
                LudoEngine.legalMoves(s, me, checkNotNull(s.dice))
            } else {
                emptyList()
            },
            notice = notice,
            opponentLeft = r.status == RoomStatus.ABANDONED,
            iWantRematch = r.rematch[id] == game.gameNumber,
            opponentWantsRematch = r.rematch[opponentUid] == game.gameNumber,
        )
    }

    /** Shows "no move" / "three 6s" / "ran out of time" for a moment when a turn passes without a move. */
    private fun watchNotices() = viewModelScope.launch {
        var previous: RoomGame? = null
        var job: Job? = null
        room.map { (it as? RoomEvent.Loaded)?.room?.game }.filterNotNull().distinctUntilChanged().collect { game ->
            val prev = previous
            previous = game
            // Only react to the very next version; after a reconnect the board just snaps.
            if (prev == null || game.version != prev.version + 1) return@collect
            val last = game.state.lastAction ?: return@collect
            val kind = when {
                last.type == ActionType.ROLL && game.state.phase == Phase.ROLL && game.state.turn != last.by ->
                    if (last.dice == 6 && prev.state.sixesInRow == 2) NoticeKind.THREE_SIXES else NoticeKind.NO_MOVE
                last.type == ActionType.TIMEOUT && game.state.phase != Phase.OVER -> NoticeKind.TIMEOUT
                else -> return@collect
            }
            job?.cancel()
            job = launch {
                notice.value = Notice(kind, last.by, last.dice)
                delay(NOTICE_MILLIS)
                notice.value = null
            }
        }
    }

    private data class TimerInput(val game: RoomGame, val me: PlayerColor)

    /** Restarts the turn timers every time the game state (or connection) changes. */
    private fun runTimers() = viewModelScope.launch {
        combine(room, uid, c.connection.connected) { event, id, online ->
            val r = (event as? RoomEvent.Loaded)?.room ?: return@combine null
            val game = r.game ?: return@combine null
            val me = r.seats?.colorOf(id ?: return@combine null) ?: return@combine null
            // Offline: no timers and no writes. They restart when the connection comes back.
            if (!online || r.status == RoomStatus.ABANDONED || game.state.phase == Phase.OVER) null
            else TimerInput(game, me)
        }.distinctUntilChanged().collectLatest { input -> input?.let { playTimers(it) } }
    }

    private suspend fun playTimers(input: TimerInput) {
        val game = input.game
        val s = game.state
        if (s.turn == input.me) {
            when (s.phase) {
                Phase.ROLL -> {
                    // My time ran out with the app open: roll for me.
                    waitUntil(game.turnDeadline)
                    act(game, Action.Roll(Dice.roll(), auto = true))
                }
                Phase.MOVE -> {
                    val legal = LudoEngine.legalMoves(s, input.me, checkNotNull(s.dice))
                    val last = s.lastAction
                    when {
                        // The roll was automatic: finish the turn right away with the first legal move.
                        last?.type == ActionType.ROLL && last.by == input.me && last.auto -> {
                            delay(AUTO_MOVE_DELAY_MILLIS)
                            act(game, Action.Move(legal.first(), auto = true))
                        }
                        // Only one choice: play it if the player doesn't tap it first.
                        legal.size == 1 -> {
                            delay(SINGLE_MOVE_DELAY_MILLIS)
                            act(game, Action.Move(legal.single()))
                        }
                        else -> {
                            waitUntil(game.turnDeadline)
                            act(game, Action.Move(legal.first(), auto = true))
                        }
                    }
                }
                Phase.OVER -> Unit
            }
        } else {
            // The opponent's app did not act in time (closed or offline). We may write a Timeout
            // once server time passes the deadline plus the grace period. Retry if the server
            // rejects it because our clock estimate was slightly early.
            waitUntil(game.turnDeadline + GameReducer.TIMEOUT_GRACE_MILLIS + TIMEOUT_MARGIN_MILLIS)
            while (!act(game, Action.Timeout)) delay(TIMEOUT_RETRY_MILLIS)
        }
    }

    private suspend fun waitUntil(serverTime: Long) {
        delay((serverTime - c.clock.now()).coerceAtLeast(0))
    }

    /** Both players asked for a rematch of this game: start it. Both phones may try; one write wins. */
    private fun startRematchWhenAgreed() = viewModelScope.launch {
        room.collect { event ->
            val r = (event as? RoomEvent.Loaded)?.room ?: return@collect
            val game = r.game ?: return@collect
            val seats = r.seats ?: return@collect
            val agreed = r.rematch[seats.hostUid] == game.gameNumber && r.rematch[seats.guestUid] == game.gameNumber
            if (game.state.phase == Phase.OVER && r.status != RoomStatus.ABANDONED && agreed) {
                c.rooms.startRematch(code, seats, game.gameNumber)
            }
        }
    }

    private companion object {
        const val NOTICE_MILLIS = 1_200L
        const val SINGLE_MOVE_DELAY_MILLIS = 1_500L
        const val AUTO_MOVE_DELAY_MILLIS = 800L
        const val TIMEOUT_MARGIN_MILLIS = 1_000L
        const val TIMEOUT_RETRY_MILLIS = 2_000L
    }
}
