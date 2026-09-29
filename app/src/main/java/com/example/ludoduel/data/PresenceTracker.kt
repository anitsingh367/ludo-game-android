package com.example.ludoduel.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps one presence session per room for the whole app. The waiting screen and the game screen
 * both acquire it; when the waiting screen hands over to the game screen the session keeps
 * running instead of being torn down (which would cancel the game screen's onDisconnect).
 * Call only from the main thread.
 */
class PresenceTracker(
    private val rooms: RoomRepository,
    private val connected: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private var code: String? = null
    private var users = 0
    private var job: Job? = null

    fun acquire(code: String, uid: String) {
        if (code != this.code) {
            job?.cancel()
            this.code = code
            users = 0
            job = scope.launch { rooms.runPresence(code, uid, connected) }
        }
        users++
    }

    fun release(code: String) {
        if (code != this.code) return
        users--
        if (users == 0) {
            job?.cancel()
            job = null
            this.code = null
        }
    }
}
