package com.example.ludoduel.ui.game

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ludoduel.AppContainer
import com.example.ludoduel.data.ChatMessage
import com.example.ludoduel.data.ChatRules
import com.example.ludoduel.data.ChatType
import com.example.ludoduel.data.GameReducer
import com.example.ludoduel.data.NewMessages
import com.example.ludoduel.data.RoomEvent
import com.example.ludoduel.data.RoomGame
import com.example.ludoduel.data.RoomStatus
import com.example.ludoduel.data.SCHEMA_VERSION
import com.example.ludoduel.data.Seats
import com.example.ludoduel.data.SendLimiter
import com.example.ludoduel.engine.Action
import com.example.ludoduel.engine.ActionType
import com.example.ludoduel.engine.Dice
import com.example.ludoduel.engine.LudoEngine
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GameStatus { LOADING, READY, CORRUPT, UPDATE_REQUIRED, GONE }

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
    /** Tokens I may tap right now. Empty whenever input is not allowed. */
    val movable: List<Int> = emptyList(),
    val opponentLeft: Boolean = false,
    val iWantRematch: Boolean = false,
    val opponentWantsRematch: Boolean = false,
    val luckyBoost: Boolean = false,
)

/** The chat as the screen shows it. */
data class ChatUi(
    /** Oldest first. The opponent's messages are left out while they are muted. */
    val messages: List<ChatMessage> = emptyList(),
    val myUid: String = "",
    /** uid -> player name. */
    val names: Map<String, String> = emptyMap(),
    /** The opponent's texts and phrases that arrived while the chat was closed. */
    val unread: Int = 0,
    val muteOpponent: Boolean = false,
    /** Online, in a loaded room. */
    val canSend: Boolean = false,
)

