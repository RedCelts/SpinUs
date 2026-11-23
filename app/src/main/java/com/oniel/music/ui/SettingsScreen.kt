package com.oniel.music.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.AuthStore
import com.oniel.music.data.Crossfade
import com.oniel.music.data.SavedAuth
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings: crossfade length, change the server address, log out, and
 * the app version. Changing the server address signs the user out so
 * they sign in again against the new address.
 */
@Composable
fun SettingsScreen(
    auth: SavedAuth,
    authStore: AuthStore,
    crossfadeSeconds: Int,
    onCrossfadeChanged: (Int) -> Unit,
    onLoggedOut: (String?) -> Unit
) {
    var showServerDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_account),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = auth.userName,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = auth.serverUrl,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.settings_playback),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_crossfade),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = if (crossfadeSeconds == 0) {
                    stringResource(R.string.settings_crossfade_off)
                } else {
                    stringResource(R.string.settings_crossfade_seconds, crossfadeSeconds)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = crossfadeSeconds.toFloat(),
            onValueChange = { onCrossfadeChanged(it.roundToInt()) },
            valueRange = Crossfade.MIN_SECONDS.toFloat()..Crossfade.MAX_SECONDS.toFloat(),
            steps = Crossfade.MAX_SECONDS - Crossfade.MIN_SECONDS - 1,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.settings_crossfade_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        TailscaleCard()
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { showServerDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_change_server))
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { showLogoutConfirm = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.ExitToApp,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(stringResource(R.string.settings_log_out))
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.settings_version, "1.0.5"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showServerDialog) {
        ChangeServerDialog(
            currentServer = auth.serverUrl,
            onConfirm = { newServer ->
                showServerDialog = false
                // A new address means a new sign-in: clear and go to
                // login, with the new address prefilled there.
                scope.launch {
                    authStore.clear()
                    onLoggedOut(newServer)
                }
            },
            onDismiss = { showServerDialog = false }
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.settings_log_out)) },
            text = { Text(stringResource(R.string.settings_log_out_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        scope.launch {
                            authStore.clear()
                            onLoggedOut(null)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text(stringResource(R.string.settings_log_out)) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ChangeServerDialog(
    currentServer: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var server by remember { mutableStateOf(currentServer) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_change_server)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_change_server_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(stringResource(R.string.login_server_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (server.isNotBlank()) onConfirm(server.trim().trimEnd('/'))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text(stringResource(R.string.settings_change_server_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
