package com.example.ludoduel.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.ludoduel.AppContainer
import com.example.ludoduel.R
import com.example.ludoduel.data.RoomEvent
import com.example.ludoduel.data.RoomStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class WaitingState { WAITING, STARTED, CLOSED }

class WaitingViewModel(private val c: AppContainer, saved: SavedStateHandle) : ViewModel() {
    val code: String = checkNotNull(saved["code"])
    private val _state = MutableStateFlow(WaitingState.WAITING)
    val state: StateFlow<WaitingState> = _state
    private var presenceAcquired = false

    init {
        viewModelScope.launch {
            val uid = c.auth.signIn()
            c.presence.acquire(code, uid)
            presenceAcquired = true
            c.rooms.observeRoom(code).collect { event ->
                _state.value = when (event) {
                    is RoomEvent.Loaded -> when (event.room.status) {
                        RoomStatus.WAITING -> WaitingState.WAITING
                        RoomStatus.PLAYING, RoomStatus.FINISHED -> WaitingState.STARTED
                        RoomStatus.ABANDONED -> WaitingState.CLOSED
                    }
                    RoomEvent.Missing, RoomEvent.Denied -> WaitingState.CLOSED
                }
            }
        }
    }

    override fun onCleared() {
        if (presenceAcquired) c.presence.release(code)
    }

    /** Cancels the room. If a guest joined at the same moment, the room observer opens the game instead. */
    fun cancel() {
        viewModelScope.launch {
            if (c.rooms.cancelRoom(code)) {
                c.settings.setActiveRoom(null)
                _state.value = WaitingState.CLOSED
            }
        }
    }
}

@Composable
fun WaitingScreen(onGameStarted: (String) -> Unit, onClosed: () -> Unit) {
    val vm = containerViewModel { c, saved -> WaitingViewModel(c, saved) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        when (state) {
            WaitingState.WAITING -> Unit
            WaitingState.STARTED -> onGameStarted(vm.code)
            WaitingState.CLOSED -> onClosed()
        }
    }
    BackHandler(onBack = vm::cancel)

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.copied)
    val shareText = stringResource(R.string.share_message, vm.code)
    val chooserTitle = stringResource(R.string.share_chooser)

    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Color.White) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.waiting_title), style = MaterialTheme.typography.titleLarge)
            Text(
                vm.code,
                fontSize = 48.sp,
                letterSpacing = 6.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.waiting_hint))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Room code", vm.code)))
                        }
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.copy)) }
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(send, chooserTitle))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.share)) }
            }
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Text(stringResource(R.string.waiting_for_opponent))
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = vm::cancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}
