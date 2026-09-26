package com.zerotranslater.quicktranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zerotranslater.MainActivity
import com.zerotranslater.ProcessTextActivity
import com.zerotranslater.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shows a small draggable pill above all other apps. Tapping it opens
 * [ProcessTextActivity] in "read the clipboard" mode.
 *
 * ## Why the clipboard is not read here
 *
 * The original design was a clipboard listener that popped the pill on every copy.
 * That cannot work on Android 10+. `ClipboardService.sendClipChangedBroadcast()`
 * gates the *dispatch itself* on `clipboardAccessAllowed(OP_READ_CLIPBOARD, ...)`,
 * so a background app's `OnPrimaryClipChangedListener` is never called at all - not
 * even to be handed a null clip. The only exemptions are the default IME, an app
 * that currently holds window focus, SystemUI, Content Capture, Augmented Autofill
 * and VirtualDevice owners. `AccessibilityService` is not among them; AOSP's
 * ClipboardService contains no occurrence of "accessib" whatsoever.
 *
 * So the pill is a deliberate, user-initiated trigger instead. The tap is what
 * grants focus: [ProcessTextActivity] then reads the clipboard itself, which the
 * platform permits because a real activity window holds focus at that moment.
 *
 * ## Why this is cheap
 *
 * There is no polling, no timer and no clipboard listener. The service exists only
 * to own one static view, so it does no work between being started and stopped.
 * That is also why it can be a foreground service without draining the battery:
 * the notification is a platform requirement for the overlay, not a symptom of
 * background work.
 */
class QuickTranslateService : Service() {

    private var windowManager: WindowManager? = null
    private var pillView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var host: PillHost? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        // The user can revoke "display over other apps" while the service is
        // running. Adding the window then throws, and a sticky restart would keep
        // retrying forever, so treat "permission gone" as "shut down".
        if (!OverlayPermission.isGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        showPill()
        return START_STICKY
    }

    override fun onDestroy() {
        removePill()
        super.onDestroy()
    }

    // region notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            QuickTranslate.NOTIFICATION_CHANNEL_ID,
            getString(R.string.quick_translate_channel),
            // LOW: this is a persistent control, not something to interrupt for.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.quick_translate_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, QuickTranslate.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle(getString(R.string.quick_translate_channel))
            .setContentText(getString(R.string.quick_translate_notification))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        // Android 14 requires every foreground service to declare a type. This one
        // shows a floating overlay, which is what SPECIAL_USE covers. Below API 34
        // the type does not exist, so the plain call is correct there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                QuickTranslate.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(QuickTranslate.NOTIFICATION_ID, notification)
        }
    }

    // endregion

    // region overlay window

    private fun showPill() {
        if (pillView != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val pillHost = PillHost(onTap = ::openClipboardOverlay)
        val view = pillHost.createView(this)
        val params = buildLayoutParams()

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            // addView throws if the permission was revoked between the check above
            // and this call. Tear down rather than crash.
            pillHost.dispose()
            stopSelf()
            return
        }

        windowManager = wm
        pillView = view
        layoutParams = params
        host = pillHost

        attachDragHandler(view, params)
    }

    /**
     * The overlay window type.
     *
     * `TYPE_APPLICATION_OVERLAY` only exists from API 26. minSdk here is 24, and on
     * 24/25 the constant is not merely absent but inlined to a value the window
     * manager rejects, so the pill would fail to appear. `TYPE_PHONE` is the
     * pre-26 equivalent: deprecated since 26, still functional, and it carries the
     * same SYSTEM_ALERT_WINDOW requirement.
     */
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val width = QuickTranslate.PILL_WIDTH_DP.dpToPx()
        val height = QuickTranslate.PILL_HEIGHT_DP.dpToPx()

        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            // FLAG_NOT_FOCUSABLE is the important flag. It is what stops the pill
            // from stealing input focus from the app underneath - the exact reason
            // the focusable-overlay clipboard trick is rejected. The user can keep
            // typing in the host app while the pill is on screen.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - width - QuickTranslate.PILL_MARGIN_DP.dpToPx())
                .coerceAtLeast(0)
            y = (metrics.heightPixels * QuickTranslate.PILL_INITIAL_Y_FRACTION)
                .roundToInt()
                .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        }
        return params
    }

    private fun attachDragHandler(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    if (moved) {
                        val metrics = resources.displayMetrics
                        params.x = (startX + dx)
                            .roundToInt()
                            .coerceIn(0, (metrics.widthPixels - v.width).coerceAtLeast(0))
                        params.y = (startY + dy)
                            .roundToInt()
                            .coerceIn(0, (metrics.heightPixels - v.height).coerceAtLeast(0))
                        runCatching { windowManager?.updateViewLayout(v, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }

                else -> false
            }
        }

        // performClick() is what actually fires the click listener, and calling it
        // without this override logs an accessibility warning.
        view.setOnClickListener { openClipboardOverlay() }
    }

    private fun openClipboardOverlay() {
        startActivity(
            Intent(this, ProcessTextActivity::class.java).apply {
                action = QuickTranslate.ACTION_READ_CLIPBOARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun removePill() {
        val view = pillView
        val wm = windowManager
        if (view != null && wm != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
        // Dispose only after the view is off the window, otherwise the composition
        // is torn down while still attached.
        host?.dispose()
        host = null
        pillView = null
        windowManager = null
        layoutParams = null
    }

    // endregion

    private fun Int.dpToPx(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        toFloat(),
        resources.displayMetrics,
    ).roundToInt()

    companion object {
        /**
         * Convenience for the settings toggle. Returns false if the overlay
         * permission is missing, so callers can send the user to system settings
         * instead of starting a service that would immediately stop itself.
         */
        fun start(context: Context): Boolean {
            if (!OverlayPermission.isGranted(context)) return false
            val intent = Intent(context, QuickTranslateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickTranslateService::class.java))
        }
    }
}
