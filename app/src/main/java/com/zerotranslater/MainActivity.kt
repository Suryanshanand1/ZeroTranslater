package com.zerotranslater

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.zerotranslater.ui.PackManagerScreen
import com.zerotranslater.ui.TranslateScreen
import com.zerotranslater.ui.theme.ZeroTranslaterTheme

class MainActivity : ComponentActivity() {

    private val preloadedText = mutableStateOf("")
    private val showPackManager = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumePreload(intent)

        setContent {
            ZeroTranslaterTheme {
                // The pack manager is a screen *inside* this activity rather than its
                // own activity, so nothing intercepts the system back gesture for it.
                // Without this, pressing back on the pack manager finishes the whole
                // task and drops the user at the launcher instead of returning to the
                // translator. Verified on device: back exited the app outright.
                BackHandler(enabled = showPackManager.value) {
                    showPackManager.value = false
                }

                if (showPackManager.value) {
                    PackManagerScreen(onBack = { showPackManager.value = false })
                } else {
                    TranslateScreen(
                        preloadedText = preloadedText.value,
                        onOpenPackManager = { showPackManager.value = true },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // "Open in ZeroTranslater" arrives here when MainActivity is already warm.
        consumePreload(intent)
    }

    private fun consumePreload(intent: Intent?) {
        val text = intent?.getStringExtra(AppIntentExtras.PRELOAD_TEXT)
        if (!text.isNullOrBlank()) {
            preloadedText.value = text
            showPackManager.value = false
        }
    }
}
