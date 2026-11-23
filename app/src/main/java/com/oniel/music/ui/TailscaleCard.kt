package com.oniel.music.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.net.Tailscale
import kotlinx.coroutines.launch

/**
 * Built-in Tailscale: on/off switch, connection status, and the sign-in /
 * sign-out buttons. Sign-in opens Tailscale's login page in the browser;
 * the card turns green on its own once the device is approved.
 */
@Composable
fun TailscaleCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by Tailscale.state.collectAsState()
    var enabled by remember { mutableStateOf(Tailscale.isEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ts_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.ts_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        message = null
                        Tailscale.setEnabled(context, it)
                    }
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                val (dotColor, statusText) = when (state.phase) {
                    Tailscale.Phase.RUNNING -> Color(0xFF2E7D32) to stringResource(R.string.ts_connected)
                    Tailscale.Phase.NEEDS_LOGIN -> Color(0xFFF9A825) to stringResource(R.string.ts_needs_login)
                    Tailscale.Phase.ERROR -> MaterialTheme.colorScheme.error to stringResource(R.string.ts_error)
                    else -> MaterialTheme.colorScheme.outline to stringResource(R.string.ts_starting)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = dotColor,
                        modifier = Modifier.size(10.dp)
                    ) {}
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.phase == Tailscale.Phase.RUNNING) {
                    val details = listOfNotNull(
                        state.deviceName ?: Tailscale.HOSTNAME,
                        state.ips.firstOrNull(),
                        state.tailnet
                    ).joinToString(" · ")
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                val error = message ?: state.error?.takeIf { state.phase == Tailscale.Phase.ERROR }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                when (state.phase) {
                    Tailscale.Phase.NEEDS_LOGIN -> Button(
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    val url = Tailscale.loginUrl()
                                    if (url != null) {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                } catch (e: Exception) {
                                    message = e.message ?: "Couldn't start sign-in"
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
                                modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(stringResource(R.string.ts_sign_in))
                    }
                    Tailscale.Phase.RUNNING -> OutlinedButton(
                        onClick = {
                            scope.launch {
                                try { Tailscale.logout() } catch (e: Exception) { message = e.message }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.ts_sign_out)) }
                    Tailscale.Phase.ERROR -> OutlinedButton(
                        onClick = {
                            Tailscale.setEnabled(context, false)
                            Tailscale.setEnabled(context, true)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.ts_retry)) }
                    else -> Unit
                }
                if (state.phase == Tailscale.Phase.RUNNING) {
                    Text(
                        text = stringResource(R.string.ts_address_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
