package com.zerotranslater

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerotranslater.engine.ProcessTextLimits
import com.zerotranslater.engine.TranslationError
import com.zerotranslater.engine.WordMeaning
import com.zerotranslater.processtext.IncomingText
import com.zerotranslater.processtext.IncomingTextResolver
import com.zerotranslater.processtext.ProcessTextViewModel
import com.zerotranslater.quicktranslate.QuickTranslate
import com.zerotranslater.ui.MeaningCard
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

    /**
     * True when this launch came from the floating pill, meaning the text has to be
     * read from the clipboard and the read cannot happen until one of this app's
     * windows holds focus. Doubles as the guard that makes the read run exactly
     * once, even though two windows can each report gaining focus.
     */
    private var awaitingClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fromClipboard = intent.action == QuickTranslate.ACTION_READ_CLIPBOARD

        if (fromClipboard) {
            // Deliberately not reading the clipboard yet. The platform only grants
            // clipboard access to an app whose UID owns a focused window, and this
            // activity's own window is never the focused one: the sheet below opens
            // as a separate dialog window (material3 ModalBottomSheet), which takes
            // focus instead and leaves the activity window behind it permanently
            // unfocused. The read is therefore triggered from whichever window
            // reports focus first - see tryConsumeClipboard().
            awaitingClipboard = true
        } else {
            if (!consume(extrasFor(intent))) return
        }

        enableEdgeToEdge()

        setContent {
            ZeroTranslaterTheme {
                ProcessTextSheet(
                    viewModel = viewModel,
                    onDismiss = { finish() },
                    onWindowFocus = { tryConsumeClipboard() },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) tryConsumeClipboard()
    }

    /**
     * Runs the deferred clipboard read, whichever focus observer got there first.
     *
     * The activity window and the sheet's dialog window each signal focus
     * independently; [awaitingClipboard] collapses them into a single read.
     * Still resolves to Empty - and finishes silently - when the clipboard holds
     * nothing, has been auto-cleared, or holds only whitespace.
     */
    private fun tryConsumeClipboard() {
        if (!awaitingClipboard) return
        awaitingClipboard = false
        consume(readClipboardText())
    }

    /**
     * Feeds text to the ViewModel.
     *
     * @return false when there was nothing usable, in which case the activity has
     *   already finished and the caller must not proceed.
     */
    private fun consume(raw: CharSequence?): Boolean =
        when (val incoming = IncomingTextResolver.resolve(raw, readOnly = true)) {
            is IncomingText.Empty -> {
                finish()
                false
            }

            is IncomingText.Text -> {
                viewModel.start(incoming.text, incoming.truncated)
                true
            }
        }

    /** The text extra matching whichever entry point launched this activity. */
    private fun extrasFor(intent: Intent): CharSequence? =
        if (intent.action == Intent.ACTION_SEND) {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        } else {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        }

    /**
     * Reads the primary clip as plain text.
     *
     * Returns null rather than throwing when the clip holds a non-text item, and
     * null when access is denied - the caller treats both as "nothing to do".
     */
    private fun readClipboardText(): CharSequence? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        return runCatching { clip.getItemAt(0).coerceToText(this) }.getOrNull()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessTextSheet(
    viewModel: ProcessTextViewModel,
    onDismiss: () -> Unit,
    onWindowFocus: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            // Everything inside this lambda composes into the sheet's own dialog
            // window, and that dialog is the window the platform actually hands
            // input focus to - the activity's transparent window stays unfocused
            // behind it, so waiting on the activity for focus never resolves.
            // Watching the sheet's window is what lets the deferred clipboard
            // read from the pill path run at all.
            val sheetView = LocalView.current
            DisposableEffect(sheetView) {
                val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus) onWindowFocus()
                }
                sheetView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
                // Focus may have arrived before this effect had a chance to run.
                if (sheetView.hasWindowFocus()) onWindowFocus()
                onDispose {
                    sheetView.viewTreeObserver
                        .takeIf { it.isAlive }
                        ?.removeOnWindowFocusChangeListener(listener)
                }
            }

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

                // Show meaning when the selected text is a single word.
                state.meaning?.let { meaning ->
                    MeaningCard(meaning = meaning)
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
