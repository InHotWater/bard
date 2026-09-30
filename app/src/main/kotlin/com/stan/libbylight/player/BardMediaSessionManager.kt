package com.stan.libbylight.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.session.MediaButtonReceiver
import com.stan.libbylight.LibbyBridge
import com.stan.libbylight.LibbyPlaybackForegroundService
import com.stan.libbylight.MainActivity
import com.stan.libbylight.library.Audiobook
import com.stan.libbylight.library.AudiobookSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single Android media session for Bard. It publishes only safe display metadata and delegates
 * every command to the already-active playback backend.
 *
 * LightOS (Light Phone III) does not render app notifications; its lock screen / Toolbox
 * now-playing surface discovers the active platform MediaSession through
 * MediaSessionManager.getActiveSessions() (see light-sdk docs/design_decisions/detached_audio.md).
 * Stock Android lock screens render the MediaStyle notification bound to this session.
 * Either way, this session must stay current while Bard's Activity is stopped, so state is fed
 * from the playback backends directly rather than only from the Compose UI (whose recomposition
 * pauses when the Activity stops).
 */
object BardMediaSessionManager {
    private const val TAG = "BardMediaSession"
    private const val LIBBY_BACKGROUND_POLL_MILLISECONDS = 2_000L
    private const val POSITION_DRIFT_REPUBLISH_MILLISECONDS = 1_500L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deduplicator = MediaCommandDeduplicator()
    private lateinit var appContext: Context
    private lateinit var session: MediaSessionCompat
    private var activeBook: Audiobook? = null
    private var state = PlayerState()
    private var artwork: Bitmap? = null
    private var artworkKey: String? = null

    // Last values handed to the platform, used to avoid re-publishing every 500 ms tick.
    private var publishedMetadataSignature: Any? = null
    private var publishedStateSignature: Any? = null
    private var publishedPositionMs = 0L
    private var publishedAtElapsed = 0L
    private var publishedSpeed = 1f
    private var publishedPlaying = false
    private var notificationSignature: Any? = null

