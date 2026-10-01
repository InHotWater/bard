package com.stan.libbylight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.media.session.MediaButtonReceiver
import com.stan.libbylight.player.BardMediaSessionManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hosts Bard's one playback notification and keeps the active playback backend perceptible.
 * Playback and source routing remain owned by the existing controllers and Bard media session.
 *
 * While playing, the service is a mediaPlayback foreground service (keeps Libby's WebView and
 * local playback out of the cached-app freezer; see INVESTIGATION.md). When paused it leaves the
 * foreground but stays *started* for [PAUSED_IDLE_STOP_MILLISECONDS], so Bard's process is not
 * cached/frozen and lock-screen (LightOS or Android) "play" commands delivered to the media
 * session are handled promptly. After that idle period the service stops, as the Light SDK's
 * own LightAudioService does.
 */
class LibbyPlaybackForegroundService : Service() {
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleStop = Runnable {
        if (!foreground.get()) {
            Log.d(TAG, "paused idle timeout; stopping playback host")
            started.set(false)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
        started.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BardMediaSessionManager.handleMediaButtonIntent(intent)
        if (intent?.action == ACTION_STOP_NOTIFICATION) {
            foreground.set(false)
            idleHandler.removeCallbacks(idleStop)
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            Log.d(TAG, "playback notification removed")
            started.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE_NOTIFICATION) {
            enterPaused()
            return START_NOT_STICKY
        }
        // Every other start comes from startForegroundService() (Bard itself or the
        // MediaButtonReceiver), which obliges startForeground() promptly. Media-button commands
        // are dispatched to the session asynchronously, so settle the paused state afterwards.
        enterForeground()
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            idleHandler.postDelayed({
                if (started.get() && instance === this &&
                    !BardMediaSessionManager.notificationSnapshot().isPlaying
                ) {
                    enterPaused()
                }
            }, MEDIA_BUTTON_SETTLE_MILLISECONDS)
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        idleHandler.removeCallbacks(idleStop)
        try {
            startForeground(NOTIFICATION_ID, buildNotification(this))
            foreground.set(true)
            mutableStartFailed.value = false
            Log.d(TAG, "foreground playback host started")
        } catch (error: RuntimeException) {
            // Android 12+ may refuse a background foreground-service start; stay started so the
            // process remains runnable, and surface the failure to the player UI.
            foreground.set(false)
            mutableStartFailed.value = true
            Log.e(TAG, "could not enter foreground")
            idleHandler.postDelayed(idleStop, PAUSED_IDLE_STOP_MILLISECONDS)
        }
    }

    private fun enterPaused() {
        foreground.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(this))
        idleHandler.removeCallbacks(idleStop)
        idleHandler.postDelayed(idleStop, PAUSED_IDLE_STOP_MILLISECONDS)
        Log.d(TAG, "foreground released; paused media notification and session retained")
    }

    override fun onDestroy() {
        idleHandler.removeCallbacksAndMessages(null)
        foreground.set(false)
        started.set(false)
        if (instance === this) instance = null
        Log.d(TAG, "playback host stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    init {
        instance = this
    }

    companion object {
        private const val CHANNEL_ID = "bard_playback"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "BardPlaybackService"
        private const val PAUSED_IDLE_STOP_MILLISECONDS = 15 * 60 * 1000L
        private const val MEDIA_BUTTON_SETTLE_MILLISECONDS = 1_500L
        private const val ACTION_PAUSE_NOTIFICATION =
            "com.stan.libbylight.action.PAUSE_PLAYBACK_NOTIFICATION"
        private const val ACTION_STOP_NOTIFICATION =
            "com.stan.libbylight.action.STOP_PLAYBACK_NOTIFICATION"
        private val foreground = AtomicBoolean(false)
        private val started = AtomicBoolean(false)
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: LibbyPlaybackForegroundService? = null
        private val mutableStartFailed = MutableStateFlow(false)
        val startFailed: StateFlow<Boolean> = mutableStartFailed.asStateFlow()

        fun update(context: Context, isPlaying: Boolean) {
            val appContext = context.applicationContext
            if (isPlaying) {
                if (foreground.get()) return
                val live = instance
                if (live != null && started.get()) {
                    foreground.set(true)
                    mainHandler.post { live.enterForeground() }
                    return
                }
                foreground.set(true)
                try {
                    appContext.startForegroundService(
                        Intent(appContext, LibbyPlaybackForegroundService::class.java),
                    )
                } catch (error: RuntimeException) {
                    foreground.set(false)
                    mutableStartFailed.value = true
                    Log.e(TAG, "could not start foreground playback host")
                }
            } else if (foreground.compareAndSet(true, false)) {
                mutableStartFailed.value = false
                val live = instance
                if (live != null) {
                    mainHandler.post { live.enterPaused() }
                } else {
                    runCatching {
                        appContext.startService(
                            Intent(appContext, LibbyPlaybackForegroundService::class.java)
                                .setAction(ACTION_PAUSE_NOTIFICATION),
                        )
                    }
                }
            }
        }

        /** Re-posts the media notification after title, artwork or play state changes. */
        fun refreshNotification(context: Context) {
            if (instance == null) return
            val appContext = context.applicationContext
            mainHandler.post {
                if (instance == null) return@post
                runCatching {
                    appContext.getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(appContext))
                }
            }
        }

        fun stopAndRemove(context: Context) {
            val appContext = context.applicationContext
            foreground.set(false)
            val live = instance
            if (live != null) {
                mainHandler.post {
                    live.stopForeground(STOP_FOREGROUND_REMOVE)
                    appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    started.set(false)
                    live.stopSelf()
                }
            } else {
                appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        }

        private fun createNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Audiobook playback controls"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }

        private fun buildNotification(context: Context): Notification {
            createNotificationChannel(context)
            val snapshot = BardMediaSessionManager.notificationSnapshot()
            val token = BardMediaSessionManager.sessionToken
            val openBard = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val back = MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            val playPause = MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                if (snapshot.isPlaying) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY,
            )
            val forward = MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackState.ACTION_SKIP_TO_NEXT,
            )
            val stop = MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackState.ACTION_STOP,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_audio_message_white)
                .setContentTitle(snapshot.title)
                .setContentText(snapshot.subtitle ?: "Bard")
                .setContentIntent(openBard)
                .setDeleteIntent(stop)
                .apply { snapshot.artwork?.let(::setLargeIcon) }
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_skip_backward_fifteen_white),
                        "Back 15 seconds",
                        back,
                    ).build(),
                )
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                            context,
                            if (snapshot.isPlaying) R.drawable.ic_pause_white else R.drawable.ic_play_white,
                        ),
                        if (snapshot.isPlaying) "Pause" else "Play",
                        playPause,
                    ).build(),
                )
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_skip_forward_fifteen_white),
                        "Forward 15 seconds",
                        forward,
                    ).build(),
                )
                .setStyle(
                    Notification.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                        .apply {
                            (token?.token as? android.media.session.MediaSession.Token)?.let(::setMediaSession)
                        },
                )
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOngoing(snapshot.isPlaying)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                // Public so Android lock screens show the transport controls. Only the book title,
                // author and cover are exposed; no library or account details.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        }
    }
}
