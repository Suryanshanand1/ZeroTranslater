package com.zerotranslater

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
