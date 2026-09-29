package com.example.ludoduel.ui.game

/** Game sounds. Each plays a short original clip (see ASSETS.md). */
enum class Sfx { ROLL, HOP, CAPTURE, HOME, SIX, WIN, LOSE, CLICK, YOUR_TURN }

/** Light vibrations for the moments that matter: roll, capture, home. */
enum class Buzz { ROLL, CAPTURE, HOME }

/** Sound and vibration output used by the animations. Implementations respect the mute and vibration settings. */
interface GameFx {
    fun play(sfx: Sfx)
    fun buzz(kind: Buzz)
}
