package com.example.ludoduel

import android.content.Context
import com.example.ludoduel.data.AuthRepository
import com.example.ludoduel.data.ConnectionMonitor
import com.example.ludoduel.data.PresenceTracker
import com.example.ludoduel.data.RoomRepository
import com.example.ludoduel.data.ServerClock
import com.example.ludoduel.data.SettingsStore
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency container. Firebase objects are created lazily, after the splash screen checks setup. */
class AppContainer(context: Context) {
    /** Lives as long as the process. Used for writes that must finish after a screen closes (leaving a room). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsStore(context)

    /** False when the app was built without app/google-services.json. */
    val firebaseConfigured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /** Set only in debug builds made with -PfirebaseEmulatorHost=... (see README). */
    private val emulatorHost = BuildConfig.FIREBASE_EMULATOR_HOST

    /** Throws if google-services.json has no Realtime Database URL (see README). */
    val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance().also { if (emulatorHost.isNotEmpty()) it.useEmulator(emulatorHost, 9000) }
    }
    val auth by lazy {
        AuthRepository(
            FirebaseAuth.getInstance().also { if (emulatorHost.isNotEmpty()) it.useEmulator(emulatorHost, 9099) }
        )
    }
    val clock by lazy { ServerClock(database, appScope) }
    val connection by lazy { ConnectionMonitor(database, appScope) }
    val rooms by lazy { RoomRepository(database, clock) }
    val presence by lazy { PresenceTracker(rooms, connection.connected, appScope) }
}
