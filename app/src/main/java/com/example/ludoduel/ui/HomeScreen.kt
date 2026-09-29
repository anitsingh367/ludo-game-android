package com.example.ludoduel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.ludoduel.AppContainer
import com.example.ludoduel.R
import com.example.ludoduel.data.SettingsStore
import com.example.ludoduel.ui.components.BlueGloss
import com.example.ludoduel.ui.components.BouncingDice
import com.example.ludoduel.ui.components.GameTitle
import com.example.ludoduel.ui.components.GlassCard
import com.example.ludoduel.ui.components.Glyph
import com.example.ludoduel.ui.components.GlyphButton
import com.example.ludoduel.ui.components.GlossyButton
import com.example.ludoduel.ui.components.GreenGloss
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
    /** The Lucky Boost setting for the room this player creates (on by default). */
    val luckyBoost: Boolean = true,
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
            val code = runCatching { c.rooms.createRoom(c.auth.signIn(), name, ui.luckyBoost) }.getOrNull()
            if (code != null) {
                c.settings.setActiveRoom(code)
                _events.send(HomeEvent.RoomCreated(code))
            }
            _ui.update { it.copy(busy = false, createFailed = code == null) }
        }
    }

    fun setLuckyBoost(on: Boolean) {
        _ui.update { it.copy(luckyBoost = on) }
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

    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            GameTitle(stringResource(R.string.app_name))
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
            BouncingDice(Modifier.padding(vertical = 4.dp))

            ui.rejoinCode?.let { code ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_rejoin_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(stringResource(R.string.home_rejoin_body, code), color = Color.White.copy(alpha = 0.9f))
                    GlossyButton(stringResource(R.string.home_rejoin), { onRejoin(code) }, Modifier.fillMaxWidth(), colors = BlueGloss, height = 52.dp)
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = ui.name,
                    onValueChange = vm::onNameChange,
                    label = { Text(stringResource(R.string.home_name_label)) },
                    singleLine = true,
                    isError = !ui.nameValid,
                    supportingText = { if (!ui.nameValid) Text(stringResource(R.string.home_name_error)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFFD54F),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.6f),
                        focusedLabelColor = Color(0xFFFFD54F),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.8f),
                        cursorColor = Color(0xFFFFD54F),
                        errorTextColor = Color.White,
                        errorSupportingTextColor = Color(0xFFFFCDD2),
                        errorBorderColor = Color(0xFFFF8A80),
                        errorLabelColor = Color(0xFFFF8A80),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Room setting for the room this player creates.
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = ui.luckyBoost, role = Role.Switch, onValueChange = vm::setLuckyBoost)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_lucky_boost), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(stringResource(R.string.home_lucky_boost_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                }
                Switch(checked = ui.luckyBoost, onCheckedChange = null)
            }
            GlossyButton(
                text = stringResource(R.string.home_create),
                onClick = vm::createRoom,
                enabled = ui.nameValid && !ui.busy,
                modifier = Modifier.fillMaxWidth(),
                content = if (ui.busy) {
                    { CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 3.dp) }
                } else {
                    null
                },
            )
            GlossyButton(
                text = stringResource(R.string.home_join),
                onClick = vm::openJoin,
                enabled = ui.nameValid && !ui.busy,
                colors = GreenGloss,
                modifier = Modifier.fillMaxWidth(),
            )
            if (ui.createFailed) {
                Text(stringResource(R.string.home_create_failed), color = Color(0xFFFFCDD2), textAlign = TextAlign.Center)
            }
        }
        // The settings gear sits on top of the scrolling content.
        GlyphButton(
            Glyph.SETTINGS,
            stringResource(R.string.settings_open),
            { showSettings = true },
            Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
        )
    }
}
