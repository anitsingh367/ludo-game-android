package com.example.ludoduel.data

import com.example.ludoduel.engine.Action
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.WinReason
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface JoinOutcome {
    /** The code belongs to the user's own room that is still waiting for a guest. */
    data class OpenWaiting(val code: String) : JoinOutcome
    data class OpenGame(val code: String) : JoinOutcome
    data object NotFound : JoinOutcome
    data object Expired : JoinOutcome
    data object Full : JoinOutcome
    data object Closed : JoinOutcome
    data object UpdateRequired : JoinOutcome
    data object Failed : JoinOutcome
}

sealed interface RoomEvent {
    data class Loaded(val room: Room) : RoomEvent
    data object Missing : RoomEvent
    data object Denied : RoomEvent
}

/** The few room fields any signed-in user may read, so a guest can check a code before joining. */
private data class PublicRoom(
    val schemaVersion: Long,
    val hostUid: String,
    val guestUid: String?,
    val status: String?,
    val expiresAt: Long?,
)

class RoomRepository(private val db: FirebaseDatabase, private val clock: ServerClock) {

    private fun roomRef(code: String) = db.getReference("rooms").child(code)

    private fun player(color: String, name: String) = mapOf(
        "color" to color,
        "name" to name,
        "connected" to true,
        "lastSeen" to ServerValue.TIMESTAMP,
    )

    /** Creates a room with a fresh code. Returns the code, or null after 5 failed attempts. */
    suspend fun createRoom(uid: String, name: String): String? {
        repeat(CREATE_ATTEMPTS) {
            val code = RoomCodes.generate()
            val now = clock.now()
            // The transaction aborts if the code already exists. If it exists and belongs to
            // someone else we cannot even read it, which also fails; both mean "try another code".
            val result = withTimeout(NETWORK_TIMEOUT_MILLIS) {
                roomRef(code).transact { data ->
                    if (data.value != null) return@transact false
                    data.value = mapOf(
                        "schemaVersion" to SCHEMA_VERSION,
                        "createdAt" to now,
                        "expiresAt" to now + ROOM_LIFETIME_MILLIS,
                        "hostUid" to uid,
                        "status" to "waiting",
                        "players" to mapOf(uid to player("red", name)),
                    )
                    true
                }
            }
            if (result is TxResult.Committed) return code
        }
        return null
    }

    suspend fun joinRoom(code: String, uid: String, name: String): JoinOutcome {
        val info = readPublic(code) ?: return JoinOutcome.NotFound
        if (info.schemaVersion != SCHEMA_VERSION) return JoinOutcome.UpdateRequired
        val status = info.status
        if (info.hostUid == uid) {
            return when (status) {
                "waiting" -> JoinOutcome.OpenWaiting(code)
                "abandoned" -> JoinOutcome.Closed
                else -> JoinOutcome.OpenGame(code)
            }
        }
        if (info.guestUid == uid) {
            return when (status) {
                "abandoned" -> JoinOutcome.Closed
                // An earlier join claimed the seat but did not finish (app killed); finish it now.
                "waiting" -> completeJoin(code, info.hostUid, uid, name)
                else -> JoinOutcome.OpenGame(code)
            }
        }
        if (status == "abandoned") return JoinOutcome.Closed
        if (info.guestUid != null) return JoinOutcome.Full
        val expiresAt = info.expiresAt ?: return JoinOutcome.Failed
        if (clock.now() >= expiresAt) return JoinOutcome.Expired
        if (status != "waiting") return JoinOutcome.Closed

        // Claim the guest seat in a transaction so two people entering the code at once
        // cannot both get in. The security rules also allow setting guestUid only once.
        val claim = withTimeout(NETWORK_TIMEOUT_MILLIS) {
            roomRef(code).child("guestUid").transact { data ->
                if (data.value != null) return@transact false
                data.value = uid
                true
            }
        }
        if (claim is TxResult.Committed) return completeJoin(code, info.hostUid, uid, name)

        // Lost the race, or the rules refused (room closed or expired meanwhile). Find out which.
        val now = readPublic(code) ?: return JoinOutcome.NotFound
        return when {
            now.guestUid == uid -> completeJoin(code, now.hostUid, uid, name)
            now.status == "abandoned" -> JoinOutcome.Closed
            now.guestUid != null -> JoinOutcome.Full
            now.expiresAt != null && clock.now() >= now.expiresAt -> JoinOutcome.Expired
            else -> JoinOutcome.Failed
        }
    }

    /** Writes the guest's player entry, starts the game and marks the room as playing, in one update. */
    private suspend fun completeJoin(code: String, hostUid: String, uid: String, name: String): JoinOutcome {
        val game = GameReducer.initial(clock.now())
        val update = mapOf(
            "players/$uid" to player("yellow", name),
            "status" to "playing",
            "game" to GameCodec.encode(game, Seats(hostUid, uid)),
        )
        return try {
            withTimeout(NETWORK_TIMEOUT_MILLIS) { roomRef(code).updateChildren(update).await() }
            JoinOutcome.OpenGame(code)
        } catch (e: Exception) {
            // Most likely the host cancelled the room between our claim and this write.
            if (readPublic(code)?.status == "abandoned") JoinOutcome.Closed else JoinOutcome.Failed
        }
    }

