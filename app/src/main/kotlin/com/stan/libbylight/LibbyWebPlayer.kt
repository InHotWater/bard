package com.stan.libbylight

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import org.json.JSONArray
import org.json.JSONObject
import com.stan.libbylight.library.LoanItem
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LibbyWebPlayer"
private const val LIBBY_ROOT = "https://libbyapp.com/"
private const val LIBBY_SHELF = "https://libbyapp.com/shelf"

/**
 * Application-scoped Libby browser session.
 *
 * Bard keeps this WebView mounted behind its native Light-style UI so Libby's
 * session and playback remain persistent without exposing Libby's interface.
 */
object LibbyWebPlayer {

    private var webView: WebView? = null
    private var applicationContext: Context? = null
    private val mutableRendererFailed = MutableStateFlow(false)
    val rendererFailed: StateFlow<Boolean> = mutableRendererFailed.asStateFlow()

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        if (webView != null) return
        applicationContext = context.applicationContext

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        val contextWrapper = MutableContextWrapper(context.applicationContext)
        val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(isDebugBuild)

        webView = WebView(contextWrapper).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true

            cookies.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    mutableRendererFailed.value = false
                    Log.d(TAG, "onPageFinished: ${safeRouteName(url)}")
                    CookieManager.getInstance().flush()
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.e(TAG, "Libby renderer disconnected crashed=${detail.didCrash()}")
                    mutableRendererFailed.value = true
                    (view.parent as? ViewGroup)?.removeView(view)
                    webView = null
                    LibbyBridge.resetForRecreatedWebView()
                    view.destroy()
                    Handler(Looper.getMainLooper()).post {
                        applicationContext?.let(::init)
                        loadLoginPage()
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    // Do not forward Libby's console text or source URLs to Logcat; they may
                    // contain authentication or setup details.
                    return true
                }
            }

            // Install the cross-origin player-frame bridge before any Libby URL loads.
            LibbyBridge.install(this)
        }
    }

    fun attachToActivity(activityContext: Context): WebView {
        val current = requireWebView()
        (current.context as? MutableContextWrapper)?.baseContext = activityContext
        current.requestFocus()
        return current
    }

    fun requireWebView(): WebView =
        webView ?: error("LibbyWebPlayer.init(context) must be called before use")

    fun detachFromCurrentParent() {
        val current = webView ?: return
        (current.parent as? ViewGroup)?.removeView(current)
        applicationContext?.let { appContext ->
            (current.context as? MutableContextWrapper)?.baseContext = appContext
        }
    }

    /** Uptime of the last full Libby page load (boot = Libby re-syncs loans with OverDrive). */
    @Volatile
    private var lastLibbyBootAtMillis = 0L

    fun shelfAgeMillis(): Long =
        if (lastLibbyBootAtMillis == 0L) Long.MAX_VALUE
        else android.os.SystemClock.elapsedRealtime() - lastLibbyBootAtMillis

    private fun markLibbyBoot() {
        lastLibbyBootAtMillis = android.os.SystemClock.elapsedRealtime()
    }

    fun loadLoginPage() {
        markLibbyBoot()
        requireWebView().loadUrl(LIBBY_ROOT)
    }

    /** Clears only this app's local WebView-backed Libby session. */
    fun clearLocalLibbySession(onComplete: () -> Unit) {
        LibbyBridge.stopPlaybackHost()
        val current = requireWebView()
        val clearPageStorage = """
            (() => {
              try { localStorage.clear(); } catch (_) {}
              try { sessionStorage.clear(); } catch (_) {}
              try {
                if (window.caches) {
                  caches.keys().then(keys => keys.forEach(key => caches.delete(key)));
                }
              } catch (_) {}
              try {
                if (indexedDB.databases) {
                  indexedDB.databases().then(databases => databases.forEach(database => {
                    if (database.name) indexedDB.deleteDatabase(database.name);
                  }));
                }
              } catch (_) {}
              return true;
            })();
        """.trimIndent()

        current.evaluateJavascript(clearPageStorage) {
            WebStorage.getInstance().deleteAllData()
            current.clearCache(true)
            current.clearHistory()
            current.clearFormData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                current.loadUrl(LIBBY_ROOT)
                onComplete()
            }
        }
    }

    fun loadLibraryPage() {
        markLibbyBoot()
        requireWebView().loadUrl(LIBBY_SHELF)
    }

    /**
     * Re-boots Libby's web app on the shelf so it syncs loans with OverDrive again, without
     * touching cookies or storage (no new setup code needed). Libby is a single-page app that
     * Bard keeps alive for hours; merely re-reading the DOM of an already-open shelf never picks
     * up loans borrowed elsewhere. Callers must not do this while a Libby book is playing: the
     * same WebView hosts the Libby player.
     */
    fun refreshLibraryPage() {
        val current = requireWebView()
        markLibbyBoot()
        val route = current.url.orEmpty().substringBefore('?').substringBefore('#')
        if (route.startsWith(LIBBY_SHELF)) {
            current.reload()
        } else {
            current.loadUrl(LIBBY_SHELF)
        }
    }

    fun openLoan(loanUrl: String) {
        require(loanUrl.startsWith("https://libbyapp.com/open/loan/")) {
            "Expected a Libby loan URL"
        }
        LibbyBridge.prepareForLoanOpen()
        requireWebView().apply {
            val currentRoute = url.orEmpty().substringBefore('?').substringBefore('#')
            val requestedRoute = loanUrl.substringBefore('?').substringBefore('#')
            if (currentRoute == requestedRoute) {
                reload()
            } else {
                loadUrl(loanUrl)
            }
        }
    }


    fun scrapeAudiobookLoans(onResult: (List<LoanItem>) -> Unit) {
        val script = """
            (() => {
                const tiles = Array.from(document.querySelectorAll(
                    '.title-tile.data-tile-class_loan.data-title-tile-format_audiobook'
                ));

                const loans = tiles.map(tile => {
                    const classText = tile.className || '';
                    const titleIdMatch = classText.match(/data-title_(\d+)/);
                    const titleId = titleIdMatch ? titleIdMatch[1] : '';
                    const returnLink = tile.querySelector(
                        'a[href*="/shelf/loans/"][href$="/return"]'
                    );
                    const returnHref = returnLink ? returnLink.getAttribute('href') || '' : '';
                    const loanMatch = returnHref.match(/\/shelf\/loans\/(\d+)-(\d+)\/return/);
                    const cardId = loanMatch ? loanMatch[1] : '';
                    const resolvedTitleId = loanMatch ? loanMatch[2] : titleId;
                    const title = (tile.querySelector('.title-tile-title')?.textContent || '').trim();
                    const author = (tile.querySelector('.title-tile-author')?.textContent || '').trim();
                    const cover = tile.querySelector('img[data-cover-id]')?.src || '';
                    const due = (tile.querySelector('a[href$="/return"] .title-tile-journey-link')?.textContent || '').trim();
                    const progress = (tile.querySelector('.title-tile-journey-percent')?.textContent || '').trim();
                    const loanUrl = cardId && resolvedTitleId
                        ? 'https://libbyapp.com/open/loan/' + cardId + '/' + resolvedTitleId
                        : '';

                    return { title, author, loanUrl, coverUrl: cover, dueText: due, progressText: progress };
                }).filter(item => item.title && item.loanUrl);

                return JSON.stringify(loans);
            })();
        """.trimIndent()

        requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript onResult(emptyList())
                val array = JSONArray(decoded)
                val items = buildList {
                    for (index in 0 until array.length()) {
                        val json = array.getJSONObject(index)
                        add(
                            LoanItem(
                                title = json.optString("title"),
                                author = json.optString("author"),
                                loanUrl = json.optString("loanUrl"),
                                coverUrl = json.optString("coverUrl"),
                                dueText = json.optString("dueText"),
                                progressText = json.optString("progressText"),
                            )
                        )
                    }
                }
                Log.d(TAG, "scrapeAudiobookLoans: ${items.size} loans")
                onResult(items)
            } catch (_: Exception) {
                Log.e(TAG, "scrapeAudiobookLoans failed")
                onResult(emptyList())
            }
        }
    }

    /** Basic Step-1 bridge diagnostic for Logcat. */
    fun ping(onResult: (String?) -> Unit) {
        val script = """
            (() => JSON.stringify({
                title: document.title,
                url: window.location.href,
                readyState: document.readyState
            }))();
        """.trimIndent()
        requireWebView().evaluateJavascript(script) { raw ->
            onResult(decodeJavascriptString(raw))
        }
    }

    /**
     * Libby does not expose a single stable login cookie. For this prototype,
     * reaching a normal Libby app route after initial setup counts as an
     * authenticated/initialized session. The page remains hidden behind Bard's
     * native UI during connection and playback.
     */
    fun checkIsLoggedIn(onResult: (Boolean) -> Unit) {
        val script = """
            (() => JSON.stringify({
                url: window.location.href,
                title: document.title,
                ready: document.documentElement.classList.contains('data-client_ready'),
                realm: Array.from(document.documentElement.classList)
                    .some(c => c === 'data-realm_shelf' || c === 'data-realm_open')
            }))();
        """.trimIndent()

        requireWebView().evaluateJavascript(script) { raw ->
            try {
                val decoded = decodeJavascriptString(raw) ?: return@evaluateJavascript onResult(false)
                val json = JSONObject(decoded)
                val url = json.optString("url")
                val initialized = json.optBoolean("ready") &&
                    (json.optBoolean("realm") || url.contains("/shelf") || url.contains("/open/loan/"))
                Log.d(TAG, "checkIsLoggedIn: route=${safeRouteName(url)} initialized=$initialized")
                onResult(initialized)
            } catch (_: Exception) {
                Log.e(TAG, "checkIsLoggedIn failed")
                onResult(false)
            }
        }
    }

    suspend fun checkIsLoggedInSuspend(): Boolean =
        kotlin.coroutines.suspendCoroutine { continuation ->
            checkIsLoggedIn { result -> continuation.resume(result) }
        }

    private fun decodeJavascriptString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            // evaluateJavascript returns a JSON-encoded JavaScript string.
            org.json.JSONTokener(raw).nextValue() as? String
        } catch (_: Exception) {
            raw.removeSurrounding("\"").replace("\\\"", "\"")
        }
    }

    private fun safeRouteName(url: String): String = when {
        url.contains("/open/loan/") -> "open-loan"
        url.contains("/shelf") -> "shelf"
        url == LIBBY_ROOT || url.substringBefore('?') == LIBBY_ROOT -> "root"
        else -> "libby-app"
    }
}
