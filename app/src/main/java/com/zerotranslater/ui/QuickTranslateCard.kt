package com.zerotranslater.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zerotranslater.R
import com.zerotranslater.quicktranslate.OverlayPermission

/**
 * Settings row for the floating Quick-Translate pill.
 *
 * `SYSTEM_ALERT_WINDOW` has no runtime permission dialog on any Android version, so
 * enabling this has to bounce the user out to a system settings page. That makes the
 * interaction worth being explicit about: a switch that silently does nothing until
 * the user goes hunting in Settings is worse than no switch, so the first attempt
 * shows a rationale explaining what is about to happen and why.
 *
 * The granted state is re-read on every ON_RESUME, because the user returns here
 * straight from the system settings page and the switch has to reflect what they
 * just did without them having to pull to refresh.
 */
@Composable
fun QuickTranslateCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(OverlayPermission.isGranted(context)) }

    // Re-checked on resume: the user grants the permission in the system settings
    // page and comes straight back here, so the switch has to reflect what they
    // just did without a manual refresh.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlayGranted = OverlayPermission.isGranted(context)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.quick_translate),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.quick_translate_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (enabled && !overlayGranted) {
                    Text(
                        text = stringResource(R.string.quick_translate_permission_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = { requested ->
                    if (!requested) {
                        onToggle(false)
                    } else if (overlayGranted) {
                        onToggle(true)
                    } else {
                        showRationale = true
                    }
                },
            )
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.quick_translate)) },
            text = { Text(stringResource(R.string.quick_translate_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        context.startActivity(OverlayPermission.settingsIntent(context))
                    },
                ) {
                    Text(stringResource(R.string.quick_translate))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}
