# Decisions

Every choice the spec did not settle, and where each numbered edge case (section 6) is handled.
Paths are relative to `app/src/main/java/com/example/ludoduel/` unless they start with the
project root (`database.rules.json`, `rules-tests/`, `app/src/test/...`).

## Security note: dice are rolled on the phone

Dice rolls use `java.security.SecureRandom` on the phone of the player who rolls
(`engine/Dice.kt`). A modified app could therefore cheat (choose its rolls, or write any token
positions for its own turn). The security rules stop many abuses: only the current player can act,
versions go up by one, values stay in range, a Timeout cannot move tokens, a rematch must start
clean. But they cannot check the Ludo rules themselves. That is acceptable for games between
friends. Moving dice and rule checks to a server would need Cloud Functions, which require the
paid Blaze plan.

## Game rules choices

- **No-move turns are part of the Roll action.** When a roll leaves no legal move (or it is the
  third 6), the engine passes the turn in the same write. There is no separate `Pass` action, so a
  turn can never get stuck waiting for a pass that a closed app never sends. The "show the dice for
  about 1.2 seconds" part is done by the UI: both phones show "X rolled 3 — no move possible" and
  the next player's dice button stays disabled for 1.2 s (`ui/game/GameViewModel.kt`, `watchNotices`).
- **A 6 with no legal move still passes the turn** (spec: "If the player has no legal move … the
  turn passes"). No extra roll is granted.
- **Safe pairs** replaced blocks (a later rule change; see "Rule change: safe pairs replace blocks"
  at the end). Nothing on the track stops a token any more.
- **Opponent tokens on a safe square** can share it; nothing is captured there.
- **Every action gets a fresh 30-second deadline** (rolling and moving are timed separately).
- **Automatic play when your own timer runs out** (item 16): an automatic roll counts as a missed
  turn (+1); the automatic move that follows it does not count again; any manual roll or move resets
  the count to 0. So a player who leaves the app open but stops playing still forfeits after 3
  turns. The automatic actions carry `auto: true` in `lastAction`.
- **When exactly one move is legal**, it is played after 1.5 s unless the player taps first. This
  counts as a normal move (the player rolled manually).
- **Timeout by the opponent** records the player who ran out of time in `lastAction.byUid`
  (not the writer), so both phones can show "X ran out of time" and the rules can check that the
  late player's missed count went up by exactly one.
- **Game 1:** host (Red) moves first. **Rematch:** the loser of the previous game moves first.

## Data model choices

- **Rematch votes store the game number** (`rematch/{uid}: 1`) instead of `true`. A vote only
  counts for the game that just ended, so a vote left over from an earlier game (for example when
  clearing the votes failed) can never start a rematch by itself. The rules check it too.
- **Public fields.** The spec says only the two players may read a room, but a guest must check a
  code before joining (to show "not found / expired / full / closed / update the app"). So any
  signed-in user may read these five fields of a room: `schemaVersion`, `hostUid`, `guestUid`,
  `status`, `expiresAt`. Everything else (`players`, `game`, `rematch`, `createdAt`) is players
  only. Rooms cannot be listed; you must know the code.
- **Times written by the phone.** `createdAt` and `expiresAt` are written by the host inside the
  create transaction, using the server-corrected clock (`.info/serverTimeOffset`). Firebase
  resolves server timestamps inside transactions on the phone anyway. The rules accept
  `createdAt` within 5 minutes of server time and require `expiresAt = createdAt + 24 h`.
  `turnDeadline` must be at most 2 minutes ahead of server time, so nobody can freeze a game.
- **Joining** is two writes: a transaction that claims `guestUid` (so two people can't both get in),
  then one multi-path update that writes the guest's player entry, sets `status: "playing"` and
  creates `game` version 1. If the app dies between the two, entering the same code again finishes
  the join (`data/RoomRepository.kt`, `joinRoom`).
- **Status:** `finished` is written by whoever wrote the game-ending action (all home or forfeit by
  missed turns). `abandoned` is written when a player leaves (during or after a game) or when the
  host cancels a waiting room. A rematch sets it back to `playing`.
- **Rooms are never deleted** by the app (no Cloud Functions on the Spark plan). They expire for
  joining after 24 h. `database.rules.json` has an `.indexOn: ["expiresAt"]` so you can find and
  delete old rooms yourself with admin access; the app makes no queries.
- **Transactions do not fire local events** (`fireLocalEvents = false`), so listeners only ever see
  server-confirmed data, as the spec requires.
- **Transactions on an uncached path.** Firebase runs the update first on the local cache, which
  is null when the path isn't cached (for example the "leave" transaction after the game screen
  stopped listening). Our transaction helper then sends the unchanged null instead of aborting, so
  the server returns the real value and the update runs again (the pattern from the Firebase docs,
  `data/FirebaseTx.kt`). Found by the rules tests.
- **Game writes finish even if the screen's timer is cancelled.** A new game state usually reaches
  the room listener before the transaction reports back, which restarts the turn timers. The
  transaction and its follow-up writes (`status: "finished"`, clearing rematch votes) therefore run
  as one non-cancellable unit (`RoomRepository.submit` / `startRematch`). Found in end-to-end
  testing: a game won by an automatic move left the status at `playing`.
- **Presence** is one app-wide session per room (`data/PresenceTracker.kt`), shared by the waiting
  screen and the game screen. Without it, the waiting screen's cleanup (which runs after the game
  screen opened) would cancel the game screen's `onDisconnect`.

