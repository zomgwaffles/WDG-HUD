package com.example.wdghud

import android.content.Context
import android.app.Activity
import android.widget.FrameLayout
import android.view.ViewGroup
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.wdghud.geometry.GridDefinition
import com.example.wdghud.geometry.SectorApiPreloadCoordinator
import com.example.wdghud.geometry.SectorGeometry
import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.SectorPreloadCache
import com.example.wdghud.geometry.SectorWindowPlanner
import com.example.wdghud.geometry.WdgWarsGrid
import org.json.JSONObject

/**
 * E5.17 G3C4: authenticated foreground-only 25-sector prefetch (full 5x5).
 * Uses the existing website session (not the API key). Never transmits BLE scan commands.
 * Cancels when the Activity becomes hidden so its service can own the watch/cache.
 */
internal class SectorFiveByFivePrefetch(private val context: Context) {
    private val requiredCount = 25
    // One canonical grid source: never give the lookahead planner an independent
    // 0.02 x 0.02 guess while GPS uses WdgWarsGrid.sectorAt/queryPoint.
    private val geometry = run {
        val origin = WdgWarsGrid.queryPoint(SectorId(0, 0))
        val north = WdgWarsGrid.queryPoint(SectorId(1, 0))
        val east = WdgWarsGrid.queryPoint(SectorId(0, 1))
        val northStep = north.first - origin.first
        val eastStep = east.second - origin.second
        require(northStep > 0.0 && eastStep > 0.0 &&
                northStep.isFinite() && eastStep.isFinite()) {
            "Invalid canonical WDGWars grid spacing"
        }
        SectorGeometry(GridDefinition(0.0, 0.0, northStep, eastStep))
    }
    private val staging = SectorPreloadCache(SectorWindowPlanner(
        geometry,
        ttlMs = 30L * 60L * 1000L,
        dynamicTtlMs = { StaleIntervalSettings.ttlMs(context) }
    ), ::validDetail)
    private val coordinator = SectorApiPreloadCoordinator(staging, WdgWarsGrid::queryPoint)
    private var webView: WebView? = null
    private var webViewHost: ViewGroup? = null
    private var pending: SectorApiPreloadCoordinator.ApiRequest? = null
    private var running = false
    private var callbacks: ((Map<SectorId, SectorPreloadCache.Record>) -> Unit)? = null
    private var onEach: ((SectorId, SectorPreloadCache.Record) -> Unit)? = null
    private var requestsAttempted = 0
    private var requestsAccepted = 0
    private var requestsFailed = 0
    private var startedAtMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val firstJsTimeoutMs = 12_000L
    private val retryJsTimeoutMs = 20_000L
    private val nativeTimeoutPaddingMs = 6_000L
    private val retryDelayMs = 900L
    private var requestTimeout: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var requestStartedAtMs = 0L
    private var attemptNumber = 0
    private var attemptToken = 0L
    private var totalRetries = 0
    private var currentCenter: SectorId? = null

    // Count only validated entries inside the CURRENT 25-sector window. A restored
    // cache can contain records from other windows; failed coordinator work
    // items must not inflate the count of genuinely missing sectors.
    private fun cachedInWindow(center: SectorId, records: Map<SectorId, SectorPreloadCache.Record>): Int =
        records.keys.count { id ->
            val dNorth = kotlin.math.abs(id.north.toLong() - center.north.toLong())
            val dEast = kotlin.math.abs(id.east.toLong() - center.east.toLong())
            dNorth <= 2L && dEast <= 2L
        }

    private fun failureCategory(reason: String): String = when {
        reason.contains("AbortError", ignoreCase = true) || reason.contains("timeout", ignoreCase = true) -> "TIMEOUT"
        reason.contains("HTTP ", ignoreCase = true) -> "HTTP"
        reason.startsWith("invalid payload") || reason.startsWith("invalid JSON") -> "VALIDATION"
        reason.contains("native evaluation", ignoreCase = true) -> "JAVASCRIPT"
        reason.contains("TypeError", ignoreCase = true) || reason.contains("Failed to fetch", ignoreCase = true) -> "NETWORK"
        else -> "OTHER"
    }

