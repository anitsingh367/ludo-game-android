package com.example.ludoduel.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface TxResult {
    data class Committed(val snapshot: DataSnapshot?) : TxResult
    /** Our update function declined to write (for example a stale version). Nothing was written. */
    data object Aborted : TxResult
    data class Failed(val error: DatabaseError) : TxResult
}

/**
 * Runs a Realtime Database transaction. [update] reads the current value, sets the new value on
 * the [MutableData] and returns true, or returns false to abort.
 *
 * The first run uses the local cache, which is null when this path is not cached (for example
 * right after a screen stopped listening). Aborting then would never ask the server. So when the
 * value is null and [update] declines, the unchanged null is sent instead: the server either
 * answers with the real value (and [update] runs again) or rejects the null write through the
 * security rules. Either way nothing wrong is written.
 *
 * Local events are not fired, so listeners only ever see server-confirmed data
 * (the UI never renders a local guess).
 */
suspend fun DatabaseReference.transact(update: (MutableData) -> Boolean): TxResult =
    suspendCancellableCoroutine { cont ->
        runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result = when {
                update(currentData) -> Transaction.success(currentData)
                currentData.value == null -> Transaction.success(currentData)
                else -> Transaction.abort()
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                cont.resume(
                    when {
                        error != null -> TxResult.Failed(error)
                        committed -> TxResult.Committed(currentData)
                        else -> TxResult.Aborted
                    }
                )
            }
        }, false)
    }
