package com.example.ludoduel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.ludoduel.AppContainer
import com.example.ludoduel.R
import com.example.ludoduel.data.SettingsStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUi(
    val name: String = "",
    val nameValid: Boolean = true,
    val rejoinCode: String? = null,
    val busy: Boolean = false,
    val createFailed: Boolean = false,
)

sealed interface HomeEvent {
    data class RoomCreated(val code: String) : HomeEvent
    data object OpenJoin : HomeEvent
}

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUi())
    val ui: StateFlow<HomeUi> = _ui
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val name = c.settings.name.first()
            _ui.update { it.copy(name = name, nameValid = isValidName(name)) }
        }
    }

    /**
     * Offers "Rejoin game" while the saved room is still being played and we sit in it.
     * Called only while the home screen is shown (this ViewModel stays alive underneath the
     * game screen, and must not clear the saved room while a game or waiting room is open).
     * Leaving a game clears the saved room, which hides the offer again.
     */
    suspend fun watchRejoin() {
        c.settings.activeRoom.collectLatest { saved ->
            _ui.update { it.copy(rejoinCode = null) }
            if (saved == null) return@collectLatest
            runCatching { c.rooms.canRejoin(saved, c.auth.signIn()) }
                .onSuccess { ok -> if (ok) _ui.update { it.copy(rejoinCode = saved) } else c.settings.setActiveRoom(null) }
        }
    }

    fun onNameChange(name: String) {
        val trimmed = name.take(SettingsStore.NAME_MAX)
        _ui.update { it.copy(name = trimmed, nameValid = isValidName(trimmed)) }
    }

    fun createRoom() {
        val ui = _ui.value
        if (!ui.nameValid || ui.busy) return
        _ui.update { it.copy(busy = true, createFailed = false) }
        viewModelScope.launch {
            val name = ui.name.trim()
            c.settings.setName(name)
            val code = runCatching { c.rooms.createRoom(c.auth.signIn(), name) }.getOrNull()
            if (code != null) {
                c.settings.setActiveRoom(code)
                _events.send(HomeEvent.RoomCreated(code))
            }
            _ui.update { it.copy(busy = false, createFailed = code == null) }
        }
    }

    fun openJoin() {
        val ui = _ui.value
        if (!ui.nameValid) return
        viewModelScope.launch {
            c.settings.setName(ui.name.trim())
            _events.send(HomeEvent.OpenJoin)
        }
    }

    private fun isValidName(name: String) = name.trim().length in SettingsStore.NAME_MIN..SettingsStore.NAME_MAX
}

@Composable
fun HomeScreen(onRoomCreated: (String) -> Unit, onJoin: () -> Unit, onRejoin: (String) -> Unit) {
    val vm = containerViewModel { c, _ -> HomeViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.watchRejoin() }
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                is HomeEvent.RoomCreated -> onRoomCreated(it.code)
                HomeEvent.OpenJoin -> onJoin()
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color.White) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))

            ui.rejoinCode?.let { code ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.home_rejoin_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.home_rejoin_body, code))
                        Button(onClick = { onRejoin(code) }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.home_rejoin))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = ui.name,
                onValueChange = vm::onNameChange,
                label = { Text(stringResource(R.string.home_name_label)) },
                singleLine = true,
                isError = !ui.nameValid,
                supportingText = { if (!ui.nameValid) Text(stringResource(R.string.home_name_error)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = vm::createRoom,
                enabled = ui.nameValid && !ui.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (ui.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.home_create))
                }
            }
            OutlinedButton(
                onClick = vm::openJoin,
                enabled = ui.nameValid && !ui.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.home_join)) }
            if (ui.createFailed) {
                Text(stringResource(R.string.home_create_failed), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