    private fun clearRequestTimeout() {
        requestTimeout?.let { mainHandler.removeCallbacks(it) }
        requestTimeout = null
    }

    private fun clearRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun armRequestTimeout(browser: WebView, request: SectorApiPreloadCoordinator.ApiRequest, token: Long) {
        clearRequestTimeout()
        requestStartedAtMs = SystemClock.elapsedRealtime()
        val watchdogMs = (if (attemptNumber == 1) firstJsTimeoutMs else retryJsTimeoutMs) + nativeTimeoutPaddingMs
        val timeout = Runnable {
            if (!running || webView !== browser || pending != request || token != attemptToken) return@Runnable
            val elapsedMs = SystemClock.elapsedRealtime() - requestStartedAtMs
            requestTimeout = null
            Log.w("WDGHUD_PREFETCH", "TIMEOUT sector=${request.id} attempt=$attemptNumber elapsedMs=$elapsedMs")
            failAttempt(browser, request, "native watchdog timeout")
        }
        requestTimeout = timeout
        mainHandler.postDelayed(timeout, watchdogMs)
    }

    private fun retryable(error: String): Boolean =
        error.contains("AbortError", ignoreCase = true) ||
            error.contains("timeout", ignoreCase = true) ||
            error.contains("TypeError", ignoreCase = true) ||
            error.contains("Failed to fetch", ignoreCase = true) ||
            error.contains("native evaluation error", ignoreCase = true)

    private fun failAttempt(browser: WebView, request: SectorApiPreloadCoordinator.ApiRequest, reason: String) {
        clearRequestTimeout()
        if (retryRunnable != null) return
        if (attemptNumber == 1 && retryable(reason)) {
            totalRetries++
            Log.w("WDGHUD_PREFETCH", "RETRY sector=${request.id} reason=$reason afterMs=$retryDelayMs")
            val task = Runnable {
                retryRunnable = null
                if (running && webView === browser && pending == request) executeAttempt(browser, request)
            }
            retryRunnable = task
            mainHandler.postDelayed(task, retryDelayMs)
            return
        }
        pending = null
        coordinator.fail(request)
        if (reason.contains("HTTP 404") || reason.contains("HTTP 204"))
            SectorUnavailableBackoff.unavailable(request.id)
        else SectorUnavailableBackoff.transientFailure(request.id)
        requestsFailed++
        Log.w("WDGHUD_PREFETCH", "FAIL sector=${request.id} category=${failureCategory(reason)} reason=$reason attempts=$attemptNumber failed=$requestsFailed")
        fetchNext(browser)
    }

    val active: Boolean get() = running

