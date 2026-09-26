package com.zerotranslater.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerotranslater.R
import com.zerotranslater.engine.LanguagePair
import com.zerotranslater.engine.ProcessTextLimits
import com.zerotranslater.engine.TranslationError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(
    /** Text handed over from the PROCESS_TEXT overlay's "Open in ..." action. */
    preloadedText: String = "",
    onOpenPackManager: () -> Unit,
) {
    val viewModel: TranslateViewModel = viewModel(factory = TranslateViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.copied)

    var pickingSource by remember { mutableStateOf(false) }
    var pickingTarget by remember { mutableStateOf(false) }

    // Fires once per distinct hand-off text.
    LaunchedEffect(preloadedText) {
        if (preloadedText.isNotBlank()) viewModel.onSourceTextChange(preloadedText)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenPackManager) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = stringResource(R.string.manage_packs),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageRow(
                sourceLanguage = state.sourceLanguage,
                targetLanguage = state.targetLanguage,
                onPickSource = { pickingSource = true },
                onPickTarget = { pickingTarget = true },
                onSwap = viewModel::swapLanguages,
            )

            OutlinedTextField(
                value = state.sourceText,
                onValueChange = viewModel::onSourceTextChange,
                label = { Text(stringResource(R.string.source_text_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.translateNow() }),
                trailingIcon = {
                    if (state.sourceText.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearInput) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.clear_input),
                            )
                        }
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.char_count,
                        state.charCount,
                        ProcessTextLimits.MAX_CHARS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PackHint(
                    packsNeeded = state.packsNeeded,
                    routedViaEnglish = state.routedViaEnglish,
                )
            }

            state.resolvedSource?.let { detected ->
                Text(
                    text = stringResource(
                        R.string.detected_language,
                        LanguagePair.displayName(detected),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.inputWasTruncated) {
                NoticeCard(
                    text = pluralStringResource(
                        R.plurals.err_truncated,
                        ProcessTextLimits.MAX_CHARS,
                        ProcessTextLimits.MAX_CHARS,
                    ),
                )
            }

            Button(
                onClick = viewModel::translateNow,
                enabled = state.canTranslate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.translate))
            }

            if (state.isBusy || state.isDownloadingPacks) {
                if (state.isDownloadingPacks) {
                    // ML Kit exposes no byte-level progress for on-device model
                    // downloads, so this is deliberately indeterminate.
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.downloading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    CircularProgressIndicator(Modifier.heightIn(min = 24.dp))
                }
            }

            state.error?.let { error ->
                ErrorCard(
                    error = error,
                    onDownload = viewModel::downloadMissingPacks,
                    onRetry = viewModel::translateNow,
                    onDismiss = viewModel::dismissError,
                )
            }

            if (state.hasOutput) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.translation_label),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, state.output)
                                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_translation),
                                )
                            }
                        }
                        Text(text = state.output, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    if (pickingSource) {
        LangPickerSheet(
            title = stringResource(R.string.source_language),
            selected = state.sourceLanguage,
            includeAutoDetect = true,
            onSelect = viewModel::onSourceLanguageChange,
            onDismiss = { pickingSource = false },
        )
    }

    if (pickingTarget) {
        LangPickerSheet(
            title = stringResource(R.string.target_language),
            selected = state.targetLanguage,
            includeAutoDetect = false,
            onSelect = viewModel::onTargetLanguageChange,
            onDismiss = { pickingTarget = false },
        )
    }
}

@Composable
private fun LanguageRow(
    sourceLanguage: String,
    targetLanguage: String,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
    onSwap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LanguageButton(
            label = stringResource(R.string.source_language),
            value = if (sourceLanguage == LanguagePair.AUTO) {
                stringResource(R.string.auto_detect)
            } else {
                LanguagePair.displayName(sourceLanguage)
            },
            onClick = onPickSource,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSwap) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = stringResource(R.string.swap_languages),
            )
        }
        LanguageButton(
            label = stringResource(R.string.target_language),
            value = LanguagePair.displayName(targetLanguage),
            onClick = onPickTarget,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LanguageButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PackHint(packsNeeded: Int?, routedViaEnglish: Boolean) {
    val count = packsNeeded ?: return
    Text(
        text = if (routedViaEnglish) {
            pluralStringResource(R.plurals.packs_needed_pivot, count, count)
        } else {
            pluralStringResource(R.plurals.packs_needed, count, count)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoticeCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ErrorCard(
    error: TranslationError,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = error.toMessage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error is TranslationError.ModelNotDownloaded) {
                    Button(onClick = onDownload) {
                        Text(stringResource(R.string.download))
                    }
                } else {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.retry))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

/**
 * Copies via the platform ClipboardManager rather than Compose's
 * `LocalClipboardManager`, which is deprecated and scheduled for removal.
 *
 * Android 13+ shows its own copy confirmation, so no extra toast is needed there;
 * the caller surfaces a snackbar only as a fallback.
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("ZeroTranslater", text))
}
