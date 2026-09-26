package com.zerotranslater.processtext

import com.zerotranslater.engine.ProcessTextLimits

/**
 * Pure, framework-free parsing of the two extras the system hands a
 * `PROCESS_TEXT` handler.
 *
 * This is deliberately a plain object with no Android imports. The
 * PROCESS_TEXT contract is the highest-risk, least-tested surface in the app -
 * a third-party app can put anything in these extras - so the logic is
 * extracted here and covered by fast JVM unit tests rather than being buried in
 * an Activity that can only be exercised on a device.
 */
object ProcessTextIntentParser {

    sealed interface Result {
        /**
         * @param text text to translate, already length-capped
         * @param readOnly the host app marked the selection read-only
         * @param truncated [text] was cut down to [ProcessTextLimits.MAX_CHARS]
         */
        data class Ready(
            val text: String,
            val readOnly: Boolean,
            val truncated: Boolean,
        ) : Result

        /** Nothing usable arrived; the caller should finish() without showing UI. */
        data object Empty : Result
    }

    /**
     * @param raw value of [android.content.Intent.EXTRA_PROCESS_TEXT]
     * @param readOnly value of [android.content.Intent.EXTRA_PROCESS_TEXT_READONLY]
     */
    fun parse(raw: CharSequence?, readOnly: Boolean): Result {
        // A null extra means the activity was launched directly rather than from the
        // selection menu; a blank one means the user selected whitespace. Both cases
        // must dismiss silently instead of flashing an empty sheet.
        if (raw.isNullOrBlank()) return Result.Empty

        val full = raw.toString()
        return if (full.length > ProcessTextLimits.MAX_CHARS) {
            Result.Ready(
                text = full.take(ProcessTextLimits.MAX_CHARS),
                readOnly = readOnly,
                truncated = true,
            )
        } else {
            Result.Ready(text = full, readOnly = readOnly, truncated = false)
        }
    }
}