    fun start(
        center: SectorId,
        existing: Map<SectorId, SectorPreloadCache.Record>,
        onComplete: (Map<SectorId, SectorPreloadCache.Record>) -> Unit,
        onRecord: (SectorId, SectorPreloadCache.Record) -> Unit = { _, _ -> }
    ) {
        if (running) return
        currentCenter = center
        staging.restore(existing)
        val initial = coordinator.begin(center, SystemClock.elapsedRealtime())
        requestsAttempted = 0
        requestsAccepted = 0
        requestsFailed = 0
        totalRetries = 0
        startedAtMs = SystemClock.elapsedRealtime()
        val initialCached = cachedInWindow(center, staging.export())
        Log.i("WDGHUD_PREFETCH", "START center=$center cached=$initialCached/${requiredCount} missing=${requiredCount - initialCached} coordinatorMissing=${initial.missing}")
        callbacks = onComplete
        onEach = onRecord
        if (coordinator.status(SystemClock.elapsedRealtime()).missing == 0) {
            Log.i("WDGHUD_PREFETCH", "DONE already cached=$initialCached/${requiredCount}; no requests")
            currentCenter = null
            callbacks = null
            onEach = null
            onComplete(staging.export())
            return
        }
        running = true
        val activity = context as? Activity
        val host = activity?.findViewById<ViewGroup>(android.R.id.content)
        if (host == null || activity.isFinishing || activity.isDestroyed) {
            Log.e("WDGHUD_PREFETCH", "CANCEL no active Activity view hierarchy")
            finish(cancel = true)
            return
        }
        val browser = WebView(activity)
        webView = browser
        webViewHost = host
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.addJavascriptInterface(object {
            @JavascriptInterface
            fun finished(sequence: Long, token: Long, response: String?, error: String?) {
                browser.post {
                    if (!running || webView !== browser) return@post
                    val request = pending ?: return@post
                    if (request.sequence != sequence || token != attemptToken || retryRunnable != null) return@post
                    clearRequestTimeout()
                    val elapsedMs = SystemClock.elapsedRealtime() - requestStartedAtMs
                    Log.i("WDGHUD_PREFETCH", "RESPONSE sector=${request.id} seq=$sequence attempt=$attemptNumber elapsedMs=$elapsedMs error=${error ?: "none"}")
                    if (error != null || response == null) {
                        failAttempt(browser, request, error ?: "empty response")
                        return@post
                    }
                    val accepted = coordinator.complete(request, response, SystemClock.elapsedRealtime())
                    if (accepted) {
                        pending = null
                        SectorUnavailableBackoff.succeeded(request.id)
                        requestsAccepted++
                        // G5A40: publish EACH validated cell immediately.
                        // Do not wait for the remaining 24-sector requests.
                        staging.export()[request.id]?.let { onEach?.invoke(request.id, it) }
                        val center = currentCenter
                        val cached = if (center != null) cachedInWindow(center, staging.export()) else staging.export().size
                        Log.i("WDGHUD_PREFETCH", "OK sector=${request.id} accepted=$requestsAccepted attempted=$requestsAttempted cached=$cached/${requiredCount}")
                        fetchNext(browser)
                    } else {
                        val reason = try {
                            val data = JSONObject(response)
                            "invalid payload: ok=${data.opt("ok")} total=${data.opt("total")} levelsType=${data.opt("levels")?.javaClass?.simpleName ?: "absent"}"
                        } catch (_: Exception) { "invalid JSON response" }
                        // The coordinator has already consumed/failed this request; do not call fail again.
                        pending = null
                        SectorUnavailableBackoff.unavailable(request.id)
                        requestsFailed++
                        Log.w("WDGHUD_PREFETCH", "FAIL sector=${request.id} category=${failureCategory(reason)} reason=$reason attempted=$requestsAttempted failed=$requestsFailed")
                        fetchNext(browser)
                    }
                }
            }
        }, "WDGPrefetch")
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (running && webView === browser && pending == null) {
                    val host = android.net.Uri.parse(url).host
                    if (host == "wdgwars.pl" || host?.endsWith(".wdgwars.pl") == true) {
                        Log.i("WDGHUD_PREFETCH", "Authenticated page loaded host=$host; beginning requests")
                        fetchNext(browser)
                    } else {
                        Log.w("WDGHUD_PREFETCH", "CANCEL unexpected page host=$host")
                        finish(cancel = true)
                    }
                }
            }
        }
        // A detached WebView may not execute asynchronous JS reliably. Keep this
        // tiny, non-interactive WebView attached while prefetch runs.
        browser.isClickable = false
        browser.isFocusable = false
        browser.alpha = 0.01f
        host.addView(browser, FrameLayout.LayoutParams(1, 1))
        Log.i("WDGHUD_PREFETCH", "BRIDGE_ATTACHED attached=${browser.isAttachedToWindow}")
        browser.loadUrl("https://wdgwars.pl/")
    }

    private fun fetchNext(browser: WebView) {
        if (!running || webView !== browser || pending != null) return
        var request = coordinator.next(SystemClock.elapsedRealtime())
        while (request != null && !SectorUnavailableBackoff.canRetry(request.id)) {
            // Do not request a known-unavailable (e.g. water) sector again on
            // every lookahead loop. Continue with the next geographic key.
            coordinator.fail(request)
            Log.i("WDGHUD_PREFETCH", "SKIP unavailable cooldown sector=${request.id}")
            request = coordinator.next(SystemClock.elapsedRealtime())
        }
        if (request == null) {
            finish(cancel = false)
            return
        }
        requestsAttempted++
        attemptNumber = 0
        pending = request
        Log.i("WDGHUD_PREFETCH", "REQUEST sector=${request.id} number=$requestsAttempted of up-to-${requiredCount}")
        executeAttempt(browser, request)
    }

    private fun executeAttempt(browser: WebView, request: SectorApiPreloadCoordinator.ApiRequest) {
        if (!running || webView !== browser || pending != request) return
        attemptNumber++
        val token = ++attemptToken
        val jsTimeoutMs = if (attemptNumber == 1) firstJsTimeoutMs else retryJsTimeoutMs
        armRequestTimeout(browser, request, token)
        // Only the current attempt token can commit a response. Late callbacks from an
        // aborted attempt are ignored, even while a retry is running for the same sector.
        val url = "/api/cell-detail?lat=${request.latitude}&lng=${request.longitude}"
        val script = """(async function(){
          const controller = new AbortController();
          const timer = setTimeout(() => controller.abort(), $jsTimeoutMs);
          try {
            const response = await fetch(${JSONObject.quote(url)}, {
              credentials: 'same-origin',
              headers: {'Accept': 'application/json'},
              signal: controller.signal
            });
            if (!response.ok || !(response.headers.get('content-type') || '').includes('json'))
              throw Error('HTTP ' + response.status + ' (check WDGWars sign-in)');
            const data = await response.json();
            WDGPrefetch.finished(${request.sequence}, $token, JSON.stringify(data), null);
          } catch(e) {
            WDGPrefetch.finished(${request.sequence}, $token, null, String(e));
          } finally {
            clearTimeout(timer);
          }
        })();""".trimIndent()
        try {
            browser.evaluateJavascript(script) { value ->
                if (running && webView === browser && pending == request && token == attemptToken) {
                    Log.i("WDGHUD_PREFETCH", "JS_EVALUATED sector=${request.id} seq=${request.sequence} attempt=$attemptNumber result=${value?.take(120) ?: "null"}")
                }
            }
        } catch (e: Exception) {
            failAttempt(browser, request, "native evaluation error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun cancel() = finish(cancel = true)

    private fun finish(cancel: Boolean) {
        if (!running) return
        val records = staging.export()
        val cached = currentCenter?.let { cachedInWindow(it, records) } ?: records.size.coerceAtMost(requiredCount)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        Log.i("WDGHUD_PREFETCH", "${if (cancel) "CANCELLED" else "FINISHED"} cached=$cached/${requiredCount} missing=${requiredCount - cached} attempted=$requestsAttempted accepted=$requestsAccepted failed=$requestsFailed retries=$totalRetries elapsedMs=$elapsedMs")
        running = false
        currentCenter = null
        clearRequestTimeout()
        clearRetry()
        pending = null
        val cb = callbacks
        callbacks = null
        onEach = null
        val view = webView
        val host = webViewHost
        webView = null
        webViewHost = null
        if (view != null) {
            try {
                view.stopLoading()
                host?.removeView(view)
                view.removeJavascriptInterface("WDGPrefetch")
                view.destroy()
                Log.i("WDGHUD_PREFETCH", "BRIDGE_DETACHED")
            } catch (e: Exception) {
                Log.w("WDGHUD_PREFETCH", "BRIDGE_CLEANUP ${e.javaClass.simpleName}")
            }
        }
        // Preserve any already-validated prefetch results when Activity yields BLE.
        cb?.invoke(staging.export())
    }

    private fun validDetail(raw: String): Boolean = try {
        val obj = JSONObject(raw)
        if (!obj.optBoolean("ok", true)) false
        else {
            val total = obj.optLong("total", -1L)
            val levels = obj.optJSONArray("levels")
            if (levels == null || total < 0 || total > Int.MAX_VALUE) false
            else {
                var sum = 0L
                var valid = true
                for (i in 0 until levels.length()) {
                    val level = levels.optJSONObject(i)
                    val number = level?.optInt("lvl", 0) ?: 0
                    val count = level?.optLong("c", -1) ?: -1
                    if (number !in 1..255 || count < 0 || count > Int.MAX_VALUE) valid = false
                    else sum += count
                }
                valid && sum == total
            }
        }
    } catch (_: Exception) { false }
}
