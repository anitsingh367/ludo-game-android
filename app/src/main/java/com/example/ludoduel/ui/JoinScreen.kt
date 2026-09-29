package com.example.ludoduel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.ludoduel.AppContainer
import com.example.ludoduel.R
import com.example.ludoduel.data.JoinOutcome
import com.example.ludoduel.data.RoomCodes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JoinUi(val code: String = "", val error: Int? = null, val busy: Boolean = false)

/**
 * Cleans the code while it is typed: uppercase, only allowed characters, at most 6. Done inside
 * the text field's editing buffer so the keyboard stays in sync (changing the text afterwards in
 * onValueChange made keyboards drop characters when typing lowercase).
 */
private object RoomCodeInput : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val typed = asCharSequence().toString()
        val cleaned = RoomCodes.clean(typed)
        if (cleaned != typed) replace(0, length, cleaned)
    }
}

class JoinViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(JoinUi())
    val ui: StateFlow<JoinUi> = _ui
    private val _opened = Channel<JoinOutcome>(Channel.BUFFERED)
    /** Emits [JoinOutcome.OpenGame] or [JoinOutcome.OpenWaiting]. */
    val opened = _opened.receiveAsFlow()

    /** The code text field. Already cleaned by [RoomCodeInput]. */
    val codeField = TextFieldState()

    init {
        viewModelScope.launch {
            snapshotFlow { codeField.text.toString() }.collect { code ->
                _ui.update { it.copy(code = code, error = null) }
            }
        }
    }

    fun join() {
        val code = _ui.value.code
        if (_ui.value.busy) return
        // Validate locally before contacting Firebase.
        if (!RoomCodes.isComplete(code)) {
            _ui.update { it.copy(error = R.string.join_error_incomplete) }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching { c.rooms.joinRoom(code, c.auth.signIn(), c.settings.name.first()) }
                .getOrDefault(JoinOutcome.Failed)
            val error = when (outcome) {
                is JoinOutcome.OpenGame, is JoinOutcome.OpenWaiting -> {
                    c.settings.setActiveRoom(code)
                    _opened.send(outcome)
                    null
                }
                JoinOutcome.NotFound -> R.string.join_error_not_found
                JoinOutcome.Expired -> R.string.join_error_expired
                JoinOutcome.Full -> R.string.join_error_full
                JoinOutcome.Closed -> R.string.join_error_closed
                JoinOutcome.UpdateRequired -> R.string.join_error_update
                JoinOutcome.Failed -> R.string.join_error_failed
            }
            _ui.update { it.copy(busy = false, error = error) }
        }
    }
}

@Composable
fun JoinScreen(onOpenGame: (String) -> Unit, onOpenWaiting: (String) -> Unit, onBack: () -> Unit) {
    val vm = containerViewModel { c, _ -> JoinViewModel(c) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.opened.collect {
            when (it) {
                is JoinOutcome.OpenGame -> onOpenGame(it.code)
                is JoinOutcome.OpenWaiting -> onOpenWaiting(it.code)
                else -> Unit
            }
        }
    }
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.join_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.join_hint))
            val fieldLabel = stringResource(R.string.join_code_field)
            BasicTextField(
                state = vm.codeField,
                inputTransformation = RoomCodeInput,
                lineLimits = TextFieldLineLimits.SingleLine,
                enabled = !ui.busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Go,
                ),
                onKeyboardAction = { vm.join() },
                modifier = Modifier.focusRequester(focus).semantics { contentDescription = fieldLabel },
                decorator = { innerTextField ->
                    Box {
                        CodeBoxes(ui.code, hasError = ui.error != null)
                        // The real text field is invisible; the boxes above show the code.
                        Box(Modifier.matchParentSize().alpha(0f)) { innerTextField() }
                    }
                },
            )
            ui.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = vm::join,
                enabled = !ui.busy && ui.code.length == RoomCodes.LENGTH,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (ui.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.join_button))
                }
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
    }
}

@Composable
private fun CodeBoxes(code: String, hasError: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(RoomCodes.LENGTH) { i ->
            val active = i == code.length
            val borderColor = when {
                hasError -> MaterialTheme.colorScheme.error
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
            Box(
                Modifier
                    .size(width = 44.dp, height = 56.dp)
                    .border(if (active) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    code.getOrNull(i)?.toString() ?: "",
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
