package com.example.ludoduel.ui.game

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ludoduel.R
import com.example.ludoduel.data.GameReducer
import com.example.ludoduel.data.RoomGame
import com.example.ludoduel.engine.ActionType
import com.example.ludoduel.engine.HOME
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.WinReason
import com.example.ludoduel.engine.YARD
import com.example.ludoduel.ui.components.Glyph
import com.example.ludoduel.ui.components.GlyphButton
import com.example.ludoduel.ui.containerViewModel
import com.example.ludoduel.ui.theme.LocalBoardColors
import kotlinx.coroutines.delay

@Composable
fun GameScreen(onExit: () -> Unit) {
    val vm = containerViewModel { c, saved -> GameViewModel(c, saved) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var showRules by rememberSaveable { mutableStateOf(false) }

    GameWindowEffects()
    SoundEffects(ui.game, muted)

    val game = ui.game
    val running = ui.status == GameStatus.READY && game != null && game.state.phase != Phase.OVER && !ui.opponentLeft
    val exit = {
        vm.leave()
        onExit()
    }
    BackHandler { if (running) confirmLeave = true else exit() }

    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color.White) {
        when (ui.status) {
            GameStatus.LOADING -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.game_loading))
            }
            GameStatus.CORRUPT -> ErrorPane(R.string.game_error_corrupt, exit)
            GameStatus.UPDATE_REQUIRED -> ErrorPane(R.string.game_error_update, exit)
            GameStatus.GONE -> ErrorPane(R.string.game_error_gone, exit)
            GameStatus.READY -> if (game != null) {
                GameContent(
                    ui = ui,
                    game = game,
                    code = vm.code,
                    muted = muted,
                    now = vm::serverNow,
                    onRoll = vm::roll,
                    onTokenTap = vm::move,
                    onToggleMute = vm::toggleMute,
                    onRules = { showRules = true },
                    onRematch = vm::requestRematch,
                    onHome = exit,
                )
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.leave_title)) },
            text = { Text(stringResource(R.string.leave_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    exit()
                }) { Text(stringResource(R.string.leave_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.stay)) } },
        )
    }
    if (showRules) HowToPlaySheet(onDismiss = { showRules = false })
}

