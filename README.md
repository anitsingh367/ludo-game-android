# Ludo Duel

Classic Ludo for exactly two players, online, in private rooms. One player creates a room and
shares a 6-character code; the other joins with the code. Host is Red, guest is Yellow.

Built with Kotlin, Jetpack Compose (Material 3), Firebase Anonymous Authentication and the
Firebase Realtime Database. No Cloud Functions, so it runs on the free Spark plan.

- `app/src/main/java/com/example/ludoduel/engine/` – the rules engine (pure Kotlin, no Android/Firebase).
- `app/src/main/java/com/example/ludoduel/data/` – Firebase auth, rooms, transactions, presence, server clock.
- `app/src/main/java/com/example/ludoduel/ui/` – Compose screens, board drawing, ViewModels.
- `database.rules.json` – Realtime Database security rules. `firebase.json` – for deploying them.
- `rules-tests/` – tests for the security rules, run against the Firebase emulator.
- `emulator/google-services.json` – placeholder config, only for trying the app with local emulators.
- `DECISIONS.md` – every assumption made, where each edge case is handled, and the visual redesign.
- `ASSETS.md` – where the font and sounds come from and their licenses (`tools/generate_sounds.py` makes the sounds).

The gear button (on Home and in the game) opens the settings: sound, vibration and colorblind mode
(a letter on every token). The "Lucky Boost 🍀" switch above "Create Room" is a room setting (see
How to Play in the game).

**After updating the app, deploy the security rules again** (`firebase deploy --only database`, or
paste `database.rules.json` into the console). This version stores new fields (Lucky Boost) and
rooms of schema version 4 (chat), which the old rules do not allow.

## Important: the build and `google-services.json`

The project builds **without** `app/google-services.json`, but that app cannot talk to Firebase:
it opens on a screen saying "Firebase is not set up". Follow the steps below to add the file, then
build again. The file is in `.gitignore` because it belongs to your Firebase project.

## Release APK to share

1. Once, create your signing key: `tools/create_release_key.sh`. It asks for a password and writes
   the key to `~/ludo-release-key/ludo-release.jks` and its details to `keystore.properties`
   (both ignored by git). Back up both and keep the password: every update must use the same key,
   or phones refuse to install it over the old version.
2. Build: `./gradlew assembleRelease`. The APK is `app/build/outputs/apk/release/app-release.apk`.
   Without `keystore.properties` the release APK is not signed and cannot be installed.
3. For each new version, raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.

## Setup (step by step)

### 1. Create a Firebase project and add the Android app

1. Go to <https://console.firebase.google.com> and click **Create a project** (any name).
   Google Analytics is not needed; you can turn it off.
2. On the project overview page click the **Android** icon ("Add app").
3. **Android package name:** `com.example.ludoduel`
   (if you change the package name later, change it in `app/build.gradle.kts` too and register
   the new name here).
4. Nickname and SHA-1 are optional. Anonymous sign-in does not need SHA-1.
5. Click **Register app**. Do **not** download `google-services.json` yet (see step 3 below),
   or download it now and download it again after step 3.

### 2. Enable Anonymous sign-in

1. In the left menu open **Build → Authentication** and click **Get started**.
2. Open the **Sign-in method** tab, click **Anonymous**, switch it **on**, and **Save**.

### 3. Create the Realtime Database and set the rules

1. In the left menu open **Build → Realtime Database** and click **Create Database**.
2. Pick a location (any), then choose **Start in locked mode**.
3. Open the **Rules** tab, delete everything there, paste the full contents of
   `database.rules.json` from this project, and click **Publish**.

   Or, with the Firebase CLI installed (`npm install -g firebase-tools`):

   ```bash
   firebase login
   firebase use --add          # pick your project
   firebase deploy --only database
   ```

### 4. Download `google-services.json`

1. Open **Project settings** (gear icon) → **General** → **Your apps** → the Android app.
2. Download `google-services.json` and put it in the `app/` folder of this project
   (`app/google-services.json`).