## UI choices

- The board is drawn in Red's orientation for Red and rotated 180° for Yellow, so each player sees
  their own yard at the bottom left. Movement is clockwise in both views.
- The two unused corners are grey (not green/blue) so only the two playing colors appear.
- Tokens show a letter (R / Y) for colorblind players; the player panels show the same letter.
- Game over is a card on top of the board (the game screen stays open so rematch works without
  leaving the room).
- Sounds are Android's built-in tones (`ToneGenerator`), so there are no sound files.
  Vibration uses the view's haptic feedback, so no `VIBRATE` permission is needed. It follows the
  phone's touch-feedback setting. The mute toggle (stored locally) turns both off.
- Only the game screen is locked to portrait (the spec asks for the game screen). On Android 16+
  large screens (tablets, foldables) Android ignores orientation locks; the layout still works.
- The room code field cleans input inside the text field's editing buffer (state-based
  `BasicTextField` with an input transformation). Cleaning it afterwards in `onValueChange` made
  the keyboard drop characters when typing lowercase (found in end-to-end testing).
- Player names: 2–16 characters after trimming, default "Player", stored locally.
- "Reconnecting…" can flash for a moment when the game screen opens, until the first connection
  is confirmed. Input is disabled during that moment, as required.

## Build choices

- **Versions:** Android Gradle Plugin 9.4.1 (built-in Kotlin), Kotlin 2.4.20, Gradle 9.8.0,
  compileSdk/targetSdk 37 (Android 17, minor version 37.2), minSdk 26, Compose BOM 2026.09.00,
  Firebase BoM 34.19.0.
- **`google-services.json` is optional for building.** The Google Services plugin is applied only
  when `app/google-services.json` exists. Without it the build succeeds and the app shows
  "Firebase is not set up". `./gradlew assembleDebug` was verified both without the file and with a
  placeholder file. The placeholder is kept at `emulator/google-services.json` (not in `app/`), for
  trying the app with the local emulators.
- **Debug-only emulator switch:** `./gradlew assembleDebug -PfirebaseEmulatorHost=10.0.2.2` makes a
  debug build use the local Firebase emulators. Release builds never do. A debug-only network
  security config allows plain HTTP to 10.0.2.2 / localhost for this. Used to test the app end to
  end without a Firebase project.
- **Rules tests:** the Firebase CLI was not installed globally, so `rules-tests/package.json`
  installs `firebase-tools` 13.35.1 (the last version that supports Node 18) and
  `@firebase/rules-unit-testing` locally. `npm test` in `rules-tests/` runs 26 tests against the
  Database emulator.

## Edge cases (section 6): where each is handled

