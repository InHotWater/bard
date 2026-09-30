package com.stan.libbylight.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.stan.libbylight.library.Audiobook
import com.stan.libbylight.library.AudiobookSource
import com.stan.libbylight.library.RssDownloadManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Loads a small cover bitmap for system media surfaces only (lock screen, notification,
 * Bluetooth). Bard's own UI never shows cover art.
 *
 * - Libby: the loan tile's cover image URL scraped from the shelf.
 * - Local: the embedded picture of the first audio part.
 * - RSS: the embedded picture of a verified downloaded file (streams are not probed).
 *
 * Never logs URLs or paths.
 */
internal object MediaArtworkLoader {
    private const val TAG = "BardArtwork"
    private const val MAX_EDGE_PX = 512
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val misses = mutableSetOf<String>()

    fun key(book: Audiobook): String = "${book.source}:${book.id}"

    fun cached(book: Audiobook): Bitmap? = cache.get(key(book))

    /** Calls [onLoaded] on the main thread when a bitmap becomes available. */
    fun load(context: Context, book: Audiobook, onLoaded: (String, Bitmap) -> Unit) {
        val key = key(book)
        cache.get(key)?.let { return onLoaded(key, it) }
        // A Libby book restored before the shelf loads has no cover URL yet; retry once it does.
        val missKey = "$key|${book.coverUrl}"
        synchronized(misses) { if (missKey in misses) return }
        val appContext = context.applicationContext
        executor.execute {
            val bitmap = runCatching { decode(appContext, book) }.getOrNull()
            if (bitmap == null) {
                synchronized(misses) { misses += missKey }
                return@execute
            }
            cache.put(key, bitmap)
            mainHandler.post { onLoaded(key, bitmap) }
        }
    }

    private fun decode(context: Context, book: Audiobook): Bitmap? = when (book.source) {
        AudiobookSource.Libby -> book.coverUrl.takeIf { it.startsWith("https://") }?.let(::download)
        AudiobookSource.Local -> embedded(
            context,
            book.parts.firstOrNull()?.playbackReference ?: book.playbackReference,
        )
        AudiobookSource.Rss -> RssDownloadManager.verifiedLocalReference(book)?.let { embedded(context, it) }
    }

    private fun embedded(context: Context, reference: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(reference)
            if (uri.scheme.isNullOrBlank() || uri.scheme == "file") {
                retriever.setDataSource(uri.path ?: reference)
            } else if (uri.scheme == "content") {
                retriever.setDataSource(context, uri)
            } else {
                return null
            }
            retriever.embeddedPicture?.let(::decodeScaled)
        } catch (_: Exception) {
            Log.d(TAG, "no embedded artwork")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun download(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            decodeScaled(bytes)
        } catch (_: Exception) {
            Log.d(TAG, "cover download failed")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE_PX && bounds.outHeight / (sample * 2) >= MAX_EDGE_PX) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= MAX_EDGE_PX) return decoded
        val scale = MAX_EDGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
