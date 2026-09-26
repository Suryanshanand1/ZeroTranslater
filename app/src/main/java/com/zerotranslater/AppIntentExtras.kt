package com.zerotranslater

/** Intent extras shared between [ProcessTextActivity] and [MainActivity]. */
object AppIntentExtras {
    /**
     * Carries selected text from the PROCESS_TEXT overlay into the main app when
     * the user taps "Open in ZeroTranslater".
     */
    const val PRELOAD_TEXT: String = "com.zerotranslater.extra.PRELOAD_TEXT"
}
