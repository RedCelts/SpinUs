package com.oniel.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.AuthStore
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.SavedAuth
import kotlinx.coroutines.launch

/**
 * First-run sign in. Server address is the Jellyfin address:
 * http://<server-IP>:8096 on the home network, or the Tailscale https
 * address when away. On success the credentials are saved in AuthStore
 * and the app moves to Home.
 */
@Composable
fun LoginScreen(
    client: JellyfinClient,
    authStore: AuthStore,
    onLoggedIn: (SavedAuth) -> Unit,
    initialServer: String? = null
) {
    var serverUrl by remember { mutableStateOf(initialServer ?: "") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.login_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; error = null },
            label = { Text(stringResource(R.string.login_server_label)) },
            placeholder = { Text(stringResource(R.string.login_server_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text(stringResource(R.string.login_username_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text(stringResource(R.string.login_password_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = {
                val address = serverUrl.trim().trimEnd('/')
                if (address.isBlank() || username.isBlank() || password.isBlank()) {
                    error = "Fill in the server address, username and password."
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    try {
                        val result = client.login(address, username.trim(), password)
                        // Open the presence socket now: without it the
                        // server only ever shows this user's own session,
                        // so friends would look broken.
                        try {
                            client.connectPresence()
                        } catch (e: Exception) {
                            // Presence is best-effort; sign-in still counts.
                        }
                        authStore.save(
                            serverUrl = address,
                            token = result.token,
                            userId = result.userId,
                            userName = result.userName,
                            isAdmin = result.isAdmin
                        )
                        onLoggedIn(
                            SavedAuth(
                                serverUrl = address,
                                token = result.token,
                                userId = result.userId,
                                userName = result.userName,
                                isAdmin = result.isAdmin
                            )
                        )
                    } catch (e: Exception) {
                        error = "Could not sign in. Check the address and your password."
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(stringResource(R.string.login_button))
        }

        Spacer(modifier = Modifier.height(24.dp))
        TailscaleCard()

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.login_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