    private suspend fun readPublic(code: String): PublicRoom? = withTimeout(NETWORK_TIMEOUT_MILLIS) {
        coroutineScope {
            val ref = roomRef(code)
            val fields = listOf("schemaVersion", "hostUid", "guestUid", "status", "expiresAt")
                .map { name -> async { ref.child(name).get().await().value } }
                .map { it.await() }
            val hostUid = fields[1] as? String ?: return@coroutineScope null
            PublicRoom(
                // A missing version is treated as "unknown", which shows the "please update" message.
                schemaVersion = (fields[0] as? Number)?.toLong() ?: 0L,
                hostUid = hostUid,
                guestUid = fields[2] as? String,
                status = fields[3] as? String,
                expiresAt = (fields[4] as? Number)?.toLong(),
            )
        }
    }

    /** True when the saved room is still being played and this user sits in it. */
    suspend fun canRejoin(code: String, uid: String): Boolean {
        val info = readPublic(code) ?: return false
        return info.status == "playing" && (info.hostUid == uid || info.guestUid == uid)
    }

    /** Host cancels a room that is still waiting. Returns false if a guest already joined. */
    suspend fun cancelRoom(code: String): Boolean {
        val result = roomRef(code).child("status").transact { data ->
            if (data.value != "waiting") return@transact false
            data.value = "abandoned"
            true
        }
        return result is TxResult.Committed
    }

    fun observeRoom(code: String): Flow<RoomEvent> = callbackFlow {
        val ref = roomRef(code)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend(Room.decode(code, snapshot.value)?.let { RoomEvent.Loaded(it) } ?: RoomEvent.Missing)
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(RoomEvent.Denied)
                close()
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /**
     * Applies one game action inside a transaction on `rooms/{code}/game`. The transaction checks
     * the version and turn, runs the engine and writes the result with version + 1. If anything
     * does not match it aborts and nothing is written. Returns true when the action was written.
     *
     * Runs to the end even if the caller is cancelled: the new game state usually reaches the
     * room listener before the transaction reports back, and that restarts the timers that may
     * have called this. The follow-up status write must still happen.
     */
    suspend fun submit(
        code: String,
        seats: Seats,
        expectedVersion: Long,
        action: Action,
        uid: String,
    ): Boolean = withContext(NonCancellable) {
        val result = roomRef(code).child("game").transact { data ->
            val current = GameCodec.decode(data.value, seats)
            val next = GameReducer.reduce(current, expectedVersion, action, uid, seats, clock.now())
                .getOrNull() ?: return@transact false
            data.value = GameCodec.encode(next, seats)
            true
        }
        if (result !is TxResult.Committed) return@withContext false
        val written = GameCodec.decode(result.snapshot?.value, seats)
        if (written?.state?.phase == Phase.OVER && written.state.winReason != WinReason.LEFT) {
            roomRef(code).child("status").setValue("finished")
        }
        true
    }

    /**
     * Leaves the room. If the game is still running this player forfeits (reason "left"),
     * whatever the current version is. Then the room is marked abandoned so the other player sees it.
     */
    suspend fun leave(code: String, seats: Seats?, uid: String) {
        if (seats != null) {
            roomRef(code).child("game").transact { data ->
                val current = GameCodec.decode(data.value, seats) ?: return@transact false
                if (current.state.phase == Phase.OVER) return@transact false
                val next = GameReducer.reduce(current, current.version, Action.Forfeit, uid, seats, clock.now())
                    .getOrNull() ?: return@transact false
                data.value = GameCodec.encode(next, seats)
                true
            }
        }
        roomRef(code).child("status").setValue("abandoned").await()
    }

    /** Records that this player wants a rematch of game number [gameNumber]. */
    fun voteRematch(code: String, uid: String, gameNumber: Long) {
        roomRef(code).child("rematch").child(uid).setValue(gameNumber)
    }

    /**
     * Starts the next game once both players voted. Both phones may call this; only one write wins.
     * Like [submit], it finishes even if the caller is cancelled, so the votes are always cleared.
     */
    suspend fun startRematch(code: String, seats: Seats, gameNumber: Long): Unit = withContext(NonCancellable) {
        val result = roomRef(code).child("game").transact { data ->
            val next = GameReducer.rematch(GameCodec.decode(data.value, seats), gameNumber, clock.now())
                .getOrNull() ?: return@transact false
            data.value = GameCodec.encode(next, seats)
            true
        }
        if (result is TxResult.Committed) {
            roomRef(code).updateChildren(mapOf("rematch" to null, "status" to "playing"))
        }
    }

    /**
     * Keeps `players/{uid}/connected` up to date while the caller is collecting. Every time the
     * connection comes back, onDisconnect is registered again and connected is set to true.
     * Runs until cancelled; then it marks the player as disconnected.
     */
    suspend fun runPresence(code: String, uid: String, connected: StateFlow<Boolean>) {
        val ref = roomRef(code).child("players").child(uid)
        val offline = mapOf("connected" to false, "lastSeen" to ServerValue.TIMESTAMP)
        try {
            connected.collect { online ->
                if (online) {
                    ref.onDisconnect().updateChildren(offline)
                    ref.updateChildren(mapOf("connected" to true, "lastSeen" to ServerValue.TIMESTAMP))
                }
            }
        } finally {
            ref.onDisconnect().cancel()
            ref.updateChildren(offline)
        }
    }

    private companion object {
        const val CREATE_ATTEMPTS = 5
        const val NETWORK_TIMEOUT_MILLIS = 15_000L
    }
}
