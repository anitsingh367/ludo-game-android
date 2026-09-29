package com.example.ludoduel.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** Emits the value at a `.info/...` path every time it changes. */
private fun <T> FirebaseDatabase.infoFlow(path: String, read: (DataSnapshot) -> T) = callbackFlow {
    val ref = getReference(path)
    val listener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            trySend(read(snapshot))
        }

        override fun onCancelled(error: DatabaseError) {
            close(error.toException())
        }
    }
    ref.addValueEventListener(listener)
    awaitClose { ref.removeEventListener(listener) }
}

/**
 * Estimated server time, from `.info/serverTimeOffset`. All timers (turn deadline, room expiry)
 * use this, never the phone clock alone.
 */
class ServerClock(db: FirebaseDatabase, scope: CoroutineScope) {
    private val offset: StateFlow<Long> =
        db.infoFlow(".info/serverTimeOffset") { (it.value as? Number)?.toLong() ?: 0L }
            .stateIn(scope, SharingStarted.Eagerly, 0L)

    fun now(): Long = System.currentTimeMillis() + offset.value
}

/** True while the app has a live connection to the Realtime Database (`.info/connected`). */
class ConnectionMonitor(db: FirebaseDatabase, scope: CoroutineScope) {
    val connected: StateFlow<Boolean> =
        db.infoFlow(".info/connected") { it.value == true }
            .stateIn(scope, SharingStarted.Eagerly, false)
}
