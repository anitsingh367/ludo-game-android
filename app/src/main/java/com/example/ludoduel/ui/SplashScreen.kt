package com.example.ludoduel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.ludoduel.AppContainer
import com.example.ludoduel.R
import com.example.ludoduel.ui.components.GameTitle
import com.google.firebase.database.DatabaseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class SplashState { LOADING, NOT_CONFIGURED, NO_DATABASE, OFFLINE, READY }

class SplashViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SplashState.LOADING)
    val state: StateFlow<SplashState> = _state

    init {
        start()
    }

    fun start() {
        if (!container.firebaseConfigured) {
            _state.value = SplashState.NOT_CONFIGURED
            return
        }
        try {
            container.database
        } catch (e: DatabaseException) {
            _state.value = SplashState.NO_DATABASE
            return
        }
        _state.value = SplashState.LOADING
        viewModelScope.launch {
            _state.value = try {
                withTimeout(20_000) { container.auth.signIn() }
                SplashState.READY
            } catch (e: Exception) {
                SplashState.OFFLINE
            }
        }
    }
}

@Composable
fun SplashScreen(onReady: () -> Unit) {
    val vm = containerViewModel { c, _ -> SplashViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == SplashState.READY) onReady() }

    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color.White) {
        Column(
            Modifier.padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GameTitle(stringResource(R.string.app_name))
            Spacer(Modifier.height(24.dp))
            val message = when (state) {
                SplashState.LOADING, SplashState.READY -> null
                SplashState.NOT_CONFIGURED -> R.string.splash_not_configured
                SplashState.NO_DATABASE -> R.string.splash_no_database
                SplashState.OFFLINE -> R.string.splash_offline
            }
            if (message == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.splash_signing_in))
            } else {
                Text(stringResource(message), textAlign = TextAlign.Center)
                if (state == SplashState.OFFLINE) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = vm::start) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}
