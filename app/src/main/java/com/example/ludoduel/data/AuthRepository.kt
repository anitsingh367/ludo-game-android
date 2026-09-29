package com.example.ludoduel.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/** Anonymous sign-in only. Firebase keeps the user on disk, so the same uid is reused on every launch. */
class AuthRepository(private val auth: FirebaseAuth) {

    /** Returns the current uid, signing in anonymously first if needed. Throws when offline. */
    suspend fun signIn(): String {
        auth.currentUser?.let { return it.uid }
        val result = auth.signInAnonymously().await()
        return checkNotNull(result.user) { "Anonymous sign-in returned no user" }.uid
    }
}
