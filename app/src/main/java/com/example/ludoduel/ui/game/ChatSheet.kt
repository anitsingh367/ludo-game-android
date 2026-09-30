package com.example.ludoduel.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ludoduel.R
import com.example.ludoduel.data.ChatMessage
import com.example.ludoduel.data.ChatRules
import com.example.ludoduel.data.ChatType
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.components.Glyph
import com.example.ludoduel.ui.components.GlyphButton
import com.example.ludoduel.ui.theme.LocalLudoPalette
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The chat button for the top bar, with a red badge for unread messages. */
@Composable
fun ChatButton(unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.chat_open)
    val unreadLabel = stringResource(R.string.chat_unread, unread)
    Box(modifier) {
        GlyphButton(Glyph.CHAT, if (unread > 0) "$label, $unreadLabel" else label, onClick)
        if (unread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-2).dp)
                    .size(20.dp)
                    .background(Color(0xFFE53935), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (unread > 9) "9+" else "$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The chat, in a half-height sheet so the top half of the board stays visible: the messages (mine
 * on the right in my color, theirs on the left in theirs, with name and time), quick phrases, and a
 * text field (at most 100 characters). Links are plain text, never clickable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(
    chat: ChatUi,
    colorOf: (uid: String) -> PlayerColor,
    opponentName: String,
    onSendText: (String) -> Boolean,
    onPhrase: (String) -> Boolean,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val height = LocalConfiguration.current.screenHeightDp.dp * 0.5f
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmReport by remember { mutableStateOf(false) }
    val send = { if (onSendText(text)) text = "" }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Black.copy(alpha = 0.2f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(height)
                // The keyboard pushes the field up; the list above it gets shorter.
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_open), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Box {
                    val more = stringResource(R.string.chat_more)
                    IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = more }) {
                        Text("⋮", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_report)) },
                            onClick = {
                                menu = false
                                confirmReport = true
                            },
                        )
                    }
                }
            }
            if (chat.muteOpponent) {
                Text(stringResource(R.string.chat_muted_note, opponentName), style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (chat.messages.isEmpty()) {
                    Text(stringResource(R.string.chat_empty), Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
                }
                // Newest at the bottom, and the list starts there.
                LazyColumn(Modifier.fillMaxSize(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(chat.messages.asReversed(), key = { it.id }) { m ->
                        MessageRow(m, mine = m.uid == chat.myUid, name = chat.names[m.uid].orEmpty(), color = colorOf(m.uid))
                    }
                }
            }
            val phrases = stringArrayResource(R.array.chat_phrases).toList()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(phrases) { phrase ->
                    AssistChip(onClick = { onPhrase(phrase) }, label = { Text(phrase) }, enabled = chat.canSend)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= ChatRules.MAX_LENGTH) text = it },
                    placeholder = { Text(stringResource(R.string.chat_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = send,
                    enabled = chat.canSend && ChatRules.clean(text) != null,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.chat_send)) }
            }
        }
    }
    if (confirmReport) {
        AlertDialog(
            onDismissRequest = { confirmReport = false },
            title = { Text(stringResource(R.string.chat_report_title, opponentName)) },
            text = { Text(stringResource(R.string.chat_report_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReport = false
                    onReport()
                }) { Text(stringResource(R.string.chat_report_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmReport = false }) { Text(stringResource(R.string.stay)) } },
        )
    }
}

@Composable
private fun MessageRow(m: ChatMessage, mine: Boolean, name: String, color: PlayerColor) {
    val colors = LocalLudoPalette.current.of(color)
    val time = remember(m.sentAt) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.sentAt)) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text("$name · $time", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val body = when (m.type) {
            ChatType.EMOJI -> EmojiArt.text(checkNotNull(m.emojiId))
            ChatType.TEXT, ChatType.PHRASE -> checkNotNull(m.text)
        }
        Text(
            body,
            color = Color.White,
            fontSize = if (m.type == ChatType.EMOJI) 28.sp else 15.sp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(colors.main, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/**
 * The opponent's message as a small speech bubble just above their panel, for about 3 seconds.
 * Drawn in the overlay; it does not take touches.
 */
@Composable
fun SpeechBubble(bubble: ChatEvent.Bubble?, panel: Rect?, color: PlayerColor, onGone: () -> Unit) {
    if (bubble == null || panel == null) return
    LaunchedEffect(bubble.id) {
        delay(BUBBLE_MILLIS)
        onGone()
    }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    Text(
        bubble.text,
        color = Color.White,
        fontSize = 14.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = (panel.width * 0.8f).toInt()))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    p.place((panel.right - p.width - gap * 3).toInt(), (panel.top - p.height - gap).toInt())
                }
            }
            .background(LocalLudoPalette.current.of(color).dark, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private const val BUBBLE_MILLIS = 3_000L
