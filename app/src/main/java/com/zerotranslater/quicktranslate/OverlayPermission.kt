package com.zerotranslater.quicktranslate

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * `SYSTEM_ALERT_WINDOW` cannot be requested with a runtime permission dialog.
 * The only route is to send the user to a system settings page, which is why
 * this is a helper rather than a `rememberLauncherForActivityResult` call.
 */
object OverlayPermission {

    /** minSdk is 24, so [Settings.canDrawOverlays] is always available. */
    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
