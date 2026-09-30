// Security rules tests for database.rules.json.
// Run with: npm test   (starts the Realtime Database emulator, runs these tests, stops it)
//
// The game objects below have exactly the shape the app writes (see GameCodec.encode in the app).

const { test, before, beforeEach, after } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');

const HOST = 'hostUid';
const GUEST = 'guestUid';
const STRANGER = 'strangerUid';
const CODE = 'ABC234';
const DAY = 24 * 60 * 60 * 1000;

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-ludo',
    database: {
      rules: fs.readFileSync(path.join(__dirname, '..', 'database.rules.json'), 'utf8'),
      host: '127.0.0.1',
      port: 9000,
    },
  });
});

beforeEach(async () => {
  await env.clearDatabase();
});

after(async () => {
  await env.cleanup();
});

const db = (uid) => (uid ? env.authenticatedContext(uid).database() : env.unauthenticatedContext().database());
const room = (uid, sub = '') => db(uid).ref(`rooms/${CODE}${sub}`);

function newRoom(now = Date.now(), host = HOST) {
  return {
    schemaVersion: 3,
    createdAt: now,
    expiresAt: now + DAY,
    hostUid: host,
    status: 'waiting',
    luckyBoost: true,
    players: { [host]: { color: 'red', name: 'Ann', connected: true, lastSeen: now } },
  };
}

function initialGame(now = Date.now()) {
  return {
    version: 1,
    gameNumber: 1,
    turnUid: HOST,
    phase: 'ROLL',
    sixesInRow: 0,
    tokens: { red: [-1, -1, -1, -1], yellow: [-1, -1, -1, -1] },
    missedTurns: { [HOST]: 0, [GUEST]: 0 },
    noSixStreak: { [HOST]: 0, [GUEST]: 0 },
    turnDeadline: now + 30000,
  };
}

/** Seeds data with rules turned off. */
async function seed(value, sub = '') {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await ctx.database().ref(`rooms/${CODE}${sub}`).set(value);
  });
}

async function seedPlaying(gameOverrides = {}) {
  const now = Date.now();
  const r = newRoom(now);
  r.guestUid = GUEST;
  r.status = 'playing';
  r.players[GUEST] = { color: 'yellow', name: 'Bo', connected: true, lastSeen: now };
  r.game = { ...initialGame(now), ...gameOverrides };
  await seed(r);
  return r;
}

// ---------- Creating rooms ----------

test('signed-out users can do nothing', async () => {
  await assertFails(room(null).set(newRoom()));
  await seed(newRoom());
  await assertFails(room(null).once('value'));
  await assertFails(room(null, '/status').once('value'));
});

test('host can create a room with a free code', async () => {
  await assertSucceeds(room(HOST).set(newRoom()));
});

test('room creation uses a transaction that aborts if the code exists', async () => {
  const result = await room(HOST).transaction((current) => (current === null ? newRoom() : undefined));
  if (!result.committed) throw new Error('first create should commit');
  // A second user trying the same code cannot read it (not their room) or overwrite it.
  await assertFails(room(STRANGER).transaction((current) => (current === null ? newRoom(Date.now(), STRANGER) : undefined)));
  await assertFails(room(STRANGER).set(newRoom(Date.now(), STRANGER)));
});

test('an existing room cannot be replaced or deleted', async () => {
  await seed(newRoom());
  await assertFails(room(HOST).set(newRoom()));
  await assertFails(room(HOST).remove());
});

test('room creation checks every field', async () => {
  const now = Date.now();
  await assertFails(room(HOST).set(newRoom(now, STRANGER))); // host must be the creator
  await assertFails(room(HOST).set({ ...newRoom(now), guestUid: GUEST }));
  await assertFails(room(HOST).set({ ...newRoom(now), status: 'playing' }));
  await assertFails(room(HOST).set({ ...newRoom(now), schemaVersion: 2 })); // older app
  await assertFails(room(HOST).set({ ...newRoom(now), schemaVersion: 4 }));
  const noBoost = newRoom(now);
  delete noBoost.luckyBoost;
  await assertFails(room(HOST).set(noBoost)); // the Lucky Boost setting must be chosen
  await assertFails(room(HOST).set({ ...newRoom(now), luckyBoost: 'yes' }));
  await assertSucceeds(room(HOST).set({ ...newRoom(now), luckyBoost: false }));
  await assertFails(room(HOST).set({ ...newRoom(now), expiresAt: now + 10 * DAY }));
  await assertFails(room(HOST).set({ ...newRoom(now), createdAt: now - DAY, expiresAt: now }));
  await assertFails(room(HOST).set({ ...newRoom(now), game: initialGame(now) }));
  await assertFails(room(HOST).set({ ...newRoom(now), extra: 1 }));
  const yellowHost = newRoom(now);
  yellowHost.players[HOST].color = 'yellow';
  await assertFails(room(HOST).set(yellowHost));
  await assertFails(db(HOST).ref('rooms/ABC0O1').set(newRoom(now))); // confusable characters
  await assertFails(db(HOST).ref('rooms/ABC23').set(newRoom(now))); // too short
});

