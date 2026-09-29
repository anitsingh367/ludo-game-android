package com.example.ludoduel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.ludoduel.LudoApp
import com.example.ludoduel.R
import kotlinx.coroutines.launch

/** Sound, vibration and colorblind mode switches. Sound is the same setting as the speaker button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as LudoApp).container.settings
    val ui = remember(context) { UiSettings(context) }
    val muted by remember(settings) { settings.muted }.collectAsState(initial = false)
    val prefs = rememberUiPrefs()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            SettingRow(stringResource(R.string.settings_sound), null, !muted) { on -> scope.launch { settings.setMuted(!on) } }
            SettingRow(stringResource(R.string.settings_vibration), null, prefs.vibration) { on -> scope.launch { ui.setVibration(on) } }
            SettingRow(
                stringResource(R.string.settings_colorblind),
                stringResource(R.string.settings_colorblind_hint),
                prefs.colorblind,
            ) { on -> scope.launch { ui.setColorblind(on) } }
        }
    }
}

@Composable
private fun SettingRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row toggles the switch (a bigger touch target than the switch alone).
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
