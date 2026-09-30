package com.example.ludoduel.ui.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.example.ludoduel.data.RoomGame
import com.example.ludoduel.engine.GameState
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.theme.LocalLudoPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The emoji overlay never takes touches: a tap on the board under a flying emoji still selects the
 * token. Runs the real board and the real overlay (Robolectric, with the unit tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmojiOverlayTapTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `a board tap under a flying emoji still selects the token`() {
        // Red to move with a 6: token 0 in the yard may come out.
        val state = GameState.initial(PlayerColor.RED).copy(phase = Phase.MOVE, dice = 6)
        val game = RoomGame(version = 1, gameNumber = 1, turnDeadline = 0, state = state)
        val flights = EmojiFlights()
        var tapped: Int? = null
        // The board is 300 dp; red token 0 sits in the yard at grid point (2, 11).
        val tokenDp = Offset(2 * 20f, 11 * 20f)
        compose.mainClock.autoAdvance = false // keep the emoji where it is, over the token
        compose.setContent {
            val density = LocalDensity.current
            val palette = LocalLudoPalette.current
            val animator = remember { GameAnimator(PlayerColor.RED, game, density, palette) }
            // My panel is placed so the emoji starts exactly over token 0 (it leaves from my avatar).
            val token = with(density) { Offset(tokenDp.x.dp.toPx(), tokenDp.y.dp.toPx()) }
            val avatarInset = with(density) { (8 + 26).dp.toPx() }
            val spots = PanelSpots(
                me = Rect(token.x - avatarInset, token.y - 60f, token.x + 400f, token.y + 60f),
                opponent = Rect(0f, 0f, 400f, 100f),
            )
            Box(Modifier.size(300.dp)) {
                LudoBoard(
                    animator = animator,
                    movable = listOf(0),
                    colorblind = false,
                    onTokenTap = { tapped = it },
                    description = "board",
                    modifier = Modifier.fillMaxSize(),
                )
                EmojiOverlay(flights, spots, SilentFx)
            }
        }
        flights.add(ChatEvent.Emoji(id = "m1", emojiId = "1f602", fromMe = true))
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, flights.flying.size)

        compose.onNodeWithContentDescription("board").performTouchInput {
            val unit = width / 15f
            click(Offset(2 * unit, 11 * unit))
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0, tapped)
        assertTrue("the emoji is still flying", flights.flying.isNotEmpty())
    }
}