// ---------- Reading ----------

test('only the two players can read the room; others see only the public fields', async () => {
  await seedPlaying();
  await assertSucceeds(room(HOST).once('value'));
  await assertSucceeds(room(GUEST).once('value'));
  await assertFails(room(STRANGER).once('value'));
  await assertFails(room(STRANGER, '/players').once('value'));
  await assertFails(room(STRANGER, '/game').once('value'));
  for (const field of ['status', 'hostUid', 'guestUid', 'expiresAt', 'schemaVersion']) {
    await assertSucceeds(room(STRANGER, `/${field}`).once('value'));
  }
  await assertFails(db(STRANGER).ref('rooms').once('value')); // no listing
});

// ---------- Joining ----------

test('a guest can claim the seat once, with their own uid', async () => {
  await seed(newRoom());
  await assertFails(room(GUEST, '/guestUid').set(STRANGER));
  await assertFails(room(HOST, '/guestUid').set(HOST)); // host cannot join own room
  await assertSucceeds(room(GUEST, '/guestUid').set(GUEST));
  await assertFails(room(STRANGER, '/guestUid').set(STRANGER)); // room is full
  await assertFails(room(GUEST, '/guestUid').set(GUEST)); // set only once
});

test('two people joining at once: only one transaction wins', async () => {
  await seed(newRoom());
  const claim = (uid) =>
    room(uid, '/guestUid')
      .transaction((current) => (current === null ? uid : undefined))
      .then((r) => r.committed, () => false);
  const [a, b] = await Promise.all([claim(GUEST), claim(STRANGER)]);
  if (a === b) throw new Error(`exactly one join should win, got ${a} and ${b}`);
});

test('cannot join an expired or cancelled room', async () => {
  const old = Date.now() - 2 * DAY;
  await seed({ ...newRoom(old) });
  await assertFails(room(GUEST, '/guestUid').set(GUEST));
  await seed({ ...newRoom(), status: 'abandoned' });
  await assertFails(room(GUEST, '/guestUid').set(GUEST));
});

test('guest finishes joining: player entry, status and game in one update', async () => {
  await seed(newRoom());
  await assertSucceeds(room(GUEST, '/guestUid').set(GUEST));
  const now = Date.now();
  const update = {
    [`players/${GUEST}`]: { color: 'yellow', name: 'Bo', connected: true, lastSeen: now },
    status: 'playing',
    game: initialGame(now),
  };
  await assertFails(room(GUEST).update({ ...update, game: { ...initialGame(now), turnUid: GUEST } })); // host must start
  await assertFails(room(GUEST).update({ ...update, [`players/${GUEST}`]: { ...update[`players/${GUEST}`], color: 'red' } }));
  await assertFails(room(GUEST).update({ status: 'playing' })); // no game yet
  await assertFails(room(STRANGER).update(update));
  await assertSucceeds(room(GUEST).update(update));
});

// ---------- Players and presence ----------

test('players write only their own entry', async () => {
  await seedPlaying();
  await assertSucceeds(room(GUEST, `/players/${GUEST}`).update({ connected: false, lastSeen: Date.now() }));
  await assertSucceeds(room(HOST, `/players/${HOST}`).onDisconnect().update({ connected: false, lastSeen: Date.now() }));
  await assertFails(room(GUEST, `/players/${HOST}`).update({ connected: false }));
  await assertFails(room(STRANGER, `/players/${STRANGER}`).set({ color: 'yellow', name: 'Eve', connected: true, lastSeen: 1 }));
  await assertFails(room(GUEST, `/players/${GUEST}`).update({ name: 'X' })); // too short
  await assertFails(room(GUEST, `/players/${GUEST}`).update({ name: 'A name that is far too long' }));
  await assertFails(room(GUEST, `/players/${GUEST}`).update({ connected: 'yes' }));
});

// ---------- Game actions ----------

function afterRoll(dice, overrides = {}) {
  return {
    ...initialGame(),
    version: 2,
    phase: 'MOVE',
    dice,
    sixesInRow: dice === 6 ? 1 : 0,
    lastAction: { type: 'roll', byUid: HOST, dice, auto: false },
    ...overrides,
  };
}

test('the current player can write the next version', async () => {
  await seedPlaying();
  await assertSucceeds(room(HOST, '/game').set(afterRoll(6)));
});

