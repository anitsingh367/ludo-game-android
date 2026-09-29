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
- **Blocks** are 2 or more tokens of one color on one shared-track square, including safe squares
  and start squares. A block on your start square keeps your tokens in the yard until it moves.
  Tokens in a home column never form a block.
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
| 30 | Capture rules (normal, safe, block) | `LudoEngine.move`; tests 30 |
| 31 | Can't land on or pass a block | `LudoEngine.targetOf`; tests 31 |
| 32 | Own stacking, blocks | tests 32 |
| 33 | Capture + 6 = one extra turn | `LudoEngine.move` (`extraTurn`); test 33 |
| 34 | Last token home ends game, no extra turn | `LudoEngine.move`; test 34 |
| 35 | Yellow wrap 51 → 0 | `LudoEngine.absoluteSquare`; tests 35 |
| 36 | Each color only in its own home column | progress 51..55 never maps to the shared track; tests 36; `BoardGeometryTest` |
| 37 | Nobody can move → still passes | `LudoEngine.roll`; test 37 |
| 38 | `schemaVersion` ≠ 1 → "Please update the app" | `joinRoom` (`UpdateRequired`); `GameViewModel.buildUi` (`UPDATE_REQUIRED`) |
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

