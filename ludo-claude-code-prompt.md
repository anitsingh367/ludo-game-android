# Build: 2-Player Online Ludo for Android (Private Rooms, Firebase)

You are building a complete, working Android app in this empty folder. Work through every milestone below, in order, until the Definition of Done at the end is fully met. Do not stop after scaffolding. After each milestone, build the project and run the tests; fix every error before moving on. Keep going without asking me questions unless you are truly blocked. If you must make a choice I haven't covered, pick the simplest option that keeps the game fair and stable, write it in `DECISIONS.md`, and continue.

## 1. What the app is

- Classic Ludo for exactly **2 players**, played **online** on two separate phones.
- Games happen only in **private rooms**: one player creates a room and gets a short code; the other enters the code to join. **No random matchmaking, no bots, no offline mode, no pass-and-play.**
- Players sit in opposite corners: host is **Red**, guest is **Yellow**.
- No accounts, no ads, no store, no chat. Keep it small and solid.

## 2. Tech stack (use exactly this)

- Kotlin, Jetpack Compose (Material 3), single-activity app, MVVM with `ViewModel` + `StateFlow`, Kotlin coroutines.
- minSdk 26, targetSdk and compileSdk = latest stable. Gradle Kotlin DSL with a version catalog (`libs.versions.toml`).
- Firebase via the Firebase BoM:
  - **Firebase Authentication — Anonymous sign-in only.**
  - **Firebase Realtime Database** (not Firestore) — chosen for its built-in presence (`.info/connected`, `onDisconnect`), transactions and server time offset.
- **No Cloud Functions** (the project must work on the free Spark plan).
- No dependency injection framework; use a simple manual `AppContainer`.
- Package name: `com.example.ludoduel` (I will change it later if needed).

## 3. Project structure

```
app/
  src/main/java/com/example/ludoduel/
    engine/     <- pure Kotlin rules engine, NO Android or Firebase imports
    data/       <- Firebase: auth, room repository, presence, server clock
    ui/         <- Compose screens, board drawing, ViewModels
  src/test/     <- unit tests for the engine and the reducer logic
database.rules.json   <- Realtime Database security rules
firebase.json         <- so rules can be deployed with the Firebase CLI
README.md             <- setup steps for me (see section 10)
DECISIONS.md          <- every assumption you made
```

The rules engine must be completely separate from Firebase and the UI so it can be unit-tested and both phones always compute identical results.

## 4. Game rules (implement exactly)

**Board and positions**
- Shared track of 52 squares, absolute indexes 0–51, moving clockwise.
- Red starts at absolute square 0. Yellow starts at absolute square 26.
- Each token's position is stored as its **progress** from its own start:
  - `-1` = in yard
  - `0..50` = on the shared track (absolute square = `(startIndex + progress) % 52`)
  - `51..55` = its own home column (only that color can be there; always safe)
  - `56` = reached home (finished)
- Safe squares (absolute): 0, 8, 13, 21, 26, 34, 39, 47. Tokens on safe squares cannot be captured, and both colors may share them.

**Turns and dice**
- One six-sided die. Rolls use `java.security.SecureRandom`.
- A token leaves the yard only on a 6, and goes to progress 0.
- Rolling a 6 gives an extra turn.
- Three 6s in a row: the third 6 is cancelled, no move happens, the turn passes to the opponent, and the six-counter resets.
- A token must move the exact number rolled; a roll cannot be split.
- A token needs the exact number to reach progress 56. If the roll would overshoot, that token cannot move.
- If the player has no legal move, show the dice result for about 1.2 seconds, then the turn passes automatically.
- If exactly one legal move exists, the app still lets the player tap it, but auto-plays it after 1.5 seconds if they don't.
- Host (Red) moves first in game 1. In rematches, the loser of the previous game starts.

**Capturing**
- Landing exactly on a square holding a **single** opponent token (not on a safe square) sends that token back to the yard (`-1`).
- A capture gives an extra turn.

**Blocks**
- Two tokens of the same color on the same non-home-column square form a block.
- The opponent cannot land on or move **past** a block. Any move that would do so is illegal.

