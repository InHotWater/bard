package com.stan.libbylight

import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.stan.libbylight.player.PlayerState
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val BRIDGE_TAG = "LibbyBridge"
private const val JS_OBJECT_NAME = "LibbyLightNative"

/** Native <-> JavaScript bridge for Libby's cross-origin audiobook frame. */
object LibbyBridge {

    private val allowedPlayerOrigins = setOf(
        "https://bflat.listen.libbyapp.com",
        "https://*.listen.libbyapp.com",
        "https://*.listen.overdrive.com",
    )

    private var activeReplyProxy: JavaScriptReplyProxy? = null
    private var activeFrameScore = -1
    private var installed = false
    private var capabilityDiagnostic = "Frame bridge not initialized"
    private var applicationContext: android.content.Context? = null

    private val pendingStateCallbacks =
        ConcurrentHashMap<String, (PlayerState) -> Unit>()

    private var currentTopLevelUrl: String = ""

    /**
     * Libby creates a brand-new cross-origin player iframe for each audiobook.
     * Any reply proxy from the previous loan becomes stale, even when the new
     * iframe has the same control score. Reset selection whenever the top-level
     * Libby route changes so the new frame can register itself.
     */
    fun onTopLevelUrlChanged(url: String) {
        if (url == currentTopLevelUrl) return

        val previousUrl = currentTopLevelUrl
        currentTopLevelUrl = url

        val previousLoan = loanRoute(previousUrl)
        val nextLoan = loanRoute(url)
        val leftPlayer = previousLoan != null && nextLoan == null

        // A new player iframe may announce itself before the top-level route
        // observer notices shelf -> loan. Do not clear that fresh connection.
        val changedLoan =
            previousLoan != null &&
            nextLoan != null &&
            previousLoan != nextLoan

        if (leftPlayer || changedLoan) {
            updatePlaybackHost(false)
            Log.d(
                BRIDGE_TAG,
                "Invalidating player frame: leftPlayer=$leftPlayer changedLoan=$changedLoan",
            )
            activeReplyProxy = null
            activeFrameScore = -1

            val waiting = pendingStateCallbacks.values.toList()
            pendingStateCallbacks.clear()
            waiting.forEach { callback ->
                callback(
                    PlayerState(
                        diagnostic = "Waiting for the newly opened audiobook player…",
                    ),
                )
            }
        }
    }

    private fun loanRoute(url: String): String? {
        val match = Regex("/open/loan/\\d+/\\d+").find(url) ?: return null
        return match.value
    }

