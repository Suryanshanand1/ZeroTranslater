package com.zerotranslater.quicktranslate

/**
 * Wiring shared between the overlay service, [com.zerotranslater.MainActivity]
 * and [com.zerotranslater.ProcessTextActivity].
 *
 * Kept in one place because all three need to agree on the action string, and a
 * typo here produces a pill that silently does nothing when tapped.
 */
object QuickTranslate {

    /**
     * Internal action understood by ProcessTextActivity as "read the clipboard
     * yourself and translate it".
     *
     * This exists only because the clipboard cannot be read from a background
     * service. See [OverlayPermission] for why the read has to happen inside an
     * activity that actually holds window focus.
     */
    const val ACTION_READ_CLIPBOARD: String = "com.zerotranslater.action.READ_CLIPBOARD"

    const val NOTIFICATION_CHANNEL_ID: String = "quick_translate_overlay"
    const val NOTIFICATION_ID: Int = 0x51A7

    /** Pill size in dp. Deliberately small enough to sit out of the way. */
    const val PILL_WIDTH_DP: Int = 56
    const val PILL_HEIGHT_DP: Int = 56

    /**
     * Distance from the right screen edge, and the initial vertical position as a
     * fraction of screen height. Both are overridable by dragging.
     */
    const val PILL_MARGIN_DP: Int = 8
    const val PILL_INITIAL_Y_FRACTION: Float = 0.45f
}
