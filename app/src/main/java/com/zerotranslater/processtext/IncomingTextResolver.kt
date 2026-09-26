package com.zerotranslater.processtext

/**
 * Text handed to the overlay activity, already validated and length-capped.
 */
sealed interface IncomingText {
    /**
     * @param text usable text, capped at [com.zerotranslater.engine.ProcessTextLimits.MAX_CHARS]
     * @param truncated [text] was cut down from something longer
     */
    data class Text(val text: String, val truncated: Boolean) : IncomingText

    /** Nothing usable arrived; the caller should finish() without showing UI. */
    data object Empty : IncomingText
}

/**
 * Single validation point for every way text can reach the overlay.
 *
 * Three separate entry points feed this activity - the `PROCESS_TEXT` selection
 * menu, the `ACTION_SEND` share sheet, and the floating pill reading the clipboard
 * - and all three need identical blank/length handling. Routing them through one
 * resolver means the 5,000-character cap and the whitespace guard cannot drift
 * apart between paths, and it keeps that behaviour in plain JVM tests rather than
 * in an Activity that can only be exercised on hardware.
 *
 * Framework-free by construction: callers pass the extra values they already
 * extracted, so this stays unit testable.
 */
object IncomingTextResolver {

    /**
     * @param raw the candidate text from whichever entry point was used
     * @param readOnly the host app marked the content read-only
     */
    fun resolve(raw: CharSequence?, readOnly: Boolean): IncomingText =
        when (val parsed = ProcessTextIntentParser.parse(raw, readOnly)) {
            is ProcessTextIntentParser.Result.Ready ->
                IncomingText.Text(text = parsed.text, truncated = parsed.truncated)

            ProcessTextIntentParser.Result.Empty -> IncomingText.Empty
        }
}