    /** Self-contained because it executes inside each matching iframe. */
    private val playerFrameScript = """
        (() => {
          if (window.__libbyLightFrameBridgeInstalled) return;
          window.__libbyLightFrameBridgeInstalled = true;

          const nativeBridge = window.$JS_OBJECT_NAME;
          const post = (payload) => {
            try { nativeBridge.postMessage(JSON.stringify(payload)); } catch (_) {}
          };

          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const text = (el) => clean(el && el.textContent);
          const aria = (el) => clean(el && el.getAttribute && el.getAttribute('aria-label'));
          const buttons = () => Array.from(document.querySelectorAll('button'));

          const findExactAria = (...labels) => {
            const wanted = labels.map((value) => value.toLowerCase());
            return buttons().find((button) => wanted.includes(aria(button).toLowerCase())) || null;
          };

          const findAriaPattern = (pattern) =>
            buttons().find((button) => pattern.test(aria(button))) || null;

          const getPlayButton = () => findExactAria('Play');
          const getPauseButton = () => findExactAria('Pause');
          const getForwardButton = () =>
            findExactAria('Advance 15 seconds', 'Forward 15 seconds', 'Skip forward 15 seconds') ||
            findAriaPattern(/^(advance|forward|skip forward)\s+15\s+seconds?\.?$/i);
          const getBackButton = () =>
            findExactAria(
              'Rewind 15 seconds',
              'Go back 15 seconds',
              'Back 15 seconds',
              'Skip back 15 seconds',
              'Skip backward 15 seconds'
            ) || findAriaPattern(/^(rewind|go back|back|skip back(?:ward)?)\s+15\s+seconds?\.?$/i);
          const getSpeedButton = () =>
            document.querySelector('button.playback-rate-button') ||
            document.querySelector('.playback-rate button') ||
            findExactAria('Playback speed', 'Change playback speed') ||
            findAriaPattern(/^playback speed(?:\.|,|:|\s|$)/i);

          const parseTime = (value) => {
            const parts = clean(value).replace(/^-/, '').split(':').map(Number);
            if (!parts.length || parts.some(Number.isNaN)) return 0;
            if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
            if (parts.length === 2) return parts[0] * 60 + parts[1];
            return 0;
          };

          const getTimes = () => {
            const clocks = document.querySelector('.timeline-clocks');
            if (!clocks) return { current: 0, remaining: 0, total: 0 };
            let current = 0;
            let remaining = 0;
            for (const el of clocks.querySelectorAll('*')) {
              const value = text(el);
              const match = value.match(/(\d+:\d+(?::\d+)?)/);
              if (!match) continue;
              if (/elapsed/i.test(value)) current = parseTime(match[1]);
              if (/remaining/i.test(value)) remaining = parseTime(match[1]);
            }
            const full = text(clocks);
            if (!remaining) {
              const match = full.match(/-\s*(\d+:\d+(?::\d+)?)/);
              if (match) remaining = parseTime(match[1]);
            }
            if (!current) {
              const all = full.match(/\d+:\d+(?::\d+)?/g) || [];
              if (all.length) current = parseTime(all[0]);
            }
            return { current, remaining, total: current + remaining };
          };

          const getSpool = () => {
            try {
              return (
                typeof BIF !== 'undefined' &&
                BIF.objects &&
                BIF.objects.spool
              ) || null;
            } catch (_) {
              return null;
            }
          };

          const speedFromPage = () => {
            const spool = getSpool();
            const value = Number(spool && spool.playbackRate);
            return Number.isFinite(value) ? value : 1;
          };

          const isSpoolPlaying = () => {
            const spool = getSpool();
            if (!spool) return Boolean(getPauseButton());

            const raw = spool.state;

            if (typeof raw === 'boolean') return raw;

            if (raw && typeof raw === 'object') {
              if (typeof raw.isPlaying === 'boolean') return raw.isPlaying;
              if (typeof raw.playing === 'boolean') return raw.playing;

              const nested =
                raw.state ??
                raw.status ??
                raw.name ??
                raw.value;

              if (nested !== undefined) {
                const value = String(nested).toLowerCase();
                if (/pause|idle|stop|end|ready/.test(value)) return false;
                if (/play|buffer/.test(value)) return true;
              }
            }

            const value = String(raw ?? '').toLowerCase();
            if (/pause|idle|stop|end|ready/.test(value)) return false;
            if (/play|buffer/.test(value)) return true;

            return Boolean(getPauseButton());
          };

          const chapterFromPage = () => {
            const selectors = [
              '.chapter-title',
              '[class*="chapter-title"]',
              '[aria-current="true"]',
              '[data-current="true"]'
            ];
            for (const selector of selectors) {
              const value = text(document.querySelector(selector));
              if (value && value.length < 160) return value;
            }
            return '';
          };

          const inventory = () => buttons().map((button, index) => ({
            index,
            aria: aria(button),
            text: text(button).slice(0, 120),
            disabled: Boolean(button.disabled)
          })).filter((item) => item.aria || item.text);

          const frameScore = () => {
            let score = 0;
            if (document.querySelector('.timeline-clocks')) score += 10;
            if (document.querySelector('.seekometer')) score += 8;
            if (getPlayButton() || getPauseButton()) score += 6;
            if (getForwardButton()) score += 4;
            if (getBackButton()) score += 4;
            if (getSpeedButton()) score += 2;
            return score;
          };

          const state = () => {
            const times = getTimes();
            const play = getPlayButton();
            const pause = getPauseButton();
            const forward = getForwardButton();
            const back = getBackButton();
            const speed = getSpeedButton();
            const score = frameScore();
            const controlsFound = score >= 10;
            const heading = text(document.querySelector('h1')) ||
              text(document.querySelector('[role="heading"]'));
            return {
              title: heading || document.title || 'Libby',
              chapter: chapterFromPage(),
              positionSeconds: times.current,
              durationSeconds: times.total,
              isPlaying: isSpoolPlaying(),
              playbackSpeed: speedFromPage(),
              controlsFound,
              frameScore: score,
              pageUrl: location.href,
              controlLabels: {
                play: aria(play),
                pause: aria(pause),
                forward: aria(forward),
                back: aria(back),
                speed: aria(speed)
              },
              diagnostic: controlsFound
                ? 'Active player frame selected.'
                : 'Libby frame connected; player controls not present here.'
            };
          };

          const click = (button) => {
            if (!button || button.disabled) return false;
            button.focus();
            button.click();
            return true;
          };

          const setSpeed = (speed) => {
            const allowed = [1, 1.25, 1.5, 1.75, 2];
            const wanted = Number(speed);

            if (!allowed.some((value) =>
              Math.abs(value - wanted) < 0.001
            )) {
              return false;
            }

            try {
              if (
                !window.BIF ||
                !BIF.objects ||
                !BIF.objects.spool
              ) {
                return false;
              }

              const spool = BIF.objects.spool;
              if (typeof spool._setPlaybackRate !== 'function') {
                return false;
              }

              spool._setPlaybackRate(wanted);

              window.setTimeout(() => {
                const actualSpeed = speedFromPage();
                post({
                  type: 'speedSelected',
                  speed: wanted,
                  actualSpeed,
                  direct: true,
                  label: 'BIF.objects.spool._setPlaybackRate'
                });
              }, 150);

              return true;
            } catch (error) {
              post({
                type: 'error',
                message: 'Direct speed change failed: ' + String(error)
              });
              return false;
            }
          };

          const seekTo = (positionMilliseconds) => {
            const spool = getSpool();
            const requested = Number(positionMilliseconds);
            if (!spool || !Number.isFinite(requested)) return false;

            // The native UI speaks in absolute book milliseconds. Libby's
            // seekWithinBook maps that value across spool.components/focus.
            const timelineDuration = getTimes().total * 1000;
            const target = Math.max(
              0,
              timelineDuration > 0
                ? Math.min(requested, timelineDuration)
                : requested
            );

            if (typeof spool.seekWithinBook === 'function') {
              spool.seekWithinBook(target);
              return true;
            }
            if (typeof spool.seek === 'function') {
              spool.seek(target);
              return true;
            }
            return false;
          };

          nativeBridge.onmessage = (event) => {
            let request;
            try { request = JSON.parse(event.data); }
            catch (_) { return; }

            const id = request.id || '';
            let result = false;
            try {
              switch (request.command) {
                case 'state':
                  post({ type: 'state', id, state: state() });
                  return;
                case 'inventory':
                  post({ type: 'inventory', id, url: location.href, score: frameScore(), buttons: inventory() });
                  return;
                case 'play': {
                  const spool = getSpool();
                  if (
                    spool &&
                    typeof spool.toggle === 'function'
                  ) {
                    if (!isSpoolPlaying()) spool.toggle();
                    result = true;
                  } else {
                    result = click(getPlayButton());
                  }
                  break;
                }

                case 'pause': {
                  const spool = getSpool();
                  if (
                    spool &&
                    typeof spool.toggle === 'function'
                  ) {
                    if (isSpoolPlaying()) spool.toggle();
                    result = true;
                  } else {
                    result = click(getPauseButton());
                  }
                  break;
                }

                case 'forward15': {
                  const spool = getSpool();
                  if (spool && typeof spool.seekBy === 'function') {
                    spool.seekBy(15000);
                    result = true;
                  } else {
                    result = click(getForwardButton());
                  }
                  break;
                }

                case 'back15': {
                  const spool = getSpool();
                  if (spool && typeof spool.seekBy === 'function') {
                    spool.seekBy(-15000);
                    result = true;
                  } else {
                    result = click(getBackButton());
                  }
                  break;
                }

                case 'seekTo':
                  result = seekTo(request.positionMilliseconds);
                  break;

                case 'seekBy': {
                  // Relative seek used by lock screen / media-button skips so the jump is
                  // measured from Libby's live position rather than a stale native snapshot.
                  const spool = getSpool();
                  const delta = Number(request.deltaMilliseconds);
                  if (!Number.isFinite(delta) || delta === 0) break;
                  if (spool && typeof spool.seekBy === 'function') {
                    spool.seekBy(delta);
                    result = true;
                  } else {
                    const button = delta > 0 ? getForwardButton() : getBackButton();
                    const presses = Math.max(1, Math.round(Math.abs(delta) / 15000));
                    for (let i = 0; i < presses; i++) result = click(button) || result;
                  }
                  break;
                }

                case 'setSpeed':
                  result = setSpeed(request.speed);
                  break;
              }
              window.setTimeout(() => {
                post({ type: 'commandResult', id, command: request.command, ok: result, state: state() });
              }, 180);
            } catch (error) {
              post({ type: 'error', id, message: String(error) });
            }
          };

          const announce = () => post({
            type: 'ready',
            state: state(),
            inventory: inventory()
          });
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', announce, { once: true });
          } else {
            announce();
          }
          window.setTimeout(announce, 1000);
          window.setTimeout(announce, 3000);
        })();
    """.trimIndent()

