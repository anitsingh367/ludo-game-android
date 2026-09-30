package com.example.ludoduel.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.RenderMode
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.ludoduel.R
import com.example.ludoduel.data.ChatRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** The bundled animated emojis (Noto Emoji Animation by Google, CC BY 4.0, see ASSETS.md). */
object EmojiArt {
    private val raw = mapOf(
        "1f602" to R.raw.emoji_1f602, "1f60d" to R.raw.emoji_1f60d, "1f60e" to R.raw.emoji_1f60e, "1f914" to R.raw.emoji_1f914,
        "1f62e" to R.raw.emoji_1f62e, "1f622" to R.raw.emoji_1f622, "1f621" to R.raw.emoji_1f621, "1f608" to R.raw.emoji_1f608,
        "1f44e" to R.raw.emoji_1f44e, "1f44f" to R.raw.emoji_1f44f, "1f64f" to R.raw.emoji_1f64f, "1f525" to R.raw.emoji_1f525,
        "1f389" to R.raw.emoji_1f389, "1f480" to R.raw.emoji_1f480, "1f91e" to R.raw.emoji_1f91e, "1f44b" to R.raw.emoji_1f44b,
    )

    fun rawRes(emojiId: String): Int = raw.getValue(emojiId)

    /**
     * The point in each animation (0..1) where it clearly looks like the emoji, shown in the tray and
     * while it flies. Picked by rendering 12 frames of each: most look right at the start; 😢 needs
     * its tear, 😡 its red face, 😈 its horns and 🎉 its confetti.
     */
    private val preview = mapOf(
        "1f602" to 0f, "1f60d" to 0f, "1f60e" to 0f, "1f914" to 0f,
        "1f62e" to 0f, "1f622" to 6 / 11f, "1f621" to 6 / 11f, "1f608" to 6 / 11f,
        "1f44e" to 0f, "1f44f" to 0f, "1f64f" to 0f, "1f525" to 0f,
        "1f389" to 4 / 11f, "1f480" to 0f, "1f91e" to 0f, "1f44b" to 0f,
    )

    fun previewProgress(emojiId: String): Float = preview.getValue(emojiId)

    /** The emoji as a character, for the chat history and accessibility. */
    fun text(emojiId: String): String = String(Character.toChars(emojiId.toInt(16)))
}

/** Emoji flights on screen: at most [MAX_FLYING] at once; more wait their turn. */
class EmojiFlights {
    /** Each flying emoji with its slot (0..2), which offsets its landing spot so they don't overlap. */
    val flying = mutableStateListOf<Pair<Int, ChatEvent.Emoji>>()
    private val waiting = ArrayDeque<ChatEvent.Emoji>()

    fun add(emoji: ChatEvent.Emoji) {
        if (flying.size < MAX_FLYING) start(emoji) else waiting.addLast(emoji)
    }

    fun done(emoji: ChatEvent.Emoji) {
        flying.removeAll { it.second.id == emoji.id }
        waiting.removeFirstOrNull()?.let(::start)
    }

    private fun start(emoji: ChatEvent.Emoji) {
        val slot = (0 until MAX_FLYING).first { s -> flying.none { it.first == s } }
        flying += slot to emoji
    }

    companion object {
        const val MAX_FLYING = 3
    }
}

/** Where the two player panels are, in the overlay's coordinates. */
data class PanelSpots(val me: Rect, val opponent: Rect)

private val EMOJI_SIZE = 64.dp
private const val FLIGHT_MILLIS = 700
private const val SHOW_MILLIS = 2_000L
private const val FADE_MILLIS = 300

/**
 * Draws the flying emojis above everything. It has no touch handling at all, so every tap goes
 * through to the board and the dice below, and it runs on its own, never waiting for or delaying
 * the game's animations.
 */
@Composable
fun EmojiOverlay(flights: EmojiFlights, spots: PanelSpots?, fx: GameFx, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        if (spots == null) return@Box
        for ((slot, emoji) in flights.flying) {
            key(emoji.id) {
                Flight(emoji, slot, spots, fx, onDone = { flights.done(emoji) })
            }
        }
    }
}