    fun init(context: Context) {
        if (::session.isInitialized) return
        appContext = context.applicationContext
        session = MediaSessionCompat(appContext, "Bard").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(callback, mainHandler)
            // Tapping the lock-screen / notification now-playing surface opens Bard.
            setSessionActivity(
                PendingIntent.getActivity(
                    appContext,
                    0,
                    Intent(appContext, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            isActive = true
        }
        // Local/RSS state keeps flowing while the Activity is stopped (screen off / lock screen).
        scope.launch {
            LocalPlaybackController.state.collect { local ->
                val source = activeBook?.source
                if (source == AudiobookSource.Local || source == AudiobookSource.Rss) {
                    state = local
                    publish()
                }
            }
        }
        publish()
    }

    val sessionToken: MediaSessionCompat.Token?
        get() = session.takeIf { ::session.isInitialized }?.sessionToken

    fun update(book: Audiobook?, playerState: PlayerState) {
        if (!::session.isInitialized) return
        val bookChanged = book?.source != activeBook?.source || book?.id != activeBook?.id
        activeBook = book
        state = playerState
        if (bookChanged || (book != null && artwork == null)) refreshArtwork(book)
        publish()
        scheduleLibbyPoll()
    }

    /** Immediate Libby foreground-host update without allowing an inactive Libby frame to stop
     * native Local/RSS playback. The next authoritative state snapshot still performs reconciliation.
     */
    fun onLibbyPlayingChanged(isPlaying: Boolean) {
        if (!::session.isInitialized || activeBook?.source != AudiobookSource.Libby) return
        if (state.isPlaying == isPlaying) return
        state = state.copy(isPlaying = isPlaying)
        publish()
        scheduleLibbyPoll()
    }

    fun notificationSnapshot(): NotificationSnapshot {
        val metadata = safeMediaMetadata(activeBook?.title.orEmpty(), activeBook?.author.orEmpty())
        return NotificationSnapshot(
            title = metadata.title,
            subtitle = metadata.subtitle,
            isPlaying = state.isPlaying,
            artwork = artwork,
        )
    }

    fun handleMediaButtonIntent(intent: android.content.Intent?) {
        if (::session.isInitialized) MediaButtonReceiver.handleIntent(session, intent)
    }

    private fun refreshArtwork(book: Audiobook?) {
        if (book == null) {
            artwork = null
            artworkKey = null
            return
        }
        val key = MediaArtworkLoader.key(book)
        if (key != artworkKey) {
            artworkKey = key
            artwork = MediaArtworkLoader.cached(book)
        }
        if (artwork != null) return
        MediaArtworkLoader.load(appContext, book) { loadedKey, bitmap ->
            if (loadedKey != artworkKey) return@load
            artwork = bitmap
            publish()
        }
    }

    private fun currentPlaybackState(book: Audiobook?): Int = when {
        book == null -> PlaybackStateCompat.STATE_NONE
        state.readiness == PlayerReadiness.Error -> PlaybackStateCompat.STATE_ERROR
        state.readiness == PlayerReadiness.Unavailable -> PlaybackStateCompat.STATE_STOPPED
        state.isPlaying -> PlaybackStateCompat.STATE_PLAYING
        state.readiness == PlayerReadiness.Buffering -> PlaybackStateCompat.STATE_BUFFERING
        // A remembered-but-not-yet-connected Libby book (or a preparing local book) is still
        // resumable from the lock screen; report it as paused instead of "connecting".
        else -> PlaybackStateCompat.STATE_PAUSED
    }

    private fun publish(updatePlaybackHost: Boolean = true) {
        val book = activeBook
        val duration = (state.durationSeconds * 1000).toLong().coerceAtLeast(0)
        val position = clampMediaSeek((state.positionSeconds * 1000).toLong(), duration)
        val safe = safeMediaMetadata(book?.title.orEmpty(), book?.author.orEmpty())

        val metadataSignature = listOf(book?.source, book?.id, safe.title, safe.subtitle, duration, artwork)
        if (metadataSignature != publishedMetadataSignature) {
            publishedMetadataSignature = metadataSignature
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, safe.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, safe.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, safe.subtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, safe.subtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, safe.subtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Bard")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                    .apply {
                        artwork?.let {
                            putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                            putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                            putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
                        }
                    }
                    .build(),
            )
        }

        val playbackState = currentPlaybackState(book)
        val speed = state.playbackSpeed.toFloat().takeIf { it > 0f } ?: 1f
        val actions = if (book == null) 0L else {
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        val stateSignature = listOf(playbackState, actions, speed, book?.id)
        val now = SystemClock.elapsedRealtime()
        val expected = if (publishedPlaying) {
            publishedPositionMs + ((now - publishedAtElapsed) * publishedSpeed).toLong()
        } else {
            publishedPositionMs
        }
        val drift = kotlin.math.abs(expected - position)
        if (stateSignature != publishedStateSignature || drift > POSITION_DRIFT_REPUBLISH_MILLISECONDS) {
            publishedStateSignature = stateSignature
            publishedPositionMs = position
            publishedAtElapsed = now
            publishedSpeed = speed
            publishedPlaying = playbackState == PlaybackStateCompat.STATE_PLAYING
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(playbackState, position, if (publishedPlaying) speed else 0f, now)
                    .setBufferedPosition(position)
                    .apply {
                        if (state.readiness == PlayerReadiness.Error) {
                            setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, "Playback unavailable")
                        }
                    }
                    .build(),
            )
        }
        val shouldBeActive = book != null
        if (session.isActive != shouldBeActive) session.isActive = shouldBeActive
        if (updatePlaybackHost) {
            LibbyPlaybackForegroundService.update(appContext, state.isPlaying)
            val snapshotSignature = listOf(safe.title, safe.subtitle, state.isPlaying, artwork)
            if (snapshotSignature != notificationSignature) {
                notificationSignature = snapshotSignature
                LibbyPlaybackForegroundService.refreshNotification(appContext)
            }
        }
    }

    /**
     * While a Libby book plays with Bard in the background, poll its player frame at a low rate so
     * pauses Bard did not cause (end of book, audio focus loss, a call) reach the lock screen.
     * The Compose poll covers the foreground case.
     */
    private fun scheduleLibbyPoll() {
        mainHandler.removeCallbacks(libbyPoll)
        if (activeBook?.source == AudiobookSource.Libby && state.isPlaying) {
            mainHandler.postDelayed(libbyPoll, LIBBY_BACKGROUND_POLL_MILLISECONDS)
        }
    }

    private val libbyPoll = Runnable {
        if (activeBook?.source != AudiobookSource.Libby || !LibbyBridge.hasActivePlayerFrame()) {
            return@Runnable
        }
        LibbyBridge.getPlayerState { snapshot ->
            mainHandler.post {
                if (activeBook?.source == AudiobookSource.Libby && snapshot.controlsFound) {
                    state = snapshot
                    publish()
                }
                scheduleLibbyPoll()
            }
        }
    }

    private fun dispatch(key: String, action: () -> Unit) {
        if (!deduplicator.shouldDispatch(key)) {
            Log.d(TAG, "duplicate media command ignored action=$key")
            return
        }
        Log.d(TAG, "media command action=$key source=${activeBook?.source?.name ?: "none"}")
        action()
    }

    private fun livePositionMilliseconds(): Long {
        val base = (state.positionSeconds * 1000).toLong()
        if (!publishedPlaying) return base
        val extrapolated = publishedPositionMs +
            ((SystemClock.elapsedRealtime() - publishedAtElapsed) * publishedSpeed).toLong()
        return maxOf(base, extrapolated)
    }

    private fun seekTo(positionMilliseconds: Long) {
        val duration = (state.durationSeconds * 1000).toLong()
        val target = clampMediaSeek(positionMilliseconds, duration)
        state = state.copy(positionSeconds = target / 1000.0)
        publish()
        when (activeBook?.source) {
            AudiobookSource.Libby -> LibbyBridge.seekTo(target)
            AudiobookSource.Local, AudiobookSource.Rss -> LocalPlaybackController.seekTo(target)
            null -> Unit
        }
    }

    /** Relative skip measured from each backend's live position. */
    private fun skipBy(deltaMilliseconds: Long) {
        val duration = (state.durationSeconds * 1000).toLong()
        val optimistic = clampMediaSeek(livePositionMilliseconds() + deltaMilliseconds, duration)
        when (activeBook?.source) {
            AudiobookSource.Libby -> LibbyBridge.seekBy(deltaMilliseconds)
            AudiobookSource.Local, AudiobookSource.Rss -> LocalPlaybackController.seekBy(deltaMilliseconds)
            null -> return
        }
        state = state.copy(positionSeconds = optimistic / 1000.0)
        publish()
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = dispatch("play") {
            when (activeBook?.source) {
                AudiobookSource.Libby -> {
                    LocalPlaybackController.pause()
                    LibbyBridge.play()
                }
                AudiobookSource.Local, AudiobookSource.Rss -> {
                    LibbyBridge.pause()
                    LocalPlaybackController.play()
                }
                null -> Unit
            }
            state = state.copy(isPlaying = true)
            publish()
            scheduleLibbyPoll()
        }

        override fun onPause() = dispatch("pause") {
            when (activeBook?.source) {
                AudiobookSource.Libby -> LibbyBridge.pause()
                AudiobookSource.Local, AudiobookSource.Rss -> LocalPlaybackController.pause()
                null -> Unit
            }
            state = state.copy(isPlaying = false)
            publish()
            scheduleLibbyPoll()
        }

        override fun onStop() = dispatch("stop") {
            when (activeBook?.source) {
                AudiobookSource.Libby -> LibbyBridge.pause()
                AudiobookSource.Local, AudiobookSource.Rss -> LocalPlaybackController.pause()
                null -> Unit
            }
            state = state.copy(isPlaying = false, readiness = PlayerReadiness.Unavailable)
            publish(updatePlaybackHost = false)
            session.isActive = false
            LibbyPlaybackForegroundService.stopAndRemove(appContext)
        }

        override fun onSeekTo(pos: Long) = dispatch("seek:$pos") { seekTo(pos) }

        override fun onFastForward() = dispatch("fast-forward") { skipBy(MEDIA_FAST_FORWARD_MILLISECONDS) }

        override fun onRewind() = dispatch("rewind") { skipBy(-MEDIA_REWIND_MILLISECONDS) }

        // Bard has no chapter queue yet. Lock screens, LightOS and headsets usually only offer
        // previous/next, so they skip back/forward 30 seconds instead of changing books.
        override fun onSkipToNext() = dispatch("next") { skipBy(MEDIA_FAST_FORWARD_MILLISECONDS) }

        override fun onSkipToPrevious() = dispatch("previous") { skipBy(-MEDIA_REWIND_MILLISECONDS) }
    }

    data class NotificationSnapshot(
        val title: String,
        val subtitle: String?,
        val isPlaying: Boolean,
        val artwork: Bitmap? = null,
    )
}
