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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.draw.drawWithContent
import com.example.ludoduel.ui.components.Pill
import com.example.ludoduel.ui.components.PillHost
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import com.example.ludoduel.ui.rememberUiPrefs
import com.example.ludoduel.ui.theme.LocalLudoPalette
import kotlinx.coroutines.delay

@Composable
fun GameScreen(onExit: () -> Unit) {
    val vm = containerViewModel { c, saved -> GameViewModel(c, saved) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var showRules by rememberSaveable { mutableStateOf(false) }

    GameWindowEffects()

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
    val prefs = rememberUiPrefs()
    val density = LocalDensity.current
    val palette = LocalLudoPalette.current
    val animator = remember(ui.me) { GameAnimator(ui.me, game, density, palette) }
    animator.fx = rememberGameFx(soundOn = !muted, vibrationOn = prefs.vibration)
    animator.texts = AnimatorTexts(
        plusOneTurn = stringResource(R.string.fx_plus_one_turn),
        noMoves = stringResource(R.string.fx_no_moves),
        threeSixes = stringResource(R.string.fx_three_sixes),
        captured = stringResource(R.string.fx_captured),
        yourTurn = stringResource(R.string.fx_your_turn),
        playersTurn = stringResource(R.string.fx_players_turn),
        ranOutOfTime = stringResource(R.string.game_timeout),
    )
    animator.nameOf = { if (it == ui.me) ui.myName else ui.opponentName }
    LaunchedEffect(animator) { animator.run() }
    LaunchedEffect(animator, game) { animator.submit(game) }

    // What the screen shows is the animator's state, which may lag the server by an animation.
    val shown = animator.shown
    val s = shown.state
    val over = s.phase == Phase.OVER
    // Input only when every animation has played and the board shows the latest state.
    val caughtUp = !animator.busy && shown == game
    val movable = if (caughtUp) ui.movable else emptyList()
    val canRoll = caughtUp && ui.canRoll

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(code, muted, onRules, onToggleMute)
            // Board and both panels share the height; spare space is spread evenly so there are no big gaps.
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly) {
                for (color in listOf(ui.me.opponent, ui.me)) {
                    val isMe = color == ui.me
                    if (isMe) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                // Slightly dimmed while we are offline (input is blocked then).
                                .drawWithContent {
                                    drawContent()
                                    if (!ui.connected) drawRect(Color.Black.copy(alpha = 0.3f))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val colorName = stringResource(if (ui.me == PlayerColor.RED) R.string.game_red else R.string.game_yellow)
                            LudoBoard(
                                animator = animator,
                                movable = movable,
                                colorblind = prefs.colorblind,
                                onTokenTap = onTokenTap,
                                description = stringResource(R.string.board_description, colorName),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (over) GameOverCard(ui, shown, onRematch, onHome)
                        }
                    }
                    val active = !over && s.turn == color
                    PlayerPanel(
                        name = if (isMe) ui.myName else ui.opponentName,
                        subtitle = stringResource(
                            when {
                                isMe -> R.string.game_you_label
                                color == PlayerColor.RED -> R.string.game_red
                                else -> R.string.game_yellow
                            },
                        ),
                        color = color,
                        active = active,
                        deadline = shown.turnDeadline,
                        now = now,
                        mirrored = !isMe,
                        modifier = Modifier.align(if (isMe) Alignment.Start else Alignment.End).padding(horizontal = 16.dp),
                    ) {
                        DiceBox(active = active) {
                            if (animator.dieOwner == color) {
                                val die = animator.dice.getValue(color)
                                val enabled = isMe && canRoll
                                Die(
                                    visual = die,
                                    color = color,
                                    enabled = enabled,
                                    description = die.face?.takeIf { !enabled }
                                        ?.let { stringResource(R.string.game_dice_description, it) }
                                        ?: stringResource(R.string.game_roll),
                                    onRoll = {
                                        animator.startLocalRoll()
                                        onRoll()
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                DieFloater(die)
                            }
                        }
                    }
                }
            }
        }
        PillHost(
            animator.pills,
            persistent = connectionPill(ui, over, now),
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 60.dp),
        )
    }
}

/** "+1 turn!" rising from the die and fading. */
@Composable
private fun DieFloater(die: DieVisual) {
    val progress = die.floater.value
    if (progress >= 1f) return
    Text(
        die.floaterText,
        color = Color(0xFF3E2723),
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .wrapContentWidth(unbounded = true)
            .graphicsLayer {
                translationY = -(20.dp.toPx() + 46.dp.toPx() * progress)
                alpha = if (progress < 0.7f) 1f else (1f - progress) / 0.3f
            }
            .background(Color(0xFFFFD54F), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
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

/**
 * The compact pill that stays up while a connection is down: mine ("Reconnecting…") or the
 * opponent's, with the time since they dropped.
 */
@Composable
private fun connectionPill(ui: GameUi, over: Boolean, now: () -> Long): Pill? {
    val amber = LocalLudoPalette.current.amber
    if (!ui.connected) return Pill(stringResource(R.string.game_reconnecting), amber, spinner = true)
    if (ui.opponentConnected || over || ui.opponentLeft) return null
    val elapsed by produceState(0L, ui.opponentLastSeen) {
        while (true) {
            value = (now() - ui.opponentLastSeen).coerceAtLeast(0)
            delay(1_000)
        }
    }
    val seconds = elapsed / 1000
    return Pill(stringResource(R.string.game_opponent_offline, "%d:%02d".format(seconds / 60, seconds % 60)), amber, spinner = true)
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