@Composable
private fun GameContent(
    ui: GameUi,
    game: RoomGame,
    code: String,
    muted: Boolean,
    now: () -> Long,
    onRoll: () -> Unit,
    onTokenTap: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onRules: () -> Unit,
    onRematch: () -> Unit,
    onHome: () -> Unit,
) {
    val s = game.state
    val over = s.phase == Phase.OVER
    val myTurn = !over && s.turn == ui.me
    val opponentTurn = !over && s.turn == ui.me.opponent
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar(code, muted, onRules, onToggleMute)
        if (!ui.connected) {
            Banner(stringResource(R.string.game_reconnecting))
        } else if (!ui.opponentConnected && !over && !ui.opponentLeft) {
            OpponentOfflineBanner(ui.opponentLastSeen, now)
        }
        // Board and both panels share the height; spare space is spread evenly so there are no big gaps.
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
            PlayerPanel(
                name = ui.opponentName,
                subtitle = stringResource(if (ui.me.opponent == PlayerColor.RED) R.string.game_red else R.string.game_yellow),
                color = ui.me.opponent,
                active = opponentTurn,
                deadline = game.turnDeadline,
                now = now,
                mirrored = true,
                modifier = Modifier.align(Alignment.End).padding(horizontal = 16.dp),
            ) {
                DiceBox(active = opponentTurn) {
                    if (opponentTurn) OldDice(ui, game, ui.me.opponent, onRoll)
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                val colorName = stringResource(if (ui.me == PlayerColor.RED) R.string.game_red else R.string.game_yellow)
                LudoBoard(
                    game = game,
                    me = ui.me,
                    movable = ui.movable,
                    onTokenTap = onTokenTap,
                    description = stringResource(R.string.board_description, colorName),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (over) GameOverCard(ui, game, onRematch, onHome)
            }
            PlayerPanel(
                name = ui.myName,
                subtitle = stringResource(R.string.game_you_label),
                color = ui.me,
                active = myTurn,
                deadline = game.turnDeadline,
                now = now,
                mirrored = false,
                modifier = Modifier.align(Alignment.Start).padding(horizontal = 16.dp),
            ) {
                DiceBox(active = myTurn) {
                    if (myTurn) OldDice(ui, game, ui.me, onRoll)
                }
            }
            Text(
                statusText(ui, game),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** TEMPORARY (redesign in progress): the old die inside the dice box, replaced by the 3D die. */
@Composable
private fun OldDice(ui: GameUi, game: RoomGame, owner: PlayerColor, onRoll: () -> Unit) {
    val s = game.state
    val shown = s.dice
    DiceButton(
        value = shown,
        rolling = ui.rolling,
        enabled = ui.canRoll && owner == ui.me,
        pipColor = LocalBoardColors.current.of(owner),
        description = if (ui.canRoll || shown == null) stringResource(R.string.game_roll)
        else stringResource(R.string.game_dice_description, shown),
        onRoll = onRoll,
        modifier = Modifier.size(52.dp),
    )
}

/** Slim top bar: room code on the left, help and sound buttons on the right. */
@Composable
private fun TopBar(code: String, muted: Boolean, onRules: () -> Unit, onToggleMute: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.game_room_code, code),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Spacer(Modifier.weight(1f))
        GlyphButton(Glyph.HELP, stringResource(R.string.game_how_to_play), onRules)
        GlyphButton(
            if (muted) Glyph.SOUND_OFF else Glyph.SOUND_ON,
            stringResource(if (muted) R.string.game_unmute else R.string.game_mute),
            onToggleMute,
        )
    }
}

@Composable
private fun statusText(ui: GameUi, game: RoomGame): String {
    val s = game.state
    fun nameOf(color: PlayerColor) = if (color == ui.me) ui.myName else ui.opponentName
    ui.notice?.let { n ->
        return when (n.kind) {
            NoticeKind.NO_MOVE -> stringResource(R.string.game_no_move, nameOf(n.by), checkNotNull(n.dice))
            NoticeKind.THREE_SIXES -> stringResource(R.string.game_three_sixes, nameOf(n.by))
            NoticeKind.TIMEOUT -> stringResource(R.string.game_timeout, nameOf(n.by))
        }
    }
    return when {
        s.phase == Phase.OVER -> ""
        s.turn != ui.me -> stringResource(R.string.game_opponent_turn, ui.opponentName)
        s.phase == Phase.ROLL -> stringResource(R.string.game_your_turn_roll)
        else -> stringResource(R.string.game_your_turn_move, checkNotNull(s.dice))
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onErrorContainer,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
            .padding(8.dp),
    )
}

@Composable
private fun OpponentOfflineBanner(lastSeen: Long, now: () -> Long) {
    val elapsed by produceState(0L, lastSeen) {
        while (true) {
            value = (now() - lastSeen).coerceAtLeast(0)
            delay(1_000)
        }
    }
    val seconds = elapsed / 1000
    val mmss = "%02d:%02d".format(seconds / 60, seconds % 60)
    Banner(stringResource(R.string.game_opponent_offline, mmss))
}

@Composable
private fun GameOverCard(ui: GameUi, game: RoomGame, onRematch: () -> Unit, onHome: () -> Unit) {
    val s = game.state
    val winner = checkNotNull(s.winner)
    val winnerName = if (winner == ui.me) ui.myName else ui.opponentName
    val loserName = if (winner == ui.me) ui.opponentName else ui.myName
    Card(
        Modifier.padding(24.dp).fillMaxWidth(),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Column(
            Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(if (winner == ui.me) R.string.over_you_won else R.string.over_you_lost),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when (checkNotNull(s.winReason)) {
                    WinReason.ALL_HOME -> stringResource(R.string.over_reason_all_home, winnerName)
                    WinReason.FORFEIT -> stringResource(R.string.over_reason_forfeit, loserName)
                    WinReason.LEFT -> stringResource(R.string.over_reason_left, loserName)
                },
                textAlign = TextAlign.Center,
            )
            val rematchNote = when {
                ui.opponentLeft -> stringResource(R.string.over_opponent_left)
                ui.iWantRematch -> stringResource(R.string.over_rematch_waiting, ui.opponentName)
                ui.opponentWantsRematch -> stringResource(R.string.over_rematch_offered, ui.opponentName)
                else -> null
            }
            rematchNote?.let { Text(it, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onHome) { Text(stringResource(R.string.over_home)) }
                Button(
                    onClick = onRematch,
                    enabled = ui.connected && !ui.opponentLeft && !ui.iWantRematch,
                ) { Text(stringResource(R.string.over_rematch)) }
            }
        }
    }
}

@Composable
private fun ErrorPane(message: Int, onHome: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(message), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onHome) { Text(stringResource(R.string.go_home)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HowToPlaySheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rules_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

/** Portrait only and screen kept awake while the game screen is shown. */
@Composable
private fun GameWindowEffects() {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(activity, view) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        view.keepScreenOn = true
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            view.keepScreenOn = false
        }
    }
}

/** Plays a sound and vibrates for rolls, captures and tokens reaching home (only for the next version, never replays). */
@Composable
private fun SoundEffects(game: RoomGame?, muted: Boolean) {
    val feedback = rememberFeedback()
    val previous = remember { arrayOfNulls<RoomGame>(1) }
    val currentMuted by rememberUpdatedState(muted)
    LaunchedEffect(game) {
        val prev = previous[0]
        previous[0] = game
        if (game == null || prev == null || currentMuted || game.version != prev.version + 1) return@LaunchedEffect
        val last = game.state.lastAction ?: return@LaunchedEffect
        when (last.type) {
            ActionType.ROLL -> feedback.roll()
            ActionType.MOVE -> {
                val from = checkNotNull(last.from)
                val to = checkNotNull(last.to)
                val special = last.captured != null || to == HOME
                if (special) {
                    // Wait for the step-by-step animation to reach the square.
                    delay(120L * (if (from == YARD) 1 else to - from))
                    if (last.captured != null) feedback.capture() else feedback.home()
                }
            }
            ActionType.TIMEOUT, ActionType.LEFT -> Unit
        }
    }
}
