package com.zerotranslater

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotranslater.engine.ProcessTextLimits
import com.zerotranslater.engine.TranslationError
import com.zerotranslater.processtext.ProcessTextIntentParser
import com.zerotranslater.processtext.ProcessTextViewModel
import com.zerotranslater.ui.toMessage
import com.zerotranslater.ui.theme.ZeroTranslaterTheme

/**
 * The system-wide "Ztranslate" action.
 *
 * Launched by the platform when the user picks Ztranslate from the text-selection
 * popup. It renders a Compose [ModalBottomSheet] inside a fully transparent
 * activity window, so the host app stays visible and focused behind it and the
 * user returns to exactly where they were on dismiss.
 */
class ProcessTextActivity : ComponentActivity() {

    private val viewModel: ProcessTextViewModel by viewModels { ProcessTextViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val parsed = ProcessTextIntentParser.parse(
            raw = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
        )

        when (parsed) {
            is ProcessTextIntentParser.Result.Empty -> {
                // Launched directly rather than from the selection menu, or the
                // selection was blank. Dismiss without ever showing a window.
                finish()
                return
            }

            is ProcessTextIntentParser.Result.Ready -> {
                viewModel.start(parsed.text, parsed.truncated)
            }
        }

        enableEdgeToEdge()

        setContent {
            ZeroTranslaterTheme {
                ProcessTextSheet(viewModel = viewModel, onDismiss = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessTextSheet(
    viewModel: ProcessTextViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.original_text_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.originalText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                if (state.truncated) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.err_truncated,
                            ProcessTextLimits.MAX_CHARS,
                            ProcessTextLimits.MAX_CHARS,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text(
                    text = stringResource(R.string.translation_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when {
                    state.isBusy -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.downloading))
                    }

                    state.error != null -> Text(
                        text = (state.error as TranslationError).toMessage(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Text(
                        text = state.translation,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE,
                            ) as? ClipboardManager
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("ZeroTranslater", state.translation),
                            )
                            onDismiss()
                        },
                        enabled = !state.isBusy && state.translation.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.copy_translation))
                    }

                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(context, MainActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    putExtra(AppIntentExtras.PRELOAD_TEXT, state.originalText)
                                },
                            )
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.open_in_app))
                    }
                }
            }
        }
    }
}
