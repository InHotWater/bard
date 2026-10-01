package com.stan.libbylight.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.stan.libbylight.R

/**
 * Bard's play/pause and 15-second skip row, drawn over the LightOS lock screen.
 *
 * Why an overlay: LightOS (checked on build 582-release-lp3) draws its lock-screen media row only for
 * its own Music/Podcasts player. The `com.lightos` app never calls
 * `MediaSessionManager.getActiveSessions()`, is not a notification listener, and bundles no Media3
 * controller, so no MediaSession, MediaStyle notification, channel, category or intent from another
 * app can reach that row. The Light SDK has no lock-screen or now-playing API either.
 *
 * What makes the overlay possible: the LightOS lock screen is not the Android keyguard. It is the
 * ordinary `com.lightos/.MainActivity` (system uid), which LightOS brings to the front when the screen
 * turns off (`light_force_focus_level` 0, the default). An app with the SYSTEM_ALERT_WINDOW appop can
 * draw a `TYPE_APPLICATION_OVERLAY` window above it. LightOS has no Settings screen for either appop
 * Bard needs, so they are granted once over adb:
 *
 *     adb shell appops set <package> SYSTEM_ALERT_WINDOW allow
 *     adb shell appops set <package> GET_USAGE_STATS allow
 *
 * Usage access is how Bard knows LightOS's lock screen is the app in front, so the row never appears
 * over another app. Without both grants nothing is drawn.
 *
 * Safety rules: the window never takes focus (FLAG_NOT_FOCUSABLE), is only as tall as the row, and
 * passes every other touch through to LightOS. It watches for outside touches so that pressing the home
 * circle, swiping or tapping anywhere else hides the row until the next wake (the lock screen and the
 * LightOS home screen are the same activity, so nothing else can tell them apart). A long press on the
 * row also hides it.
 */
@SuppressLint("StaticFieldLeak") // application context only
object LockScreenControlsOverlay {
    private const val TAG = "BardLockOverlay"
    private const val PREFS = "bard_lockscreen_overlay"
    private const val KEY_ENABLED = "enabled"
    private const val TOP_APP_POLL_MILLISECONDS = 700L
    private const val FIRST_USAGE_QUERY_WINDOW_MILLISECONDS = 24 * 60 * 60 * 1000L
    private const val BUTTON_GESTURE_GRACE_MILLISECONDS = 400L

    // LP3 layout grid (27 columns), see ui/LightGrid.kt.
    private const val GRID_COLUMNS = 27f
    private const val ICON_GRID_UNITS = 1.5f
    private const val ROW_GRID_UNITS = 3f
    private const val TITLE_GRID_UNITS = 1.6f
    private const val TITLE_SIDE_PADDING_GRID_UNITS = 2f
    // Centre of the button row as a fraction of panel height: below the LightOS clock and date,
    // above the home circle.
    private const val ROW_CENTRE_FRACTION = 0.78f
    // LightText "Detail" size: 20 design px on a 600 px reference height.
    private const val TITLE_DESIGN_PX = 20f
    private const val DESIGN_REFERENCE_PX = 600f

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var windowManager: WindowManager? = null
    private var usageStats: UsageStatsManager? = null

    private var root: View? = null
    private var playPauseView: ImageView? = null
    private var titleView: TextView? = null

    private var hasBook = false
    private var isPlaying = false
    private var title = ""
    private var screenOn = true
    private var dismissedThisWake = false
    private var startedActivities = 0
    private var lastButtonTouchAt = 0L

    // Incremental usage-event scan.
    private var lastUsageQueryEnd = 0L
    private var topPackage: String? = null
    private var topClass: String? = null

    fun init(application: Application) {
        if (::appContext.isInitialized) return
        appContext = application.applicationContext
        windowManager = appContext.getSystemService(WindowManager::class.java)
        usageStats = appContext.getSystemService(UsageStatsManager::class.java)
        screenOn = appContext.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        appContext.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        if (screenOn) handler.post(pollTopApp)
    }

    // ----- inputs -----