@Composable
private fun Flight(emoji: ChatEvent.Emoji, slot: Int, spots: PanelSpots, fx: GameFx, onDone: () -> Unit) {
    val density = LocalDensity.current
    val (from, to) = remember(emoji.id, spots) { flightEnds(emoji.fromMe, slot, spots, density) }
    val flight = remember { Animatable(0f) }
    val bounce = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    var landed by remember { mutableStateOf(false) }
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(EmojiArt.rawRes(emoji.emojiId)))
    val playing by animateLottieCompositionAsState(composition, isPlaying = landed, iterations = LottieConstants.IterateForever)

    LaunchedEffect(emoji.id) {
        flight.animateTo(1f, tween(FLIGHT_MILLIS, easing = FastOutSlowInEasing))
        landed = true
        fx.play(Sfx.POP)
        if (!emoji.fromMe) fx.buzz(Buzz.EMOJI)
        launch { bounce.animateTo(1f, tween(320, easing = LinearEasing)) }
        delay(SHOW_MILLIS)
        fade.animateTo(1f, tween(FADE_MILLIS))
        onDone()
    }

    val control = arcControl(from, to)
    val t = flight.value
    val sizePx = with(density) { EMOJI_SIZE.toPx() }
    // A faint trail: a few fading dots behind the emoji while it flies.
    if (t in 0.02f..0.98f) {
        Canvas(Modifier.fillMaxSize()) {
            for (k in 1..5) {
                val tt = t - k * 0.06f
                if (tt <= 0f) break
                drawCircle(
                    Color.White.copy(alpha = 0.28f * (1f - k / 6f)),
                    radius = sizePx * (0.16f - k * 0.02f),
                    center = bezier(from, control, to, tt),
                )
            }
        }
    }
    val pos = bezier(from, control, to, t)
    val grow = 0.6f + 0.4f * t
    val pop = 1f + 0.25f * sin(bounce.value * PI).toFloat()
    val scale = grow * pop * (1f - 0.4f * fade.value)
    LottieAnimation(
        composition = composition,
        progress = { if (landed) playing else EmojiArt.previewProgress(emoji.emojiId) },
        renderMode = RenderMode.SOFTWARE,
        modifier = Modifier
            .size(EMOJI_SIZE)
            .graphicsLayer {
                translationX = pos.x - sizePx / 2
                translationY = pos.y - sizePx / 2
                scaleX = scale
                scaleY = scale
                alpha = 1f - fade.value
            },
    )
}

/**
 * Start (the sender's avatar) and landing spot (next to the receiver's name: above the top panel
 * or below the bottom one, so it does not cover the board), offset sideways by [slot].
 */
private fun flightEnds(fromMe: Boolean, slot: Int, spots: PanelSpots, density: Density): Pair<Offset, Offset> = with(density) {
    val avatarInset = (8 + 26).dp.toPx()
    val nameInset = 110.dp.toPx()
    val step = 60.dp.toPx() * slot
    val lift = 40.dp.toPx()
    // My panel is at the bottom (avatar on the left); the opponent's at the top (avatar on the right).
    val myAvatar = Offset(spots.me.left + avatarInset, spots.me.center.y)
    val theirAvatar = Offset(spots.opponent.right - avatarInset, spots.opponent.center.y)
    val landByMe = Offset(spots.me.left + nameInset + step, spots.me.bottom + lift)
    val landByThem = Offset(spots.opponent.right - nameInset - step, spots.opponent.top - lift)
    if (fromMe) myAvatar to landByThem else theirAvatar to landByMe
}

/** A control point to one side of the straight line, for a gentle curve. */
private fun arcControl(from: Offset, to: Offset): Offset {
    val d = to - from
    val length = sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(1f)
    val side = Offset(-d.y / length, d.x / length) * (0.25f * length)
    return (from + to) / 2f + side
}

private fun bezier(a: Offset, c: Offset, b: Offset, t: Float): Offset {
    val u = 1 - t
    return a * (u * u) + c * (2 * u * t) + b * (t * t)
}

/** Parses the 16 emojis in the background once, so the tray and flights show them at once. */
@Composable
fun PreloadEmojis() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ChatRules.EMOJI_IDS.forEach { LottieCompositionFactory.fromRawRes(context, EmojiArt.rawRes(it)) }
    }
}

/** The round button next to my panel that opens the emoji tray. */
@Composable
fun EmojiButton(description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.22f else 0.1f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("😊", fontSize = 22.sp)
    }
}

/**
 * The tray of 16 emojis (4 x 4) above the emoji button. Each shows its preview frame and plays once
 * from the start while pressed. A tap sends it and closes the tray; when [onPick] refuses (too soon), the tray
 * shakes gently instead.
 */
@Composable
fun EmojiTray(onPick: (String) -> Boolean, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Popup(
        alignment = Alignment.BottomEnd,
        offset = with(density) { IntOffset(0, -52.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .graphicsLayer { translationX = shake.value }
                .shadow(10.dp, RoundedCornerShape(20.dp))
                .background(Color.White, RoundedCornerShape(20.dp))
                .padding(8.dp),
        ) {
            ChatRules.EMOJI_IDS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { id ->
                        TrayEmoji(id) {
                            if (onPick(id)) {
                                onDismiss()
                            } else {
                                scope.launch {
                                    for (x in listOf(10f, -10f, 7f, -7f, 3f, 0f)) shake.animateTo(x * density.density, tween(45))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrayEmoji(emojiId: String, onClick: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(EmojiArt.rawRes(emojiId)))
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Plays once from the start each time it is pressed; the preview frame otherwise.
    val progress by animateLottieCompositionAsState(composition, isPlaying = pressed, iterations = 1, restartOnPlay = true)
    val preview = EmojiArt.previewProgress(emojiId)
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = EmojiArt.text(emojiId) },
        contentAlignment = Alignment.Center,
    ) {
        LottieAnimation(composition, progress = { if (pressed) progress else preview }, renderMode = RenderMode.SOFTWARE, modifier = Modifier.size(46.dp))
    }
}