test('the game is written through a transaction', async () => {
  await seedPlaying();
  // Like the app: when the local cache is empty (null) return it unchanged so the server sends the real value.
  const r = await room(HOST, '/game').transaction((g) => (g === null ? g : g.version === 1 ? afterRoll(6) : undefined));
  if (!r.committed) throw new Error('transaction should commit');
});

test('version must go up by exactly one', async () => {
  await seedPlaying();
  await assertFails(room(HOST, '/game').set(afterRoll(6, { version: 3 })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { version: 1 })));
});

test('only the player whose turn it is may act', async () => {
  await seedPlaying();
  await assertFails(room(GUEST, '/game').set(afterRoll(6, { lastAction: { type: 'roll', byUid: GUEST, dice: 6, auto: false } })));
  await assertFails(room(STRANGER, '/game').set(afterRoll(6)));
  // The actor must record themselves as the actor.
  await assertFails(room(HOST, '/game').set(afterRoll(6, { lastAction: { type: 'roll', byUid: GUEST, dice: 6, auto: false } })));
});

test('dice and token values are validated', async () => {
  await seedPlaying();
  await assertFails(room(HOST, '/game').set(afterRoll(7)));
  await assertFails(room(HOST, '/game').set(afterRoll(0)));
  await assertFails(room(HOST, '/game').set(afterRoll(2.5)));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { tokens: { red: [57, -1, -1, -1], yellow: [-1, -1, -1, -1] } })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { tokens: { red: [-2, -1, -1, -1], yellow: [-1, -1, -1, -1] } })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { tokens: { red: [-1, -1, -1], yellow: [-1, -1, -1, -1] } })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { tokens: { red: [-1, -1, -1, -1, -1], yellow: [-1, -1, -1, -1] } })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { phase: 'DANCE' })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { turnUid: STRANGER })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { turnDeadline: Date.now() + 10 * 60 * 1000 })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { cheat: true })));
  await assertSucceeds(room(HOST, '/game').set(afterRoll(6)));
});

test('the game cannot be deleted', async () => {
  await seedPlaying();
  await assertFails(room(HOST, '/game').remove());
});

// ---------- Timeouts ----------

function timeoutBy(extra = {}) {
  return {
    ...initialGame(),
    version: 2,
    turnUid: GUEST,
    missedTurns: { [HOST]: 1, [GUEST]: 0 },
    lastAction: { type: 'timeout', byUid: HOST, auto: false },
    ...extra,
  };
}

test('the other player may write a Timeout only 5 seconds after the deadline', async () => {
  await seedPlaying({ turnDeadline: Date.now() - 2000 });
  await assertFails(room(GUEST, '/game').set(timeoutBy()));
  await seedPlaying({ turnDeadline: Date.now() - 6000 });
  await assertSucceeds(room(GUEST, '/game').set(timeoutBy()));
});

test('a Timeout must add one missed turn and must not move tokens', async () => {
  await seedPlaying({ turnDeadline: Date.now() - 60000 });
  await assertFails(room(GUEST, '/game').set(timeoutBy({ missedTurns: { [HOST]: 0, [GUEST]: 0 } })));
  await assertFails(room(GUEST, '/game').set(timeoutBy({ missedTurns: { [HOST]: 3, [GUEST]: 0 } })));
  await assertFails(room(GUEST, '/game').set(timeoutBy({ tokens: { red: [-1, -1, -1, -1], yellow: [0, -1, -1, -1] } })));
  await assertFails(room(GUEST, '/game').set(timeoutBy({ lastAction: { type: 'timeout', byUid: GUEST, auto: false } })));
  // A normal action by the waiting player is still not allowed after the deadline.
  await assertFails(room(GUEST, '/game').set(afterRoll(6, { lastAction: { type: 'roll', byUid: GUEST, dice: 6, auto: false } })));
});

test('three missed turns: the Timeout that forfeits is allowed', async () => {
  await seedPlaying({ turnDeadline: Date.now() - 60000, missedTurns: { [HOST]: 2, [GUEST]: 0 } });
  await assertSucceeds(
    room(GUEST, '/game').set(
      timeoutBy({
        missedTurns: { [HOST]: 3, [GUEST]: 0 },
        turnUid: HOST,
        phase: 'OVER',
        turnDeadline: 0,
        winnerUid: GUEST,
        winReason: 'forfeit',
      }),
    ),
  );
});

// ---------- Leaving ----------

function leftBy(uid, winner) {
  return {
    ...initialGame(),
    version: 2,
    phase: 'OVER',
    turnDeadline: 0,
    lastAction: { type: 'left', byUid: uid, auto: false },
    winnerUid: winner,
    winReason: 'left',
  };
}

