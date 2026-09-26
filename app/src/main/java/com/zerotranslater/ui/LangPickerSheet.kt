package com.zerotranslater.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerotranslater.R
import com.zerotranslater.engine.LanguagePair

/**
 * Searchable language chooser.
 *
 * @param includeAutoDetect adds the [LanguagePair.AUTO] sentinel. Source only:
 *   auto-detecting the *target* language is not a meaningful operation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LangPickerSheet(
    title: String,
    selected: String,
    includeAutoDetect: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val selectedLabel = stringResource(R.string.selected_label, languageLabel(selected))

    val options = remember(query, includeAutoDetect) {
        buildList {
            if (includeAutoDetect) add(LanguagePair.AUTO)
            addAll(LanguagePair.supportedLanguages)
        }.filter { code ->
            // LanguagePair.displayName is deliberately not @Composable so it can be
            // called from inside remember, outside a composition.
            query.isBlank() || LanguagePair.displayName(code).contains(query, ignoreCase = true)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.search_languages)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }

        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(options, key = { it }) { code ->
                val label = languageLabel(code)
                ListItem(
                    headlineContent = {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    // The BCP-47 code is useful when hunting for a specific
                    // language, but is noise for the majority of users.
                    supportingContent = if (code == LanguagePair.AUTO) null else {
                        { Text(code) }
                    },
                    leadingContent = if (code == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(code)
                            onDismiss()
                        }
                        .then(
                            // Only the selected row carries the state, otherwise
                            // TalkBack announces every row as selected.
                            if (code == selected) {
                                Modifier.semantics { stateDescription = selectedLabel }
                            } else {
                                Modifier
                            },
                        ),
                )
                HorizontalDivider()
            }
        }
    }
}

/** "Auto-detect" for the sentinel, otherwise the localised language name. */
@Composable
private fun languageLabel(code: String): String =
    if (code == LanguagePair.AUTO) stringResource(R.string.auto_detect)
    else LanguagePair.displayName(code)