Do this **after** creating the database: the file then contains the database URL. If you
downloaded it before, download it again. (Without the URL the app shows "No Realtime Database
found in google-services.json".)

### 5. Build and run

1. Open this folder in Android Studio (**File → Open**) and wait for Gradle sync.
2. Run the app on **two** devices or emulators (Run ▶, pick a device; then pick another device
   and run again). Both need internet.
3. On the first phone enter a name and tap **Create room**. You get a code like `ABC234`.
4. On the second phone tap **Join room**, type the code and tap **Join**. The game starts;
   Red (the host) rolls first.

From a terminal instead of Android Studio:

```bash
./gradlew assembleDebug        # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # runs all unit tests, including 1,000 random games
```

(The project uses the JDK bundled with Android Studio. From a terminal, point `JAVA_HOME` at it,
for example `export JAVA_HOME=/path/to/android-studio/jbr`.)

### Optional: try it without a Firebase project (local emulators)

Needs Node.js 18+ and Java (Android Studio's `jbr` folder works as `JAVA_HOME`). This runs
Firebase Auth and the Realtime Database on your computer and uses a debug-only switch in the app.
It is how the app was tested end to end.

1. Start the emulators (leave this terminal open):

   ```bash
   cd rules-tests && npm install
   npx firebase emulators:start --project demo-ludo --only auth,database --config ../firebase.json
   ```

2. Copy the placeholder config (it points at the fake project `demo-ludo`):

   ```bash
   cp emulator/google-services.json app/google-services.json
   ```

3. Build with the emulator switch and install on each device (emulator or USB phone):

   ```bash
   ./gradlew assembleDebug -PfirebaseEmulatorHost=127.0.0.1
   adb -s <device> reverse tcp:9000 tcp:9000
   adb -s <device> reverse tcp:9099 tcp:9099
   adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   `adb reverse` makes `127.0.0.1` on the phone reach your computer. It is needed even on the
   Android emulator: after a reconnect the Database emulator tells the app to use `127.0.0.1`.
   You can see the data with
   `curl -H "Authorization: Bearer owner" "http://127.0.0.1:9000/.json?ns=demo-ludo-default-rtdb"`.

4. To go back to your real project: put your own `google-services.json` in `app/` and build
   without `-PfirebaseEmulatorHost`.

### Security rules tests

```bash
cd rules-tests
npm install
npm test          # starts the Database emulator, runs rules.test.js, stops it
```

## Manual test checklist (two phones)

Tick each one on two real phones or two emulators.

- [ ] **Join by code:** A creates a room, B joins with the code. Both see the board; Red rolls first.
- [ ] **Code input:** typing `abc 234` becomes `ABC234`; `0`, `O`, `1`, `I`, `L` are ignored; Join stays disabled until 6 characters.
- [ ] **Wrong code:** join `ZZZZZZ` → "Room not found".
- [ ] **Full room:** a third phone joins the same code → "Room is full".
- [ ] **Own room:** the host enters their own code on the Join screen → their waiting room opens.
- [ ] **Cancelled room:** host taps Cancel while waiting; another phone joining → "Room closed".
- [ ] **Share:** Share on the waiting screen opens the share sheet with "Join my Ludo game! Code: …".
- [ ] **Airplane mode mid-game:** turn on airplane mode on B. B shows "Reconnecting…" and cannot tap; A shows "Opponent disconnected — waiting (mm:ss)". Turn it off: B catches up to the current board with no duplicate moves.
- [ ] **Wi-Fi to mobile data:** switch networks during a turn; the game continues, nothing is duplicated.
- [ ] **Kill and rejoin:** during a game, swipe the app away on B (or restart the phone). Open it again: Home shows "Rejoin game?"; tap it and the current board appears.
- [ ] **Timeout skips:** on your turn, don't touch anything for 30 seconds: the app rolls and moves for you. With the app closed on B, A's app skips B's turn about 35 seconds after it started ("… ran out of time").
- [ ] **3 missed turns forfeit:** close the app on B and wait; after B's 3rd missed turn A wins with "missed 3 turns in a row".
- [ ] **Leave:** press Back during a game → "Leave game? You will lose." → Leave. The other phone shows "… left the game" and "Opponent left".
- [ ] **Rematch:** finish a game (or use the timeout forfeit), both tap Rematch. A new game starts; the loser rolls first. If one player taps Home instead, the other sees "Opponent left".
- [ ] **Rotation:** rotating the phone keeps the game in portrait and nothing is lost.
- [ ] **Mute:** the speaker button turns sound and vibration off and on.