**Extra turns**
- Extra turn for: rolling a 6 (unless it's the third 6), capturing, or a token reaching home. Multiple reasons in one move still give only one extra turn.

**Winning**
- First player to get all 4 tokens to 56 wins. The game ends immediately.
- A player also wins if the opponent forfeits (leaves, or misses 3 turns in a row — see section 6).

**Engine API** (suggested)
- `data class GameState(...)`, `sealed interface Action { Roll(value), Move(tokenIndex), Pass, Timeout, Forfeit }`
- `fun legalMoves(state, color, dice): List<Int>`
- `fun apply(state, action, actor): Result<GameState>` — returns an error for any illegal action, never throws, never mutates input.
- The engine is the only place rules live. The UI and repository never re-implement rules.

## 5. Firebase data model (Realtime Database)

```
rooms/{code}
  schemaVersion: 1
  createdAt: <server timestamp>
  expiresAt: <createdAt + 24h>
  hostUid, guestUid (null until joined)
  status: "waiting" | "playing" | "finished" | "abandoned"
  players/{uid}: { color, name, connected: bool, lastSeen: <server timestamp> }
  game:
    version: Int            // increases by exactly 1 on every write
    gameNumber: Int         // 1, 2, 3... for rematches
    turnUid
    phase: "ROLL" | "MOVE" | "OVER"
    dice: Int | null
    sixesInRow: Int
    tokens: { red: [p,p,p,p], yellow: [p,p,p,p] }
    missedTurns: { <uid>: Int }
    turnDeadline: <server ms>
    lastAction: { type, byUid, token, from, to, captured, dice }
    winnerUid, winReason: "all_home" | "forfeit" | "left"
  rematch/{uid}: true
```

- **Every game write goes through a Realtime Database transaction** on `rooms/{code}/game` that: reads current state, checks `turnUid` and `version`, runs the engine's `apply`, and writes the new state with `version + 1`. If the transaction's view doesn't match (someone else moved, or a stale tap), abort and do nothing.
- Both phones render **only** from the state received from Firebase — never from local guesses. Optimistic UI is allowed for the dice animation only.
- Use `.info/serverTimeOffset` for all time calculations (turn timer, expiry). Never trust the phone's clock alone.

## 6. Edge cases you must handle (each one needs code, and a test where possible)

**Creating and joining rooms**
1. Room code: 6 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no 0/O/1/I/L). Create it with a transaction that fails if the code exists; retry with a new code up to 5 times.
2. Join with a code that doesn't exist → "Room not found".
3. Join an expired room (`expiresAt` passed) → "This room has expired".
4. Join a room that already has a guest → "Room is full".
5. Host tries to join their own room from the join screen → just open their room.
6. Code input: uppercase automatically, trim spaces, ignore confusable characters, validate length before contacting Firebase.
7. Host can cancel a waiting room → status `abandoned`; a guest trying to join sees "Room closed".
8. Share button uses the Android share sheet with a message like `Join my Ludo game! Code: ABC234`.
9. Guest joining uses a transaction so two people entering the same code at once can't both get in.

**Connection and presence**
10. Presence: on entering a room, write `connected: true`, and register `onDisconnect` to set `connected: false` and `lastSeen`. Re-register every time `.info/connected` goes true again.
11. Watch `.info/connected`. While disconnected, show a clear "Reconnecting…" banner and **disable all game input**. Don't queue moves while offline.
12. When the opponent's `connected` is false, show "Opponent disconnected — waiting (mm:ss)". The game keeps its normal turn timer.
13. After reconnecting, snap the board to the latest state (no replaying of missed animations beyond the last action).
14. Airplane mode, switching Wi-Fi to mobile data, and a weak signal must never corrupt the game or duplicate a move.

**Turn timer and inactivity**
15. Each turn has a 30-second deadline stored as `turnDeadline` (server time). Show a countdown ring on the current player's side.
16. If the current player's own app is open and their time runs out, it auto-plays: rolls if in ROLL phase, then makes the first legal move (or passes).
17. If the current player's app is closed or offline, the **opponent's app** may write a `Timeout` action, but only once `serverNow > turnDeadline + 5 seconds`. This passes the turn and increases that player's `missedTurns`.
18. A successful manual action resets that player's `missedTurns` to 0.
19. 3 missed turns in a row → that player forfeits; the other wins with reason `forfeit`.
20. Security rules must also enforce: only the current player may write, **except** a Timeout by the other player after the deadline.

**App lifecycle**
21. App goes to background mid-game → game continues; when reopened, the latest state shows.
22. App killed or phone restarted → on next launch, if the saved room (store the code in DataStore) is still `playing` and this uid is in it, show "Rejoin game?" on the home screen.
23. Screen rotation → lock the game screen to portrait; state must still survive configuration changes and process death (use `SavedStateHandle` for the room code).
24. Double taps and fast taps: disable input while a transaction is running; ignore taps on tokens that aren't legal moves.
25. Back button during a game → confirm dialog "Leave game? You will lose." → confirming forfeits (reason `left`).

**Game logic edge cases (unit test every one)**
26. No token out of the yard and roll isn't 6 → auto pass.
27. Rolled 6 with all tokens in the yard → only "bring out" moves are legal.
28. Exact roll needed to finish; overshoot is illegal for that token.
29. Three 6s in a row → cancelled, turn passes, counter resets.
30. Capture on a normal square; no capture on safe squares; no capture of a block.
31. Moving past or onto an opponent block is illegal.
32. Own tokens can stack; two own tokens form a block.
33. Extra turn from capture + 6 in the same move is only one extra turn.
34. Last token reaching home ends the game instantly — no extra turn is given.
35. Board wrap-around: Yellow's progress crossing absolute square 51 → 0 is calculated correctly.
36. Yellow's tokens never enter Red's home column and vice versa.
37. A state where neither player has any legal move for a roll still passes correctly (no infinite loop).