| # | Edge case | Where |
|---|-----------|-------|
| 1 | 6-char code from the safe alphabet, created with a transaction, 5 attempts | `data/RoomCodes.kt`; `data/RoomRepository.kt` `createRoom`; rules `$code` + create rule; `RoomCodesTest` |
| 2 | Unknown code → "Room not found" | `RoomRepository.joinRoom` (`NotFound`); `ui/JoinScreen.kt` |
| 3 | Expired → "This room has expired" | `joinRoom` (server clock vs `expiresAt`); rules `guestUid` (`now < expiresAt`) |
| 4 | Guest already there → "Room is full" | `joinRoom` (`Full`, also after losing the claim transaction); rules write-once `guestUid` |
| 5 | Host enters own code → opens their room | `joinRoom` (`OpenWaiting` / `OpenGame`); `ui/Navigation.kt` |
| 6 | Uppercase, trim, drop confusables, check length first | `RoomCodes.clean` / `isComplete`; `JoinViewModel.onCodeChange` / `join`; `RoomCodesTest` |
| 7 | Host cancels → `abandoned`; guest sees "Room closed" | `WaitingViewModel.cancel` → `RoomRepository.cancelRoom` (transaction, only from `waiting`); `joinRoom` (`Closed`); rules `status` |
| 8 | Share sheet with "Join my Ludo game! Code: …" | `ui/WaitingScreen.kt` (`ACTION_SEND` chooser); `strings.xml` `share_message` |
| 9 | Join with a transaction; two joiners can't both get in | `joinRoom` (transaction on `guestUid`); rules write-once; `rules-tests` "two people joining at once" |
| 10 | Presence with `onDisconnect`, re-registered on every reconnect | `RoomRepository.runPresence`; `data/PresenceTracker.kt` |
| 11 | `.info/connected` banner "Reconnecting…", input off, no queued moves | `data/ServerClock.kt` `ConnectionMonitor`; `GameViewModel.buildUi` (`inputOpen`); timers stop offline (`runTimers`); `ui/game/GameScreen.kt` banner |
| 12 | "Opponent disconnected — waiting (mm:ss)", timer keeps running | `GameScreen.kt` `OpponentOfflineBanner`; timers do not depend on the opponent's presence |
| 13 | After reconnecting, snap to latest state | `ui/game/LudoBoard.kt` `rememberTokenPositions` (animates only version + 1, otherwise snaps); same for notices and sounds |
| 14 | Network changes never corrupt or duplicate | every game write is a transaction that checks the version the player saw (`GameReducer.reduce`, `RoomRepository.submit`); rules require version + 1; no local events |
| 15 | 30 s `turnDeadline` in server time, countdown ring | `GameReducer.TURN_MILLIS`; `ServerClock`; `GameScreen.kt` `CountdownRing` |
| 16 | Own time runs out → auto roll, then first legal move | `GameViewModel.playTimers` (own turn) |
| 17 | Opponent writes Timeout after deadline + 5 s | `GameViewModel.playTimers` (other turn); `GameReducer.reduce` checks time and writer; rules game branch 3 |
| 18 | Manual action resets missed turns | `engine/LudoEngine.kt` `roll` / `move` |
| 19 | 3 missed turns → forfeit | `LudoEngine.timeout` and automatic roll; `MAX_MISSED_TURNS`; `LudoEngineTest`, `GameReducerTest` |
| 20 | Rules: only current player writes, except Timeout after deadline | `database.rules.json` `game/.write`; `rules-tests` "only the player whose turn it is", "Timeout only 5 seconds after the deadline" |
| 21 | Background → game continues, latest state on return | ViewModel keeps listening and running timers while the process lives; UI renders the latest state |
| 22 | Killed / restarted → "Rejoin game?" | `data/SettingsStore.kt` (`activeRoom`, DataStore); `HomeViewModel.watchRejoin`; `RoomRepository.canRejoin` |
| 23 | Portrait lock; survives config change and process death | `GameScreen.kt` `GameWindowEffects`; room code from navigation args in `SavedStateHandle` (`GameViewModel.code`) |
| 24 | Double / fast taps | `GameViewModel.act` (`busy` flag, input off while a transaction runs); `move` ignores tokens not in `movable`; board hit-test only on movable tokens; version check rejects stale taps |
| 25 | Back → "Leave game? You will lose." → forfeit (`left`) | `GameScreen.kt` `BackHandler` + dialog; `GameViewModel.leave` → `RoomRepository.leave` |
| 26 | No token out and not a 6 → pass | `LudoEngine.roll`; `LudoEngineTest` 26 |
| 27 | 6 with all in yard → only bring-out moves | `LudoEngine.targetOf`; test 27 |
| 28 | Exact roll to finish | `LudoEngine.targetOf`; test 28 |
| 29 | Three 6s → cancelled, pass, reset | `LudoEngine.roll`; tests 29, 29b |
| 30 | Capture rules (normal, safe, safe pair) | `LudoEngine.move`; tests 30 and "safe pair" |
| 31 | Changed: no blocks. Tokens pass and share safe pairs | `LudoEngine.targetOf`; "safe pair" tests |
| 32 | Own stacking, safe pairs | "safe pair" tests |
| 33 | Capture + 6 = one extra turn | `LudoEngine.move` (`extraTurn`); test 33 |
| 34 | Last token home ends game, no extra turn | `LudoEngine.move`; test 34 |
| 35 | Yellow wrap 51 → 0 | `LudoEngine.absoluteSquare`; tests 35 |
| 36 | Each color only in its own home column | progress 51..55 never maps to the shared track; tests 36; `BoardGeometryTest` |
| 37 | Nobody can move → still passes | `LudoEngine.roll`; test 37 |
| 38 | `schemaVersion` ≠ 4 (1 before Lucky Boost, 2 before safe pairs, 3 before chat) → "Please update the app" | `joinRoom` (`UpdateRequired`); `GameViewModel.buildUi` (`UPDATE_REQUIRED`) |
| 39 | Invalid game state → "Something went wrong with this game" + Home | `data/GameCodec.kt` `decode` + `LudoEngine.isValid`; `Room.gameCorrupt`; `GameScreen.kt` `ErrorPane`; `GameCodecTest` |
| 40 | Anonymous sign-in, reused; retry screen when it fails | `data/AuthRepository.kt`; `ui/SplashScreen.kt` (retry) |
| 41 | Game over: winner, reason, Rematch / Home | `GameScreen.kt` `GameOverCard` |
| 42 | Rematch after both votes; new game number; clear votes; "Opponent left" | `GameViewModel.requestRematch` / `startRematchWhenAgreed`; `RoomRepository.startRematch`; `GameReducer.rematch`; rules rematch branch |
| 43 | Leaving after the game sets status so the other sees it | `RoomRepository.leave` (`abandoned`); `GameUi.opponentLeft` |

## Tests

- `./gradlew test`: 63 unit tests. Engine (every case 26–37 plus timers, forfeits, validation), a 1,000-game random
  test, room codes, code cleaning, the transaction reducer (with fake uids and times, no Firebase),
  the Firebase codec, and board geometry.
- `rules-tests/`: 26 security-rules tests against the Realtime Database emulator.

## End-to-end verification (done on 2026-09-29)

The debug app was run on two Android 16 emulators (Pixel 7) against the local Firebase Auth and
Database emulators (`-PfirebaseEmulatorHost`, see README). Driven with adb; the game state was
read from the Database emulator. Checked:

- Sign-in, create room, join by code, both boards (Yellow's rotated), names and colors.
- A full game of 283 actions to "all home": every write raised the version by exactly 1; taps on
  tokens that could not move were ignored.
- Capture (seeded position): the token steps square by square, the captured token goes back to its
  yard, the capturer rolls again; both phones show the same result.
- Game over card, rematch after both votes (loser starts, votes cleared, status back to playing).
- Code input (lowercase, spaces, 0/O/1/I/L), "Room not found", "Room is full" (third user),
  "Room closed" (cancelled and left rooms), "This room has expired", "Please update the app",
  host entering their own code, Share sheet text.
- Killing the app: the other phone shows "Opponent disconnected — waiting (mm:ss)" within seconds;
  its app writes a Timeout about 35 s into each missed turn; the 3rd one ends the game by forfeit.
- Relaunch after kill: "Rejoin game?" opens the current board. No offer once the game finished.
- Back during a game: "Leave game? You will lose." → the other phone shows "… left the game" and
  "Opponent left", Rematch disabled.
- Own timer running out: the app rolls and moves by itself; 3 in a row forfeit.
- Airplane mode: "Reconnecting…" banner, dice disabled, taps do nothing; after turning it off the
  board snaps to the latest state.
- Portrait lock on the game screen, dark theme, How-to-play sheet, mute toggle.
- A corrupted game node (token at 99): "Something went wrong with this game." and Go home.

Not checkable with emulators: how fast the real Firebase server notices a phone that lost its
network without closing the connection (airplane mode). The emulator network kept the old
connection open, so the "Opponent disconnected" banner only appeared when the app was killed.
Real Firebase detects such a drop after its heartbeat times out (typically within about a minute).
Please check this on real phones (README checklist).


## Visual redesign (branch `ui-redesign`)

A visual and animation redesign only. The rules engine, Firebase code, data model and security rules
are unchanged (see "What changed where" below). All 10 milestones of the redesign brief are done.

### The 10 problems and how they were fixed

1. **Dull flat grey screen** → royal-blue-to-purple gradient background with a faint (6%) pattern of dice, stars and diamonds (`ui/theme/Theme.kt`).
2. **Two grey corners** → all four corners drawn in bright Red, Green, Yellow, Blue; unused corners just have empty yards (`ui/game/BoardSurface.kt`).
3. **Empty space above and below the board** → the board fills the width minus 12 dp; the panels, board and slim top bar share the height with the spare space spread evenly.
4. **Mismatched panels** → one `PlayerPanel` design for both players, placed next to their own corner (mine bottom-left, opponent top-right), each with avatar, timer ring and dice box (`ui/game/PlayerPanel.kt`).
5. **Unmarked start squares** → each start square is filled with its color and has a white arrow pointing the way to go.
6. **Flat token circles and dim yard spots** → 3D map-pin pawns (gradient, shine, outline, shadow) and yards with white insets and light-tinted slots (`ui/game/TokenPainter.kt`).
7. **Overlapping stacked tokens** → tokens sharing a square shrink to 70% and fan out diagonally; a block gets a shared outline.
8. **Dice showing the other player's roll in their color** → the die sits in the current player's dice box and only shows that player's roll; it slides to the other box when the turn changes.
9. **Big red slab banner** → compact pills that slide down from the top; the reconnecting pills are amber with a spinner ("Opponent reconnecting… 0:33").
10. **Lifeless movement, no feedback** → hop-by-hop movement with arcs, squash, capture flash/stars/fly-home, home confetti, dice tumble/pop/glow, sounds and vibration.

### Choices made

- **Animation queue** (`ui/game/GameAnimator.kt`). Every game state from Firebase is handed to the
  queue. If it is exactly the next version, its `lastAction` is played: roll (600 ms tumble, landing
  pop, then "+1 turn!", "No moves" or "3 sixes — turn lost") → move (140 ms hops) → capture → home →
  turn pill. Only then does the board show that state. A version gap (reconnect), a new game, or more
  than 3 waiting states snaps straight to the latest state. Input (rolling, tapping tokens) is only
  allowed when the queue is idle and shows the latest state, so the board never runs ahead of what
  the player sees and new actions wait for the current animation.
- **My own roll** starts tumbling the moment I tap (the only optimistic UI, as before); the real value
  lands it. If the write fails, it stops by itself after 5 s.
- **The 1.2-second "no move" notice** used to live in the ViewModel. The queue's "No moves" pill plus
  a 1-second hold now does this on both phones, so the ViewModel's notice timer was removed.
- **Avatars** show the player's initial. A picked emoji avatar would have to be stored in Firebase for
  the opponent to see it, which would change the data model (not allowed for this redesign).
- **Board colors**: in Red's view Green is top-left and Blue bottom-right (clockwise Red, Green,
  Yellow, Blue). Yellow's view is the same board turned 180°, so each player's own corner is always
  bottom-left, next to their own panel.
- **Finished tokens** are drawn as one pawn in their color's center triangle with a counter.
- **Tap area**: at least 48 dp around a token even when stacked; a tap on a stack picks the token
  that can move, and if several can, the first one.
- **Colorblind mode** (off by default) brings back a letter (R / Y) on every token.
- **Settings sheet** (gear on Home and in the game): Sound (the existing mute setting), Vibration,
  Colorblind mode. Vibration and colorblind mode are stored in a separate `ui_settings` DataStore
  (`ui/UiSettings.kt`) so the data layer is untouched. The whole row toggles its switch.
