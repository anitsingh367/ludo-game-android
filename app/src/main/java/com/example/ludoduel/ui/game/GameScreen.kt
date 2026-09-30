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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import com.example.ludoduel.ui.components.PillView
import com.example.ludoduel.ui.components.BlueGloss
import com.example.ludoduel.ui.components.GlossyButton
import com.example.ludoduel.ui.components.Scrim
import com.example.ludoduel.ui.components.GameTitle
import com.example.ludoduel.ui.components.Trophy
import com.example.ludoduel.ui.components.ConfettiRain
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ludoduel.R
import com.example.ludoduel.data.GameReducer
import com.example.ludoduel.data.RoomGame
import com.example.ludoduel.engine.ActionType
import com.example.ludoduel.engine.HOME
import com.example.ludoduel.engine.LudoEngine
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.WinReason
import com.example.ludoduel.engine.YARD
import com.example.ludoduel.ui.components.Glyph
import com.example.ludoduel.ui.components.GlyphButton
import com.example.ludoduel.ui.SettingsSheet
import com.example.ludoduel.ui.containerViewModel
import com.example.ludoduel.ui.rememberUiPrefs
import com.example.ludoduel.ui.theme.LocalLudoPalette
import kotlinx.coroutines.delay

/** How long the number stays visible before the only movable token moves by itself. */
private const val AUTO_MOVE_DELAY_MILLIS = 500L

/** Space between the board and each player panel. */
private val PANEL_GAP = 10.dp