**Versioning and data safety**
38. If `schemaVersion` in the room isn't 1, show "Please update the app to join this room".
39. Reject any received game state that fails basic validation (wrong token count, positions outside -1..56) — show "Something went wrong with this game" and let the player go home.
40. Anonymous auth: sign in on first launch, reuse the same user afterwards. If sign-in fails (no internet), show a retry screen.

**Rematch and ending**
41. Game over screen shows winner, reason, and "Rematch" / "Home".
42. Rematch starts only when both players have set `rematch/{uid} = true`; then reset `game` with `gameNumber + 1` and clear `rematch`. If the other player left, show "Opponent left".
43. When either player leaves after the game, set status appropriately so the other sees it.

## 7. Security rules (`database.rules.json`)

Write real, strict rules — not open test rules. Requirements:
- Must be signed in to read or write anything.
- `rooms/{code}` can be **created** only if it doesn't exist, with `hostUid == auth.uid`, `guestUid` null, `status == "waiting"`, `schemaVersion == 1`.
- `guestUid` can be set only once, only by the joining user to their own uid, only while `status == "waiting"` and `now < expiresAt`, and not by the host.
- Only `hostUid` and `guestUid` can read the room.
- A player can write only their own `players/{uid}` entry and their own `rematch/{uid}`.
- `game` writes: new `version` must equal old `version + 1`; writer must be `turnUid`, **or** the other player writing a Timeout when `now > turnDeadline + 5000`; `dice` must be null or 1–6; token positions must be integers in -1..56.
- Add `.indexOn` where needed. Include comments explaining each rule.
- Note in `DECISIONS.md`: dice are rolled on the phone, so a modified app could cheat. That's acceptable for games between friends; moving dice to a server would need Cloud Functions (Blaze plan).

## 8. Screens and UI

1. **Splash** → anonymous sign-in, then Home.
2. **Home**: player name field (saved locally, 2–16 chars, default "Player"), "Create Room", "Join Room", and "Rejoin game" when applicable.
3. **Create / Waiting room**: big room code, Copy and Share buttons, "Waiting for opponent…", Cancel.
4. **Join**: 6-box code entry, Join button, clear error messages.
5. **Game**: board drawn with Compose `Canvas` (scales to any screen size, keeps square aspect), both players' names and colors, whose turn it is, countdown ring, dice button (only enabled on your turn in ROLL phase), legal tokens glow and are tappable, token moves animate step by step (~120 ms per step), captures animate back to yard, reconnecting/opponent-offline banners, a small "How to play" sheet.
6. **Game over**: winner, reason, Rematch, Home.

Also:
- Colorblind-friendly: each token shows a letter or shape as well as color.
- Sound and vibration on roll/capture/home, with a mute toggle.
- Light and dark theme support.
- All user-facing text in `strings.xml`.
- Keep the screen awake during a game.

## 9. Tests

- Unit tests for the engine covering every case in section 6 items 26–37, plus a randomized test that plays 1,000 full games with random legal moves and checks: no crashes, every game ends, token counts stay 4 per color, positions stay in range.
- Unit tests for room code generation and code input cleaning.
- Tests for the transaction reducer logic (the function that takes current game state + action + uid and returns the new state or a rejection) using fakes, without Firebase.
- If the Firebase CLI and emulator are available, add rules tests; if not, skip them and note it in `DECISIONS.md`.

## 10. README for me

Write step-by-step setup for a non-expert:
1. Create a Firebase project, add an Android app with the package name.
2. Download `google-services.json` into `app/`.
3. Enable Anonymous sign-in in Authentication.
4. Create a Realtime Database and paste in (or deploy with the Firebase CLI) `database.rules.json`.
5. Open in Android Studio, run on two devices or emulators, create a room on one and join on the other.
6. A short manual test checklist covering: join by code, full room, wrong code, airplane mode mid-game, killing the app and rejoining, timeout skips, 3-missed-turn forfeit, rematch.

The project must build even before `google-services.json` exists, OR the README must clearly say the build needs it first. Add `google-services.json` to `.gitignore`.

## 11. Definition of Done

- `./gradlew assembleDebug` succeeds (with a placeholder `google-services.json` if needed for the build; note this clearly).
- `./gradlew test` passes, including the 1,000-game random test.
- Every numbered edge case in section 6 is handled in code; list each number in `DECISIONS.md` with where it's handled.
- Security rules file is complete and strict.
- README and DECISIONS are written.
- No TODOs left in the code for required features.

Start by writing a short plan, then build milestone by milestone: (1) project setup, (2) engine + tests, (3) Firebase auth + room create/join, (4) game sync with transactions, (5) presence, timers, timeouts, (6) UI and animations, (7) rematch and lifecycle, (8) security rules, (9) README, DECISIONS, final build and test run.
