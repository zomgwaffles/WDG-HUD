package com.example.wdghud

import android.Manifest
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.app.Dialog
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Button as AndroidButton
import android.widget.TextView
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.JsonReader
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Typography
import java.util.UUID
import java.util.Locale
import com.example.wdghud.geometry.WdgWarsGrid
import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.SectorPreloadCache
import com.example.wdghud.geometry.SectorScanCoverage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WDGHUD_BLE"
        private const val WATCH_NAME = "WDG HUD"
        private val SERVICE_UUID = UUID.fromString("e4c1a100-7c0d-4f91-a2d4-7a50a9d1b001")
        private val COMMAND_UUID = UUID.fromString("e4c1a101-7c0d-4f91-a2d4-7a50a9d1b001")
        private val RESPONSE_UUID = UUID.fromString("e4c1a102-7c0d-4f91-a2d4-7a50a9d1b001")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var websiteLoginVerified by mutableStateOf(false)
    // Interior query points for /api/cell-detail, in 3x3 display order.
    // Territory anchors are kept separate for gang ownership and grid geometry.
    private var lastGridCoordinates = emptyList<Pair<Double, Double>>()

    // G5A49: Account-specific public card URLs are optional, never hardcoded.
    private val profileCardPattern = Regex("^https://wdgwars\\.pl/card/[A-Za-z0-9_-]{1,100}\\.svg$")
    private var profileCardUrl by mutableStateOf("")
    private var profileCardInput by mutableStateOf("")
    private var profileCardError by mutableStateOf(false)

    private fun validProfileCardUrl(url: String): Boolean =
        profileCardPattern.matches(url)

    private fun profileCardPreferenceKey(key: String): String {
        if (key.isBlank()) return ""
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.trim().toByteArray(Charsets.UTF_8))
        val accountId = digest.take(16).joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xff)
        }
        return "${ActivityRankProbe.CARD_URL_KEY}_$accountId"
    }

    private fun savedProfileCard(key: String): String {
        val prefKey = profileCardPreferenceKey(key)
        if (prefKey.isEmpty()) return ""
        val url = getSharedPreferences("wdghud", Context.MODE_PRIVATE)
            .getString(prefKey, "").orEmpty()
        return url.takeIf(::validProfileCardUrl).orEmpty()
    }

    private fun loadProfileCardForAccount(key: String) {
        profileCardUrl = savedProfileCard(key)
        profileCardInput = profileCardUrl
        profileCardError = false
    }

    private fun saveProfileCard() {
        val value = profileCardInput.trim()
        val prefKey = profileCardPreferenceKey(apiKey)
        if (prefKey.isEmpty() || !validProfileCardUrl(value)) {
            profileCardError = true
            return
        }
        getSharedPreferences("wdghud", Context.MODE_PRIVATE).edit()
            .putString(prefKey, value).apply()
        profileCardUrl = value
        profileCardInput = value
        profileCardError = false
    }

    private fun clearProfileCard() {
        val prefKey = profileCardPreferenceKey(apiKey)
        if (prefKey.isNotEmpty()) {
            getSharedPreferences("wdghud", Context.MODE_PRIVATE).edit()
                .remove(prefKey).apply()
        }
        profileCardUrl = ""
        profileCardInput = ""
        profileCardError = false
    }

    private val bluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val scanner by lazy { bluetoothAdapter.bluetoothLeScanner }
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val cellHandler = Handler(Looper.getMainLooper())
    private var cellPackets = emptyList<String>()
    // G5A36: full sector names from the same territory response, staged until END.
    private var pendingSectorNames = emptyList<String>()
    private var e3Phase = "idle"
    private var e3ExpectedLevels = 0
    private var e5ExpectedRaw = 0
    private var e5RawPackets = 0
    // E5.14: check cell movement every 30 seconds; cache authenticated detail results
    // by geographical query point. Cache entries are immutable copies of verified JSON.
    private val sectorCheckIntervalMs = 30000L
    private val detailCacheTtlMs: Long get() = StaleIntervalSettings.ttlMs(this)
    private data class CachedDetail(val payload: String, val fetchedAtMs: Long)
    private val detailCache = mutableMapOf<String, CachedDetail>()
    // E5.15: share validated geographic detail results with the hidden-Activity
    // foreground service, preserving E5.14 cache across app switches.
    private fun loadSharedSectorCache() {
        SectorCacheStore.load(this).forEach { (k, entry) ->
            val existing = detailCache[k]
            if (existing == null || entry.fetchedAtMs > existing.fetchedAtMs)
                detailCache[k] = CachedDetail(entry.payload, entry.fetchedAtMs)
        }
    }
    private fun saveSharedSectorCache() {
        SectorCacheStore.save(this, detailCache.mapValues { (_, value) ->
            SectorCacheStore.Entry(value.payload, value.fetchedAtMs)
        })
    }

    // G5A42: every sector ever stored remains in SQLite. The Activity RAM map
    // may be trimmed, but a return-trip GPS center restores the 25 nearest
    // historical sectors before lookahead/phone-to-watch cache delivery.
    private fun restoreHistoryWindow(center: SectorId) {
        val ids = WdgWarsGrid.window(center, 2)
        val lookup = linkedMapOf<String, SectorId>()
        ids.forEach { id ->
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            lookup[detailCacheKey(lat, lon)] = id
        }
        val history = SectorCacheStore.loadKeys(this, lookup.keys)
        var restored = 0
        history.forEach { (key, entry) ->
            val id = lookup[key] ?: return@forEach
            // Discard corrupt persisted data, but do not erase historical rows.
            if (WatchGeoPackets.build(id, entry.payload) == null) return@forEach
            if ((detailCache[key]?.fetchedAtMs ?: Long.MIN_VALUE) < entry.fetchedAtMs) {
                detailCache[key] = CachedDetail(entry.payload, entry.fetchedAtMs)
                restored++
            }
        }
        territoryForCache = territoryForCache + TerritoryCacheStore.loadWindow(this, ids)
        if (restored > 0) {
            warmWatchCachePending = true
            Log.i("WDGHUD_CACHE", "G5A42 HISTORY_RESTORED center=$center rows=$restored/25")
        }
    }

    private fun detailCacheKey(lat: Double, lon: Double): String =
        "${kotlin.math.round(lat * 1000000.0).toLong()}:${kotlin.math.round(lon * 1000000.0).toLong()}"
    private val refreshIntervalMs = 300000L
    private var lastAutoRefreshMs = 0L
    private var lastGpsCell: SectorId? = null
    // G5A38: GPS center is communicated independently of network lookups.
    private var pendingGridCenter: SectorId? = null
    private var geoCacheSupported = false
    private val geoPackets = WatchGeoQueue()
    private val geoRetry = RecoveryBackoff(listOf(5_000L, 15_000L, 30_000L, 60_000L))
    private var desiredWatchCenter: SectorId? = null
    private val offlineSectorTracker = OfflineSectorTracker()
    // G5A41: permanent, offline anchor geometry; never touched by AP refresh.
    private val anchorRegistry by lazy { SectorAnchorRegistry(applicationContext) }
    // Registry expansion must never delay GPS -> CENTER BLE traffic.
    private val anchorExecutor = Executors.newSingleThreadExecutor()
    private var awaitingCenter: SectorId? = null
    private var lastAcknowledgedCenter: SectorId? = null
    private var lastMissedCenter: SectorId? = null
    private var missRefillAttemptedFor: SectorId? = null
    private val bleRetry = RecoveryBackoff()
    private var bleAttemptStartedAtMs = 0L
    private var writeStartedAtMs = 0L
    private var centerSentAtMs = 0L
    private var territoryForCache = emptyMap<SectorId, Pair<String?, String?>>()
    // G5A39: transfer existing phone cache to the watch without waiting for HTTP.
    // Requeue after a geographic change if the previous 25-sector batch was busy.
    private var warmWatchCachePending = false
    private var prefetchTargetCenter: SectorId? = null
    private var prefetchPreviousCenter: SectorId? = null
    private var nextPrefetchRetryAtMs = 0L
    private var lastPrefetchPlanAtMs = 0L

    private fun queueCachedWatchSectors() {
        if (!geoCacheSupported || geoPackets.active) { warmWatchCachePending = true; return }
        val center = desiredWatchCenter ?: return
        val records = linkedMapOf<SectorId, String>()
        val ordered = MovingWindowPriority.ordered(center, prefetchPreviousCenter) { id ->
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            detailCache[detailCacheKey(lat, lon)]?.let { cached ->
                val (gang, color) = territoryForCache[id] ?: (null to null)
                geoPackets.needsDelivery(id, cached.payload, gang, color)
            } ?: false
        }
        ordered.take(2).forEach { id ->
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            detailCache[detailCacheKey(lat, lon)]?.let { records[id] = it.payload }
        }
        warmWatchCachePending = ordered.size > records.size
        if (records.isNotEmpty()) {
            geoPackets.enqueue(records) { id -> territoryForCache[id] ?: (null to null) }
            Log.i("WDGHUD_GEO", "WATCH_REFILL queued=${records.size} remaining=${ordered.size - records.size} center=$center")
        }
    }

    private val geoPump = object : Runnable {
        override fun run() {
            val clock = android.os.SystemClock.elapsedRealtime()
            if (awaitingCenter != null && clock - centerSentAtMs > 6000L) awaitingCenter = null
            // Local GPS math runs independently of network transfer progress.
            if (activityHasFocusForBle && desiredWatchCenter != null &&
                clock - lastPrefetchPlanAtMs >= 2_000L) {
                lastPrefetchPlanAtMs = clock
                prefetchFiveByFive()
            }
            val atomicTransfer = e3Phase == "begin" || e3Phase == "cells" ||
                    e3Phase == "levels" || e3Phase == "end"
            val canCenter = activityHasFocusForBle && ready && geoCacheSupported &&
                    !writePending && viewportPackets.isEmpty() &&
                    !cellAwaitingAck && !levelAwaitingAck &&
                    !geoPackets.waitingForAck
            val idleBle = canCenter && !atomicTransfer
            val center = desiredWatchCenter
            if (canCenter && center != null && center != lastAcknowledgedCenter &&
                awaitingCenter == null) {
                if (writeCommand("CENTER,${center.north},${center.east}")) {
                    awaitingCenter = center
                    centerSentAtMs = clock
                    Log.i("WDGHUD_GEO", "WATCH_SEND_CENTER=$center previousAck=$lastAcknowledgedCenter")
                }
            } else if (idleBle && awaitingCenter == null) {
                if (warmWatchCachePending && !geoPackets.active) queueCachedWatchSectors()
                if (!geoPackets.active && geoPackets.lastError != null &&
                    geoRetry.canAttempt(clock)) {
                    geoRetry.fail(clock)
                    warmWatchCachePending = true
                    Log.w("WDGHUD_RECOVERY", "Geo ACK retry: resend only unacknowledged sectors")
                }
                val wasActive = geoPackets.active
                geoPackets.next(clock, !writePending)?.let { packet ->
                    if (writeCommand(packet)) geoPackets.sent(clock)
                }
                if (wasActive && !geoPackets.active && geoPackets.lastError == null &&
                    lastMissedCenter != null) {
                    lastAcknowledgedCenter = null
                    geoRetry.succeed()
                    Log.i("WDGHUD_RECOVERY", "Watch refill acknowledged; retry CENTER")
                }
            }
            cellHandler.postDelayed(this, 120L)
        }
    }

    // G5A40: maintain the moving 25-sector envelope continuously, not only
    // after a BLE CENTER ACK. Activity owns this while visible; service takes
    // over using independent authenticated HTTPS requests while Activity hidden.
    private val fiveByFivePrefetch by lazy { SectorFiveByFivePrefetch(this) }
    private fun prefetchFiveByFive() {
        if (!activityHasFocusForBle) return
        val center = desiredWatchCenter ?: latestLocation?.let { WdgWarsGrid.sectorAt(it.latitude, it.longitude) } ?: return
        val clock = android.os.SystemClock.elapsedRealtime()
        // Fetch the complete geographic working window from permanent history.
        // Revisit is a disk cache hit, not automatically a new API fetch.
        restoreHistoryWindow(center)
        if (fiveByFivePrefetch.active) {
            if (prefetchTargetCenter == center) return
            // Old-center prefetch is not allowed to consume the new leading edge.
            fiveByFivePrefetch.cancel()
        }
        if (prefetchTargetCenter == center && clock < nextPrefetchRetryAtMs) return
        val existing = mutableMapOf<SectorId, SectorPreloadCache.Record>()
        WdgWarsGrid.window(center, 2).forEach { id ->
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            detailCache[detailCacheKey(lat, lon)]?.let { cached ->
                if (cached.fetchedAtMs <= clock && clock - cached.fetchedAtMs < detailCacheTtlMs)
                    existing[id] = SectorPreloadCache.Record(cached.payload, cached.fetchedAtMs)
            }
        }
        val needed = MovingWindowPriority.ordered(center, prefetchPreviousCenter) { id ->
            id !in existing && SectorUnavailableBackoff.canRetry(id)
        }
        if (needed.isEmpty()) return
        prefetchTargetCenter = center
        nextPrefetchRetryAtMs = clock + 30_000L // bounded retry after an unsuccessful run
        Log.i("WDGHUD_PREFETCH", "G5A40 CACHE_PLAN center=$center fresh=${existing.size}/25 missing=${needed.size} " +
                "visibleMissing=${needed.count { it in WdgWarsGrid.window(center, 1) }}")
        fiveByFivePrefetch.start(center, existing, onComplete = { results ->
            if (activityHasFocusForBle) {
                results.forEach { (id, record) ->
                    val (lat, lon) = WdgWarsGrid.queryPoint(id)
                    val cacheKey = detailCacheKey(lat, lon)
                    if ((detailCache[cacheKey]?.fetchedAtMs ?: -1L) < record.fetchedAtMs)
                        detailCache[cacheKey] = CachedDetail(record.payload, record.fetchedAtMs)
                }
                saveSharedSectorCache()
                warmWatchCachePending = true
                // If the GPS center changed during HTTP, schedule its plan NOW.
                if (desiredWatchCenter != center) cellHandler.post { prefetchFiveByFive() }
            }
        }, onRecord = { id, record ->
            if (activityHasFocusForBle) {
                val (lat, lon) = WdgWarsGrid.queryPoint(id)
                val cacheKey = detailCacheKey(lat, lon)
                SectorUnavailableBackoff.succeeded(id)
                if ((detailCache[cacheKey]?.fetchedAtMs ?: -1L) < record.fetchedAtMs) {
                    detailCache[cacheKey] = CachedDetail(record.payload, record.fetchedAtMs)
                    SectorCacheStore.saveOne(this@MainActivity, cacheKey,
                        SectorCacheStore.Entry(record.payload, record.fetchedAtMs))
                    Log.i("WDGHUD_CACHE", "G5A41 AP_RECORD id=$id lastSyncUtc=${SectorFreshness.toUtc(record.fetchedAtMs, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())}")
                    warmWatchCachePending = true
                    Log.i("WDGHUD_PREFETCH", "G5A40 PREFETCH_OK id=$id center=$center watchAck=$lastAcknowledgedCenter")
                }
            }
        })
    }
    // G4D: explicit BLE ACKs; never interleave a viewport transaction with a
    // legacy BEGIN/CELL/LVL/RAW/END batch. UI Activity is the sole owner here.
    private var viewportSequence = 0L
    private var viewportPackets = emptyList<String>()
    private var viewportNext = 0
    private var viewportAwaitingAck = false
    private var viewportAckDeadlineMs = 0L
    private var viewportTarget: SectorId? = null
    private var lastViewportCenter: SectorId? = null
    private val viewportPump = object : Runnable {
        override fun run() {
            if (viewportPackets.isEmpty()) return
            if (!activityHasFocusForBle || !ready || watchCapsState != "V2") {
                stopViewport("BLE unavailable")
                return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (viewportAwaitingAck && now > viewportAckDeadlineMs) {
                stopViewport("ACK timeout packet=$viewportNext")
                return
            }
            if (!viewportAwaitingAck && !writePending && viewportNext < viewportPackets.size) {
                if (writeCommand(viewportPackets[viewportNext])) {
                    viewportAwaitingAck = true
                    viewportAckDeadlineMs = now + 3500L
                }
            }
            cellHandler.postDelayed(this, 100L)
        }
    }
    private fun stopViewport(reason: String) {
        if (viewportPackets.isNotEmpty()) Log.i("WDGHUD_VIEWPORT", "STOP $reason")
        cellHandler.removeCallbacks(viewportPump)
        viewportPackets = emptyList()
        viewportNext = 0
        viewportAwaitingAck = false
        viewportTarget = null
    }
    private fun onViewportResponse(message: String): Boolean {
        if (!message.startsWith("V") && !message.startsWith("VTILE")) return false
        if (viewportPackets.isEmpty()) return true
        val expected = when {
            viewportNext == 0 -> "VBEGIN:OK"
            viewportNext == viewportPackets.lastIndex -> "VEND:OK"
            else -> "VTILE:OK"
        }
        if (!viewportAwaitingAck) return true
        if (message != expected) {
            stopViewport("watch rejected packet=$viewportNext response=$message")
            return true
        }
        viewportAwaitingAck = false
        viewportNext++
        if (viewportNext == viewportPackets.size) {
            lastViewportCenter = viewportTarget
            Log.i("WDGHUD_VIEWPORT", "COMMITTED center=$lastViewportCenter")
            stopViewport("complete")
        } else cellHandler.post(viewportPump)
        return true
    }
    private fun cachedTotal(id: SectorId, now: Long): Int? {
        val (lat, lon) = WdgWarsGrid.queryPoint(id)
        val entry = detailCache[detailCacheKey(lat, lon)] ?: return null
        if (entry.fetchedAtMs > now || now - entry.fetchedAtMs >= detailCacheTtlMs) return null
        return try {
            val obj = JSONObject(entry.payload)
            if (!obj.optBoolean("ok", true)) return null
            val total = obj.optLong("total", -1)
            val levels = obj.optJSONArray("levels") ?: return null
            if (total !in 0..Int.MAX_VALUE.toLong()) return null
            var sum = 0L
            for (i in 0 until levels.length()) {
                val level = levels.optJSONObject(i) ?: return null
                val number = level.optInt("lvl", 0)
                val count = level.optLong("c", -1)
                if (number !in 1..255 || count !in 0..Int.MAX_VALUE.toLong()) return null
                sum += count
            }
            if (sum == total) total.toInt() else null
        } catch (_: Exception) { null }
    }
    private fun sendCachedViewportIfNeeded(center: SectorId) {
        if (!activityHasFocusForBle || !ready || watchCapsState != "V2" ||
            viewportPackets.isNotEmpty() || center == lastViewportCenter ||
            e3Phase != "idle" || scanAnnouncePending || apiLoading || levelFetchPending ||
            cellPackets.isNotEmpty() || levelPackets.isNotEmpty() || progressActive) return
        val now = android.os.SystemClock.elapsedRealtime()
        val ids = WdgWarsGrid.window(center, 1)
        val totals = ids.associateWith { cachedTotal(it, now) }
        if (totals.values.none { it != null }) return // No useful cache; keep current display.
        viewportSequence++
        viewportPackets = CachedViewportPackets.build(center, viewportSequence, totals)
        viewportNext = 0
        viewportTarget = center
        Log.i("WDGHUD_VIEWPORT", "START center=$center present=${totals.values.count { it != null }}/9")
        cellHandler.post(viewportPump)
    }

    private var autoRefreshStatus by mutableStateOf("AUTO // waiting for GPS + watch")
    private fun canAutoRefresh(): Boolean {
        val fix = latestLocation ?: return false
        val age = (android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1000000L
        return ready && gpsActive && age in 0..30000L && !apiLoading &&
                !levelFetchPending && !scanAnnouncePending && e3Phase == "idle" && cellPackets.isEmpty() && levelPackets.isEmpty()
    }
    private fun tryAutoRefresh(reason: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!canAutoRefresh()) return false
        if (lastAutoRefreshMs != 0L && now - lastAutoRefreshMs < 15000L) return false
        lastAutoRefreshMs = now
        autoRefreshStatus = "AUTO // $reason"
        Log.i("WDGHUD_AUTO", "Triggering: $reason")
        fetch3x3()
        return true
    }
    private val autoRefreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshTick = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            val fix = latestLocation
            val validFix = fix != null &&
                    (android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1000000L in 0..30000L
            if (validFix && fix != null) {
                // Use the same canonical global sector ID as the background service.
                // A busy transfer never commits a new sector, so movement is retried.
                val cell = anchorRegistry.locate(fix.latitude, fix.longitude)
                if (cell != null && cell != lastGpsCell) {
                    // G5A38.3: CENTER is sent by geoPump from the GPS callback.
                    // Do not start an API scan while the watch can recenter from
                    // its own flash cache. A confirmed miss may fall back only
                    // after phone cache refill has had a chance to complete.
                    if (geoCacheSupported && ready && activityHasFocusForBle) {
                        if (lastMissedCenter == cell && lastAcknowledgedCenter == cell &&
                            awaitingCenter == null && !geoPackets.active &&
                            !fiveByFivePrefetch.active) {
                            // A missing AP cell (including over water) is not a
                            // reason to block navigation on a full 9-cell sync.
                            prefetchFiveByFive()
                        }
                    } else tryAutoRefresh("GPS sector changed (legacy watch)")
                }
            }
            if (validFix && lastGridCoordinates.size == 9 &&
                (lastAutoRefreshMs == 0L || now - lastAutoRefreshMs >= refreshIntervalMs) && canAutoRefresh()) {
                val stale = lastGridCoordinates.any { (lat, lon) ->
                    detailCache[detailCacheKey(lat, lon)]?.let { now - it.fetchedAtMs >= detailCacheTtlMs } ?: true
                }
                if (stale) {
                    if (geoCacheSupported && ready && activityHasFocusForBle) {
                        lastAutoRefreshMs = now
                        Log.i("WDGHUD_RECOVERY", "Stale detail: background 25-sector prefetch, keep watch 3x3")
                        prefetchFiveByFive()
                    } else tryAutoRefresh("stale grid detail")
                }
            }
            autoRefreshHandler.postDelayed(this, sectorCheckIntervalMs)
        }
    }

    private var e3Deadline = 0L
    private var levelPackets = emptyList<String>()
    private var levelNext = 0
    private var levelAwaitingAck = false
    private var levelAckDeadline = 0L
    private var levelBrowser: WebView? = null
    private var levelFetchPending = false
    private var cellNext = 0
    private var cellAwaitingAck = false
    private var cellAckDeadline = 0L
    private var cellTransferStatus by mutableStateOf("No live cells sent")
    // G5A40.1: PROG is AP LEVEL UPLINK only. The initial nine CELL and
    // optional NAME metadata packets DO NOT move the progress bar.
    // Each acknowledged LVL or RAW packet advances it; END commits 100%.
    private var uplinkTotalPackets = 0
    private var progressActive = false
    private var progressTarget = 0
    private var progressLastSent = -1
    private var progressLastSendMs = 0L
    private fun setScanProgress(value: Int) {
        if (!progressActive) return
        progressTarget = value.coerceIn(0, 99)
    }
    private val progressPump = object : Runnable {
        override fun run() {
            if (!progressActive) return
            val now = android.os.SystemClock.elapsedRealtime()
            val safeToWrite = viewportPackets.isEmpty() && ready && !writePending && !cellAwaitingAck &&
                    !levelAwaitingAck && awaitingCenter == null &&
                    (desiredWatchCenter == lastAcknowledgedCenter || !geoCacheSupported) &&
                    e3Phase != "begin" && e3Phase != "end"
            if (safeToWrite && progressTarget != progressLastSent &&
                now - progressLastSendMs >= 350L && writeCommand("PROG,$progressTarget")) {
                progressLastSent = progressTarget
                progressLastSendMs = now
            }
            cellHandler.postDelayed(this, 150L)
        }
    }
    private fun beginScanProgress() {
        progressActive = true
        progressTarget = 0
        progressLastSent = -1
        progressLastSendMs = 0L
        cellHandler.removeCallbacks(progressPump)
        cellHandler.post(progressPump)
    }
    private fun endScanProgress() {
        progressActive = false
        cellHandler.removeCallbacks(progressPump)
    }
    private fun updateTransferProgress() {
        if (!progressActive) return
        // levelNext counts acknowledged LVL + RAW packets only.
        if (uplinkTotalPackets > 0)
            setScanProgress(ApUplinkProgress.percent(levelNext, uplinkTotalPackets))
    }
    private val cellPump = object : Runnable {
        override fun run() {
            if (e3Phase == "idle") return
            // Navigation outranks AP uplink. Pause BETWEEN acknowledged packets
            // until the most recent GPS CENTER has been ACKed by the watch.
            val newCenter = desiredWatchCenter
            if (geoCacheSupported && newCenter != null && newCenter != lastAcknowledgedCenter &&
                !cellAwaitingAck && !levelAwaitingAck && e3Deadline == 0L) {
                if (awaitingCenter == null && !writePending && ready && !geoPackets.waitingForAck) {
                    if (writeCommand("CENTER,${newCenter.north},${newCenter.east}")) {
                        awaitingCenter = newCenter
                        centerSentAtMs = android.os.SystemClock.elapsedRealtime()
                        Log.i("WDGHUD_GEO", "G5A41 CENTER_PRIORITY=$newCenter during=$e3Phase")
                    }
                }
                cellHandler.postDelayed(this, 100L)
                return
            }
            if (awaitingCenter != null) { cellHandler.postDelayed(this, 100L); return }
            // GPS telemetry is independent of sector snapshots. Between acknowledged
            // CELL/LVL/RAW writes, allow the newest GPS fix onto the BLE link.
            // Do not interleave with BEGIN/END or an unacknowledged data packet.
            if (ready && !writePending && !cellAwaitingAck && !levelAwaitingAck &&
                e3Phase != "begin" && e3Phase != "end") {
                latestLocation?.let { sendGpsIfReady(it) }
            }
            if (e3Phase == "begin" || e3Phase == "end") {
                if (e3Phase == "begin" && !watchFullSectorNamesSupported) {
                    finishCellTransfer("GANG96 lost before BEGIN; previous snapshot preserved")
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() > e3Deadline && e3Deadline != 0L) {
                    finishCellTransfer("E3 ${e3Phase.uppercase()} TIMEOUT")
                    return
                }
                if (e3Deadline == 0L && !writePending) {
                    val packet = if (e3Phase == "begin") {
                        val center = pendingGridCenter
                        "BEGIN,$e3ExpectedLevels,$e5ExpectedRaw" +
                                (if (geoCacheSupported && center != null)
                                    ",1,${center.north},${center.east}"
                                else if (watchFullSectorNamesSupported) ",1" else "")
                    } else "END"
                    if (writeCommand(packet)) {
                        e3Deadline = android.os.SystemClock.elapsedRealtime() + 5000L
                        cellTransferStatus = "E3 // ${e3Phase.uppercase()}"
                    }
                }
                cellHandler.postDelayed(this, 100L)
                return
            }
            if (!ready) { finishCellTransfer("UPLINK LOST // Watch disconnected"); return }
            if (e3Phase == "levels" && levelPackets.isEmpty()) {
                e3Phase = "end"
                e3Deadline = 0L
                cellHandler.post(this)
                return
            }
            if (e3Phase == "levels" && levelPackets.isNotEmpty()) {
                if (levelAwaitingAck) {
                    if (android.os.SystemClock.elapsedRealtime() > levelAckDeadline) {
                        finishCellTransfer("LEVEL SYNC TIMEOUT // ${levelNext + 1}/${levelPackets.size}")
                        return
                    }
                } else if (levelNext < levelPackets.size && !writePending) {
                    if (writeCommand(levelPackets[levelNext])) {
                        levelAwaitingAck = true
                        levelAckDeadline = android.os.SystemClock.elapsedRealtime() + 5000L
                        cellTransferStatus = "LEVEL UPLINK // ${levelNext + 1}/${levelPackets.size}"
                    }
                }
                cellHandler.postDelayed(this, 100L)
                return
            }
            if (cellAwaitingAck) {
                if (android.os.SystemClock.elapsedRealtime() > cellAckDeadline) {
                    finishCellTransfer("Timed out waiting for cell/name ACK at ${cellNext + 1}/${cellPackets.size}")
                    return
                }
            } else if (cellNext < cellPackets.size && !writePending) {
                if (writeCommand(GangWireName.forWatch(cellPackets[cellNext], watchGangNameSupported))) {
                    cellAwaitingAck = true
                    cellAckDeadline = android.os.SystemClock.elapsedRealtime() + 5000L
                    cellTransferStatus = "Sending live cells: ${cellNext + 1}/${cellPackets.size}"
                }
            }
            cellHandler.postDelayed(this, 100L)
        }
    }
    private fun finishCellTransfer(message: String) {
        cellHandler.removeCallbacks(cellPump)
        cellPackets = emptyList()
        pendingSectorNames = emptyList()
        levelPackets = emptyList()
        e3Phase = "idle"
        e3Deadline = 0L
        uplinkTotalPackets = 0
        cellAwaitingAck = false
        levelAwaitingAck = false
        cellTransferStatus = message
        if (message != "Preparing live cells") pendingGridCenter = null
        if (message != "Preparing live cells") endScanProgress()
        if (!message.startsWith("UPLINK COMPLETE") && message != "Preparing live cells") cancelScanningAnnouncement()
    }
    private fun sendLiveCells(packets: List<String>) {
        if (!ready) { cellTransferStatus = "Connect the watch, then fetch again"; return }
        finishCellTransfer("Preparing live cells")
        cellPackets = packets
        cellNext = 0
        e3Phase = "preparing"
        cellTransferStatus = "E3 // Fetching all AP levels before uplink"
        fetchLevelsForCurrentGrid()
    }
    private var latestLocation: Location? = null
    // Last trustworthy course over ground. Retain it when stopping; do not invent north.
    private var lastReliableBearing: Float? = null
    private var apiStatus by mutableStateOf("No API test yet")
    private var apiResults by mutableStateOf("")
    private var apiLoading by mutableStateOf(false)
    // E5.13A: hardware-backed where supported; no key plaintext in preferences.
    // Keystore keys are device-bound. A backup restored to another phone cannot decrypt this blob.
    private val credentialPrefs by lazy { getSharedPreferences("wdghud_credentials", Context.MODE_PRIVATE) }
    private fun keyForCredentials(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey("wdghud_api_key_aes_v1", null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                "wdghud_api_key_aes_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
    private fun persistApiKey(value: String) {
        try {
            if (value.isEmpty()) {
                credentialPrefs.edit().remove("api_key_cipher_v1").apply()
                return
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyForCredentials())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + ciphertext
            credentialPrefs.edit().putString(
                "api_key_cipher_v1", Base64.encodeToString(payload, Base64.NO_WRAP)
            ).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to store API credentials: ${e.javaClass.simpleName}")
        }
    }
    private fun restoreApiKey(): String {
        val encoded = credentialPrefs.getString("api_key_cipher_v1", null) ?: return ""
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > 28) // 12-byte GCM IV, 16-byte authentication tag, at least 1 byte data
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyForCredentials(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Stored API credentials unavailable after restore/reset: ${e.javaClass.simpleName}")
            ""
        }
    }
    private var drivingSessionActive by mutableStateOf(false)
    // Blocks Activity reconnects during foreground-service ownership.
    private var activityHasFocusForBle = true
    private var apiKey by mutableStateOf("")
    private var keyTestStatus by mutableStateOf("UPLINK // Awaiting credentials")
    private var apiKeyError by mutableStateOf(false)
    private var keyTesting by mutableStateOf(false)
    // Coalescing retry: never queue a backlog of old GPS positions. Send only
    // the newest location whenever the BLE command characteristic is free.
    private val gpsRetryHandler = Handler(Looper.getMainLooper())
    private val gpsRetryTick = object : Runnable {
        override fun run() {
            latestLocation?.let { sendGpsIfReady(it) }
            gpsRetryHandler.postDelayed(this, 250L)
        }
    }
    private var lastGpsSentAt = 0L
    private var negotiatedMtu = 23
    private var writePending = false
    private var gpsSentCount by mutableIntStateOf(0)
    private var gpsAckCount by mutableIntStateOf(0)
    private var gpsActive by mutableStateOf(false)
    private var gpsText by mutableStateOf("GPS not started")
    // G5A38.1: no one-shot GPS_PROVIDER gate. This source recovers automatically
    // when provider, permission, or the satellite fix becomes available again.
    private val recoveringGps by lazy {
        RecoveringGpsSource(this, onFix = { location ->
            latestLocation = location
            // Every fresh GPS fix is checked against locally indexed sector
            // boundaries, regardless of API loading or BLE transfer state.
            val previousCenter = desiredWatchCenter
            desiredWatchCenter = anchorRegistry.locate(location.latitude, location.longitude)
            if (desiredWatchCenter != previousCenter && desiredWatchCenter != null) {
                prefetchPreviousCenter = previousCenter
                val newAnchorCenter = desiredWatchCenter!!
                anchorExecutor.execute { anchorRegistry.ensureWindowAsync(newAnchorCenter) }
                geoPackets.retainWindow(WdgWarsGrid.window(desiredWatchCenter!!, 2))
                warmWatchCachePending = true
                Log.i("WDGHUD_GEO", "G5A40 GPS_SECTOR=$desiredWatchCenter previous=$previousCenter " +
                        "WATCH_ACK=$lastAcknowledgedCenter")
                // GPS/CENTER stays independent: historical disk reads happen on
                // a subsequent task, not inside the GPS-to-sector calculation.
                cellHandler.post {
                    if (desiredWatchCenter == newAnchorCenter) {
                        restoreHistoryWindow(newAnchorCenter)
                        prefetchFiveByFive()
                    }
                }
            }
            val speedMph = if (location.hasSpeed()) location.speed * 2.2369363 else 0.0
            val movingBearing = location.hasBearing() && location.hasSpeed() &&
                    location.speed >= 0.9f
            if (movingBearing) lastReliableBearing = location.bearing
            val bearing = if (movingBearing) {
                "%.0f°".format(Locale.US, location.bearing)
            } else {
                lastReliableBearing?.let { "%.0f° (last)".format(Locale.US, it) }
                    ?: "N/A (not yet established)"
            }
            gpsText = "Source: %s\nLat: %.6f\nLon: %.6f\nSpeed: %.1f mph\nBearing: %s".format(
                Locale.US, location.provider ?: "location", location.latitude,
                location.longitude, speedMph, bearing
            )
            Log.d("WDGHUD_GPS", gpsText.replace("\n", " | "))
            sendGpsIfReady(location)
        }, onPhase = { phase, message ->
            gpsActive = phase == GpsRecoveryPolicy.Phase.READY
            if (phase != GpsRecoveryPolicy.Phase.READY) gpsText = message
            if (phase == GpsRecoveryPolicy.Phase.STALE ||
                phase == GpsRecoveryPolicy.Phase.PROVIDERS_OFF ||
                phase == GpsRecoveryPolicy.Phase.PERMISSION_REQUIRED) {
                signalGpsUnavailable()
            }
        })
    }

    private fun startGps() = recoveringGps.start()

    private fun stopGps() {
        recoveringGps.stop()
        gpsActive = false
    }

    // G5A38.2: recovery gate + GATT handshake/write watchdog. Only the
    // foreground Activity owns BLE while visible; service owns it otherwise.
    // G5A43: one protocol-level monitor for the Activity-owned BLE link.
    // Watch firmware already answers PING with PONG; no firmware changes needed.
    private val linkHealth = BleLinkHealth()
    private var bleHealthText by mutableStateOf("OFFLINE")
    private fun idleForHeartbeat(): Boolean = activityHasFocusForBle && ready &&
            watchGatt != null && !writePending && watchCapsState != "UNKNOWN" &&
            watchCapsState != "PROBING" && e3Phase == "idle" &&
            !cellAwaitingAck && !levelAwaitingAck && !scanAnnouncePending &&
            viewportPackets.isEmpty() && !viewportAwaitingAck &&
            !geoPackets.active && !geoPackets.waitingForAck &&
            awaitingCenter == null && operatorProfilePackets.isEmpty() &&
            operatorProfileAwaiting == null

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectTick = object : Runnable {
        override fun run() {
            val clock = android.os.SystemClock.elapsedRealtime()
            val hasPermissions = if (Build.VERSION.SDK_INT >= 31) {
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true
            if (activityHasFocusForBle) {
                val link = watchGatt
                if (!ready && link != null && bleAttemptStartedAtMs != 0L &&
                    clock - bleAttemptStartedAtMs > 18_000L) {
                    Log.w(TAG, "GATT handshake timed out; reconnecting")
                    bleRetry.fail(clock)
                    closeGatt(link)
                } else if (ready && writePending && writeStartedAtMs != 0L &&
                    clock - writeStartedAtMs > 7_000L) {
                    Log.w(TAG, "GATT write callback timed out; reconnecting")
                    bleRetry.fail(clock)
                    link?.let { closeGatt(it) }
                }
                if (ready) {
                    when (linkHealth.evaluate(clock, idleForHeartbeat())) {
                        BleLinkHealth.Action.PING -> if (writeCommand("PING")) {
                            linkHealth.onPingSent(clock)
                            Log.i(TAG, "G5A43 HEARTBEAT_SENT owner=activity")
                        }
                        BleLinkHealth.Action.MISSED -> Log.w(TAG,
                            "G5A43 HEARTBEAT_TIMEOUT owner=activity missed=${linkHealth.missedPings}")
                        BleLinkHealth.Action.RECOVER -> {
                            Log.e(TAG, "G5A43 LINK_UNRESPONSIVE owner=activity; reconnecting")
                            status = "Watch unresponsive; reconnecting"
                            bleRetry.fail(clock)
                            watchGatt?.let { closeGatt(it) }
                        }
                        BleLinkHealth.Action.NONE -> Unit
                    }
                }
                bleHealthText = linkHealth.diagnostic(clock)
                if (watchPairingInProgress && pairingStartMs > 0 &&
                    clock - pairingStartMs > 90000L) {
                    watchPairingInProgress = false
                    pairingRejected = true
                    status = "Pairing timed out. Tap SCAN to retry."
                }
                if (!ready && !scanning && watchGatt == null && hasPermissions &&
                    !watchPairingInProgress && !pairingRejected &&
                    bluetoothAdapter.isEnabled && bleRetry.canAttempt(clock)) {
                    startScanning(autoConnect = true)
                }
            }
            reconnectHandler.postDelayed(this, 2_000L)
        }
    }
    private var scanning = false
    private var watchDevice by mutableStateOf<BluetoothDevice?>(null)
    private var lastClockSyncSentAt = 0L
    private var watchGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null
    private var ready by mutableStateOf(false)
    // G4B negotiation is per GATT connection. Unanswered/legacy watches remain legacy.
    private var watchCapsState = "UNKNOWN"
    private var watchGangNameSupported = false
    private var watchFullSectorNamesSupported = false
    // G5A36.1: hold a requested sync until GANG96 is actually negotiated.
    // Never silently replace the last valid grid with a 20-character CELL name.
    private var fullNameSyncPending = false
    private val capsHandler = Handler(Looper.getMainLooper())
    private var capsAttempts = 0
    private val capsProbe = object : Runnable {
        override fun run() {
            if (!ready || watchGatt == null || watchCapsState != "UNKNOWN") return
            if (e3Phase != "idle" || writePending || scanAnnouncePending) {
                if (++capsAttempts < 15) capsHandler.postDelayed(this, 200L)
                else watchCapsState = "LEGACY"
                return
            }
            if (writeCommand(WatchCapabilities.QUERY)) {
                watchCapsState = "PROBING"
                capsHandler.postDelayed({
                    if (watchCapsState == "PROBING") {
                        watchCapsState = "LEGACY"
                        if (fullNameSyncPending) {
                            fullNameSyncPending = false
                            apiStatus = "GANG96 handshake timed out; reconnect watch and Sync again"
                        }
                        Log.i(TAG, "G5A36.1 CAPS unavailable; no truncated sector uplink permitted")
                    }
                }, 2500L)
            } else if (++capsAttempts < 15) capsHandler.postDelayed(this, 200L)
            else watchCapsState = "LEGACY"
        }
    }

    private var status by mutableStateOf("Ready to scan")
    private var responseText by mutableStateOf("No response yet")


    // G5A19: account refresh happens only when the watch opens OPERATOR.
    // Two ACKed small packets are independent of sector BEGIN/CELL/LVL/RAW/END.
    private val operatorProfileExecutor = Executors.newSingleThreadExecutor()
    private var operatorProfileFetching = false
    private var operatorProfilePackets = emptyList<String>()
    private var operatorProfileIndex = 0
    private var operatorProfileAwaiting: String? = null
    private var operatorProfileAckAt = 0L
    // G5A45: TEAM traffic is independent of AP uplink and GRID cache.
    private val teamExecutor = Executors.newSingleThreadExecutor()
    private var teamSnapshot: TeamProfileWire.Team? = null
    private var teamSnapshotKey = ""
    private var teamSnapshotLoaded = false // null team is a valid, cached 404
    private var teamFetchedAt = 0L // wall-clock epoch; survives process restart
    private var teamFetching = false
    private var teamLastAttemptAt = 0L // elapsedRealtime failure backoff
    private var teamNeedsResponse = false // watch is waiting for an uncached fetch
    private var teamRequestedPage: Int? = null
    private var teamDeferredPage: Int? = null
    private var teamPackets = emptyList<String>()
    private var teamPacketIndex = 0
    private var teamAwaiting: String? = null
    private var teamAckAt = 0L
    private val teamPump = object : Runnable {
        override fun run() {
            if (teamPackets.isEmpty()) return
            if (!activityHasFocusForBle || !ready) { clearTeamTransfer(); return }
            val now = android.os.SystemClock.elapsedRealtime()
            if (teamAwaiting != null) {
                if (now - teamAckAt > 6000L) {
                    Log.w(TAG, "G5A45.5 TEAM ACK timeout $teamAwaiting")
                    clearTeamTransfer()
                    return
                }
            } else if (!writePending && e3Phase == "idle" &&
                viewportPackets.isEmpty() && !scanAnnouncePending &&
                !cellAwaitingAck && !levelAwaitingAck &&
                operatorProfilePackets.isEmpty() && awaitingCenter == null) {
                val packet = teamPackets[teamPacketIndex]
                if (writeCommand(packet)) {
                    teamAwaiting = TeamProfileWire.expectedAck(packet)
                    teamAckAt = now
                }
            }
            cellHandler.postDelayed(this, 120L)
        }
    }
    private fun clearTeamTransfer() {
        teamPackets = emptyList()
        teamPacketIndex = 0
        teamAwaiting = null
        cellHandler.removeCallbacks(teamPump)
    }
    private fun queueTeamPackets(packets: List<String>) {
        if (!activityHasFocusForBle || !ready) return
        clearTeamTransfer()
        teamPackets = packets
        cellHandler.post(teamPump)
    }
    private fun loadCachedTeam(key: String, rereadDisk: Boolean = false) {
        if (teamSnapshotKey != key) {
            teamSnapshotKey = key
            teamSnapshotLoaded = false
            teamSnapshot = null
            teamFetchedAt = 0L
        } else if (teamSnapshotLoaded && !rereadDisk) return
        // A different BLE owner may have refreshed the shared disk cache.
        TeamSnapshotStore.load(applicationContext, key)?.let { entry ->
            if (!teamSnapshotLoaded || entry.fetchedAtEpochMs > teamFetchedAt) {
                teamSnapshot = entry.team
                teamSnapshotLoaded = true
                teamFetchedAt = entry.fetchedAtEpochMs
            }
        }
    }
    private fun maybeRefreshTeam(key: String) {
        if (teamFetching || key.isBlank() || !activityHasFocusForBle) return
        val age = System.currentTimeMillis() - teamFetchedAt
        if (teamSnapshotLoaded && age in 0 until TeamProfileWire.CACHE_MS) return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (teamLastAttemptAt != 0L && elapsed - teamLastAttemptAt < 60_000L) return
        teamLastAttemptAt = elapsed
        teamFetching = true
        teamExecutor.execute {
            var snapshot: TeamProfileWire.Team? = null
            var ok = false
            try { snapshot = TeamProfileWire.fetch(key); ok = true }
            catch (e: Exception) {
                Log.w(TAG, "G5A45.5 TEAM background fetch: ${e.javaClass.simpleName}: ${e.message}")
            }
            val fetched = System.currentTimeMillis()
            if (ok) TeamSnapshotStore.save(applicationContext, key, snapshot, fetched)
            runOnUiThread {
                teamFetching = false
                if (apiKey.trim() != key) return@runOnUiThread
                // Push only when a COMPLETE newly fetched snapshot actually differs.
                // The watch stores it in NVS; a timed API refresh never blanks it.
                val teamChanged = ok && (!teamSnapshotLoaded || TeamProfileWire.summary(teamSnapshot) != TeamProfileWire.summary(snapshot))
                if (ok) {
                    teamSnapshot = snapshot
                    teamSnapshotKey = key
                    teamSnapshotLoaded = true
                    teamFetchedAt = fetched
                }
                if (teamNeedsResponse && activityHasFocusForBle && ready) {
                    val requested = teamRequestedPage
                    teamNeedsResponse = false
                    teamRequestedPage = null
                    queueTeamPackets(if (!teamSnapshotLoaded) listOf("TEAM,ERR")
                    else if (requested == null) TeamProfileWire.summary(teamSnapshot)
                    else TeamProfileWire.page(teamSnapshot, requested))
                } else if (teamChanged && activityHasFocusForBle && ready &&
                    teamPackets.isEmpty() && operatorProfilePackets.isEmpty()) {
                    // Opportunistic idle-time delivery, even when TEAM is not open.
                    queueTeamPackets(TeamProfileWire.summary(teamSnapshot))
                }
            }
        }
    }
    private fun requestTeam(page: Int?) {
        if (!activityHasFocusForBle || !ready) return
        if (page != null && teamPackets.isNotEmpty()) { teamDeferredPage = page; return }
        val key = apiKey.trim()
        if (key.isBlank()) { queueTeamPackets(listOf("TEAM,ERR")); return }
        loadCachedTeam(key)
        if (teamSnapshotLoaded) {
            // Return last-good cached data immediately; refresh is nonblocking.
            queueTeamPackets(if (page == null) TeamProfileWire.summary(teamSnapshot)
            else TeamProfileWire.page(teamSnapshot, page))
            maybeRefreshTeam(key)
        } else {
            // No snapshot yet: wait for the single in-flight background fetch.
            teamNeedsResponse = true
            teamRequestedPage = page
            maybeRefreshTeam(key)
            // A recent failed attempt is throttled: report an error, not an endless spinner.
            if (!teamFetching) {
                teamNeedsResponse = false
                queueTeamPackets(listOf("TEAM,ERR"))
            }
        }
    }
    private val teamBackgroundRefresh = object : Runnable {
        override fun run() {
            if (activityHasFocusForBle) {
                val key = apiKey.trim()
                if (key.isNotBlank()) { loadCachedTeam(key, rereadDisk = true); maybeRefreshTeam(key) }
            }
            cellHandler.postDelayed(this, 60_000L)
        }
    }
    private fun onTeamMessage(message: String): Boolean {
        if (message == "TEAM:GET") { requestTeam(null); return true }
        if (message.startsWith("TEAM:PAGE:")) {
            val page = message.substringAfterLast(':').toIntOrNull()
            if (page != null && page in 0..65534) requestTeam(page)
            return true
        }
        if (!message.startsWith("TEAM:")) return false
        if (teamPackets.isNotEmpty()) {
            if (message == teamAwaiting) {
                teamAwaiting=null
                teamPacketIndex++
                if (teamPacketIndex == teamPackets.size) {
                    clearTeamTransfer()
                    val next = teamDeferredPage
                    teamDeferredPage=null
                    if (next != null) requestTeam(next)
                } else {
                    cellHandler.removeCallbacks(teamPump)
                    cellHandler.post(teamPump)
                }
            } else if (teamAwaiting != null) {
                Log.w(TAG,"G5A45 TEAM packet rejection: $message")
                clearTeamTransfer()
            }
        }
        return true
    }

    private val operatorProfilePump = object : Runnable {
        override fun run() {
            if (operatorProfilePackets.isEmpty()) return
            if (!activityHasFocusForBle || !ready) { clearOperatorProfileTransfer(); return }
            val now = android.os.SystemClock.elapsedRealtime()
            if (operatorProfileAwaiting != null) {
                if (now - operatorProfileAckAt > 6000L) {
                    Log.w(TAG, "G5A19 profile BLE ACK timeout: $operatorProfileAwaiting")
                    clearOperatorProfileTransfer()
                    return
                }
            } else if (!writePending && viewportPackets.isEmpty() && e3Phase == "idle" &&
                !scanAnnouncePending && !cellAwaitingAck && !levelAwaitingAck) {
                val packet = operatorProfilePackets[operatorProfileIndex]
                if (writeCommand(packet)) {
                    operatorProfileAwaiting = when {
                        packet.startsWith("PROFILE,S,") -> "PROFILE:S:OK"
                        packet.startsWith("PROFILE,N,") -> "PROFILE:N:OK"
                        packet.startsWith("PROFILE,G,") ->
                            "PROFILE:G:${packet.substringAfter("PROFILE,G,").substringBefore(',')}:OK"
                        packet.startsWith("PROFILE,A,") -> "PROFILE:A:OK"
                        packet == "PROFILE,E" -> "PROFILE:E:OK"
                        else -> "PROFILE:ERR:OK"
                    }
                    operatorProfileAckAt = now
                }
            }
            cellHandler.postDelayed(this, 120L)
        }
    }
    private fun clearOperatorProfileTransfer() {
        operatorProfilePackets = emptyList()
        operatorProfileIndex = 0
        operatorProfileAwaiting = null
        cellHandler.removeCallbacks(operatorProfilePump)
    }
    private fun queueOperatorProfilePackets(packets: List<String>) {
        if (!activityHasFocusForBle || !ready) return
        clearOperatorProfileTransfer()
        operatorProfilePackets = packets
        cellHandler.post(operatorProfilePump)
    }
    private fun onOperatorProfileMessage(message: String): Boolean {
        if (message == "PROFILE:GET") {
            refreshOperatorProfile()
            return true
        }
        if (!message.startsWith("PROFILE:")) return false
        if (operatorProfilePackets.isNotEmpty()) {
            if (message == operatorProfileAwaiting) {
                operatorProfileAwaiting = null
                operatorProfileIndex++
                if (operatorProfileIndex == operatorProfilePackets.size) {
                    Log.i(TAG, "G5A19 profile refresh delivered and ACKed")
                    clearOperatorProfileTransfer()
                } else {
                    cellHandler.removeCallbacks(operatorProfilePump)
                    cellHandler.post(operatorProfilePump)
                }
            } else if (message != "PROFILE:GET") {
                Log.w(TAG, "G5A19 profile packet rejected: $message")
                clearOperatorProfileTransfer()
            }
        }
        return true
    }
    private fun refreshOperatorProfile() {
        if (!activityHasFocusForBle || !ready || operatorProfileFetching) return
        val key = apiKey.trim()
        if (key.isEmpty()) {
            Log.w(TAG, "G5A19 profile refresh requires saved API key")
            queueOperatorProfilePackets(listOf("PROFILE,ERR"))
            return
        }
        operatorProfileFetching = true
        operatorProfileExecutor.execute {
            var connection: HttpURLConnection? = null
            var packets = listOf("PROFILE,ERR")
            try {
                connection = URL("https://wdgwars.pl/api/me").openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 12000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("X-API-Key", key)
                connection.setRequestProperty("Accept", "application/json")
                require(connection.responseCode == 200 &&
                        connection.contentType.orEmpty().contains("json", ignoreCase = true)) {
                    "HTTP ${connection.responseCode} or invalid content type"
                }
                val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                fun count(field: String): Long = data.optLong(field, -1L).also {
                    require(it in 0L..999999999L) { "Missing/invalid $field" }
                }
                fun safe(value: String, default: String) = value
                    .map { if (it.code in 32..126 && it != ',') it else '?' }
                    .joinToString("").trim().ifBlank { default }
                val user = safe(data.optString("username", ""), "UNKNOWN").take(20)
                val gang = safe(data.optString("gang", ""), "NO GANG")
                // No silent 20-character gang truncation. Supports up to 96
                // ASCII characters without changing the negotiated BLE MTU.
                require(gang.length <= 96) { "Gang name exceeds 96 characters" }
                // /api/me returns "badges" as an array of badge IDs, not a number.
                // Permit a numeric count as a backward-compatible alternative.
                val badgeCount = when (val badges = data.opt("badges")) {
                    is JSONArray -> badges.length().toLong()
                    is Number -> badges.toLong()
                    else -> throw IllegalArgumentException("Missing/invalid badges")
                }
                require(badgeCount in 0L..999999999L) { "Invalid badge count" }
                val gangChunks = gang.chunked(24)
                val profileData = mutableListOf(
                    "PROFILE,S,${count("wifi")},${count("ble")},${count("total")},$badgeCount",
                    "PROFILE,N,$user")
                gangChunks.forEachIndexed { index, chunk ->
                    profileData.add("PROFILE,G,$index,${gangChunks.size},$chunk")
                }
                // Optional 30-day activity rank: not the numeric leaderboard position.
                // Never fabricate the value when the public SVG is unreadable.
                var activity = ActivityRankProbe.fromProfile(data)
                if (activity.rank == null || activity.days == null) {
                    val cardUrl = savedProfileCard(key)
                    if (cardUrl.isNotEmpty()) {
                        val card = try { ActivityRankProbe.fetchCardCached(cardUrl) }
                        catch (_: Exception) { ActivityRankProbe.Activity(null, null) }
                        activity = ActivityRankProbe.Activity(
                            activity.rank ?: card.rank, activity.days ?: card.days)
                    }
                }
                ActivityRankProbe.packet(activity)?.let { profileData.add(it) }
                profileData.add("PROFILE,E") // Commit only after every chunk is ACKed.
                require(profileData.all { it.toByteArray(Charsets.UTF_8).size <= 61 })
                packets = profileData
                Log.i(TAG, "G5A19 /api/me profile fetched")
            } catch (e: Exception) {
                Log.w(TAG, "G5A19 profile refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                connection?.disconnect()
            }
            runOnUiThread {
                operatorProfileFetching = false
                if (activityHasFocusForBle && ready) queueOperatorProfilePackets(packets)
            }
        }
    }
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
            Log.i(TAG, "G5A43 GATT_STATE owner=activity status=$statusCode state=$newState")
            if (gatt !== watchGatt) {
                try { gatt.close() } catch (_: Exception) { }
                return
            }
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection error: $statusCode")
                runOnUiThread {
                    ready = false
                    status = "Connection failed: $statusCode"
                    bleRetry.fail(android.os.SystemClock.elapsedRealtime())
                }
                writePending = false
                closeGatt(gatt)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    runOnUiThread { status = "Connected. Discovering services..." }
                    try {
                        if (!gatt.discoverServices()) {
                            runOnUiThread {
                                status = "Service discovery could not start"
                                bleRetry.fail(android.os.SystemClock.elapsedRealtime())
                                closeGatt(gatt)
                            }
                        }
                    } catch (_: SecurityException) {
                        runOnUiThread { status = "Bluetooth permission missing" }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    runOnUiThread {
                        ready = false
                        status = "Disconnected"
                        if (activityHasFocusForBle) bleRetry.fail(android.os.SystemClock.elapsedRealtime())
                    }
                    writePending = false
                    negotiatedMtu = 23
                    closeGatt(gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
            if (gatt !== watchGatt) return
            try {
                if (!BleBondPolicy.backgroundMayConnect(gatt.device.bondState)) {
                    status = "Watch not bonded; pair it from the app"
                    closeGatt(gatt)
                    return
                }
            } catch (_: SecurityException) { closeGatt(gatt); return }
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    status = "Service discovery failed: $statusCode"
                    bleRetry.fail(android.os.SystemClock.elapsedRealtime())
                    closeGatt(gatt)
                }
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            val command = service?.getCharacteristic(COMMAND_UUID)
            val response = service?.getCharacteristic(RESPONSE_UUID)
            if (command == null || response == null) {
                runOnUiThread {
                    status = "WDG HUD characteristics not found"
                    bleRetry.fail(android.os.SystemClock.elapsedRealtime())
                    closeGatt(gatt)
                }
                return
            }
            commandCharacteristic = command
            responseCharacteristic = response
            try {
                if (!gatt.setCharacteristicNotification(response, true)) {
                    runOnUiThread { status = "Could not enable local notifications" }
                    return
                }
                val descriptor = response.getDescriptor(CCCD_UUID)
                if (descriptor == null) {
                    runOnUiThread { status = "Notification descriptor not found" }
                    return
                }
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val result = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, value)
                } else {
                    descriptor.value = value
                    gatt.writeDescriptor(descriptor).let { if (it) 0 else -1 }
                }
                if (result != 0) {
                    runOnUiThread { status = "Could not request notifications: $result" }
                }
            } catch (_: SecurityException) {
                runOnUiThread { status = "Bluetooth permission missing" }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (gatt !== watchGatt) return // ignore callbacks from an old GATT session
            if (descriptor.uuid == CCCD_UUID) {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    try {
                        if (!gatt.requestMtu(185)) {
                            runOnUiThread { status = "MTU request could not start" }
                        }
                    } catch (_: SecurityException) {
                        runOnUiThread { status = "Bluetooth permission missing" }
                    }
                } else {
                    runOnUiThread { status = "Notification setup failed: $statusCode" }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, statusCode: Int) {
            runOnUiThread {
                if (gatt !== watchGatt) return@runOnUiThread
                negotiatedMtu = if (statusCode == BluetoothGatt.GATT_SUCCESS) mtu else 23
                ready = negotiatedMtu >= 64
                if (ready) bleRetry.succeed()
                status = if (ready) "Connected - GPS ready (MTU $negotiatedMtu)"
                else "MTU too small ($negotiatedMtu); GPS not enabled"
                if (!ready) {
                    bleRetry.fail(android.os.SystemClock.elapsedRealtime())
                    closeGatt(gatt)
                }
                if (ready) {
                    linkHealth.onReady(android.os.SystemClock.elapsedRealtime())
                    bleHealthText = linkHealth.diagnostic(android.os.SystemClock.elapsedRealtime())
                    Log.i(TAG, "G5A43 LINK_READY owner=activity session=${linkHealth.sessionCount}")
                    // Restore current center and any historical sectors lost in a
                    // previous partial BLE session; do not restart API fetching.
                    lastAcknowledgedCenter = null
                    warmWatchCachePending = true
                    bleAttemptStartedAtMs = 0L
                    watchCapsState = "UNKNOWN"
                    watchGangNameSupported = false
                    watchFullSectorNamesSupported = false
                    fullNameSyncPending = false
                    stopViewport("new BLE session")
                    lastViewportCenter = null
                    viewportSequence = 0L
                    capsAttempts = 0
                    capsHandler.removeCallbacks(capsProbe)
                    capsHandler.postDelayed(capsProbe, 500L)
                    lastClockSyncSentAt = 0L  // new GATT session
                    sendClockIfDue()
                    latestLocation?.let { sendGpsIfReady(it) }
                    // G5A47: start or restore the driving session only after
                    // the watch has completed its BLE handshake. ACTION_START
                    // does not create a second service or take Activity GATT ownership.
                    if (activityHasFocusForBle) startDrivingSession()
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            if (characteristic.uuid != COMMAND_UUID) return
            runOnUiThread {
                if (gatt !== watchGatt) return@runOnUiThread
                writePending = false
                writeStartedAtMs = 0L
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "BLE write failed: $statusCode; restarting link")
                    status = "BLE write failed: $statusCode"
                    bleRetry.fail(android.os.SystemClock.elapsedRealtime())
                    closeGatt(gatt)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == RESPONSE_UUID && gatt === watchGatt) showResponse(value, gatt)
        }

        @Deprecated("Used on Android versions before API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == RESPONSE_UUID && gatt === watchGatt) {
                val value = characteristic.value ?: return
                showResponse(value, gatt)
            }
        }
    }

    private fun showResponse(value: ByteArray, sourceGatt: BluetoothGatt) {
        val message = value.toString(Charsets.UTF_8)
        Log.d(TAG, "Watch response: $message")
        runOnUiThread {
            if (sourceGatt !== watchGatt) return@runOnUiThread
            val clock = android.os.SystemClock.elapsedRealtime()
            val isPong = linkHealth.onResponse(clock, message)
            bleHealthText = linkHealth.diagnostic(clock)
            if (isPong) {
                Log.i(TAG, "G5A43 HEARTBEAT_PONG owner=activity rttMs=${linkHealth.lastPongRttMs}")
                responseText = "PONG"
                return@runOnUiThread
            }
            responseText = message
            StaleIntervalSettings.applyWatchMessage(this@MainActivity, message)?.let { minutes ->
                // Re-evaluate the current 25-sector window with the new age;
                // retain cached records and their original successful timestamps.
                warmWatchCachePending = true
                nextPrefetchRetryAtMs = 0L
                Log.i(TAG, "G5A45.4 STALE_INTERVAL=$minutes minutes")
                cellHandler.post { prefetchFiveByFive() }
                return@runOnUiThread
            }
            if (message == "CENTER:OK" || message == "CENTER:MISS") {
                val acknowledged = awaitingCenter
                acknowledged?.let { lastAcknowledgedCenter = it }
                lastMissedCenter = if (message == "CENTER:MISS") acknowledged else null
                awaitingCenter = null
                if (message == "CENTER:MISS" && acknowledged != null &&
                    missRefillAttemptedFor != acknowledged) {
                    geoPackets.invalidate(WdgWarsGrid.window(acknowledged, 1))
                    warmWatchCachePending = true
                    // Try the existing phone cache and 25-sector lookahead once
                    // before allowing an eventual full-grid scan fallback.
                    missRefillAttemptedFor = acknowledged
                    cellHandler.post { prefetchFiveByFive() }
                }
                if (message == "CENTER:OK" && acknowledged != null) {
                    warmWatchCachePending = true // cache first, HTTP later
                    // The watch has committed a geographically correct 3x3
                    // from its own cache. Missing tile keys show UNKNOWN until
                    // validated per-sector cache records are delivered.
                    lastGpsCell = acknowledged
                    lastGridCoordinates = WdgWarsGrid.window(acknowledged, 1)
                        .map { WdgWarsGrid.queryPoint(it) }
                    // The watch has already updated. Refill stale phone records
                    // in background; never immediately downgrade to a full-grid sync.
                    lastAutoRefreshMs = android.os.SystemClock.elapsedRealtime()
                    // Replenish the outer lookahead independently of the watch view.
                    cellHandler.post { prefetchFiveByFive() }
                }
                Log.i("WDGHUD_GEO", "G5A40 WATCH_ACK_CENTER=$lastAcknowledgedCenter status=$message desired=$desiredWatchCenter")
                return@runOnUiThread
            }
            if (geoPackets.accept(message)) return@runOnUiThread
            if (onOperatorProfileMessage(message)) return@runOnUiThread
            if (onTeamMessage(message)) return@runOnUiThread
            if (message.startsWith("CAPS,") || (watchCapsState == "PROBING" && message == "ERR:UNKNOWN_COMMAND")) {
                watchCapsState = WatchCapabilities.classify(message)
                watchGangNameSupported = WatchCapabilities.supportsGangNames(message)
                watchFullSectorNamesSupported = WatchCapabilities.supportsFullSectorNames(message)
                geoCacheSupported = WatchCapabilities.supportsGeoCache(message)
                if (geoCacheSupported) warmWatchCachePending = true
                Log.i(TAG, "G5A36.1 watch capabilities=$message fullNames=$watchFullSectorNamesSupported")
                if (fullNameSyncPending) {
                    fullNameSyncPending = false
                    if (watchFullSectorNamesSupported) {
                        cellHandler.post { fetch3x3() }
                    } else {
                        apiStatus = "Watch did not negotiate GANG96; existing snapshot preserved"
                    }
                }
                // G5A35: no totals-only viewport push after reconnect.
                // A valid previous watch snapshot stays visible until a full detailed commit.
                return@runOnUiThread
            }
            if (onViewportResponse(message)) return@runOnUiThread
            if (message == "GPS:OK") gpsAckCount++
            if (message == "SCAN:OK" && scanAnnouncePending) {
                scanAnnouncePending = false
                cellHandler.removeCallbacks(scanAnnouncePump)
                fetch3x3Now()
                return@runOnUiThread
            }
            if (message.startsWith("SCAN:") && scanAnnouncePending) {
                scanAnnouncePending = false
                cellHandler.removeCallbacks(scanAnnouncePump)
                apiStatus = "Watch rejected SECTOR SCAN announcement: $message"
                return@runOnUiThread
            }
            if (e3Phase == "begin" && message == "BEGIN:OK") {
                setScanProgress(0)
                e3Phase = "cells"
                e3Deadline = 0L
                cellHandler.post(cellPump)
                return@runOnUiThread
            }
            if (e3Phase == "end" && message == "END:OK") {
                lastGpsCell = pendingGridCenter
                lastAutoRefreshMs = android.os.SystemClock.elapsedRealtime()
                val count = e3ExpectedLevels
                finishCellTransfer("UPLINK COMPLETE // 9 sectors + $count tiers + $e5ExpectedRaw raw levels")
                cellHandler.post { prefetchFiveByFive() }
                return@runOnUiThread
            }
            if ((e3Phase == "begin" || e3Phase == "end") &&
                (message.startsWith("BEGIN:") || message.startsWith("END:"))) {
                finishCellTransfer("E3 WATCH REJECTED // $message")
                return@runOnUiThread
            }
            if (levelPackets.isNotEmpty() && levelAwaitingAck) {
                val sentPacket = levelPackets.getOrNull(levelNext) ?: ""
                val expectedAck = if (sentPacket.startsWith("RAW,")) "RAW:OK" else "LVL:OK"
                if (message == expectedAck) {
                    levelAwaitingAck = false
                    levelNext++
                    updateTransferProgress()
                    if (levelNext == levelPackets.size) {
                        val count = levelPackets.size
                        e3Phase = "end"
                        e3Deadline = 0L
                        cellHandler.post(cellPump)
                    }
                } else if (message.startsWith("LVL:") || message.startsWith("RAW:")) {
                    finishCellTransfer("LEVEL UPLINK REJECTED // $message")
                }
            }
            if (cellPackets.isNotEmpty() && cellAwaitingAck) {
                val expected = if (cellPackets.getOrNull(cellNext)?.startsWith("NAME,") == true)
                    "NAME:OK" else "CELL:OK"
                if (message == expected) {
                    cellAwaitingAck = false
                    cellNext++
                    // CELL/NAME are sector metadata: no AP bar progress here.
                    if (cellNext == cellPackets.size) {
                        cellPackets = emptyList()
                        e3Phase = "levels"
                        cellTransferStatus = "E3 // Sending AP levels"
                        cellHandler.post(cellPump)
                    }
                } else if (message.startsWith("CELL:") || message.startsWith("NAME:")) {
                    finishCellTransfer("Watch rejected cell ${cellNext + 1}: $message")
                }
            }
        }
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { closeGatt(gatt) }
            return
        }
        try { gatt.disconnect() } catch (_: Exception) { }
        try { gatt.close() } catch (_: Exception) { }
        if (watchGatt === gatt) {
            linkHealth.onDisconnected(status)
            bleHealthText = linkHealth.diagnostic(android.os.SystemClock.elapsedRealtime())
            // A disconnected partial transaction must NOT block new capability
            // negotiation. The watch retains its last fully validated grid.
            ready = false
            scanAnnouncePending = false
            cellHandler.removeCallbacks(scanAnnouncePump)
            if (e3Phase != "idle") finishCellTransfer("UPLINK LOST // retry at next sector check")
            else endScanProgress()
            writeStartedAtMs = 0L
            bleAttemptStartedAtMs = 0L
            lastAutoRefreshMs = 0L
            watchGatt = null
            commandCharacteristic = null
            responseCharacteristic = null
            writePending = false
            clearOperatorProfileTransfer()
            negotiatedMtu = 23
            capsHandler.removeCallbacks(capsProbe)
            watchCapsState = "UNKNOWN"
            watchGangNameSupported = false
            watchFullSectorNamesSupported = false
            geoCacheSupported = false
            geoPackets.resetRemoteState()
            geoRetry.succeed() // next GATT session can refill from saved records
            awaitingCenter = null
            lastAcknowledgedCenter = null
            fullNameSyncPending = false
            stopViewport("GATT closed")
            lastViewportCenter = null
            viewportSequence = 0L
        }
    }

    // G5A44: only the visible Activity initiates first-time Bluetooth bonding.
    // A cancelled PIN dialog stops automatic retries until the user taps SCAN.
    private var watchPairingInProgress = false
    private var pairingRejected = false
    private var pairingStartMs = 0L
    private var pairingAddress: String? = null
    private var bondReceiverRegistered = false
    private val watchBondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                ?: return
            if (device.address != pairingAddress) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    watchPairingInProgress = false
                    pairingRejected = false
                    pairingAddress = null
                    Log.i(TAG, "G5A44 BOND_COMPLETE owner=activity")
                    status = "Watch paired securely; connecting..."
                    if (activityHasFocusForBle && watchGatt == null) {
                        watchDevice = device
                        connectToWatch()
                    }
                }
                BluetoothDevice.BOND_NONE -> {
                    if (watchPairingInProgress) {
                        watchPairingInProgress = false
                        pairingRejected = true
                        pairingAddress = null
                        status = "Pairing canceled or failed. Tap SCAN to retry."
                        Log.w(TAG, "G5A44 BOND_FAILED owner=activity")
                    }
                }
            }
        }
    }

    private var connectAfterScan = false
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: "Unnamed device"
            if (name == WATCH_NAME) {
                runOnUiThread { watchDevice = result.device; status = "Found WDG HUD!" }
                stopScanning()
                if (connectAfterScan) {
                    connectAfterScan = false
                    runOnUiThread { if (!ready) connectToWatch() }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanning = false
                status = "Scan failed: $errorCode"
                bleRetry.fail(android.os.SystemClock.elapsedRealtime())
            }
        }
    }

    private fun stopScanning() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
    }

    private fun startScanning(autoConnect: Boolean = false) {
        if (!activityHasFocusForBle) return
        if (watchPairingInProgress) return
        val retryingFailedPairing = pairingRejected && !autoConnect
        if (!autoConnect) pairingRejected = false
        if (pairingRejected) return
        if (ready) return
        connectAfterScan = autoConnect || retryingFailedPairing
        if (!bluetoothAdapter.isEnabled) { status = "Please enable Bluetooth"; return }
        stopScanning()
        watchDevice = null
        ready = false
        try {
            scanning = true
            status = "Scanning..."
            scanner?.startScan(scanCallback)
            // End unsuccessful discovery after 15 seconds so reconnectTick can retry.
            reconnectHandler.postDelayed({
                if (scanning && !ready && activityHasFocusForBle) {
                    stopScanning()
                    bleRetry.fail(android.os.SystemClock.elapsedRealtime())
                }
            }, 15000L)
        } catch (_: SecurityException) {
            scanning = false
            status = "Bluetooth permission missing"
        }
    }

    private fun connectToWatch() {
        if (!activityHasFocusForBle) return
        val device = watchDevice ?: run { status = "Scan for watch first"; return }
        try {
            // No GATT protocol traffic before the OS has authenticated the
            // watch's six-digit DisplayOnly passkey and stored its bond.
            when (BleBondPolicy.foreground(device.bondState, pairingRejected)) {
                BleBondPolicy.ForegroundAction.WAIT -> {
                    watchPairingInProgress = true
                    pairingStartMs = android.os.SystemClock.elapsedRealtime()
                    pairingAddress = device.address
                    status = "Pairing... enter code shown on the watch"
                    return
                }
                BleBondPolicy.ForegroundAction.PAIR -> {
                    // Install the matching address BEFORE asking Android to
                    // start pairing; fast bond broadcasts cannot be missed.
                    pairingStartMs = android.os.SystemClock.elapsedRealtime()
                    watchPairingInProgress = true
                    pairingAddress = device.address
                    val started = device.createBond()
                    if (started) {
                        status = "Pairing... enter code shown on the watch"
                        Log.i(TAG, "G5A44 BOND_REQUESTED owner=activity")
                    } else {
                        watchPairingInProgress = false
                        pairingAddress = null
                        pairingRejected = true
                        status = "Could not start secure pairing; tap SCAN to retry"
                    }
                    return
                }
                BleBondPolicy.ForegroundAction.CONNECT -> Unit
                BleBondPolicy.ForegroundAction.BLOCK -> {
                    status = "Tap SCAN to retry canceled pairing"
                    return
                }
            }
            watchPairingInProgress = false
            watchGatt?.let { closeGatt(it) }
            ready = false
            status = "Connecting..."
            bleAttemptStartedAtMs = android.os.SystemClock.elapsedRealtime()
            watchGatt = device.connectGatt(this, false, gattCallback)
        } catch (_: SecurityException) {
            status = "Bluetooth permission missing"
        }
    }

    private fun writeCommand(message: String): Boolean {
        val gatt = watchGatt ?: return false
        val command = commandCharacteristic ?: return false
        if (!ready || writePending) return false
        val payload = message.toByteArray(Charsets.UTF_8)
        if (payload.size > negotiatedMtu - 3) {
            Log.e(TAG, "Packet too large for MTU $negotiatedMtu: ${payload.size} bytes")
            return false
        }
        return try {
            writePending = true
            val result = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(command, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                command.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                command.value = payload
                gatt.writeCharacteristic(command).let { if (it) 0 else -1 }
            }
            if (result != 0) {
                writePending = false
                writeStartedAtMs = 0L
                Log.e(TAG, "Write request rejected: $result")
                false
            } else {
                writeStartedAtMs = android.os.SystemClock.elapsedRealtime()
                true
            }
        } catch (_: SecurityException) {
            writePending = false
            writeStartedAtMs = 0L
            runOnUiThread { status = "Bluetooth permission missing" }
            false
        }
    }

    private fun sendPing() {
        if (writeCommand("PING")) {
            responseText = "Waiting for PONG..."
            Log.d(TAG, "Sent PING")
        } else {
            Log.d(TAG, "PING not sent (not ready or BLE busy)")
        }
    }

    private val gpsOffHandler = Handler(Looper.getMainLooper())
    private var gpsOffPending = false
    private val gpsOffAttempt = object : Runnable {
        override fun run() {
            if (!gpsOffPending || !ready) return
            if (writeCommand("GPSOFF")) {
                gpsOffPending = false
            } else {
                gpsOffHandler.postDelayed(this, 200L)
            }
        }
    }
    private fun signalGpsUnavailable() {
        gpsOffPending = true
        gpsOffHandler.removeCallbacks(gpsOffAttempt)
        gpsOffHandler.post(gpsOffAttempt)
    }

    private fun sendClockIfDue(): Boolean {
        if (!ready || writePending || viewportPackets.isNotEmpty() ||
            operatorProfilePackets.isNotEmpty() ||
            cellAwaitingAck || levelAwaitingAck || e3Phase != "idle") return false
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (lastClockSyncSentAt != 0L && elapsed - lastClockSyncSentAt < 600_000L)
            return false
        val clock = java.util.Calendar.getInstance() // phone's local time, 24-hour
        val packet = String.format(Locale.US, "TIME,%04d,%02d,%02d,%02d,%02d,%02d,%d",
            clock.get(java.util.Calendar.YEAR),
            clock.get(java.util.Calendar.MONTH) + 1,
            clock.get(java.util.Calendar.DAY_OF_MONTH),
            clock.get(java.util.Calendar.HOUR_OF_DAY),
            clock.get(java.util.Calendar.MINUTE),
            clock.get(java.util.Calendar.SECOND),
            clock.get(java.util.Calendar.DAY_OF_WEEK) - 1)
        if (!writeCommand(packet)) return false
        lastClockSyncSentAt = elapsed
        Log.d(TAG, "G5A15 watch local-time sync sent")
        return true
    }

    private fun sendGpsIfReady(location: Location) {
        if (!gpsActive || !ready || viewportPackets.isNotEmpty() || operatorProfilePackets.isNotEmpty() ||
            writePending || cellAwaitingAck || levelAwaitingAck ||
            e3Phase == "begin" || e3Phase == "end") return
        // Never send a cached location as a new fix; wait for fresh GPS telemetry.
        val fixAgeMs = (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (fixAgeMs !in 0L..30000L) return
        if (sendClockIfDue()) return // leave one GPS write slot for time sync
        gpsOffPending = false
        gpsOffHandler.removeCallbacks(gpsOffAttempt)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastGpsSentAt < 1000L) return
        val speedMph = if (location.hasSpeed()) location.speed * 2.2369363 else 0.0
        // Lower speed threshold allows a walking test. An invalid new heading must
        // NOT erase the last reliable course on the watch when motion stops.
        // Send the SAME bearing the UI displays. The UI records a new heading
        // when motion is reliable and shows the last established heading at rest.
        // Never substitute a valid north (0 degrees) for an unknown heading.
        val bearingValid = lastReliableBearing != null
        val bearing = lastReliableBearing?.toDouble() ?: 0.0
        val packet = String.format(
            Locale.US, "GPS,%.6f,%.6f,%.1f,%.1f,%d",
            location.latitude, location.longitude, speedMph, bearing,
            if (bearingValid) 1 else 0
        )
        if (writeCommand(packet)) {
            lastGpsSentAt = now
            Log.d(TAG, "Sent GPS: $packet")
            runOnUiThread { gpsSentCount++ }
        }
    }

    private fun testApiKey() {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            apiKeyError = true
            keyTestStatus = "AUTH // Credentials required"
            return
        }
        if (keyTesting) return
        apiKeyError = false
        keyTesting = true
        keyTestStatus = "AUTH // Verifying credentials..."
        apiExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://wdgwars.pl/api/me").openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("X-API-Key", key)
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val type = connection.contentType ?: "unknown"
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val validJson = try { JSONObject(body); true } catch (_: Exception) { false }
                runOnUiThread {
                    keyTestStatus = when {
                        code == 200 && validJson -> "AUTH VERIFIED // Uplink authorized"
                        else -> "AUTH REJECTED // HTTP $code ($type)"
                    }
                    keyTesting = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    keyTestStatus = "AUTH ERROR // ${e.javaClass.simpleName}"
                    keyTesting = false
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    // Diagnostic only: checks whether X-API-Key can access cell-detail without browser cookies.
    private var cellDetailTesting by mutableStateOf(false)
    private var cellDetailResult by mutableStateOf("Cell detail API not tested")

    private fun testCellDetailApi() {
        if (cellDetailTesting) return
        val key = apiKey.trim()
        if (key.isEmpty()) { cellDetailResult = "Enter your API key first"; return }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            cellDetailResult = "Precise location permission required"; return
        }
        val location = latestLocation
        if (location == null) { cellDetailResult = "Start GPS and wait for a fix first"; return }
        cellDetailTesting = true
        cellDetailResult = "Testing /api/cell-detail using API key only..."
        val lat = location.latitude
        val lon = location.longitude
        apiExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(String.format(Locale.US,
                    "https://wdgwars.pl/api/cell-detail?lat=%.8f&lng=%.8f", lat, lon))
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("X-API-Key", key)
                connection.setRequestProperty("Accept", "application/json")
                val statusCode = connection.responseCode
                val contentType = connection.contentType ?: "unknown"
                val redirect = connection.getHeaderField("Location")
                val result = if (statusCode == 200 && contentType.contains("json", ignoreCase = true)) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", false)) {
                        "HTTP 200 JSON, but ok=false: " + json.optString("error", "no details")
                    } else {
                        val levels = json.optJSONArray("levels")
                        val sorted = mutableListOf<Pair<Int, Int>>()
                        if (levels != null) {
                            for (i in 0 until levels.length()) {
                                val level = levels.optJSONObject(i) ?: continue
                                sorted.add(level.optInt("lvl") to level.optInt("c"))
                            }
                        }
                        val top = sorted.sortedByDescending { it.second }.take(3)
                            .joinToString(" - ") { "L${it.first}:${it.second}" }
                        "SUCCESS: API key accesses cell-detail!\n" +
                                "GPS: %.5f, %.5f\n".format(Locale.US, lat, lon) +
                                "Total APs: ${json.optInt("total")}\n" +
                                "Highest level: L${json.optInt("max_level")}\n" +
                                "Top 3 by count: $top"
                    }
                } else {
                    "HTTP $statusCode ($contentType)" +
                            (if (redirect != null) "\nRedirect: $redirect" else "") +
                            "\nAPI key alone did not return cell-detail JSON."
                }
                runOnUiThread { cellDetailResult = result; cellDetailTesting = false }
            } catch (e: Exception) {
                runOnUiThread {
                    cellDetailResult = "Cell detail test failed: ${e.javaClass.simpleName}: ${e.message}"
                    cellDetailTesting = false
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    // Use the WebView's own WDGWars session cookies. The API-key client does
    // not have access to /api/cell-detail; never copy cookies into it.
    private fun fetchLevelsForCurrentGrid() {
        if (lastGridCoordinates.size != 9 || levelFetchPending) {
            cellTransferStatus = "LEVEL UPLINK // 9-sector grid unavailable"
            cancelScanningAnnouncement()
            return
        }
        levelFetchPending = true
        cellTransferStatus = "LEVEL UPLINK // Querying authenticated sectors"
        val browser = WebView(this)
        levelBrowser = browser
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.addJavascriptInterface(object {
            @JavascriptInterface
            fun progress(done: Int, total: Int) {
                // API fetch is NOT the AP-to-watch uplink: do not send PROG.
            }
            @JavascriptInterface
            fun report(json: String) {
                runOnUiThread {
                    if (levelBrowser !== browser) return@runOnUiThread
                    levelFetchPending = false
                    try {
                        val result = JSONObject(json)
                        val items = result.optJSONArray("results")
                        val packets = mutableListOf<String>()
                        // The territory grid and /api/cell-detail may report different
                        // AP totals. Use cell-detail.total alongside its own histogram;
                        // retain territory gang ID and official border color.
                        val detailedCells = cellPackets.toMutableList()
                        var successful = 0
                        val rawPackets = mutableListOf<String>()
                        val detailDiagnostics = mutableListOf<String>()
                        val detailSignatures = mutableMapOf<String, MutableList<Int>>()
                        if (items != null) for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val index = item.optInt("index", -1)
                            val levels = item.optJSONArray("levels")
                            val detailedTotal = item.optLong("total", -1L)
                            if (index !in 0..8 || levels == null || detailedTotal < 0L ||
                                detailedTotal > Int.MAX_VALUE) continue
                            successful++
                            val requested = lastGridCoordinates.getOrNull(index)
                            Log.i("WDGHUD_GRID", "DETAIL[$index] requested=$requested total=$detailedTotal levels=${levels.length()} serverLat=${item.optString("api_lat", "-")} serverLng=${item.optString("api_lng", "-")}")
                            // Transmit ALL nonempty hardness tiers, not just the 3 shown.
                            // Watch selects dominant + two hardest after validating totals.
                            // Tier ID = 10-level bands (1..10); raw levels retain full precision.
                            val tierCounts = mutableMapOf<Int, Long>()
                            val rawEntries = mutableListOf<Pair<Int, Long>>()
                            for (j in 0 until levels.length()) {
                                val obj = levels.optJSONObject(j) ?: continue
                                val lvl = obj.optInt("lvl", 0)
                                val count = obj.optLong("c", -1L)
                                if (lvl !in 1..255 || count < 0L) {
                                    throw IllegalStateException("Invalid AP level in sector $index: $lvl/$count")
                                }
                                if (count == 0L) continue
                                rawEntries.add(lvl to count)
                                val tier = ApTierBands.fromLevel(lvl)
                                tierCounts[tier] = (tierCounts[tier] ?: 0L) + count
                            }
                            // Abort instead of showing an incomplete or truncated snapshot.
                            val summed = tierCounts.values.sum()
                            val signature = "$detailedTotal:" + tierCounts.toSortedMap().entries.joinToString(";") {
                                "${it.key}=${it.value}"
                            }
                            detailSignatures.getOrPut(signature) { mutableListOf() }.add(index)
                            val detailLat = requested?.first ?: Double.NaN
                            val detailLon = requested?.second ?: Double.NaN
                            detailDiagnostics.add("DETAIL[$index] (%.5f, %.5f) AP=%d tiers=%d".format(
                                Locale.US, detailLat, detailLon, detailedTotal, tierCounts.size))
                            if (summed != detailedTotal) {
                                throw IllegalStateException(
                                    "Sector $index cell-detail levels sum $summed != cell-detail total $detailedTotal"
                                )
                            }
                            val original = detailedCells.firstOrNull {
                                it.startsWith("CELL,$index,")
                            } ?: throw IllegalStateException("Missing territory packet for sector $index")
                            val fields = original.split(',', limit = 6)
                            if (fields.size != 6) {
                                throw IllegalStateException("Invalid territory packet for sector $index")
                            }
                            detailedCells[index] = "CELL,$index,$detailedTotal,${fields[3]},${fields[4]},${fields[5]}"
                            rawEntries.sortedBy { it.first }.forEach { (level, amount) ->
                                if (amount > Int.MAX_VALUE) throw IllegalStateException("Raw level exceeds Int32")
                                rawPackets.add("RAW,$index,$level,$amount")
                            }
                            tierCounts.toSortedMap().forEach { (tier, amount) ->
                                if (amount > Int.MAX_VALUE) {
                                    throw IllegalStateException("Sector $index tier count exceeds Int32")
                                }
                                packets.add("LVL,$index,${tier - 1},$tier,$amount")
                            }
                        }
                        // Matching AP counts alone do not prove a mapping error; log
                        // any exact repeated detail totals/histograms for diagnostics.
                        if (successful == 9 && ready) {
                            val duplicates = detailSignatures.values.filter { it.size > 1 }
                            val diagnosticSummary = buildString {
                                append("\n\nE4.3 CELL-DETAIL RESULTS\n")
                                append(detailDiagnostics.sorted().joinToString("\n"))
                                append("\nIdentical AP/hardness profiles: ")
                                append(if (duplicates.isEmpty()) "none" else duplicates.joinToString("; ") {
                                    it.sorted().joinToString(prefix = "slots ", separator = ",")
                                })
                                append(" (identical profiles can occur legitimately)")
                            }
                            apiResults += diagnosticSummary
                            Log.i("WDGHUD_GRID", diagnosticSummary)
                            // Cache ONLY fully validated responses, never partial/error data.
                            // Cache by stable geographic point, never by 3x3 tile index.
                            val cacheNow = android.os.SystemClock.elapsedRealtime()
                            for (j in 0 until (items?.length() ?: 0)) {
                                val item = items?.optJSONObject(j) ?: continue
                                val idx = item.optInt("index", -1)
                                val point = lastGridCoordinates.getOrNull(idx) ?: continue
                                if (item.optBoolean("fromCache", false)) continue
                                val payload = JSONObject(item.toString()).apply { remove("index") }
                                // Reused entries retain their original age (not refreshed).
                                val k = detailCacheKey(point.first, point.second)
                                if (!detailCache.containsKey(k) ||
                                    cacheNow - (detailCache[k]?.fetchedAtMs ?: 0L) >= detailCacheTtlMs) {
                                    detailCache[k] = CachedDetail(payload.toString(), cacheNow)
                                }
                            }
                            // Bound only this transient RAM working set.
                            // G5A42 SQLite history retains ALL prior records.
                            if (detailCache.size > 2000) {
                                detailCache.entries.sortedBy { it.value.fetchedAtMs }
                                    .take(detailCache.size - 1600).forEach { detailCache.remove(it.key) }
                            }
                            saveSharedSectorCache()
                            // Both CELL totals and LVL tiers now originate from the
                            // same authenticated cell-detail response per sector.
                            cellPackets = detailedCells +
                                    (if (watchFullSectorNamesSupported) pendingSectorNames else emptyList())
                            levelPackets = packets + rawPackets
                            uplinkTotalPackets = ApUplinkProgress.countApLevelPackets(levelPackets)
                            beginScanProgress() // validated AP data; actual BLE BEGIN next
                            e5ExpectedRaw = rawPackets.size
                            levelNext = 0
                            levelAwaitingAck = false
                            e3ExpectedLevels = packets.size
                            e3Phase = "begin"
                            e3Deadline = 0L
                            cellHandler.post(cellPump)
                        } else {
                            finishCellTransfer("LEVEL UPLINK PARTIAL // $successful/9 authenticated sectors; sign in to WDGWars")
                        }
                    } catch (e: Exception) {
                        cellTransferStatus = "LEVEL UPLINK ERROR // ${e.message ?: e.javaClass.simpleName}"
                        finishCellTransfer(cellTransferStatus)
                    } finally {
                        browser.destroy()
                        if (levelBrowser === browser) levelBrowser = null
                    }
                }
            }
        }, "WDGHudResult")
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (levelBrowser !== browser) return
                val host = android.net.Uri.parse(url).host
                if (host != "wdgwars.pl" && host?.endsWith(".wdgwars.pl") != true) {
                    levelFetchPending = false
                    cellTransferStatus = "LEVEL UPLINK // WDGWars login required"
                    finishCellTransfer(cellTransferStatus)
                    browser.destroy()
                    levelBrowser = null
                    return
                }
                val locations = org.json.JSONArray()
                lastGridCoordinates.forEach { (lat, lon) ->
                    locations.put(org.json.JSONArray().put(lat).put(lon))
                }
                val cachedResults = org.json.JSONArray()
                val fetchTime = android.os.SystemClock.elapsedRealtime()
                // A missing/stale sector is fetched; fresh cached sectors are reused.
                lastGridCoordinates.forEachIndexed { index, (lat, lon) ->
                    val cached = detailCache[detailCacheKey(lat, lon)]
                    if (cached != null && fetchTime - cached.fetchedAtMs in 0 until detailCacheTtlMs) {
                        val item = JSONObject(cached.payload)
                        item.put("index", index)
                        cachedResults.put(item)
                    }
                }
                val js = """(async function() {
                    const locations = $locations;
                    const cached = $cachedResults;
                    const cacheByIndex = new Map(cached.map(item => [item.index, item]));
                    const results = [];
                    for (let i = 0; i < locations.length; i++) {
                        const [lat, lng] = locations[i];
                        if (cacheByIndex.has(i)) {
                            results.push(cacheByIndex.get(i));
                            WDGHudResult.progress(i + 1, locations.length);
                            continue;
                        }
                        try {
                            const path = '/api/cell-detail?lat=' + encodeURIComponent(lat) +
                                         '&lng=' + encodeURIComponent(lng);
                            const r = await fetch(path, {credentials:'same-origin',
                                headers:{'Accept':'application/json'}});
                            const type = r.headers.get('content-type') || '';
                            if (!r.ok || !type.includes('json')) {
                                results.push({index:i, error:'Login required'}); continue;
                            }
                            const data = await r.json();
                            if (!data.ok || !Array.isArray(data.levels)) {
                                results.push({index:i, error:'No levels'}); continue;
                            }
                            results.push({index:i, total:data.total, levels:data.levels,
                                api_lat:data.lat ?? null, api_lng:data.lng ?? null});
                        } catch (e) { results.push({index:i, error:String(e)}); }
                        finally { WDGHudResult.progress(i + 1, locations.length); }
                    }
                    WDGHudResult.report(JSON.stringify({results:results}));
                })();""".trimIndent()
                browser.evaluateJavascript(js, null)
            }
        }
        browser.loadUrl("https://wdgwars.pl/")
        // Prevent a stalled page from leaving the app stuck in a loading state.
        cellHandler.postDelayed({
            if (levelBrowser === browser && levelFetchPending) {
                levelFetchPending = false
                levelBrowser = null
                browser.stopLoading()
                browser.destroy()
                cellTransferStatus = "LEVEL UPLINK TIMEOUT // Verify website login"
                finishCellTransfer(cellTransferStatus)
            }
        }, 45000L)
    }

    // The website login stays inside WebView's own cookie store. We never read
    // or copy session cookies into the app's API-key HTTP client.
    // G5A48: the old manual AP-level tests were diagnostics, not part of normal
    // sector synchronization. Verify the logged-in website automatically.
    private fun openWebsiteLogin() {
        val dialog = Dialog(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = TextView(this).apply {
            text = "Log in to WDGWars. Your login will be used automatically for sector updates."
            setPadding(20, 16, 20, 16)
        }
        layout.addView(heading)
        val browser = WebView(this)
        browser.settings.javaScriptEnabled = true // Required by WDGWars and authenticated sector fetching.
        browser.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        browser.addJavascriptInterface(object {
            @JavascriptInterface
            fun verified() {
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    websiteLoginVerified = true
                    getPreferences(Context.MODE_PRIVATE).edit()
                        .putBoolean("website_login_verified", true).apply()
                    Log.i(TAG, "G5A48 Website login verified automatically")
                }
            }
        }, "WDGHudLogin")
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return true
                // Keep the embedded browser on WDGWars domains only.
                return !(host == "wdgwars.pl" || host.endsWith(".wdgwars.pl"))
            }

            override fun onPageFinished(view: WebView, url: String) {
                val host = android.net.Uri.parse(url).host ?: return
                if (host != "wdgwars.pl" && !host.endsWith(".wdgwars.pl")) return
                // Reuse the authenticated endpoint formerly used by the manual
                // login test. A failed/unauthenticated request changes nothing.
                val location = latestLocation ?: return
                val path = String.format(Locale.US,
                    "/api/cell-detail?lat=%.8f&lng=%.8f", location.latitude, location.longitude)
                val js = """(async function() {
                    try {
                        const response = await fetch(${JSONObject.quote(path)}, {
                            credentials: 'same-origin', headers: {'Accept': 'application/json'}
                        });
                        if (!response.ok || !(response.headers.get('content-type') || '').includes('json')) return;
                        const data = await response.json();
                        if (data && data.ok === true && Array.isArray(data.levels)) WDGHudLogin.verified();
                    } catch (_) { /* Website session not yet authenticated; keep login open. */ }
                })();""".trimIndent()
                view.evaluateJavascript(js, null)
            }
        }
        layout.addView(browser, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val close = AndroidButton(this).apply { text = "Close login window" }
        close.setOnClickListener { dialog.dismiss() }
        layout.addView(close)
        dialog.setContentView(layout)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnDismissListener {
            browser.stopLoading()
            browser.destroy()
        }
        browser.loadUrl("https://wdgwars.pl/")
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private data class TerritoryCell(
        val lat: Double, val lon: Double, val count: Int,
        val gangId: Int, val users: Int, val towers: Int, val color: String?,
        val gangName: String?
    )

    // Start the watch announcement BEFORE contacting the territory API.
    // Wait for the watch's acknowledgement so SCANNING is not delayed until BEGIN.
    private var scanAnnouncePending = false
    private var scanAnnounceDeadline = 0L
    private val scanAnnouncePump = object : Runnable {
        override fun run() {
            if (!scanAnnouncePending) return
            if (!ready || android.os.SystemClock.elapsedRealtime() > scanAnnounceDeadline) {
                scanAnnouncePending = false
                apiStatus = "Could not start SECTOR SCAN on the watch"
                return
            }
            if (!writePending && writeCommand("SCAN")) {
                // Wait for SCAN:OK. Do not issue another SCAN until its ACK.
                return
            }
            cellHandler.postDelayed(this, 100L)
        }
    }
    private fun cancelScanningAnnouncement() {
        endScanProgress()
        // Best effort cleanup for API failure; retries if a GPS packet owns the BLE write.
        val stop = object : Runnable {
            override fun run() {
                if (ready && !writePending && !cellAwaitingAck && !levelAwaitingAck &&
                    e3Phase == "idle" && !writeCommand("SCANSTOP")) {
                    cellHandler.postDelayed(this, 150L)
                } else if (ready && writePending) {
                    cellHandler.postDelayed(this, 150L)
                }
            }
        }
        cellHandler.post(stop)
    }
    private fun fetch3x3() {
        if (!activityHasFocusForBle) return
        if (viewportPackets.isNotEmpty()) {
            cellHandler.postDelayed({ fetch3x3() }, 300L)
            return
        }
        if (apiLoading || scanAnnouncePending || e3Phase != "idle" || levelFetchPending) return
        if (apiKey.trim().isEmpty()) {
            apiKeyError = true
            apiStatus = "AUTH ERROR // KEY REQUIRED"
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            apiStatus = "Precise location permission required"; return
        }
        if (latestLocation == null) { apiStatus = "Start GPS and wait for a fix first"; return }
        if (!ready) { apiStatus = "Connect the watch before scanning"; return }
        // G5A36.1: a fresh GATT connection may still be probing capabilities.
        // Never send a legacy CELL-only snapshot when GANG96 is not confirmed.
        if (!watchFullSectorNamesSupported) {
            if (watchCapsState == "UNKNOWN" || watchCapsState == "PROBING") {
                fullNameSyncPending = true
                apiStatus = "Waiting for full gang-name BLE negotiation"
                return
            }
            apiStatus = "Watch GANG96 not confirmed; reconnect watch before syncing"
            return
        }
        geoPackets.abort("full snapshot priority")
        fiveByFivePrefetch.cancel() // prioritize real watch sync over speculative preload
        // SECTOR SCAN is shown ONLY when a required sector in the 25-tile
        // pattern has no fresh, validated AP detail stored locally.
        val fix = latestLocation ?: return
        val center = WdgWarsGrid.sectorAt(fix.latitude, fix.longitude)
        val now = android.os.SystemClock.elapsedRealtime()
        val missing = SectorScanCoverage.missingIds(center) { id -> cachedTotal(id, now) != null }
        Log.i("WDGHUD_SCAN", "COVERAGE center=$center missing=${missing.size}/25 announce=${missing.isNotEmpty()}")
        // CHECKING reflects an actual nearby 25-sector coverage evaluation,
        // never the routine 30-second GPS poll. SCAN and BEGIN automatically
        // supersede it; the watch has a timeout if API/BLE becomes unavailable.
        if (!writePending && operatorProfilePackets.isEmpty() && !cellAwaitingAck &&
            !levelAwaitingAck && e3Phase == "idle") {
            writeCommand("HUD,CHECK,START")
        }
        if (missing.isEmpty()) {
            // Cache hit: no misleading scan blink, but the legacy full-detail
            // transfer still uses SYNCHRONIZING and its real progress bar.
            apiStatus = "Cached 25/25 // synchronizing"
            fetch3x3Now()
        } else {
            scanAnnouncePending = true
            scanAnnounceDeadline = now + 6000L
            apiStatus = "SECTOR SCAN // ${missing.size}/25 missing"
            cellHandler.post(scanAnnouncePump)
        }
    }
    private fun fetch3x3Now() {
        if (apiLoading) return
        val key = apiKey.trim()
        if (key.isEmpty()) {
            apiKeyError = true
            apiStatus = "AUTH ERROR // KEY REQUIRED"
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            apiStatus = "Precise location permission required"; return
        }
        val location = latestLocation
        if (location == null) { apiStatus = "Start GPS and wait for a fix first"; return }
        val gpsLat = location.latitude
        val gpsLon = location.longitude
        apiLoading = true
        apiStatus = "Reading territory cells near GPS..."
        apiResults = ""
        apiExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://wdgwars.pl/api/member-territories")
                    .openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 45000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("X-API-Key", key)
                val code = connection.responseCode
                val type = connection.contentType ?: "unknown"
                if (code != 200 || !type.contains("json", ignoreCase = true)) {
                    throw IllegalStateException("HTTP $code ($type), redirect=${connection.getHeaderField("Location") ?: "none"}")
                }

                // Stream the worldwide JSON rather than loading it all into phone memory.
                val nearby = mutableListOf<TerritoryCell>()
                var scanned = 0
                var ok = false
                connection.inputStream.bufferedReader().use { input ->
                    JsonReader(input).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "ok" -> ok = reader.nextBoolean()
                                "cells" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        var lat = Double.NaN
                                        var lon = Double.NaN
                                        var count = 0
                                        var gangId = 0
                                        var users = 0
                                        var towers = 0
                                        var color: String? = null
                                        var gangName: String? = null
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "lat" -> lat = reader.nextDouble()
                                                "lng" -> lon = reader.nextDouble()
                                                "count" -> count = reader.nextInt()
                                                "gang_id" -> gangId = reader.nextInt()
                                                "gang" -> gangName = when (reader.peek()) {
                                                    android.util.JsonToken.STRING -> reader.nextString()
                                                    android.util.JsonToken.NULL -> { reader.nextNull(); null }
                                                    else -> { reader.skipValue(); null }
                                                }
                                                "users" -> users = reader.nextInt()
                                                "towers" -> towers = reader.nextInt()
                                                "color" -> {
                                                    if (reader.peek() == android.util.JsonToken.NULL) reader.nextNull()
                                                    else color = reader.nextString().takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
                                                }
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                        scanned++
                                        if (lat.isFinite() && lon.isFinite() &&
                                            kotlin.math.abs(lat - gpsLat) <= 0.08 &&
                                            kotlin.math.abs(lon - gpsLon) <= 0.10) {
                                            nearby.add(TerritoryCell(lat, lon, count, gangId, users, towers, color, gangName))
                                        }
                                    }
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
                if (!ok) throw IllegalStateException("API returned ok=false")
                // Canonical 0.02 x 0.02 sector grid; territory records supply ownership only.
                // Unlisted / unclaimed cells MUST still get their own cell-detail query.
                val centerId = WdgWarsGrid.sectorAt(gpsLat, gpsLon)
                val sectorIds = WdgWarsGrid.window(centerId, 1)
                val territoryBySector = nearby.associateBy { cell ->
                    WdgWarsGrid.sectorAt(cell.lat, cell.lon)
                }
                val lines = StringBuilder()
                lines.append("GPS: %.5f, %.5f\n".format(Locale.US, gpsLat, gpsLon))
                lines.append("WDGWars sector: $centerId, step=0.02 x 0.02\n")
                val packets = mutableListOf<String>()
                val namePackets = mutableListOf<String>()
                val gridCoordinates = mutableListOf<Pair<Double, Double>>()
                var found = 0
                for ((index, id) in sectorIds.withIndex()) {
                    val (queryLat, queryLon) = WdgWarsGrid.queryPoint(id)
                    val match = territoryBySector[id]
                    gridCoordinates.add(queryLat to queryLon)
                    val gangLabel = GangWireName.encode(match != null, match?.gangId ?: 0, match?.gangName)
                    packets.add("CELL,$index,${match?.count ?: 0},${match?.gangId ?: 0},${match?.color ?: "-"},$gangLabel")
                    namePackets.addAll(GangWireName.namePackets(
                        index, match != null, match?.gangId ?: 0, match?.gangName))
                    if (match != null) found++
                    lines.append("DETAIL [$index] ${id}: %.7f, %.7f  owner=%s\n".format(
                        Locale.US, queryLat, queryLon, match?.gangId?.toString() ?: "unlisted"))
                }
                check(gridCoordinates.size == 9 && gridCoordinates.distinct().size == 9)
                lines.append("Territory cells matched $found/9; detail requests planned 9/9; scanned %,d worldwide".format(
                    Locale.US, scanned))
                Log.d("WDGHUD_API", lines.toString())
                runOnUiThread {
                    apiStatus = "Territory matches: $found/9; requesting detail for 9/9"
                    apiResults = lines.toString()
                    lastGridCoordinates = gridCoordinates.toList()
                    val verifiedOwnership = territoryBySector.mapValues { (_, owner) ->
                        GangWireName.encodeFull(true, owner.gangId, owner.gangName) to owner.color
                    }
                    // Retain verified gang/color metadata across app and
                    // driving-service ownership changes, never replace it
                    // with UNKNOWN from a partial lookup.
                    TerritoryCacheStore.merge(this@MainActivity, verifiedOwnership)
                    territoryForCache = TerritoryCacheStore.load(this@MainActivity)
                    pendingGridCenter = centerId
                    sendLiveCells(packets)
                    pendingSectorNames = namePackets
                }
            } catch (e: Exception) {
                Log.e("WDGHUD_API", "Territory grid failed", e)
                runOnUiThread {
                    apiStatus = "Territory grid failed: ${e.javaClass.simpleName}"
                    apiResults = e.message ?: "Unknown error"
                    cancelScanningAnnouncement()
                }
            } finally {
                connection?.disconnect()
                runOnUiThread { apiLoading = false }
            }
        }
    }

    private fun startDrivingSession() {
        val locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bluetoothGranted = Build.VERSION.SDK_INT < 31 ||
                (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
        if (!locationGranted || !bluetoothGranted) {
            status = "Grant GPS and Bluetooth permissions before starting"
            return
        }
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                android.content.Intent(this, DrivingSessionService::class.java)
                    .setAction(DrivingSessionService.ACTION_START)
            )
            drivingSessionActive = true
            getSharedPreferences("wdghud_session", Context.MODE_PRIVATE)
                .edit().putBoolean("active", true).apply()
        } catch (e: Exception) {
            status = "Could not start session: ${e.message}"
        }
    }

    private fun stopDrivingSession() {
        drivingSessionActive = false
        getSharedPreferences("wdghud_session", Context.MODE_PRIVATE)
            .edit().putBoolean("active", false).apply()
        startService(android.content.Intent(this, DrivingSessionService::class.java)
            .setAction(DrivingSessionService.ACTION_STOP))
    }

    override fun onStart() {
        super.onStart()
        activityHasFocusForBle = true
        if (ready && watchGatt != null)
            linkHealth.onReady(android.os.SystemClock.elapsedRealtime())
        loadSharedSectorCache()
        territoryForCache = TerritoryCacheStore.load(this)
        // Do not reuse a pre-pause location as if it were a new GPS reading.
        latestLocation = null
        desiredWatchCenter = null
        prefetchTargetCenter = null
        prefetchPreviousCenter = null
        nextPrefetchRetryAtMs = 0L
        startGps() // idempotent; also resumes location after Activity returns
        if (drivingSessionActive) {
            startService(android.content.Intent(this, DrivingSessionService::class.java)
                .setAction(DrivingSessionService.ACTION_UI_ACTIVE))
            // Reclaim the BLE link after service gives it up.
            cellHandler.postDelayed({ if (!ready && !scanning) startScanning(autoConnect = true) }, 1800L)
        }
    }

    override fun onStop() {
        clearOperatorProfileTransfer()
        stopViewport("Activity hidden")
        fiveByFivePrefetch.cancel()
        // Must happen before disconnect: never let Activity auto-reconnect in background.
        activityHasFocusForBle = false
        linkHealth.onDisconnected("Activity hidden; BLE handed to driving service")
        bleHealthText = linkHealth.diagnostic(android.os.SystemClock.elapsedRealtime())
        saveSharedSectorCache()
        if (drivingSessionActive) {
            // Single BLE owner: the service takes over after the Activity releases GATT.
            stopScanning()
            try { watchGatt?.disconnect(); watchGatt?.close() } catch (_: SecurityException) { }
            watchGatt = null
            commandCharacteristic = null
            responseCharacteristic = null
            ready = false
            writePending = false
            if (e3Phase != "idle") finishCellTransfer("Activity hidden // transfer deferred to driving service")
            scanAnnouncePending = false
            cellHandler.removeCallbacks(scanAnnouncePump)
            startService(android.content.Intent(this, DrivingSessionService::class.java)
                .setAction(DrivingSessionService.ACTION_UI_HIDDEN))
        }
        // Foreground Activity no longer owns location. Driving service takes
        // over when active; cached watch data is never changed here.
        stopGps()
        latestLocation = null
        desiredWatchCenter = null
        super.onStop()
    }

    override fun onDestroy() {
        if (bondReceiverRegistered) {
            unregisterReceiver(watchBondReceiver)
            bondReceiverRegistered = false
        }
        stopViewport("Activity destroyed")
        fiveByFivePrefetch.cancel()
        levelBrowser?.let { browser ->
            browser.stopLoading()
            browser.destroy()
        }
        levelBrowser = null
        levelFetchPending = false
        stopGps()
        cellHandler.removeCallbacks(cellPump)
        cellHandler.removeCallbacks(scanAnnouncePump)
        gpsRetryHandler.removeCallbacks(gpsRetryTick)
        autoRefreshHandler.removeCallbacks(autoRefreshTick)
        cellHandler.removeCallbacks(geoPump)
        reconnectHandler.removeCallbacks(reconnectTick)
        stopScanning()
        apiExecutor.shutdownNow()
        anchorExecutor.shutdownNow()
        operatorProfileExecutor.shutdownNow()
        teamExecutor.shutdownNow()
        cellHandler.removeCallbacks(teamPump)
        cellHandler.removeCallbacks(teamBackgroundRefresh)
        try { watchGatt?.disconnect() } catch (_: SecurityException) { }
        watchGatt?.close()
        watchGatt = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bluetooth bond broadcasts are system-owned; do not accept a custom
        // app protocol command as proof of pairing.
        ContextCompat.registerReceiver(this, watchBondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
        bondReceiverRegistered = true
        websiteLoginVerified = getPreferences(Context.MODE_PRIVATE).getBoolean("website_login_verified", false)
        apiKey = restoreApiKey()
        loadProfileCardForAccount(apiKey)
        loadSharedSectorCache()
        Log.i("WDGHUD_CACHE", "G5A42 HISTORY_TOTAL=${SectorCacheStore.count(this)} persisted AP sectors")
        territoryForCache = TerritoryCacheStore.load(this)
        drivingSessionActive = getSharedPreferences("wdghud_session", Context.MODE_PRIVATE)
            .getBoolean("active", false)
        // Configure system bars only after the decor view is attached. On some
        // Samsung/Android 16 devices window.insetsController throws before attach.
        window.decorView.post {
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.rgb(6, 9, 10)
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.rgb(6, 9, 10)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.decorView.windowInsetsController?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = 0
            }
        }
        gpsRetryHandler.post(gpsRetryTick)
        autoRefreshHandler.postDelayed(autoRefreshTick, 10000L)
        cellHandler.post(geoPump)
        cellHandler.postDelayed(teamBackgroundRefresh, 15_000L)
        reconnectHandler.postDelayed(reconnectTick, 2_000L)
        setContent {
            val permissions = if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps()
                val bluetoothGranted = if (Build.VERSION.SDK_INT >= 31)
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                else true
                if (bluetoothGranted) startScanning(autoConnect = true)
                else status = "Bluetooth permission required for watch discovery"
            }

            val gpsPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) startGps()
                else gpsText = "Precise location permission denied"
            }

            // On first launch, request permissions once; on subsequent launches, start automatically.
            LaunchedEffect(Unit) {
                val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                else {
                    startGps()
                    startScanning(autoConnect = true)
                }
            }

            // G5A37: visual-only shell. The title is permanently at the top;
            // data-fetching, BLE callbacks, GPS timers, and login flows above are unchanged.
            val cyberColors = darkColorScheme(
                primary = HudCyan,
                onPrimary = HudBackground,
                secondary = HudBlue,
                onSecondary = HudBackground,
                background = HudBackground,
                onBackground = HudText,
                surface = HudPanelBackground,
                onSurface = HudText,
                surfaceVariant = Color(0xFF14252B),
                onSurfaceVariant = HudMuted,
                outline = HudBorder,
                error = HudRed
            )
            MaterialTheme(
                colorScheme = cyberColors,
                typography = Typography(
                    headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Monospace),
                    titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace)
                )
            ) {
                var showDiagnostics by rememberSaveable { mutableStateOf(false) }
                Column(
                    modifier = Modifier.fillMaxSize()
                        .background(HudBackground)
                        .safeDrawingPadding()
                ) {
                    // FIX: pinned above ALL other controls, including the profile card.
                    HudAppHeader(connected = ready)

                    Column(
                        modifier = Modifier.weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (websiteLoginVerified && profileCardUrl.isNotEmpty()) {
                            HudPanel("OPERATOR PROFILE", "00", HudGreen) {
                                key(profileCardUrl) {
                                    AndroidView(
                                        factory = { context ->
                                            WebView(context).apply {
                                                settings.javaScriptEnabled = false
                                                settings.domStorageEnabled = false
                                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                                loadDataWithBaseURL(
                                                    "https://wdgwars.pl/",
                                                    """<html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="margin:0;background:transparent"><img src="$profileCardUrl" style="width:100%;height:auto;display:block" alt="WDGWars profile card"></body></html>""",
                                                    "text/html", "UTF-8", null
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                            .height(220.dp)
                                            .border(1.dp, HudBorder, RoundedCornerShape(5.dp))
                                    )
                                }
                            }
                        }

                        HudPanel("WATCH UPLINK", "01") {
                            val uplinkLabel = when {
                                ready -> "LINK ESTABLISHED // WDG HUD"
                                status.contains("Scanning", ignoreCase = true) -> "SCANNING // SEEKING WATCH"
                                status.contains("Connecting", ignoreCase = true) ||
                                        status.contains("Discovering", ignoreCase = true) -> "HANDSHAKE // WATCH"
                                else -> "LINK OFFLINE // WDG HUD"
                            }
                            val uplinkColor = when {
                                ready -> HudGreen
                                status.contains("Scanning", ignoreCase = true) ||
                                        status.contains("Connecting", ignoreCase = true) ||
                                        status.contains("Discovering", ignoreCase = true) -> HudBlue
                                else -> HudRed
                            }
                            Text(
                                uplinkLabel,
                                color = uplinkColor,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(9.dp))
                            HudReadout("BLE STATUS", status)
                            Spacer(Modifier.height(9.dp))
                            HudReadout("LINK HEALTH", bleHealthText)
                            Spacer(Modifier.height(9.dp))
                            HudReadout("WATCH RESPONSE", responseText, HudBlue)
                            Spacer(Modifier.height(9.dp))
                            HudReadout("GPS PACKETS / ACKNOWLEDGED", "$gpsSentCount TX  /  $gpsAckCount ACK")
                            if (!ready) {
                                Spacer(Modifier.height(14.dp))
                                HudSecondaryAction("RECONNECT WATCH") {
                                    val missing = permissions.filter {
                                        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                                    }
                                    if (missing.isEmpty()) startScanning(autoConnect = true)
                                    else permissionLauncher.launch(missing.toTypedArray())
                                }
                            }
                        }

                        HudPanel("DRIVING SESSION", "02", HudBlue) {
                            Text(
                                if (drivingSessionActive) "SESSION ACTIVE" else "SESSION INACTIVE",
                                color = if (drivingSessionActive) HudGreen else HudMuted,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "BACKGROUND GPS RELAY / WATCH LINK",
                                color = HudMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            HudAction(
                                label = if (drivingSessionActive) "STOP DRIVING SESSION" else "START DRIVING SESSION",
                                destructive = drivingSessionActive
                            ) {
                                if (drivingSessionActive) stopDrivingSession() else startDrivingSession()
                            }
                        }

                        HudPanel("SECTOR MATRIX / 3×3", "03") {
                            HudReadout("REFRESH POLICY", "5 MIN / GPS SECTOR CHANGE", HudCyan)
                            Spacer(Modifier.height(8.dp))
                            HudReadout("AUTO REFRESH", autoRefreshStatus)
                            Spacer(Modifier.height(11.dp))
                            HudAction(
                                label = if (apiLoading || levelFetchPending) "SYNCHRONIZING..." else "SYNC // 3×3 SECTOR GRID",
                                enabled = !apiLoading && cellPackets.isEmpty() &&
                                        levelPackets.isEmpty() && !levelFetchPending
                            ) {
                                lastAutoRefreshMs = android.os.SystemClock.elapsedRealtime()
                                fetch3x3()
                            }
                            Spacer(Modifier.height(12.dp))
                            HudReadout("API STATUS", apiStatus)
                            Spacer(Modifier.height(9.dp))
                            HudReadout("WATCH TRANSFER", cellTransferStatus, HudGreen)
                            if (apiResults.isNotBlank()) {
                                Spacer(Modifier.height(13.dp))
                                HudSecondaryAction(if (showDiagnostics) "HIDE SECTOR DETAILS" else "SHOW SECTOR DETAILS") {
                                    showDiagnostics = !showDiagnostics
                                }
                                if (showDiagnostics) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        apiResults,
                                        color = HudMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }

                        HudPanel("GPS TELEMETRY", "04", HudBlue) {
                            Text(
                                gpsText,
                                color = HudText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }

                        HudPanel("WDGWARS ACCESS", "05") {
                            Text(
                                "AUTHENTICATED API UPLINK",
                                color = HudMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = {
                                    if (it != apiKey) loadProfileCardForAccount(it)
                                    apiKey = it
                                    persistApiKey(it)
                                    if (it.isNotBlank()) {
                                        apiKeyError = false
                                        if (keyTestStatus == "AUTH // Credentials required")
                                            keyTestStatus = "AUTH // Awaiting verification"
                                    }
                                },
                                label = { Text("WDGWARS // ACCESS KEY") },
                                placeholder = {
                                    Text(
                                        if (apiKeyError) "AUTH ERROR // KEY REQUIRED" else "Enter access key",
                                        color = if (apiKeyError) HudRed else HudMuted
                                    )
                                },
                                isError = apiKeyError,
                                visualTransformation = PasswordVisualTransformation(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = HudText, fontSize = 13.sp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            HudAction(
                                label = if (keyTesting) "VERIFYING..." else "VERIFY UPLINK",
                                enabled = !keyTesting && !apiLoading
                            ) { testApiKey() }
                            Spacer(Modifier.height(9.dp))
                            HudReadout(
                                "AUTH STATUS",
                                keyTestStatus,
                                if (apiKeyError || keyTestStatus.startsWith("AUTH REJECTED") ||
                                    keyTestStatus.startsWith("AUTH ERROR")) HudRed else HudText
                            )
                            Spacer(Modifier.height(12.dp))
                            HudSecondaryAction("LOGIN TO WDGWARS WEBSITE") { openWebsiteLogin() }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "OPTIONAL // PUBLIC PROFILE CARD",
                                color = HudMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = profileCardInput,
                                onValueChange = {
                                    profileCardInput = it
                                    profileCardError = false
                                },
                                label = { Text("WDGWARS // PROFILE CARD URL") },
                                placeholder = { Text("https://wdgwars.pl/card/ID-CODE.svg") },
                                isError = profileCardError,
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    color = HudText,
                                    fontSize = 12.sp
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (profileCardError) {
                                Text(
                                    "Enter your own https://wdgwars.pl/card/...svg URL and API key.",
                                    color = HudRed,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            HudSecondaryAction("SAVE PROFILE CARD") { saveProfileCard() }
                            if (profileCardUrl.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                HudSecondaryAction("REMOVE PROFILE CARD") { clearProfileCard() }
                            }
                        }



                        Text(
                            "WDG // HUD   /   ANDROID CONTROL TERMINAL",
                            modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 12.dp),
                            color = HudBorder,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