@Composable
fun GameScreen(onExit: () -> Unit) {
    val vm = containerViewModel { c, saved -> GameViewModel(c, saved) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var showRules by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

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
                    onSettings = { showSettings = true },
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
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}

@Composable
private fun GameContent(
    ui: GameUi,
    game: RoomGame,
    code: String,
    muted: Boolean,
    now: () -> Long,
    onRoll: (onResult: (Boolean) -> Unit) -> Unit,
    onTokenTap: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onRules: () -> Unit,
    onSettings: () -> Unit,
    onRematch: () -> Unit,
    onHome: () -> Unit,
) {
    val prefs = rememberUiPrefs()
    val density = LocalDensity.current
    val palette = LocalLudoPalette.current
    val animator = remember(ui.me) { GameAnimator(ui.me, game, density, palette) }
    animator.fx = LocalGameFx.current
    animator.texts = AnimatorTexts(
        plusOneTurn = stringResource(R.string.fx_plus_one_turn),
        noMovesSix = stringResource(R.string.fx_no_moves_six),
        noMovesExact = stringResource(R.string.fx_no_moves_exact),
        noMovesBoth = stringResource(R.string.fx_no_moves_both),
        threeSixes = stringResource(R.string.fx_three_sixes),
        captured = stringResource(R.string.fx_captured),
        yourTurn = stringResource(R.string.fx_your_turn),
        playersTurn = stringResource(R.string.fx_players_turn),
        ranOutOfTime = stringResource(R.string.game_timeout),
        couldNotRoll = stringResource(R.string.fx_could_not_roll),
        cantMove = stringResource(R.string.fx_cant_move),
        luckyBoost = stringResource(R.string.fx_lucky_boost),
    )
    animator.luckyBoost = ui.luckyBoost
    animator.nameOf = { if (it == ui.me) ui.myName else ui.opponentName }
    LaunchedEffect(animator) { animator.run() }
    LaunchedEffect(animator, game) { animator.submit(game) }
    // Safety net for my roll: no confirmed roll within 5 s gives up and re-enables the die.
    val rollState = animator.myRollState
    LaunchedEffect(rollState) {
        if (rollState is RollMachine.State.Waiting) {
            delay(RollMachine.TIMEOUT_MILLIS)
            animator.tickRoll(System.currentTimeMillis())
        }
    }
    // Coming back to the app runs the same check (the timer may have paused in the background).
    LifecycleResumeEffect(animator) {
        animator.tickRoll(System.currentTimeMillis())
        onPauseOrDispose { }
    }

    // What the screen shows is the animator's state, which may lag the server by an animation.
    val shown = animator.shown
    val s = shown.state
    val over = s.phase == Phase.OVER
    // Input only when every animation has played and the board shows the latest state.
    val caughtUp = !animator.busy && shown == game
    val movable = if (caughtUp) ui.movable else emptyList()
    val canRoll = caughtUp && ui.canRoll
    // Exactly one token can move: move it by itself shortly after the dice has landed (the player
    // sees the number first). Board taps do nothing meanwhile. Only while a move can be sent (online,
    // no write running), so a move that could not be sent is tried again when it can.
    val autoToken = if (caughtUp) LudoEngine.onlyMovableToken(s, ui.me)?.takeIf { it in movable } else null
    LaunchedEffect(autoToken, shown.version) {
        if (autoToken != null) {
            delay(AUTO_MOVE_DELAY_MILLIS)
            onTokenTap(autoToken)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(code, muted, onRules, onToggleMute, onSettings)
            val panel = @Composable { color: PlayerColor, modifier: Modifier ->
                val isMe = color == ui.me
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
                    modifier = modifier,
                ) {
                    DiceBox(active = active) {
                        if (animator.dieOwner == color) {
                            val die = animator.dice.getValue(color)
                            val enabled = isMe && canRoll && animator.myRollState == RollMachine.State.Idle
                            Die(
                                visual = die,
                                color = color,
                                enabled = enabled,
                                description = die.face?.takeIf { !enabled }
                                    ?.let { stringResource(R.string.game_dice_description, it) }
                                    ?: stringResource(R.string.game_roll),
                                onRoll = {
                                    if (animator.tapRoll(System.currentTimeMillis())) {
                                        onRoll { sent -> if (!sent) animator.rollSendFailed() }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            DieFloater(die)
                        }
                    }
                }
            }
            // Spare height goes above the top panel and below the bottom panel, never between a panel
            // and the board. Each panel sits next to its own corner of the board.
            Spacer(Modifier.weight(1f))
            panel(ui.me.opponent, Modifier.align(Alignment.End).padding(horizontal = 8.dp))
            Spacer(Modifier.height(PANEL_GAP))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
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
                    tapsEnabled = autoToken == null,
                    colorblind = prefs.colorblind,
                    onTokenTap = onTokenTap,
                    description = stringResource(R.string.board_description, colorName),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(PANEL_GAP))
            panel(ui.me, Modifier.align(Alignment.Start).padding(horizontal = 8.dp))
            Spacer(Modifier.weight(1f))
        }
        if (over) WinOverlay(ui, shown, animator.fx, onRematch, onHome)
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
private fun TopBar(
    code: String,
    muted: Boolean,
    onRules: () -> Unit,
    onToggleMute: () -> Unit,
    onSettings: () -> Unit,
) {
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
        GlyphButton(Glyph.SETTINGS, stringResource(R.string.settings_open), onSettings)
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

/**
 * Game over. The winner gets a trophy with a crown, confetti rain and a victory sound; the loser a
 * gentler "Good game!". Both see the reason, the rematch state and the Rematch / Home buttons.
 */
@Composable
private fun WinOverlay(ui: GameUi, game: RoomGame, fx: GameFx, onRematch: () -> Unit, onHome: () -> Unit) {
    val s = game.state
    val winner = checkNotNull(s.winner)
    val iWon = winner == ui.me
    val winnerName = if (iWon) ui.myName else ui.opponentName
    val loserName = if (iWon) ui.opponentName else ui.myName
    val appear = remember(game) { Animatable(0f) }
    LaunchedEffect(game) {
        fx.play(if (iWon) Sfx.WIN else Sfx.LOSE)
        appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
    }
    Scrim {
        if (iWon) ConfettiRain(Modifier.fillMaxSize())
        Column(
            Modifier
                .padding(24.dp)
                .graphicsLayer {
                    scaleX = 0.7f + 0.3f * appear.value
                    scaleY = 0.7f + 0.3f * appear.value
                    alpha = appear.value.coerceIn(0f, 1f)
                }
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Trophy(Modifier.size(if (iWon) 170.dp else 120.dp).graphicsLayer { alpha = if (iWon) 1f else 0.75f })
            GameTitle(stringResource(if (iWon) R.string.over_you_won else R.string.over_good_game), fontSize = 44)
            WinnerChip(winnerName, winner)
            Text(
                when (checkNotNull(s.winReason)) {
                    WinReason.ALL_HOME -> stringResource(R.string.over_reason_all_home, winnerName)
                    WinReason.FORFEIT -> stringResource(R.string.over_reason_forfeit, loserName)
                    WinReason.LEFT -> stringResource(R.string.over_reason_left, loserName)
                },
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            val rematchNote = when {
                ui.opponentLeft -> stringResource(R.string.over_opponent_left)
                ui.iWantRematch -> stringResource(R.string.over_rematch_waiting, ui.opponentName)
                ui.opponentWantsRematch -> stringResource(R.string.over_rematch_offered, ui.opponentName)
                else -> null
            }
            rematchNote?.let { PillView(Pill(it, LocalLudoPalette.current.amber)) }
            GlossyButton(
                text = stringResource(R.string.over_rematch),
                onClick = onRematch,
                enabled = ui.connected && !ui.opponentLeft && !ui.iWantRematch,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
            GlossyButton(
                text = stringResource(R.string.over_home),
                onClick = onHome,
                colors = BlueGloss,
                height = 52.dp,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
        }
    }
}

/** The winner's name next to a pawn in their color. */
@Composable
private fun WinnerChip(name: String, color: PlayerColor) {
    val colors = LocalLudoPalette.current.of(color)
    val measurer = rememberTextMeasurer()
    Row(
        Modifier
            .background(Brush.horizontalGradient(listOf(colors.main, colors.dark)), RoundedCornerShape(50))
            .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(22.dp, 30.dp)) {
            drawPawn(Offset(size.width / 2, size.height * 0.58f), size.width * 1.3f, colors, PawnLook(), null, measurer)
        }
        Text(name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
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
