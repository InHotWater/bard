package com.stan.libbylight.player

// Lock screen, notification, Bluetooth and LightOS transport controls skip by the same
// audiobook-friendly interval in both directions. 15 seconds matches Bard's in-app
// rewind/forward buttons and Libby's own skip buttons.
internal const val MEDIA_FAST_FORWARD_MILLISECONDS = 15_000L
internal const val MEDIA_REWIND_MILLISECONDS = 15_000L

internal fun clampMediaSeek(positionMilliseconds: Long, durationMilliseconds: Long): Long {
    val upperBound = durationMilliseconds.takeIf { it > 0 } ?: Long.MAX_VALUE
    return positionMilliseconds.coerceIn(0L, upperBound)
}

internal fun mediaFastForwardTarget(positionMilliseconds: Long, durationMilliseconds: Long): Long =
    clampMediaSeek(positionMilliseconds + MEDIA_FAST_FORWARD_MILLISECONDS, durationMilliseconds)

internal fun mediaRewindTarget(positionMilliseconds: Long, durationMilliseconds: Long): Long =
    clampMediaSeek(positionMilliseconds - MEDIA_REWIND_MILLISECONDS, durationMilliseconds)

/**
 * "Previous" on a lock screen or headset is a rewind for audiobooks. Restarting the whole book
 * (the music-player convention) would lose the listener's place, so it never jumps to zero.
 */
internal fun mediaPreviousTarget(positionMilliseconds: Long, durationMilliseconds: Long): Long =
    mediaRewindTarget(positionMilliseconds, durationMilliseconds)

internal data class SafeMediaMetadata(
    val title: String,
    val subtitle: String?,
)

internal fun safeMediaMetadata(title: String, author: String): SafeMediaMetadata =
    SafeMediaMetadata(
        title = title.ifBlank { "Bard" },
        subtitle = author.takeIf { it.isNotBlank() },
    )

internal class MediaCommandDeduplicator(
    private val nowMilliseconds: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val windowMilliseconds: Long = 250L,
) {
    private var lastKey: String? = null
    private var lastAt = Long.MIN_VALUE

    fun shouldDispatch(key: String): Boolean {
        val now = nowMilliseconds()
        val duplicate = key == lastKey && now - lastAt in 0..windowMilliseconds
        lastKey = key
        lastAt = now
        return !duplicate
    }
}