    /** Called by [BardMediaSessionManager] whenever what it publishes changes. Cheap and idempotent. */
    fun onPlaybackChanged(hasBook: Boolean, isPlaying: Boolean, title: String) {
        if (!::appContext.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { onPlaybackChanged(hasBook, isPlaying, title) }
            return
        }
        val glyphChanged = isPlaying != this.isPlaying
        val titleChanged = title != this.title
        this.hasBook = hasBook
        this.isPlaying = isPlaying
        this.title = title
        if (glyphChanged) playPauseView?.setImageResource(playGlyph())
        if (titleChanged) titleView?.text = title
        apply()
    }

    var enabled: Boolean
        get() = ::appContext.isInitialized && prefs().getBoolean(KEY_ENABLED, true)
        set(value) {
            if (!::appContext.isInitialized) return
            prefs().edit().putBoolean(KEY_ENABLED, value).apply()
            apply()
        }

    fun canDrawOverlays(): Boolean =
        ::appContext.isInitialized && Settings.canDrawOverlays(appContext)

    fun hasUsageAccess(): Boolean {
        if (!::appContext.isInitialized) return false
        val appOps = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION") // still the API that reads a uid's mode without noting it
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** One-line status for Settings. */
    fun statusText(): String = when {
        !canDrawOverlays() && !hasUsageAccess() -> "Not set up. Needs two one-time adb permissions (below)."
        !canDrawOverlays() -> "Needs the overlay permission (adb, below)."
        !hasUsageAccess() -> "Needs usage access (adb, below)."
        !enabled -> "Off."
        else -> "On. Controls appear on the LightOS lock screen while a book is loaded."
    }

    fun adbCommands(): List<String> {
        val pkg = if (::appContext.isInitialized) appContext.packageName else "com.stan.libbylight"
        return listOf(
            "adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "adb shell appops set $pkg GET_USAGE_STATS allow",
        )
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            apply()
        }
        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            apply()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // A dismissal lasts for one look at the phone.
                    dismissedThisWake = false
                    handler.removeCallbacks(pollTopApp)
                    handler.post(pollTopApp)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    dismissedThisWake = false
                    handler.removeCallbacks(pollTopApp)
                }
                else -> return
            }
            apply()
        }
    }

    /** There is no callback for "which app is in front", so poll usage events while the screen is on. */
    private val pollTopApp = object : Runnable {
        override fun run() {
            if (!screenOn) return
            apply()
            handler.postDelayed(this, TOP_APP_POLL_MILLISECONDS)
        }
    }

    private fun refreshTopApp() {
        val usm = usageStats ?: return
        val now = System.currentTimeMillis()
        val from = if (lastUsageQueryEnd == 0L) now - FIRST_USAGE_QUERY_WINDOW_MILLISECONDS else lastUsageQueryEnd
        val events = runCatching { usm.queryEvents(from, now) }.getOrNull() ?: return
        lastUsageQueryEnd = now
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                topPackage = event.packageName
                topClass = event.className
            }
        }
    }

    private fun inputs(): LockScreenOverlayPolicy.Inputs {
        val enabledNow = enabled
        val canDraw = canDrawOverlays()
        val usage = hasUsageAccess()
        val cheapOk = enabledNow && canDraw && usage && hasBook && screenOn && startedActivities == 0
        // Only pay for the usage-events query when it can change the answer.
        val onLock = if (cheapOk) {
            refreshTopApp()
            LockScreenOverlayPolicy.isLightOsLockScreen(
                topPackage,
                topClass,
                setOf(LockScreenOverlayPolicy.LIGHTOS_PACKAGE),
            )
        } else false
        return LockScreenOverlayPolicy.Inputs(
            enabled = enabledNow,
            canDrawOverlays = canDraw,
            hasUsageAccess = usage,
            hasBook = hasBook,
            screenOn = screenOn,
            bardInForeground = startedActivities > 0,
            topIsLightOsLockScreen = onLock,
            dismissedThisWake = dismissedThisWake,
        )
    }

    private fun apply() {
        if (!::appContext.isInitialized) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { apply() }
            return
        }
        when (LockScreenOverlayPolicy.decide(inputs(), shown = root != null)) {
            LockScreenOverlayPolicy.Action.Show -> show()
            LockScreenOverlayPolicy.Action.Hide -> hide()
            LockScreenOverlayPolicy.Action.Nothing -> Unit
        }
    }

    private fun dismiss(explicit: Boolean) {
        if (!explicit && SystemClock.uptimeMillis() - lastButtonTouchAt < BUTTON_GESTURE_GRACE_MILLISECONDS) return
        dismissedThisWake = true
        apply()
    }

    // ----- window -----

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (root != null) return
        val wm = windowManager ?: return
        val metrics = appContext.resources.displayMetrics
        val unitPx = metrics.widthPixels / GRID_COLUMNS
        val iconPx = (unitPx * ICON_GRID_UNITS).toInt()
        val rowPx = (unitPx * ROW_GRID_UNITS).toInt()
        val titlePx = (unitPx * TITLE_GRID_UNITS).toInt()
        val sidePadding = (unitPx * TITLE_SIDE_PADDING_GRID_UNITS).toInt()

        val label = TextView(appContext).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                TITLE_DESIGN_PX * metrics.heightPixels / metrics.density / DESIGN_REFERENCE_PX,
            )
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(sidePadding, 0, sidePadding, 0)
        }
        val back = glyph(iconPx, R.drawable.ic_skip_backward_fifteen_white, "Back 15 seconds") {
            BardMediaSessionManager.overlayRewind()
        }
        val toggle = glyph(iconPx, playGlyph(), "Play or pause") {
            BardMediaSessionManager.overlayPlayPause()
        }
        val forward = glyph(iconPx, R.drawable.ic_skip_forward_fifteen_white, "Forward 15 seconds") {
            BardMediaSessionManager.overlayForward()
        }
        val row = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Even thirds so play/pause stays centred.
            for (glyphCell in listOf(back, toggle, forward)) {
                addView(glyphCell.cell, LinearLayout.LayoutParams(0, rowPx, 1f))
            }
        }
        val column = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, titlePx))
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowPx))
        }
        val container = OutsideTouchFrame(appContext) { dismiss(explicit = false) }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, titlePx + rowPx))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            titlePx + rowPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.heightPixels * ROW_CENTRE_FRACTION - rowPx / 2f - titlePx).toInt().coerceAtLeast(0)
            setTitle("Bard lock screen controls")
        }
        val added = runCatching { wm.addView(container, params) }
        if (added.isFailure) {
            // Appop revoked between the check and now, or the type is refused. Behave as before.
            Log.w(TAG, "overlay not added: ${added.exceptionOrNull()?.javaClass?.simpleName}")
            return
        }
        root = container
        playPauseView = toggle.icon
        titleView = label
    }

    private fun hide() {
        val view = root ?: return
        root = null
        playPauseView = null
        titleView = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun playGlyph(): Int = if (isPlaying) R.drawable.ic_pause_white else R.drawable.ic_play_white

    /** One third of the row: the whole cell is the tap target, the glyph inside is [iconPx] square. */
    private class GlyphCell(val icon: ImageView, val cell: FrameLayout)

    @SuppressLint("ClickableViewAccessibility")
    private fun glyph(iconPx: Int, resource: Int, description: String, onTap: () -> Unit): GlyphCell {
        val icon = ImageView(appContext).apply {
            setImageResource(resource)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // The drawables are white already; the tint keeps a stray black variant visible.
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val cell = FrameLayout(appContext).apply {
            contentDescription = description
            isClickable = true
            isLongClickable = true
            addView(icon, FrameLayout.LayoutParams(iconPx, iconPx, Gravity.CENTER))
            setOnTouchListener { _, _ ->
                lastButtonTouchAt = SystemClock.uptimeMillis()
                false
            }
            setOnClickListener { onTap() }
            setOnLongClickListener {
                dismiss(explicit = true)
                true
            }
        }
        return GlyphCell(icon, cell)
    }

    private class OutsideTouchFrame(context: Context, private val onOutside: () -> Unit) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutside()
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }
}