- **Vibration** uses the system vibrator (so it works even when the phone's touch feedback is off),
  which needs the normal `VIBRATE` permission; it was added to the manifest.
- **Sounds and font**: all sounds are generated by `tools/generate_sounds.py`; the font is Baloo 2
  (SIL Open Font License). Details and licenses in `ASSETS.md`.
- **Dark mode** keeps the bright board and panels and uses a deeper background gradient; sheets and
  dialogs use a dark Material scheme.

### Performance

What was done to keep it smooth:

- The static board and the background pattern each have their own graphics layer and are built
  once (`drawWithCache`), so they are not re-recorded while tokens, dice or the timer animate on top.
  Tokens, effects, dice, timer ring and decorations are separate layers too.
- Animated values are read only while drawing (never during composition), so animations redraw a
  layer without recomposing screens.
- Nothing animates when nothing needs to: bobbing and the pulsing ring run only while a token can
  move; the frame clock for effects runs only while a burst is alive; the timer ring redraws about
  15 times a second (it moves a few pixels per second) and every frame only in its pulsing last 5 s.
- Particle positions are computed from time; nothing is stored or updated per particle.

**60 fps could not be verified here.** The only test devices were two Android emulators with
software rendering (SwiftShader) on a host with a load average near 30, where even the system
Settings app was janky on 66–94% of frames while scrolling. In that setup any redraw of this screen
took about 150 ms, dominated by compositing the full screen in software. While waiting for the
opponent the game now redraws about 12 times a second (only the timer ring) instead of every
frame. Please check smoothness on a real mid-range phone: Settings → Developer options → Profile
HWUI rendering → On screen as bars.

### Previews

`@Preview`s: board in both orientations (`BoardSurface.kt`), pawns in several states
(`TokenPainter.kt`), the die with every face plus the six glow and red flash (`Die.kt`), both player
panels (`PlayerPanel.kt`), and the title, buttons, letter tiles and trophy (`ui/components/Showcase.kt`).

### What changed where

`git diff --stat main..ui-redesign` shows **no changes** in `engine/`, `data/`, `AppContainer.kt`,
`LudoApp.kt`, `database.rules.json`, `firebase.json`, `rules-tests/`, or the engine and data tests.
Everything else that changed is display code:

- `ui/` (screens, board, tokens, dice, animations, pills, settings) and `ui/theme/`.
- `ui/game/GameViewModel.kt`: only the old 1.2-second notice and an unused `rolling` flag were removed
  (their job moved to the animation queue). Turn timers, auto-play and Firebase writes are unchanged.
- `MainActivity.kt`: puts the gradient background and the app-wide sound output around the screens.
- `AndroidManifest.xml`: the `VIBRATE` permission.
- Resources: the font, the sounds, new strings. Plus `tools/generate_sounds.py`, `licenses/`,
  `ASSETS.md`, and UI tests (`BoardGeometryTest`, new `PickTokenTest`).

## Fix-up round (branch `fixup-round`)

The version before this round is tagged `redesign-v1`. Changes, in the order of the brief:

### 1. Bigger board, thin border, compact panels

- The thick gold frame is now a 3 dp dark-brown border (`#5A3E2B`, 6 dp corners, soft shadow); the
  board is the full width minus 8 dp on each side.
- Both player panels are the same size and style (64 dp tall, 86% of the width); only the brightness
  shows whose turn it is (no more 1.05x scale or glow for the active one). They sit 10 dp from the
  board; spare height goes above the top panel and below the bottom panel only.

### 2. Bigger tokens, precise touch, automatic single moves

- Pawn measurements live in one place (`PawnShape` in `ui/game/TokenPainter.kt`): about 90% of a
  square, standing on a base disc. Everything a pawn draws at rest or while bobbing (head, base,
  shadow, glow ring) fits inside its own square, so the bob is small (9% of a square). A hop along
  the top row is not lifted above the board's edge. `TokenBoundsTest` checks every track square,
  home-column square and yard slot for both colors in both views, every stack size, and the finish
  spot.
- Smart tap (`pickToken` in `LudoBoard.kt`): the nearest token that can legally move within 1.5
  squares; tokens that cannot move are ignored completely. Tokens on the same square (or all in the
  yard) make the same move, so they count as one candidate. If two candidates on different squares
  are within 0.3 squares of each other in distance, both grow to 1.3x and a second tap decides.
  A short vibration confirms the pick. No landing squares, captures or safe squares are ever
  previewed.
- Exactly one token with a legal move (`LudoEngine.onlyMovableToken`, counting tokens) moves by
  itself 0.5 s after the dice has landed; board taps do nothing meanwhile. Two or more (including a
  six with all four in the yard, or two tokens on one square) means the player chooses. This
  replaced the ViewModel's 1.5-second single-move timer. The 30-second timeout auto-play is
  unchanged, so the game still moves on when the app is in the background.

### 3. The dice bug ("rolling forever")

**Root cause (from reading the code; it could not be reproduced).** The old roll had two separate
things driving my die. A tap started an optimistic "local tumble": a loop that kept changing the face
until either the confirmed roll reached the animation queue and found the loop still running, a snap
cancelled it, or a 5-second timer ran out. Nothing tied that loop to whether the roll was actually
sent: `GameViewModel.roll()` could ignore the tap without saying so (for example when the ViewModel
was busy with another write, such as an automatic move from the turn timer, or when its "can roll"
differed from what the screen had shown one frame earlier). The die then tumbled with no roll on its
way, while the turn timer kept running. A later state update (such as the move after tapping a
token) snapped the board and stopped it. On the emulators I could not trigger it: about 40 rolls with
double taps, token taps during the roll, app switches, dropped connections and non-stop die taps,
with logging on every roll, all handed over correctly. So the exact trigger on your phone is not
confirmed; the design flaw above is what allowed it, and it is gone.

**Fix.** My die is now a small finite state machine (`ui/game/RollMachine.kt`, 10 tests): a tap is
accepted only when idle and disables the die at once; the roll is sent and the ViewModel reports
whether it was written; the dice animation starts only for a confirmed roll, plays once with a fixed
length (about 900 ms) and lands on the confirmed value; then the die is idle again. A failed send, or
no confirmation within 5 seconds (also checked when the app comes back to the foreground), returns
the die to idle with the pill "Couldn't roll — tap again". The optimistic loop no longer exists. The
turn timer's automatic play lives in the ViewModel and does not depend on the screen.

**Stress test.** At first a debug-only "×200" button in the top bar (it passed on two emulators).
Later removed from the app at your request; it is now the automated test `RollStressTest` (see
"Tap and timer fixes" at the end).

### 4. Real 3D dice

`ui/game/DieCube.kt` (pure math, 5 tests) and `ui/game/Die.kt`: a cube with 8 corners, rotation
matrices about X, Y and Z, a fixed viewing tilt, perspective projection, only the faces facing the
viewer drawn back to front, each shaded by its angle to a light from the top left. Ivory faces with
rounded corners, black pips, the 1-pip in the player's color, opposite faces adding up to 7, and a
soft shadow on the table that shrinks while the die is up. The roll (about 900 ms): the die jumps up
(24 dp, 1.3x) while tumbling on a random mix of axes, fast then slower (ease-out), and lands with the
confirmed number facing the viewer, a small bounce and a wobble; rattle during the tumble, "clack" on
landing (`sfx_clack.wav`, generated like the other sounds). Whole extra turns are added to the resting
angles, so it always lands exactly on the value (tested for every value). The six glow, "+1 turn",
three-sixes red shake and idle wiggle stay. `@Preview`s show every face and several angles.

### 5. Classic board look

Flat warm colors (Red `#D9443A`, Green `#3F9B4F`, Yellow `#EDBE2E`, Blue `#2F7FCF`, also used for
panels and tokens), cream squares (`#FBF6EA`), a 1 dp dark line (`#3B2F2A` at 55%) around every
square including colored ones (so the yellow home column's squares are visible), yards with a thick
colored border around a cream inner square and four colored slots with thin dark rings, colored home
columns joined to colored start squares with an arrow, outlined dark-grey stars, flat center
triangles with thin dark lines, and a 4% paper grain generated once and cached with the board.
Tokens are classic pins: a pearl-white pin holding a glossy ball in the player's color with a white
highlight, on a matching base disc with a thin dark ring. Style only was taken from the reference
board you sent; the layout and all drawing are our own.

### 6. Stacking

- The translucent box is gone. It was drawn around "blocks" using the displayed game state, which
  still counted a token that had started to leave as part of the stack until the whole move finished,
  and token positions that were mid-animation; so it stretched and kept the tokens at stack size.
- Stacks are now laid out only from tokens at rest (`BoardGeometry.layout(positions, viewer)`); the
  animation queue leaves out any token that is moving, and re-lays out the moment a token leaves a
  square, lands, is knocked out, or lands back in its yard. 2 tokens: side by side at 60%; 3-4: 2x2 at
  50%; 5-8 (only possible on a safe square holding both colors): 3x3 at 33%. No counts or badges.
  `StackLayoutTest` covers a stack forming, a token leaving, a capture landing on the square, and a
  safe square with both colors.

### 7. Lucky Boost

- Rules engine: `Dice.roll(streak, boost)` / `Dice.rollFor(state, color, boost)` using `SecureRandom`
  (`engine/Dice.kt`). Chance of a 6 by the number of rolls in a row without a 6: 0-2 → 1 in 6,
  3 → 1 in 3, 4 → 1 in 2, 5+ → certain; when it is not a 6 the other five numbers stay equally likely.
- The count (`noSixRed` / `noSixYellow` in `GameState`, `noSixStreak` per uid in Firebase) grows on
  each roll without a 6 while the player has **no token on the board**; tokens in the home column
  count as on the board (your instruction), finished tokens do not. A 6, or any token on the board,
  resets it; a timeout (no roll) leaves it unchanged. With a token on the board every roll is normal.
- Room setting `luckyBoost` (default on), chosen with a switch above "Create Room" on the home
  screen; it cannot be changed after the room is created.
- Openness: explained in How to Play; a 6 rolled while the boost was raising the odds shows the pill
  "Lucky Boost! 🍀" (a 6 that would also have come naturally shows it too; the app cannot tell).
- Data model: room `schemaVersion` is now **2**. Rooms made by the previous version (1) show "Please
  update the app", and the previous version refuses version-2 rooms the same way, so the two versions
  never play against each other with different rules.
- Security rules: creating a room needs `schemaVersion` 2 and a boolean `luckyBoost`; games need
  `noSixStreak` with a whole number of 0 or more for each of the two players. The rules must be
  deployed to Firebase for this version to work (see README).
- Tests (`LuckyBoostTest`): 100,000 rolls are about 1/6 each (seeded and real `SecureRandom`); the
  boosted chances match the table with the other five numbers even; the boost never applies with a
  token on the track or in the home column; over 1,000 random games with the boost on, nobody went
  more than 5 turns in the yard without a 6 (so the 6th turn at the latest brings one).

## Rule change: safe pairs replace blocks (branch `safe-pairs`)

Your instruction, after the fix-up round. It replaces the spec's block rule (section 4 "Blocks",
edge cases 31 and 32).

- **Safe pair:** two or more tokens of one color on one square. They cannot be captured, on any
  square. They are not walls: any token may pass over them or land on their square, which is then
  shared.
- **Capture, only on landing:** the landing square must hold exactly one opponent token and must not
  be a safe square (star or start). Two or more opponent tokens there: the square is shared, no
  capture. If a pair later splits and leaves one opponent token sharing a normal square, nothing
  happens until a token lands there again.
- **Engine** (`LudoEngine.kt`): the block check is gone from `targetOf` (it now only needs the token's
  progress and the roll), and `isOpponentBlock` is deleted. The capture code was already "exactly one
  opponent token on a non-safe square" and did not change.
- **Side effect:** a token on the shared track (progress 0..50) can now always move. "No moves" is
  only possible for yard tokens without a 6 and for home-column tokens whose roll would overshoot.
  This also removes the case where a blocked token made the app auto-move the only other token.
- **"No moves" pill** now says why: "No moves — need a 6 to come out" (every token left is in the
  yard), "No moves — need exact number to reach home" (every token left is in the home column), or
  "No moves — need a 6 or exact number" (some of each; my wording, not given in the instruction).
  Worked out in `GameAnimator.playRoll` from the roller's tokens before the roll.
- **How to Play** explains safe pairs, and now says start squares are safe too (they always were).
- **Both phones must play the same rules:** room `schemaVersion` is now **3**. Older versions show
  "Please update the app" for version-3 rooms, and this version does the same for older rooms.
  Security rules: room creation and `schemaVersion` need 3. **The rules must be deployed again.**
- **Tests:** the block tests are deleted. New tests: passing over a pair (also coming out of the yard
  onto a start square held by a pair), landing on an opponent pair on a normal square (shared), a
  pair splitting and then a token landing on the single token left (captured), my pair being landed
  on (shared), and "the player chooses when one token is behind an opponent pair". Landing on a
  single opponent token is covered by the existing test 30. The 1,000-game random test passes with
  the new rules. Rules tests: schema 3 accepted, 2 and 4 refused.

## Tap and timer fixes (branch `safe-pairs`)

- **A tap directly on my own token means that token.** If it can move, it moves. If it cannot, no
  other token moves: it shakes a little and the pill "Can't move" shows (not repeated while it is
  up). Only a tap that is not on one of my tokens uses the nearest-movable-token helper (1.5 squares,
  "too close" second tap). "Directly on" = inside the area the pawn draws at its current size
  (`PawnShape.envelope`, the same one the bounds test uses), so a stacked token counts at its smaller
  size and a "too close" token at its enlarged size. This only applies while I am choosing a move.
  Code: `tokenUnderTap` and the tap handler in `LudoBoard.kt`, `GameAnimator.cantMove`; tests in
  `PickTokenTest`.
- **My die tap wins over the timer's automatic roll.** A tap that registers before the deadline
  already won (it starts my write at once, and the timer then finds a write running and stops). The
  gap was a tap that registers (finger lifted) while the timer's automatic roll is being written:
  the tap was refused ("Couldn't roll") and the app then moved the first legal token by itself 0.5 s
  after that roll. Now `GameViewModel.roll` remembers the tap for that turn; the die waits for the
  roll being written instead of failing; and `playTimers` skips the automatic move after an
  automatic roll that I tapped for. I choose my move within the fresh move time that every roll
  sets (30 s, the normal move timer; nothing new was added). The automatic roll still counts as a
  missed turn in the data, and my manual move resets that count to 0 as usual. The opponent's
  Timeout rule (5 s after the deadline) is unchanged. Not covered by a unit test (the ViewModel needs
  Firebase); it needs checking on a phone.
- **×200 button removed.** The stress test is now `RollStressTest`: 200 taps on my die through
  `RollMachine` while the engine plays the game, with a refused write, a 5 s timeout, a repeated
  update or a board jump now and then. The die must be ready again after every roll.
- **Found by that test:** after a board jump (for example on reconnecting) while my die was waiting
  for its roll, the die stayed disabled for up to 5 s and then showed a false "Couldn't roll".
  `RollMachine.snapped` now also clears the waiting state; a roll of mine arriving later is still
  shown. New test in `RollMachineTest`.

## Final review and stress test before release (branch `safe-pairs`)

- **Fixes from a code review of the whole branch:**
  - The automatic single move now fires only while a move can be sent (online, no write running).
    Before, a move dropped by a short disconnect was never retried and board taps stayed off until
    the 30-second timer moved the token.
  - A refused die tap no longer counts as "tapped for this roll" (only a tap that races the timer's
    automatic roll does), so it cannot delay the automatic move of a player who is away.
  - `LudoEngine.isValid` rejects a MOVE state with no legal move. Only a modified app could write
    one; it crashed the other phone before, and now shows "Something went wrong with this game".
- **End-to-end on two emulators (local Firebase emulators):** a full game played by tapping through
  both apps (370 updates: 193 rolls, 176 moves, 6 captures, won by bringing all four home); rematch
  (loser first); all three "No moves" pills; "Can't move" on a token that cannot move (nothing
  moves); landing on an opponent pair (shared, no capture, turn passes); disconnect and reconnect
  ("Opponent reconnecting…", "Reconnecting…", then the board catches up); 3 missed turns forfeit;
  kill and rejoin; wrong code; code input clean-up; room full; room closed; leave (both sides);
  3,000 random taps on each phone (no crash, no "not responding").
- **Seen only under very heavy load (load average above 30):** the host's first turn can run out
  while its screen is still loading, because the turn clock starts when the guest joins. Not changed.

## Chat and animated emoji reactions (branch `chat-emoji`)

No change to the rules or the engine.

- **Emoji set:** all 16 requested emojis exist in Google's Noto Emoji Animation set; none was swapped.
  They are bundled in `res/raw` as `emoji_<code point>.json` (about 1.1 MB, unchanged), never
  downloaded at runtime, and played with Airbnb's `lottie-compose`. Credit: Settings → Credits and
  `ASSETS.md` (CC BY 4.0).
- **Software rendering for Lottie:** on the test emulators, drawing the emojis with Lottie's default
  hardware path made the whole emulator process die (both emulators, every time the tray opened).
  With `RenderMode.SOFTWARE` it works. At 46–64 dp software drawing is cheap, and it avoids depending
  on a phone's graphics driver, so it is used everywhere.
- **First frames:** the tray shows each emoji's first frame, as asked. For three of them the first
  frame looks different from the emoji itself (😈 starts as a plain smile, 😢 before its tear, 🎉
  as the cone only).
- **How long an emoji shows after landing:** the animations are 0.7–5 s long. It plays for 2 s,
  looping, then fades and shrinks (300 ms). Short ones repeat, long ones are cut.
- **Flight:** from the sender's avatar to next to the receiver's name, on a curve (700 ms,
  ease-in-out), growing from 60% to 64 dp, with a trail of fading dots. It lands above the top panel
  or below the bottom panel, so it does not cover the board. Then a bounce, the animation, a "pop"
  (respects Sound) and, on the receiver's phone, a light vibration (respects Vibration). At most 3
  fly at once; more wait. Each flying emoji has a slot (0–2) that moves its landing spot 60 dp
  sideways.
- **Taps:** the overlay (flights and bubbles) has no touch handling at all, so taps reach the board
  and the dice. `EmojiOverlayTapTest` (Robolectric) taps a token under a flying emoji. It was also
  checked that the test fails if the overlay takes taps.
- **Old emojis are not replayed:** `NewMessages` treats the first snapshot after opening the screen,
  and the first after a real reconnect (online → offline → online), as history.
- **Rate limits** (app side): 1 emoji per 2 s and 1 text or phrase per second, per player. A refused
  emoji shakes the tray. The security rules do not rate-limit.
- **Unread badge and bubbles** count only the opponent's texts and phrases (emojis show as flights).
  Opening the chat clears the badge.
- **Chat sheet:** half the screen height; the keyboard pushes the field up (checked on the emulator:
  Send stays visible). Also reachable from the game over screen (chat button with badge). Text is
  plain; nothing becomes a link. Quick phrases are sent as type "phrase" in English (the other player
  sees the same words).
- **Mute opponent** (Settings, in a game only): hides their messages, bubbles and emojis for the
  rest of this game screen. My own sending still works. It is not saved.
- **Report** (chat menu): after a confirmation, saves `reports/{pushId}` with the room code, both
  uids, the last 20 messages (both players) and the server time. Nobody can read reports through the
  app.
- **Data:** `rooms/{code}/chat/{pushId}` with `uid`, `type`, `text` or `emojiId`, `seq`, `sentAt`
  (server time), and `rooms/{code}/chatSeq`.
  - **Why `seq`/`chatSeq` were added:** the rules language cannot count children, so "delete only to
    trim" could not be written with the requested fields alone. Each message has a sequence number.
    `chatSeq` must go up by exactly 1 in the same write that adds a message. A message can be deleted
    only when `seq <= chatSeq - 50`. If both players send at the same moment, one write is refused
    and the app retries with the next number.
- **Security rules:** chat readable and writable only by the two players; `uid` must be the writer;
  `type` one of three; `text` 1–100 characters; `emojiId` one of the 16; `sentAt` must be the server
  time; no edits. Reports are write-only, by a player of that room, about the other player.
  `schemaVersion` is now **4**. **The rules must be deployed again** (not done: waiting for you).
- **Tests:** `ChatTest` (text clean-up and length, both rate limits, no replay on open and on
  reconnect, trimming to 50, decoding), `EmojiFlightsTest` (at most 3, slots, queue order),
  `EmojiOverlayTapTest`; rules tests for chat and reports (non-member, sending as the other player,
  101 characters, unknown emoji, server time, sequence numbers, no edits, trimming, reports, schema
  4 accepted and 3 refused).
- **New sound:** `sfx_pop.wav`, generated last by `tools/generate_sounds.py` so every existing sound
  stays byte-for-byte the same.
- **Checked on two emulators (local Firebase emulators):** tray; emoji flight on both phones
  (sender: to the opponent's panel; receiver: from the sender's panel to their own); chat with text
  (spaces cleaned), quick phrase, bubble and unread badge; keyboard; game over chat; report saved;
  mute hides everything from the opponent while my own sending works; emoji rate limit; credits.