test('either player can forfeit by leaving, and must be the loser', async () => {
  await seedPlaying();
  await assertFails(room(GUEST, '/game').set(leftBy(GUEST, GUEST)));
  await assertFails(room(GUEST, '/game').set(leftBy(HOST, HOST)));
  await assertSucceeds(room(GUEST, '/game').set(leftBy(GUEST, HOST))); // not the guest's turn, still allowed
});

test('no action is allowed once the game is over, except a rematch', async () => {
  await seedPlaying({ phase: 'OVER', turnDeadline: 0, winnerUid: HOST, winReason: 'all_home', version: 5 });
  await assertFails(room(HOST, '/game').set(afterRoll(3, { version: 6 })));
  await assertFails(room(GUEST, '/game').set({ ...leftBy(GUEST, HOST), version: 6 }));
});

// ---------- Rematch ----------

async function seedOver() {
  await seedPlaying({ phase: 'OVER', turnDeadline: 0, winnerUid: HOST, winReason: 'all_home', version: 9 });
  await seed('finished', '/status');
}

function rematchGame() {
  return { ...initialGame(), version: 10, gameNumber: 2, turnUid: GUEST };
}

test('players vote for a rematch of the finished game only', async () => {
  await seedPlaying();
  await assertFails(room(HOST, `/rematch/${HOST}`).set(1)); // game still running
  await seedOver();
  await assertFails(room(HOST, `/rematch/${GUEST}`).set(1)); // not their vote
  await assertFails(room(HOST, `/rematch/${HOST}`).set(2)); // wrong game number
  await assertFails(room(HOST, `/rematch/${HOST}`).set(true));
  await assertFails(room(STRANGER, `/rematch/${STRANGER}`).set(1));
  await assertSucceeds(room(HOST, `/rematch/${HOST}`).set(1));
});

test('a rematch starts only after both votes, with the loser first', async () => {
  await seedOver();
  await assertFails(room(HOST, '/game').set(rematchGame()));
  await assertSucceeds(room(HOST, `/rematch/${HOST}`).set(1));
  await assertFails(room(HOST, '/game').set(rematchGame()));
  await assertSucceeds(room(GUEST, `/rematch/${GUEST}`).set(1));
  await assertFails(room(HOST, '/game').set({ ...rematchGame(), turnUid: HOST })); // winner may not start
  await assertFails(room(HOST, '/game').set({ ...rematchGame(), gameNumber: 3 }));
  await assertFails(room(HOST, '/game').set({ ...rematchGame(), tokens: { red: [0, -1, -1, -1], yellow: [-1, -1, -1, -1] } }));
  await assertSucceeds(room(HOST, '/game').set(rematchGame()));
  await assertSucceeds(room(HOST).update({ rematch: null, status: 'playing' }));
});

// ---------- Room status ----------

test('host can cancel a waiting room; the guest then cannot join', async () => {
  await seed(newRoom());
  await assertFails(room(STRANGER, '/status').set('abandoned'));
  const r = await room(HOST, '/status').transaction((s) => (s === null ? s : s === 'waiting' ? 'abandoned' : undefined));
  if (!r.committed || r.snapshot.val() !== 'abandoned') throw new Error('cancel should commit');
  await assertFails(room(GUEST, '/guestUid').set(GUEST));
});

test('status changes follow the allowed transitions', async () => {
  await seedPlaying();
  await assertFails(room(HOST, '/status').set('waiting'));
  await assertFails(room(STRANGER, '/status').set('abandoned'));
  await assertFails(room(HOST, '/status').set('closed'));
  await assertSucceeds(room(HOST, '/status').set('finished'));
  await assertSucceeds(room(GUEST, '/status').set('abandoned'));
  await assertSucceeds(room(HOST, '/status').set('abandoned'));
  await assertFails(room(HOST, '/status').set('playing'));
});

// ---------- Lucky Boost ----------

test('the Lucky Boost setting cannot be changed after the room is created', async () => {
  await seedPlaying();
  await assertFails(room(HOST, '/luckyBoost').set(false));
  await assertFails(room(GUEST, '/luckyBoost').set(false));
});

test('noSixStreak is required and must be a whole number, 0 or more', async () => {
  await seedPlaying();
  const withoutStreak = afterRoll(6);
  delete withoutStreak.noSixStreak;
  await assertFails(room(HOST, '/game').set(withoutStreak));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { noSixStreak: { [HOST]: -1, [GUEST]: 0 } })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { noSixStreak: { [HOST]: 1.5, [GUEST]: 0 } })));
  await assertFails(room(HOST, '/game').set(afterRoll(6, { noSixStreak: { [HOST]: 0, [GUEST]: 0, [STRANGER]: 2 } })));
  await assertSucceeds(room(HOST, '/game').set(afterRoll(6, { noSixStreak: { [HOST]: 4, [GUEST]: 0 } })));
});