/** Things to show once, for new messages only (never for history, see [NewMessages]). */
sealed interface ChatEvent {
    /** An emoji reaction: it flies from the sender's panel to the other player's. */
    data class Emoji(val id: String, val emojiId: String, val fromMe: Boolean) : ChatEvent
    /** The opponent's text or phrase while the chat is closed: a speech bubble by their panel. */
    data class Bubble(val id: String, val text: String) : ChatEvent
}

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
    /** The game version I last tapped the die at (see [roll]). */
    private var rollTappedAt: Long? = null
    private var presenceAcquired = false

    private val room: StateFlow<RoomEvent?> =
        c.rooms.observeRoom(code).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val muted: StateFlow<Boolean> = c.settings.muted.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val ui: StateFlow<GameUi> = combine(room, uid, c.connection.connected, busy, ::buildUi)
        .stateIn(viewModelScope, SharingStarted.Eagerly, GameUi())

    private val chatOpen = MutableStateFlow(false)
    private val unread = MutableStateFlow(0)
    /** Hides the opponent's messages, bubbles and emojis for the rest of this game screen. */
    private val muteOpponent = MutableStateFlow(false)
    private val newMessages = NewMessages()
    private val emojiLimit = SendLimiter(ChatRules.EMOJI_INTERVAL_MILLIS)
    private val textLimit = SendLimiter(ChatRules.TEXT_INTERVAL_MILLIS)
    private val _chatEvents = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
    val chatEvents: SharedFlow<ChatEvent> = _chatEvents

    val chat: StateFlow<ChatUi> = combine(room, uid, c.connection.connected, unread, muteOpponent) { event, id, online, unreadCount, mute ->
        val r = (event as? RoomEvent.Loaded)?.room ?: return@combine ChatUi()
        val myUid = id.orEmpty()
        ChatUi(
            messages = if (mute) r.chat.filter { it.uid == myUid } else r.chat,
            myUid = myUid,
            names = r.players.mapValues { it.value.name },
            unread = unreadCount,
            muteOpponent = mute,
            canSend = online && id != null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUi())

    init {
        viewModelScope.launch {
            val id = c.auth.signIn()
            uid.value = id
            c.presence.acquire(code, id)
            presenceAcquired = true
            c.settings.setActiveRoom(code)
        }
        runTimers()
        startRematchWhenAgreed()
        watchChat()
    }

    override fun onCleared() {
        if (presenceAcquired) c.presence.release(code)
    }

    fun serverNow(): Long = c.clock.now()

    /**
     * Sends my roll. [onResult] is told whether a roll is on its way (false: ignored or refused).
     * If the timer's automatic roll is being written at the same moment, my tap still wins: it is
     * remembered for this turn (that roll counts as mine and no automatic move follows it, see
     * [playTimers]), and the die waits for that roll.
     */
    fun roll(onResult: (Boolean) -> Unit) {
        val ui = ui.value
        val game = ui.game
        val myRoll = game != null && game.state.turn == ui.me && game.state.phase == Phase.ROLL
        when {
            myRoll && busy.value -> {
                rollTappedAt = game.version
                onResult(true)
            }
            game == null || !ui.canRoll -> onResult(false)
            else -> viewModelScope.launch { onResult(act(game, Action.Roll(rollValue(game)))) }
        }
    }

    fun move(token: Int) {
        val ui = ui.value
        val game = ui.game ?: return
        // Taps on tokens that are not legal moves are ignored.
        if (token !in ui.movable) return
        viewModelScope.launch { act(game, Action.Move(token)) }
    }

    /** Sends an emoji reaction. False when it is too soon after the last one (the tray shakes). */
    fun sendEmoji(emojiId: String): Boolean {
        if (!chat.value.canSend || !emojiLimit.tryAcquire(System.currentTimeMillis())) return false
        sendChat(ChatType.EMOJI, null, emojiId)
        return true
    }

    /** Sends typed text (cleaned, see [ChatRules.clean]). False when empty, too long or too soon. */
    fun sendText(raw: String): Boolean {
        val text = ChatRules.clean(raw) ?: return false
        return sendMessage(ChatType.TEXT, text)
    }

    /** Sends one of the quick phrases. False when too soon. */
    fun sendPhrase(text: String): Boolean = sendMessage(ChatType.PHRASE, text)

    private fun sendMessage(type: ChatType, text: String): Boolean {
        if (!chat.value.canSend || !textLimit.tryAcquire(System.currentTimeMillis())) return false
        sendChat(type, text, null)
        return true
    }

    private fun sendChat(type: ChatType, text: String?, emojiId: String?) {
        val r = (room.value as? RoomEvent.Loaded)?.room ?: return
        val id = uid.value ?: return
        viewModelScope.launch { c.rooms.sendChat(code, id, type, text, emojiId, r.chat, r.chatSeq) }
    }

    /** The chat sheet opened or closed. Opening it marks everything as read. */
    fun setChatOpen(open: Boolean) {
        chatOpen.value = open
        if (open) unread.value = 0
    }

    fun setMuteOpponent(mute: Boolean) {
        muteOpponent.value = mute
        if (mute) unread.value = 0
    }

    /** Reports the opponent with the last 20 messages of the room. [onDone] is told whether it was saved. */
    fun reportOpponent(onDone: (Boolean) -> Unit) {
        val r = (room.value as? RoomEvent.Loaded)?.room ?: return onDone(false)
        val id = uid.value ?: return onDone(false)
        val opponentUid = r.seats?.let { if (it.hostUid == id) it.guestUid else it.hostUid } ?: return onDone(false)
        viewModelScope.launch {
            onDone(runCatching { c.rooms.report(code, id, opponentUid, r.chat.takeLast(REPORT_MESSAGES)) }.isSuccess)
        }
    }

    /**
     * Turns new chat messages into flights and bubbles. The first snapshot after opening the screen
     * and the first after a reconnect are history only, so nothing old is replayed.
     */
    private fun watchChat() {
        viewModelScope.launch {
            var seenOnline = false
            var dropped = false
            c.connection.connected.collect { online ->
                if (online && dropped) newMessages.reset()
                if (online) seenOnline = true
                dropped = !online && seenOnline
            }
        }
        viewModelScope.launch {
            room.collect { event ->
                val r = (event as? RoomEvent.Loaded)?.room ?: return@collect
                for (m in newMessages.of(r.chat)) {
                    val mine = m.uid == uid.value
                    if (!mine && muteOpponent.value) continue
                    when (m.type) {
                        ChatType.EMOJI -> _chatEvents.tryEmit(ChatEvent.Emoji(m.id, checkNotNull(m.emojiId), mine))
                        ChatType.TEXT, ChatType.PHRASE -> if (!mine && !chatOpen.value) {
                            unread.value++
                            _chatEvents.tryEmit(ChatEvent.Bubble(m.id, checkNotNull(m.text)))
                        }
                    }
                }
            }
        }
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

    /** My roll for [game], with the room's Lucky Boost setting (see [Dice.rollFor]). */
    private fun rollValue(game: RoomGame): Int {
        val loaded = checkNotNull((room.value as? RoomEvent.Loaded)?.room)
        val me = checkNotNull(loaded.seats?.colorOf(checkNotNull(uid.value)))
        return Dice.rollFor(game.state, me, loaded.luckyBoost)
    }

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

    private fun buildUi(event: RoomEvent?, id: String?, connected: Boolean, busy: Boolean): GameUi {
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
            canRoll = inputOpen && s.phase == Phase.ROLL,
            movable = if (inputOpen && s.phase == Phase.MOVE) {
                LudoEngine.legalMoves(s, me, checkNotNull(s.dice))
            } else {
                emptyList()
            },
            opponentLeft = r.status == RoomStatus.ABANDONED,
            iWantRematch = r.rematch[id] == game.gameNumber,
            opponentWantsRematch = r.rematch[opponentUid] == game.gameNumber,
            luckyBoost = r.luckyBoost,
        )
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
                    act(game, Action.Roll(rollValue(game), auto = true))
                }
                Phase.MOVE -> {
                    val legal = LudoEngine.legalMoves(s, input.me, checkNotNull(s.dice))
                    val last = s.lastAction
                    when {
                        // The roll was automatic and I did not tap the die for it: finish the turn
                        // right away with the first legal move. If I did tap, I choose my move within
                        // the fresh move time, like after any roll.
                        last?.type == ActionType.ROLL && last.by == input.me && last.auto && rollTappedAt != game.version - 1 -> {
                            delay(AUTO_MOVE_DELAY_MILLIS)
                            act(game, Action.Move(legal.first(), auto = true))
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
        const val AUTO_MOVE_DELAY_MILLIS = 800L
        const val TIMEOUT_MARGIN_MILLIS = 1_000L
        const val TIMEOUT_RETRY_MILLIS = 2_000L
        /** How many recent messages a report includes. */
        const val REPORT_MESSAGES = 20
    }
}
