package com.zerotranslater.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerotranslater.R
import com.zerotranslater.engine.LanguagePair

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackManagerScreen(onBack: () -> Unit) {
    val viewModel: PackManagerViewModel = viewModel(factory = PackManagerViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_packs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.wifi_only_downloads),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.wifi_only_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.wifiOnlyDownloads,
                        onCheckedChange = viewModel::setWifiOnlyDownloads,
                    )
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            state.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = error.toMessage(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = viewModel::refresh) {
                                Text(stringResource(R.string.retry))
                            }
                            TextButton(onClick = viewModel::dismissError) {
                                Text(stringResource(R.string.close))
                            }
                        }
                    }
                }
            }

            if (!state.isLoading && state.packs.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_packs_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(state.packs, key = { it.language }) { pack ->
                    val busy = state.busyLanguage == pack.language
                    ListItem(
                        headlineContent = { Text(LanguagePair.displayName(pack.language)) },
                        supportingContent = {
                            Text(
                                text = if (pack.isDownloaded) {
                                    stringResource(R.string.pack_installed)
                                } else {
                                    stringResource(R.string.pack_not_installed)
                                },
                            )
                        },
                        trailingContent = {
                            when {
                                busy -> CircularProgressIndicator(
                                    modifier = Modifier.padding(8.dp),
                                )

                                pack.isDownloaded -> TextButton(
                                    onClick = { viewModel.delete(pack.language) },
                                ) {
                                    Text(stringResource(R.string.delete))
                                }

                                else -> TextButton(
                                    onClick = { viewModel.download(pack.language) },
                                ) {
                                    Text(stringResource(R.string.download))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