    /** Must be called before the first page is loaded. */
    fun install(webView: WebView) {
        if (installed) return
        applicationContext = webView.context.applicationContext

        val messageSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val scriptSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        capabilityDiagnostic =
            "Frame injection: documentStart=$scriptSupported messages=$messageSupported"
        Log.d(BRIDGE_TAG, capabilityDiagnostic)

        if (!messageSupported || !scriptSupported) return

        WebViewCompat.addWebMessageListener(
            webView,
            JS_OBJECT_NAME,
            allowedPlayerOrigins,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy,
                ) {
                    handleFrameMessage(
                        raw = message.data,
                        sourceOrigin = sourceOrigin,
                        isMainFrame = isMainFrame,
                        replyProxy = replyProxy,
                    )
                }
            },
        )

        WebViewCompat.addDocumentStartJavaScript(
            webView,
            playerFrameScript,
            allowedPlayerOrigins,
        )

        installed = true
        Log.d(BRIDGE_TAG, "Frame bridge installed")
    }

    fun getPlayerState(onResult: (PlayerState) -> Unit) {
        val proxy = activeReplyProxy
        if (proxy == null) {
            onResult(PlayerState(diagnostic = "$capabilityDiagnostic; waiting for active player frame"))
            return
        }

        val id = UUID.randomUUID().toString()
        pendingStateCallbacks[id] = onResult
        proxy.postMessage(
            JSONObject().put("id", id).put("command", "state").toString(),
        )
    }

    fun hasActivePlayerFrame(): Boolean = activeReplyProxy != null && activeFrameScore >= 0

    /**
     * An explicit loan open may target the same top-level URL after another source was active.
     * In that case no URL-change callback occurs, so retire the old iframe proxy before reloading.
     */
    fun prepareForLoanOpen() {
        updatePlaybackHost(false)
        activeReplyProxy = null
        activeFrameScore = -1
        val waiting = pendingStateCallbacks.values.toList()
        pendingStateCallbacks.clear()
        waiting.forEach { callback ->
            callback(PlayerState(diagnostic = "Reconnecting to Libby player"))
        }
        capabilityDiagnostic = "Waiting for active Libby player frame"
        Log.d(BRIDGE_TAG, "player frame invalidated for explicit loan open")
    }

    fun play() = command("play")
    fun pause() = command("pause")
    fun forward15() = command("forward15")
    fun back15() = command("back15")

    /** Seeks to an absolute position in the book without exposing Libby internals to the UI. */
    fun seekTo(positionMilliseconds: Long) {
        command(
            "seekTo",
            JSONObject().put("positionMilliseconds", positionMilliseconds.coerceAtLeast(0)),
        )
    }

    /** Relative seek measured from Libby's live position (used by system media controls). */
    fun seekBy(deltaMilliseconds: Long) {
        if (deltaMilliseconds == 0L) return
        command("seekBy", JSONObject().put("deltaMilliseconds", deltaMilliseconds))
    }

    fun setSpeed(speed: Double) {
        command("setSpeed", JSONObject().put("speed", speed))
    }

    fun logControlInventory() = command("inventory")

    private fun command(name: String, extras: JSONObject = JSONObject()) {
        val proxy = activeReplyProxy
        if (proxy == null) {
            Log.w(BRIDGE_TAG, "$name ignored: active player frame is not connected")
            return
        }
        val id = UUID.randomUUID().toString()
        proxy.postMessage(extras.put("id", id).put("command", name).toString())
        Log.d(BRIDGE_TAG, "sent $name to frameScore=$activeFrameScore")
    }

    private fun handleFrameMessage(
        raw: String?,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (raw.isNullOrBlank()) return
        try {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "ready" -> {
                    val state = json.optJSONObject("state") ?: JSONObject()
                    val score = state.optInt("frameScore", 0)
                    val labels = state.optJSONObject("controlLabels")
                    Log.d(
                        BRIDGE_TAG,
                        "Frame ready main=$isMainFrame score=$score labelsAvailable=${labels != null}",
                    )
                    if (score > activeFrameScore) {
                        activeFrameScore = score
                        activeReplyProxy = replyProxy
                        Log.d(BRIDGE_TAG, "Selected active player frame score=$score")
                    }
                }

                "state" -> {
                    val id = json.optString("id")
                    val callback = pendingStateCallbacks.remove(id) ?: return
                    val playerState = PlayerState.fromJson(json.optJSONObject("state") ?: JSONObject())
                    updatePlaybackHost(playerState.isPlaying)
                    callback(playerState)
                }

                "commandResult" -> {
                    val state = json.optJSONObject("state")
                    state?.let { updatePlaybackHost(it.optBoolean("isPlaying", false)) }
                    Log.d(
                        BRIDGE_TAG,
                        "${json.optString("command")} -> ${json.optBoolean("ok")} " +
                            "playing=${state?.optBoolean("isPlaying")}",
                    )
                }

                "inventory" -> Log.d(
                    BRIDGE_TAG,
                    "Inventory received score=${json.optInt("score")} " +
                        "buttonCount=${json.optJSONArray("buttons")?.length() ?: 0}",
                )

                "speedSelected" -> Log.d(
                    BRIDGE_TAG,
                    "Speed selected requested=${json.optDouble("speed")} " +
                        "actual=${json.optDouble("actualSpeed")} " +
                        "direct=${json.optBoolean("direct")}",
                )

                "speedOptionMissing" -> Log.w(
                    BRIDGE_TAG,
                    "Speed option missing ${json.optDouble("speed")}",
                )

                "error" -> {
                    updatePlaybackHost(false)
                    Log.e(BRIDGE_TAG, "Frame reported an error")
                }
            }
        } catch (_: Exception) {
            Log.e(BRIDGE_TAG, "Could not parse frame message")
        }
    }

    fun stopPlaybackHost() = updatePlaybackHost(false)

    fun resetForRecreatedWebView() {
        stopPlaybackHost()
        installed = false
        activeReplyProxy = null
        activeFrameScore = -1
        pendingStateCallbacks.clear()
        applicationContext = null
        capabilityDiagnostic = "Frame bridge not initialized"
    }

    private fun updatePlaybackHost(isPlaying: Boolean) {
        com.stan.libbylight.player.BardMediaSessionManager.onLibbyPlayingChanged(isPlaying)
    }

}
