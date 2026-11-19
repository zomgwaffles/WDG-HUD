package com.example.wdghud

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.*
import com.example.wdghud.geometry.WdgWarsGrid
import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.SectorScanCoverage

/**
 * E5.15 development build: foreground scanning engine when WDG HUD Activity is hidden.
 * Owns GPS, BLE, territory lookup, authenticated detail WebView, sector cache,
 * 30-second sector checks, and acknowledged transactional watch snapshots.
 * No network work occurs on main thread. No Activity/Service simultaneous GATT owner.
 */
@Suppress("DEPRECATION")
class DrivingSessionService : Service() {
    companion object {
        const val ACTION_START = "com.example.wdghud.START_SESSION"
        const val ACTION_STOP = "com.example.wdghud.STOP_SESSION"
        const val ACTION_UI_HIDDEN = "com.example.wdghud.UI_HIDDEN"
        const val ACTION_UI_ACTIVE = "com.example.wdghud.UI_ACTIVE"
        private const val CHANNEL = "wdghud_drive"
        private const val NOTIF_ID = 13013
        private const val WATCH_NAME = "WDG HUD"
        private val SERVICE_UUID = UUID.fromString("e4c1a100-7c0d-4f91-a2d4-7a50a9d1b001")
        private val COMMAND_UUID = UUID.fromString("e4c1a101-7c0d-4f91-a2d4-7a50a9d1b001")
        private val RESPONSE_UUID = UUID.fromString("e4c1a102-7c0d-4f91-a2d4-7a50a9d1b001")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val lookaheadExecutor = Executors.newSingleThreadExecutor()
    private val anchorExecutor = Executors.newSingleThreadExecutor()
    private val location by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val bluetooth by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var pendingWrite = false
    private var pendingWriteAt = 0L
    private var watchCapsState = "UNKNOWN"
    private var watchAccountCapable = false
    private var watchGangNameSupported = false
    private var watchFullSectorNamesSupported = false
    private var capsSinceMs = 0L
    private var capsProbeAtMs = 0L
    private var active = false
    private var activityVisible = true
    private var scanning = false
    private val bleRetry = RecoveryBackoff()
    // G5A43: same heartbeat policy when the foreground service owns BLE.
    private val linkHealth = BleLinkHealth()
    private fun idleForHeartbeat(): Boolean = !activityVisible && mtu >= 64 &&
        gatt != null && !pendingWrite && !inProgress && awaiting == null &&
        awaitingCenter == null && !geoQueue.active && !geoQueue.waitingForAck &&
        operatorProfilePackets.isEmpty() && operatorProfileAwaiting == null &&
        teamPackets.isEmpty() && teamAwaiting == null &&
        watchCapsState != "UNKNOWN" && watchCapsState != "PROBING"

    private fun tickLinkHealth() {
        val clock = now()
        when (linkHealth.evaluate(clock, idleForHeartbeat())) {
            BleLinkHealth.Action.PING -> if (send("PING")) {
                linkHealth.onPingSent(clock)
                log("G5A43 HEARTBEAT_SENT owner=service")
            }
            BleLinkHealth.Action.MISSED -> log(
                "G5A43 HEARTBEAT_TIMEOUT owner=service missed=${linkHealth.missedPings}")
            BleLinkHealth.Action.RECOVER -> {
                log("G5A43 LINK_UNRESPONSIVE owner=service; reconnecting")
                notifyStatus("Watch unresponsive · reconnecting")
                bleRetry.fail(clock)
                disconnectBle()
            }
            BleLinkHealth.Action.NONE -> Unit
        }
    }
    private val sectorRetry = RecoveryBackoff(listOf(5_000L, 15_000L, 30_000L, 60_000L))
    private var gattStartedAtMs = 0L
    private val stopScanTimer = object : Runnable {
        override fun run() {
            if (scanning) {
                stopBleScan()
                bleRetry.fail(now())
                log("BLE discovery timeout; retry in ${bleRetry.remainingMs(now())}ms")
            }
        }
    }
    private var latest: Location? = null
    private var lastBearing: Float? = null
    private var lastGpsWrite = 0L
    private var lastClockSyncSentAt = 0L
    private var lastScanMs = 0L
    private var lastSector: SectorId? = null
    // G5A38 watch-first geographic cache, independent of API scan state.
    private var geoCacheSupported = false
    private val geoQueue = WatchGeoQueue()
    private val geoRetry = RecoveryBackoff(listOf(5_000L, 15_000L, 30_000L, 60_000L))
    private var desiredWatchCenter: SectorId? = null
    private val offlineSectorTracker = OfflineSectorTracker()
    private val anchorRegistry by lazy { SectorAnchorRegistry(applicationContext) }
    private var awaitingCenter: SectorId? = null
    private var lastCenterAck: SectorId? = null
    private var lastCenterMiss: SectorId? = null
    private var missRefillAttemptedFor: SectorId? = null
    private var centerSentAt = 0L
    private var territoryForCache = emptyMap<SectorId, Pair<String?, String?>>()
    private fun driveGeo() {
        if (activityVisible || mtu < 64 || !geoCacheSupported) return
        // A WebView/API lookup does not own the BLE batch. Keep GPS-driven
        // CENTER commands active while background scanning/download is busy.
        // CENTER can be interleaved BETWEEN acknowledged AP packets. BEGIN
        // and END acknowledgements may be pending; those commands are handled
        // independently of CENTER by the watch. Do not overtake a CELL/LVL/RAW.
        if (awaiting != null && stage != "begin" && stage != "end") return
        if (awaitingCenter != null && now() - centerSentAt > 6000L) awaitingCenter = null
        if (pendingWrite) return
        val center = desiredWatchCenter
        if (center != null && center != lastCenterAck && awaitingCenter == null &&
            !geoQueue.waitingForAck) {
            if (send("CENTER,${center.north},${center.east}")) {
                awaitingCenter = center
                centerSentAt = now()
                log("G5A40 WATCH_SEND_CENTER=$center previousAck=$lastCenterAck")
            }
        } else if (awaitingCenter == null && !inProgress && awaiting == null) {
            // K,B..K,E can be interrupted by CENTER between packets, but must
            // NOT enter the watch during an atomic nine-sector BEGIN..END.
            val wasActive = geoQueue.active
            geoQueue.next(now(), true)?.let { packet ->
                if (send(packet)) geoQueue.sent(now())
            }
            if (wasActive && !geoQueue.active && geoQueue.lastError == null &&
                lastCenterMiss != null) {
                lastCenterAck = null
                geoRetry.succeed()
                log("Watch cache refilled; retrying previously missed CENTER")
            }
        }
    }
    private fun warmFromCachedDetails() {
        if (!geoCacheSupported || geoQueue.active) { geoWarmPending = true; return }
        geoWarmPending = false
        val center = desiredWatchCenter ?: lastSector ?: return
        val records = linkedMapOf<SectorId, String>()
        // Limit each BLE chunk to two sectors so new GPS CENTER gets priority.
        val sorted = MovingWindowPriority.ordered(center, lookaheadPreviousCenter) { id ->
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            details[key(lat, lon)]?.let { detail ->
                val (gang, color) = territoryForCache[id] ?: (null to null)
                geoQueue.needsDelivery(id, detail.data.toString(), gang, color)
            } ?: false
        }
        sorted.take(2).forEach { id ->
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            details[key(lat, lon)]?.let { records[id] = it.data.toString() }
        }
        geoWarmPending = sorted.size > records.size
        if (records.isNotEmpty()) {
            geoQueue.enqueue(records) { id -> territoryForCache[id] ?: (null to null) }
            log("G5A40 WATCH_REFILL queued=${records.size} remaining=${sorted.size - records.size} center=$center")
        }
    }

    private var lastSectorCheck = 0L
    private var inProgress = false
    private var awaiting: String? = null
    private var expectedPacket = ""
    private var deadline = 0L
    private var stage = "idle"
    // Whether the 25-sector check found unavailable/stale detail.
    private var coverageRequiresScan = false
    private var packetQueue = emptyList<String>()
    private var packetIndex = 0
    // G5A40.1: independent AP-level counters; CELL/NAME must not advance PROG.
    private var apLevelPacketsTotal = 0
    private var acknowledgedApLevelPackets = 0
    private var expectedLevels = 0
    private var expectedRaw = 0
    private var page: WebView? = null
    private var currentGridKeys = emptyList<String>()
    private var pendingGridKeys = emptyList<String>()
    private var lastProgress = -1
    private var lastProgressAt = 0L
    private data class Detail(val data: JSONObject, val atMs: Long)
    private val details = mutableMapOf<String, Detail>()
    private fun restoreSharedCache() {
        SectorCacheStore.load(this).forEach { (k, value) ->
            val existing = details[k]
            if (existing == null || value.fetchedAtMs > existing.atMs)
                try { details[k] = Detail(JSONObject(value.payload), value.fetchedAtMs) }
                catch (_: Exception) { }
        }
    }
    private fun saveSharedCache() {
        SectorCacheStore.save(this, details.mapValues { (_, value) ->
            SectorCacheStore.Entry(value.data.toString(), value.atMs)
        })
    }

    // G5A42: return-trip hydration, independent of whether the 25-sector RAM
    // working set still contains the last visited records.
    private fun restoreHistoryWindow(center: SectorId) {
        val ids = WdgWarsGrid.window(center, 2)
        val lookup = linkedMapOf<String, SectorId>()
        ids.forEach { id ->
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            lookup[key(lat, lon)] = id
        }
        var restored = 0
        SectorCacheStore.loadKeys(this, lookup.keys).forEach { (k, entry) ->
            val id = lookup[k] ?: return@forEach
            if (WatchGeoPackets.build(id, entry.payload) == null) return@forEach
            if ((details[k]?.atMs ?: Long.MIN_VALUE) < entry.fetchedAtMs) {
                try { details[k] = Detail(JSONObject(entry.payload), entry.fetchedAtMs); restored++ }
                catch (_: Exception) { /* Invalid legacy record: do not crash navigation. */ }
            }
        }
        territoryForCache = territoryForCache + TerritoryCacheStore.loadWindow(this, ids)
        if (restored > 0) {
            geoWarmPending = true
            log("G5A42 HISTORY_RESTORED center=$center rows=$restored/25")
        }
    }

    private val ttl: Long get() = StaleIntervalSettings.ttlMs(this)

    // G5A38: background 25-sector prefetch uses the SAME logged-in website
    // WebView origin as the nine-sector request. Never blocks cached recenter.
    // G5A40: continuous independently scheduled 5x5 lookahead. The old service
    // created a DETACHED WebView and did not publish data until ALL 25 requests
    // finished. That can stall for minutes or fail entirely in the background.
    // Use the same website-session cookies via CookieManager, but do validated
    // HTTPS detail GETs on a dedicated background executor. No HTTP for GPS.
    private var lookaheadCenter: SectorId? = null
    private var lookaheadJob: Future<*>? = null
    @Volatile private var lookaheadGeneration = 0
    private var lookaheadAccepted = 0
    private var lookaheadRequested = 0
    private var lookaheadPreviousCenter: SectorId? = null
    private var nextLookaheadAtMs = 0L
    private var lastLookaheadPlanAtMs = 0L
    private var geoWarmPending = false

    private fun stopBackgroundLookahead() {
        lookaheadGeneration++
        lookaheadJob?.cancel(true)
        lookaheadJob = null
        lookaheadCenter = null
    }

    private fun onGpsSector(center: SectorId?) {
        val previous = desiredWatchCenter
        desiredWatchCenter = center
        if (center == null || center == previous) return
        lookaheadPreviousCenter = previous
        anchorRegistry.ensureWindowAsync(center)
        geoQueue.retainWindow(WdgWarsGrid.window(center, 2))
        geoWarmPending = true
        // Always preempt old region; do not wait for an ACK/API request.
        stopBackgroundLookahead()
        nextLookaheadAtMs = 0L
        log("G5A40 GPS_SECTOR=$center previous=$previous WATCH_ACK=$lastCenterAck")
        // GPS recenter is already scheduled; the database is only a source for
        // AP content and must never delay the CENTER control packet.
        main.post {
            if (desiredWatchCenter == center) {
                restoreHistoryWindow(center)
                startBackgroundLookahead(center)
            }
        }
    }

    private fun validateDetail(text: String): JSONObject? {
        return try {
            val obj = JSONObject(text)
            val total = obj.optLong("total", -1)
            val levels = obj.optJSONArray("levels") ?: return null
            if (!obj.optBoolean("ok", true) || total !in 0..Int.MAX_VALUE.toLong()) return null
            var sum = 0L
            val seen = mutableSetOf<Int>()
            for (i in 0 until levels.length()) {
                val level = levels.optJSONObject(i) ?: return null
                val n = level.optInt("lvl", 0)
                val c = level.optLong("c", -1)
                if (n !in 1..255 || c !in 0..Int.MAX_VALUE.toLong() || !seen.add(n)) return null
                sum += c
                if (sum > total) return null
            }
            if (sum == total) obj else null
        } catch (_: Exception) { null }
    }

    private fun startBackgroundLookahead(center: SectorId) {
        if (!active || activityVisible || desiredWatchCenter != center || lookaheadCenter != null) return
        // Historical AP rows are cumulative; never interpret RAM eviction as
        // 'not visited'. A valid stale row is shown first, then refreshed.
        restoreHistoryWindow(center)
        val clock = now()
        if (clock < nextLookaheadAtMs) return
        val targets = MovingWindowPriority.ordered(center, lookaheadPreviousCenter) { id ->
            !hasCachedSector(id, clock) && SectorUnavailableBackoff.canRetry(id)
        }
        val visibleKeys = WdgWarsGrid.window(center, 1).toSet()
        val actualFresh = WdgWarsGrid.window(center, 2).count { hasCachedSector(it, clock) }
        log("G5A41 CACHE_PLAN center=$center fresh=$actualFresh/25 " +
            "requestEligible=${targets.size} unavailableCoolingDown=${25 - actualFresh - targets.size} " +
            "visibleMissing=${targets.count { it in visibleKeys }}")
        if (targets.isEmpty()) return
        if (PortalHttpPolicy.isLimited()) {
            nextLookaheadAtMs = clock + PortalHttpPolicy.remainingMs().coerceAtLeast(30_000L)
            return
        }
        // A session can be established in the Activity's authenticated WebView.
        // CookieManager shares those cookies with this background service.
        val cookies = try { CookieManager.getInstance().getCookie("https://wdgwars.pl/") }
            catch (_: Exception) { null }
        val webUserAgent = try { android.webkit.WebSettings.getDefaultUserAgent(this) }
            catch (_: Exception) { "Mozilla/5.0" }
        if (cookies.isNullOrBlank()) {
            nextLookaheadAtMs = clock + 30_000L
            log("G5A40 PREFETCH_AUTH_MISSING: login to wdgwars.pl in the Android app")
            return
        }
        lookaheadCenter = center
        lookaheadRequested = targets.size
        lookaheadAccepted = 0
        val generation = ++lookaheadGeneration
        nextLookaheadAtMs = clock + 30_000L // bounded retry even on errors
        lookaheadJob = lookaheadExecutor.submit {
            for (id in targets) {
                if (Thread.currentThread().isInterrupted || generation != lookaheadGeneration ||
                    PortalHttpPolicy.isLimited()) break
                val (lat, lon) = WdgWarsGrid.queryPoint(id)
                var connection: HttpURLConnection? = null
                try {
                    val url = URL("https://wdgwars.pl/api/cell-detail?lat=$lat&lng=$lon")
                    connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false // NEVER send cookies to a redirect
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 10_000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Cookie", cookies)
                    connection.setRequestProperty("Accept", "application/json")
                    // Match the authenticated WebView while identifying our separate product.
                    connection.setRequestProperty("User-Agent", PortalHttpPolicy.webUserAgent(webUserAgent))
                    connection.setRequestProperty("Referer", "https://wdgwars.pl/")
                    val status = PortalHttpPolicy.responseCode(connection)
                    if (status == 404 || status == 204) {
                        main.post {
                            SectorUnavailableBackoff.unavailable(id)
                            log("G5A41 PREFETCH_NO_RECORD id=$id HTTP=$status; continuing")
                        }
                        continue // A missing API record is not a missing geographic sector.
                    }
                    if (status != 200) throw IllegalStateException("HTTP $status")
                    if (!(connection.contentType ?: "").lowercase(Locale.US).contains("json"))
                        throw IllegalStateException("non-JSON response (login expired?)")
                    val payload = connection.inputStream.bufferedReader().use { it.readText() }
                    main.post {
                        if (generation != lookaheadGeneration || desiredWatchCenter != center) return@post
                        val verified = validateDetail(payload)
                        if (verified == null) {
                            SectorUnavailableBackoff.unavailable(id)
                            log("G5A41 PREFETCH_UNAVAILABLE id=$id (UNKNOWN; not a zero-AP response)")
                            return@post
                        }
                        val (y, x) = WdgWarsGrid.queryPoint(id)
                        val cacheKey = key(y, x)
                        val accepted = Detail(verified, now())
                        details[cacheKey] = accepted
                        SectorCacheStore.saveOne(this@DrivingSessionService, cacheKey,
                            SectorCacheStore.Entry(accepted.data.toString(), accepted.atMs))
                        SectorUnavailableBackoff.succeeded(id)
                        lookaheadAccepted++
                        // Each verified record was already committed individually.
                        // Never rewrite the full RAM cache on each HTTP result.
                        geoWarmPending = true
                        log("G5A41 PREFETCH_OK id=$id lastSyncUtc=${System.currentTimeMillis()} " +
                            "accepted=$lookaheadAccepted/$lookaheadRequested watchAck=$lastCenterAck")
                    }
                } catch (e: Exception) {
                    main.post {
                        if (generation == lookaheadGeneration) {
                            SectorUnavailableBackoff.transientFailure(id)
                            log("G5A41 PREFETCH_FAIL id=$id error=${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                } finally {
                    connection?.disconnect()
                }
            }
            main.post {
                if (generation != lookaheadGeneration) return@post
                val accepted = lookaheadAccepted
                lookaheadCenter = null
                lookaheadJob = null
                log("G5A40 PREFETCH_DONE center=$center accepted=$accepted/$lookaheadRequested")
                nextLookaheadAtMs = now() + if (accepted == 0) 30_000L else 5_000L
                // A new center cannot be held hostage by the old run.
                if (desiredWatchCenter != center) desiredWatchCenter?.let { startBackgroundLookahead(it) }
            }
        }
    }

    private fun tickLookahead() {
        if (activityVisible || !active) return
        val clock = now()
        if (clock - lastLookaheadPlanAtMs < 2_000L) return
        lastLookaheadPlanAtMs = clock
        desiredWatchCenter?.let { center ->
            if (lookaheadCenter != null && lookaheadCenter != center) {
                stopBackgroundLookahead()
                nextLookaheadAtMs = 0L
            }
            if (lookaheadCenter == null) startBackgroundLookahead(center)
        }
    }

    private data class Territory(val lat: Double, val lon: Double, val count: Int,
                                 val gang: Int, val color: String?, val gangName: String?)
    private data class Grid(val centers: List<Pair<Double, Double>>,
                            val cells: List<String>, val names: List<String>)

    private fun log(message: String) {
        Log.i(if (message.startsWith("G5A40")) "WDGHUD_GEO" else "WDGHUD_E515", message)
    }
    private fun now() = SystemClock.elapsedRealtime()
    private fun key(lat: Double, lon: Double) = "${round(lat * 1000000).toLong()}:${round(lon * 1000000).toLong()}"
    private fun freshGps(): Location? = latest?.takeIf {
        (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1000000L in 0..30000
    }


    // G5A19: identical on-entry OPERATOR data path for the background BLE owner.
    private val operatorProfileExecutor = Executors.newSingleThreadExecutor()
    private var operatorProfileFetching = false
    private var operatorProfilePackets = emptyList<String>()
    // Reject an old asynchronous /api/me result after an account edit.
    private var operatorProfileAccountKey = ""
    private var operatorProfileIndex = 0
    private var operatorProfileAwaiting: String? = null
    private var operatorProfileAckAt = 0L
    // G5A45: same TEAM channel while the driving service owns BLE.
    private val teamExecutor = Executors.newSingleThreadExecutor()
    private var teamSnapshot: TeamProfileWire.Team? = null
    private var teamSnapshotKey = ""
    private var teamSnapshotLoaded = false
    private var teamFetchedAt = 0L // wall-clock epoch, persists across owners
    private var teamFetching = false
    private var teamLastAttemptAt = 0L
    private var teamNeedsResponse = false
    private var teamRequestedPage: Int? = null
    private var teamDeferredPage: Int? = null
    private var teamPackets = emptyList<String>()
    private var teamPacketIndex = 0
    private var teamAwaiting: String? = null
    private var teamAckAt = 0L
    private fun resetTeamTransfer() {
        teamPackets=emptyList();teamPacketIndex=0;teamAwaiting=null
    }
    private fun sendTeamIfReady() {
        if (accountChangePending() || !watchAccountCapable ||
            (teamSnapshotKey.isNotBlank() && teamSnapshotKey != readApiKey())) {
            resetTeamTransfer()
            return
        }
        if (teamPackets.isEmpty() || activityVisible || mtu<64 || inProgress || pendingWrite ||
            awaitingCenter!=null || geoQueue.active || geoQueue.waitingForAck ||
            operatorProfilePackets.isNotEmpty()) return
        if (teamAwaiting!=null) {
            if (now()-teamAckAt>6000L) { log("G5A45.5 TEAM ACK timeout");resetTeamTransfer() }
            return
        }
        val packet=teamPackets[teamPacketIndex]
        if (send(packet)) {
            teamAwaiting=TeamProfileWire.expectedAck(packet)
            teamAckAt=now()
        }
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
        if (accountChangePending() || teamFetching || key.isBlank() || activityVisible || !active) return
        val age = System.currentTimeMillis() - teamFetchedAt
        if (teamSnapshotLoaded && age in 0 until TeamProfileWire.CACHE_MS) return
        val elapsed = now()
        if (teamLastAttemptAt != 0L && elapsed - teamLastAttemptAt < 60_000L) return
        teamLastAttemptAt = elapsed
        teamFetching = true
        teamExecutor.execute {
            var snapshot: TeamProfileWire.Team?=null
            var ok=false
            try { snapshot=TeamProfileWire.fetch(key);ok=true }
            catch(e:Exception) {log("G5A45.5 TEAM background fetch: ${e.javaClass.simpleName}: ${e.message}")}
            val fetched=System.currentTimeMillis()
            if(ok) TeamSnapshotStore.save(applicationContext,key,snapshot,fetched)
            main.post {
                teamFetching=false
                if(readApiKey()!=key) return@post
                val teamChanged=ok && (!teamSnapshotLoaded || TeamProfileWire.summary(teamSnapshot) != TeamProfileWire.summary(snapshot))
                if(ok) {
                    teamSnapshot=snapshot;teamSnapshotKey=key
                    teamSnapshotLoaded=true;teamFetchedAt=fetched
                }
                if(teamNeedsResponse && !activityVisible && gatt!=null && mtu>=64) {
                    val requested=teamRequestedPage
                    teamNeedsResponse=false;teamRequestedPage=null
                    resetTeamTransfer()
                    teamPackets=if(!teamSnapshotLoaded) listOf("TEAM,ERR")
                        else if(requested==null) TeamProfileWire.summary(teamSnapshot)
                        else TeamProfileWire.page(teamSnapshot,requested)
                } else if(teamChanged && !activityVisible && gatt!=null && mtu>=64 &&
                    teamPackets.isEmpty() && operatorProfilePackets.isEmpty()) {
                    teamPackets=TeamProfileWire.summary(teamSnapshot)
                }
            }
        }
    }
    private fun requestTeam(page: Int?) {
        if (accountChangePending() || !watchAccountCapable ||
            activityVisible || gatt==null || mtu<64) return
        if (page!=null && teamPackets.isNotEmpty()) {teamDeferredPage=page;return}
        val key=readApiKey()
        if (key.isBlank()) {resetTeamTransfer();teamPackets=listOf("TEAM,ERR");return}
        loadCachedTeam(key)
        if (teamSnapshotLoaded) {
            resetTeamTransfer()
            teamPackets=if(page==null) TeamProfileWire.summary(teamSnapshot)
                else TeamProfileWire.page(teamSnapshot,page)
            maybeRefreshTeam(key)
        } else {
            teamNeedsResponse=true;teamRequestedPage=page
            maybeRefreshTeam(key)
            if(!teamFetching) {
                teamNeedsResponse=false
                resetTeamTransfer();teamPackets=listOf("TEAM,ERR")
            }
        }
    }
    private val teamBackgroundRefresh = object : Runnable {
        override fun run() {
            if(active && !activityVisible) {
                val key=readApiKey()
                if(key.isNotBlank()) {loadCachedTeam(key, rereadDisk = true);maybeRefreshTeam(key)}
            }
            main.postDelayed(this, 60_000L)
        }
    }
    private fun onTeamMessage(message:String):Boolean {
        if(message=="TEAM:GET") {requestTeam(null);return true}
        if(message.startsWith("TEAM:PAGE:")) {
            message.substringAfterLast(':').toIntOrNull()?.takeIf { it in 0..65534 }?.let {requestTeam(it)}
            return true
        }
        if(!message.startsWith("TEAM:")) return false
        if(teamPackets.isNotEmpty()) {
            if(message==teamAwaiting) {
                teamAwaiting=null;teamPacketIndex++
                if(teamPacketIndex==teamPackets.size) {
                    resetTeamTransfer()
                    val next=teamDeferredPage
                    teamDeferredPage=null
                    if(next!=null)requestTeam(next)
                }
            } else if(teamAwaiting!=null) {
                log("G5A45 TEAM packet rejection: $message")
                resetTeamTransfer()
            }
        }
        return true
    }

    private fun resetOperatorProfileTransfer() {
        operatorProfileAccountKey = ""
        operatorProfilePackets = emptyList()
        operatorProfileIndex = 0
        operatorProfileAwaiting = null
    }
    private fun sendOperatorProfileIfReady() {
        if (accountChangePending() ||
            (operatorProfileAccountKey.isNotBlank() && operatorProfileAccountKey != readApiKey())) {
            resetOperatorProfileTransfer()
            return
        }
        if (operatorProfilePackets.isEmpty() || activityVisible || mtu < 64 || inProgress ||
            awaitingCenter != null || (desiredWatchCenter != null && desiredWatchCenter != lastCenterAck)) return
        if (operatorProfileAwaiting != null) {
            if (now() - operatorProfileAckAt > 6000L) {
                log("G5A19 profile BLE ACK timeout: $operatorProfileAwaiting")
                resetOperatorProfileTransfer()
            }
            return
        }
        if (pendingWrite) return
        val packet = operatorProfilePackets[operatorProfileIndex]
        if (send(packet)) {
            operatorProfileAwaiting = when {
                packet.startsWith("OWNER,") -> "OWNER:OK"
                packet.startsWith("PROFILE,S,") -> "PROFILE:S:OK"
                packet.startsWith("PROFILE,N,") -> "PROFILE:N:OK"
                packet.startsWith("PROFILE,G,") ->
                    "PROFILE:G:${packet.substringAfter("PROFILE,G,").substringBefore(',')}:OK"
                packet.startsWith("PROFILE,A,") -> "PROFILE:A:OK"
                packet == "PROFILE,E" -> "PROFILE:E:OK"
                else -> "PROFILE:ERR:OK"
            }
            operatorProfileAckAt = now()
        }
    }
    private fun onOperatorProfileMessage(message: String): Boolean {
        if (message == "PROFILE:GET") {
            if (operatorProfilePackets.isEmpty()) refreshOperatorProfile()
            return true
        }
        if (!message.startsWith("PROFILE:") && !message.startsWith("OWNER:")) return false
        if (operatorProfilePackets.isNotEmpty()) {
            if (message == operatorProfileAwaiting) {
                operatorProfileAwaiting = null
                operatorProfileIndex++
                if (operatorProfileIndex == operatorProfilePackets.size) {
                    log("G5A19 profile refresh delivered and ACKed")
                    resetOperatorProfileTransfer()
                }
            } else if (message != "PROFILE:GET") {
                log("G5A19 profile packet rejected: $message")
                resetOperatorProfileTransfer()
            }
        }
        return true
    }
    private fun refreshOperatorProfile() {
        if (!watchAccountCapable || accountChangePending() ||
            activityVisible || gatt == null || mtu < 64 || operatorProfileFetching) return
        val key = readApiKey()
        if (key.isBlank()) {
            log("G5A19 profile refresh requires saved API key")
            resetOperatorProfileTransfer()
            operatorProfilePackets = listOf("PROFILE,ERR")
            return
        }
        OperatorProfileSnapshotStore.loadFresh(this, key)?.let { cached ->
            resetOperatorProfileTransfer()
            operatorProfilePackets = cached
            return
        }
        operatorProfileFetching = true
        operatorProfileExecutor.execute {
            var connection: HttpURLConnection? = null
            var packets = listOf("PROFILE,ERR")
            var verifiedPackets: List<String>? = null
            try {
                connection = URL("https://wdgwars.pl/api/me").openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 12000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("X-API-Key", key)
                connection.setRequestProperty("Accept", "application/json")
                require(PortalHttpPolicy.responseCode(connection) == 200 &&
                    connection.contentType.orEmpty().contains("json", ignoreCase = true)) {
                    "HTTP ${PortalHttpPolicy.responseCode(connection)} or invalid content type"
                }
                val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                fun count(field: String): Long = data.optLong(field, -1L).also {
                    require(it in 0L..999999999L) { "Missing/invalid $field" }
                }
                fun safe(value: String, default: String) = value
                    .map { if (it.code in 32..126 && it != ',') it else '?' }
                    .joinToString("").trim().ifBlank { default }
                val user = AccountOwnerWire.claimedUsername(data.optString("username", ""))
                val ownerPacket = AccountOwnerWire.ownerPacket(user)
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
                // The service never reuses an unrelated account's public SVG.
                // Optional card enrichment is owned by the Activity's per-account URL.
                ActivityRankProbe.packet(ActivityRankProbe.fromProfile(data))
                    ?.let { profileData.add(it) }
                profileData.add("PROFILE,E") // Commit only after every chunk is ACKed.
                require(profileData.all { it.toByteArray(Charsets.UTF_8).size <= 61 })
                packets = listOf(ownerPacket) + profileData
                verifiedPackets = packets
                log("G5A49.4d /api/me owner/profile fetched")
            } catch (e: Exception) {
                log("G5A19 profile refresh failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                connection?.disconnect()
            }
            main.post {
                operatorProfileFetching = false
                if (!accountChangePending() && readApiKey() == key) {
                    // Successful HTTP data survives foreground/background handoff.
                    verifiedPackets?.let {
                        OperatorProfileSnapshotStore.saveVerified(this@DrivingSessionService, key, it)
                    }
                    if (!activityVisible && watchAccountCapable && gatt != null && mtu >= 64 &&
                        packets.firstOrNull()?.startsWith("OWNER,") == true) {
                        resetOperatorProfileTransfer()
                        operatorProfileAccountKey = key
                        operatorProfilePackets = packets
                    }
                }
            }
        }
    }
    // G5A38.1: recoverable location input owned by this service only when
    // the Activity is hidden. No location callback mutates watch snapshot data.
    private var gpsOffPending = false
    private val recoveringGps by lazy {
        RecoveringGpsSource(this, onFix = { fix ->
            latest = fix
            // Foreground GPS service owns navigation when the Activity is hidden.
            // This is purely local boundary math, with no HTTP lookup.
            onGpsSector(anchorRegistry.locate(fix.latitude, fix.longitude))
            if (fix.hasBearing() && fix.hasSpeed() && fix.speed >= 0.9f)
                lastBearing = fix.bearing
            gpsOffPending = false
            if (!activityVisible) sendGps()
        }, onPhase = { phase, description ->
            log("GPS $phase: $description")
            if (phase == GpsRecoveryPolicy.Phase.STALE ||
                phase == GpsRecoveryPolicy.Phase.PROVIDERS_OFF ||
                phase == GpsRecoveryPolicy.Phase.PERMISSION_REQUIRED) latest = null
            if (!activityVisible && (phase == GpsRecoveryPolicy.Phase.STALE ||
                        phase == GpsRecoveryPolicy.Phase.PROVIDERS_OFF ||
                        phase == GpsRecoveryPolicy.Phase.PERMISSION_REQUIRED)) {
                gpsOffPending = true
            }
        })
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.scanRecord?.deviceName != WATCH_NAME || gatt != null) return
            stopBleScan()
            try {
                // First-time pairing must be initiated in the visible app;
                // background services must not unexpectedly summon a PIN UI.
                if (!BleBondPolicy.backgroundMayConnect(result.device.bondState)) {
                    log("G5A44 UNBONDED_WATCH: open the app to pair securely")
                    notifyStatus("Watch not paired · open WDG HUD to enter code")
                    bleRetry.fail(now())
                    return
                }
                gattStartedAtMs = now()
                gatt = result.device.connectGatt(this@DrivingSessionService, false, bleCallback)
                if (gatt == null) {
                    gattStartedAtMs = 0L
                    bleRetry.fail(now())
                }
            } catch (e: Exception) {
                bleRetry.fail(now())
                log("BLE connection: ${e.javaClass.simpleName}")
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post {
                stopBleScan()
                bleRetry.fail(now())
                log("BLE scan failed $errorCode")
            }
        }
    }

    private val bleCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) {
            log("G5A43 GATT_STATE owner=service status=$status state=$newState")
            main.post {
                if (gatt !== link) {
                    try { link.close() } catch (_: Exception) { }
                    return@post
                }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (!link.discoverServices()) {
                            bleRetry.fail(now())
                            disconnectBle()
                            abort("Service discovery could not start")
                        }
                    } catch (_: Exception) {
                        bleRetry.fail(now())
                        disconnectBle()
                        abort("Service discovery failed")
                    }
                } else {
                    bleRetry.fail(now())
                    disconnectBle()
                    abort("BLE disconnected")
                }
            }
        }
        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            main.post {
                if (gatt !== link) return@post
                try {
                    if (!BleBondPolicy.backgroundMayConnect(link.device.bondState)) {
                        log("G5A44 bond missing in background; open app to re-pair")
                        bleRetry.fail(now()); disconnectBle(); return@post
                    }
                } catch (_: SecurityException) {
                    bleRetry.fail(now()); disconnectBle(); return@post
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    bleRetry.fail(now()); disconnectBle(); return@post
                }
                command = link.getService(SERVICE_UUID)?.getCharacteristic(COMMAND_UUID)
                val response = link.getService(SERVICE_UUID)?.getCharacteristic(RESPONSE_UUID)
                if (command == null || response == null) {
                    bleRetry.fail(now()); disconnectBle(); return@post
                }
                try {
                    link.setCharacteristicNotification(response, true)
                    val descriptor = response.getDescriptor(CCCD_UUID) ?: run { disconnectBle(); return@post }
                    if (Build.VERSION.SDK_INT >= 33)
                        link.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        link.writeDescriptor(descriptor)
                    }
                } catch (_: Exception) { bleRetry.fail(now()); disconnectBle() }
            }
        }
        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) main.post {
                if (gatt !== link) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    bleRetry.fail(now()); disconnectBle(); return@post
                }
                try {
                    if (!link.requestMtu(185)) { bleRetry.fail(now()); disconnectBle() }
                } catch (_: Exception) { bleRetry.fail(now()); disconnectBle() }
            }
        }
        override fun onMtuChanged(link: BluetoothGatt, value: Int, status: Int) {
            main.post {
                if (gatt !== link) return@post
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) value else 23
                if (mtu < 64) {
                    bleRetry.fail(now()); disconnectBle(); return@post
                }
                bleRetry.succeed()
                linkHealth.onReady(now())
                log("G5A43 LINK_READY owner=service session=${linkHealth.sessionCount}")
                // Never assume prior remote ACK state after a new GATT session.
                // Queue local disk-cache refill independently of API requests.
                geoWarmPending = true
                lastSectorCheck = 0L
                gattStartedAtMs = 0L
                log("BLE ready=${mtu >= 64}")
                watchCapsState = "UNKNOWN"
                watchAccountCapable = false
                watchGangNameSupported = false
                watchFullSectorNamesSupported = false
                geoCacheSupported = false
                awaitingCenter = null
                lastCenterAck = null
                lastCenterMiss = null
                geoQueue.resetRemoteState()
                capsSinceMs = now()
                capsProbeAtMs = capsSinceMs + 500L
                lastClockSyncSentAt = 0L  // new GATT connection
                if (!activityVisible) sendClockIfDue()
            }
        }
        override fun onCharacteristicChanged(link: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
            if (char.uuid == RESPONSE_UUID) main.post {
                if (gatt === link) response(value.toString(Charsets.UTF_8))
            }
        }
        @Deprecated("For Android API below 33")
        override fun onCharacteristicChanged(link: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == RESPONSE_UUID) {
                val bytes = char.value ?: return
                main.post { if (gatt === link) response(bytes.toString(Charsets.UTF_8)) }
            }
        }
        override fun onCharacteristicWrite(link: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (gatt !== link) return@post
                pendingWrite = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    abort("BLE write failed $status")
                    bleRetry.fail(now())
                    disconnectBle()
                }
            }
        }
    }

    private fun send(message: String): Boolean {
        if (activityVisible || pendingWrite || mtu < 64) return false
        val link = gatt ?: return false
        val c = command ?: return false
        val bytes = message.toByteArray(Charsets.UTF_8)
        if (bytes.size > mtu - 3) return false
        return try {
            pendingWrite = true
            pendingWriteAt = now()
            val ok = if (Build.VERSION.SDK_INT >= 33)
                link.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = bytes
                link.writeCharacteristic(c)
            }
            if (!ok) pendingWrite = false
            ok
        } catch (_: Exception) { pendingWrite = false; false }
    }

    private fun sendClockIfDue(): Boolean {
        // No TIME injection into the acknowledged BEGIN/CELL/LVL/RAW/END batch.
        if (activityVisible || inProgress || pendingWrite || operatorProfilePackets.isNotEmpty() || mtu < 64) return false
        val elapsed = now()
        if (lastClockSyncSentAt != 0L && elapsed - lastClockSyncSentAt < 600_000L)
            return false
        val clock = java.util.Calendar.getInstance()
        val packet = String.format(Locale.US, "TIME,%04d,%02d,%02d,%02d,%02d,%02d,%d",
            clock.get(java.util.Calendar.YEAR),
            clock.get(java.util.Calendar.MONTH) + 1,
            clock.get(java.util.Calendar.DAY_OF_MONTH),
            clock.get(java.util.Calendar.HOUR_OF_DAY),
            clock.get(java.util.Calendar.MINUTE),
            clock.get(java.util.Calendar.SECOND),
            clock.get(java.util.Calendar.DAY_OF_WEEK) - 1)
        if (!send(packet)) return false
        lastClockSyncSentAt = elapsed
        log("G5A15 watch local-time sync sent")
        return true
    }

    private fun sendGps() {
        if (operatorProfilePackets.isNotEmpty()) return
        if (inProgress && (awaiting != null || stage == "begin" || stage == "end")) return
        val fix = freshGps() ?: return
        if (now() - lastGpsWrite < 1000) return
        if (sendClockIfDue()) return
        val packet = String.format(Locale.US, "GPS,%.6f,%.6f,%.1f,%.1f,%d", fix.latitude,
            fix.longitude, if (fix.hasSpeed()) fix.speed * 2.2369363 else 0.0,
            lastBearing?.toDouble() ?: 0.0, if (lastBearing != null) 1 else 0)
        if (send(packet)) lastGpsWrite = now()
    }

    private fun response(message: String) {
        val isPong = linkHealth.onResponse(now(), message)
        if (isPong) {
            log("G5A43 HEARTBEAT_PONG owner=service rttMs=${linkHealth.lastPongRttMs}")
            return
        }
        StaleIntervalSettings.applyWatchMessage(this, message)?.let { minutes ->
            // Don't modify last-good timestamps or evict historical sectors.
            nextLookaheadAtMs = 0L
            desiredWatchCenter?.let { startBackgroundLookahead(it) }
            log("G5A45.4 STALE_INTERVAL=$minutes minutes")
            return
        }
        if (message == "CENTER:OK" || message == "CENTER:MISS") {
            val acknowledged = awaitingCenter
            acknowledged?.let { lastCenterAck = it }
            lastCenterMiss = if (message == "CENTER:MISS") acknowledged else null
            awaitingCenter = null
            if (message == "CENTER:MISS" && acknowledged != null &&
                missRefillAttemptedFor != acknowledged) {
                // Attempt cached/lookahead refill once per requested center.
                // On a second MISS, let checkSector enter its validated full-
                // scan fallback rather than looping the same cache packets.
                missRefillAttemptedFor = acknowledged
                geoQueue.invalidate(WdgWarsGrid.window(acknowledged, 1))
                geoWarmPending = true
                warmFromCachedDetails()
                nextLookaheadAtMs = 0L
                startBackgroundLookahead(acknowledged)
            }
            if (message == "CENTER:OK" && acknowledged != null) {
                // This is an ACKed watch-side 3x3 commit, not an API guess.
                lastSector = acknowledged
                currentGridKeys = WdgWarsGrid.window(acknowledged, 1).map { id ->
                    val (lat, lon) = WdgWarsGrid.queryPoint(id)
                    key(lat, lon)
                }
                val clock = now()
                // If phone data is stale/missing, let the regular service poll
                // refetch it; the watch keeps its already-recentered snapshot.
                lastScanMs = clock // watch already centered; stale detail is refreshed separately
                // First refill from validated PHONE cache, no HTTP delay.
                geoWarmPending = true
                // GPS planner independently maintains the 25-sector window.
            }
            log("G5A40 WATCH_ACK_CENTER=$lastCenterAck status=$message desired=$desiredWatchCenter " +
                "cacheVisible=${acknowledged?.let { c -> WdgWarsGrid.window(c, 1).count { hasCachedSector(it, now()) } } ?: 0}/9")
            return
        }
        if (geoQueue.accept(message)) return
        if (onOperatorProfileMessage(message)) return
        if (onTeamMessage(message)) return
        if (message.startsWith("CAPS,") || (watchCapsState == "PROBING" && message == "ERR:UNKNOWN_COMMAND")) {
            watchCapsState = WatchCapabilities.classify(message)
            watchAccountCapable = AccountOwnerWire.supported(message)
            watchGangNameSupported = WatchCapabilities.supportsGangNames(message)
            watchFullSectorNamesSupported = WatchCapabilities.supportsFullSectorNames(message)
            geoCacheSupported = WatchCapabilities.supportsGeoCache(message)
            if (geoCacheSupported) warmFromCachedDetails()
            if (watchAccountCapable) refreshOperatorProfile()
            log("G4B watch capabilities=$watchCapsState gang=$watchGangNameSupported")
            return
        }
        if (!inProgress) return
        if (message == awaiting) {
            awaiting = null
            if (stage == "checking") {
                // The watch acknowledged CHECKING before the next scan/lookup stage.
                // No arbitrary four-second hold delays a needed fetch.
                if (coverageRequiresScan) {
                    stage = "scan"
                } else {
                    stage = "lookup"
                    deadline = now() + 60000
                    fetchTerritories()
                }
            } else if (stage == "scan") {
                stage = "lookup"
                deadline = now() + 60000
                fetchTerritories()
            } else if (stage == "begin") {
                stage = "packets"
                packetIndex = 0
                deadline = 0L
                progress(0)
            } else if (stage == "packets") {
                // The response matched the ACK for packetQueue[packetIndex].
                // Only LVL/RAW packets count; 9 CELL + NAME packets contribute 0%.
                val ackedPacket = packetQueue.getOrNull(packetIndex)
                packetIndex++
                deadline = 0L
                if (ackedPacket != null && ApUplinkProgress.isApLevelPacket(ackedPacket)) {
                    acknowledgedApLevelPackets++
                    val pct = ApUplinkProgress.percent(
                        acknowledgedApLevelPackets, apLevelPacketsTotal)
                    progress(pct)
                }
            } else if (stage == "end") {
                val complete = lastSector
                lastScanMs = now()
                finish("9 sectors committed (center=$complete)")
            }
            return
        }
        if (message.startsWith("SCAN:") || message.startsWith("BEGIN:") ||
            message.startsWith("CELL:") || message.startsWith("LVL:") ||
            message.startsWith("RAW:") || message.startsWith("END:")) {
            if (awaiting != null) abort("Unexpected response $message while awaiting $awaiting")
        }
    }

    private fun arm(packet: String, ack: String): Boolean {
        if (!send(packet)) return false
        expectedPacket = packet
        awaiting = ack
        deadline = now() + 6000
        return true
    }

    private fun progress(value: Int) {
        val capped = value.coerceIn(0, 99)
        if (capped <= lastProgress || now() - lastProgressAt < 350 || pendingWrite || awaiting != null) return
        if (send("PROG,$capped")) { lastProgress = capped; lastProgressAt = now() }
    }

    private var lastOwnershipRefreshCheckMs = -30_000L
    private val pulse = object : Runnable {
        override fun run() {
            if (!active) return
            if (!activityVisible) {
                if (gpsOffPending && !pendingWrite && !inProgress && send("GPSOFF"))
                    gpsOffPending = false
                if (gatt != null && mtu < 64 && gattStartedAtMs != 0L &&
                    now() - gattStartedAtMs > 18_000L) {
                    log("GATT handshake timed out; reconnecting")
                    bleRetry.fail(now())
                    disconnectBle()
                }
                if (gatt == null && !scanning && bleRetry.canAttempt(now())) startBleScan()
                if (pendingWrite && now() - pendingWriteAt > 7000) {
                    pendingWrite = false
                    bleRetry.fail(now())
                    disconnectBle()
                }
                // GPS cache recenter runs every 200 ms regardless of API work.
                // The tracker also runs on every fresh GPS callback.
                freshGps()?.let { fix ->
                    onGpsSector(anchorRegistry.locate(fix.latitude, fix.longitude))
                }
                // G5A50.4c: refresh official gang ownership independent of AP caching.
                if (now() - lastOwnershipRefreshCheckMs >= 30_000L) {
                    lastOwnershipRefreshCheckMs = now()
                    freshGps()?.let { fix ->
                        TerritoryOwnershipRefresh.requestIfDue(
                            this@DrivingSessionService, readApiKey(), fix.latitude, fix.longitude
                        ) { changed ->
                            if (active && !activityVisible && changed > 0) {
                                territoryForCache = TerritoryCacheStore.load(this@DrivingSessionService)
                                geoWarmPending = true // also resend AP-identical sectors with new gang/color
                                log("G5A50.4c WATCH_METADATA_REFRESH changed=$changed")
                            }
                        }
                    }
                }
                driveGeo()
                tickLookahead()
                if (inProgress) driveTransfer()
                else {
                    // G4B: one non-blocking capability query per connection.
                    // Never inject it into an acknowledged BEGIN/CELL/.../END transaction.
                    if (watchCapsState == "PROBING" && now() - capsSinceMs > 2500L) {
                        watchCapsState = "LEGACY"
                        log("G4B CAPS timeout; legacy BLE retained")
                    }
                    if (watchCapsState == "UNKNOWN" && now() >= capsProbeAtMs &&
                        mtu >= 64 && !pendingWrite) {
                        if (send(WatchCapabilities.QUERY)) {
                            watchCapsState = "PROBING"
                            capsSinceMs = now()
                        }
                    }
                    if (!geoQueue.active && geoQueue.lastError != null &&
                        geoCacheSupported && geoRetry.canAttempt(now())) {
                        geoRetry.fail(now())
                        geoWarmPending = true
                        log("Geo packet timeout; retry cached records")
                    }
                    if (geoWarmPending && !geoQueue.active && !inProgress) warmFromCachedDetails()
                    sendClockIfDue() // also works if GPS is unavailable
                    sendGps()
                    // Revisit a failed sector transition earlier, but only when
                    // its bounded retry gate permits another attempt.
                    val interval = if (sectorRetry.failedAttempts > 0) 5_000L else 30_000L
                    if (now() - lastSectorCheck >= interval) {
                        lastSectorCheck = now()
                        checkSector()
                    }
                }
                sendOperatorProfileIfReady()
                sendTeamIfReady()
                tickLinkHealth() // after navigation and AP work; never preempt them
            }
            main.postDelayed(this, 200L)
        }
    }

    private fun driveTransfer() {
        if (!inProgress) return
        if (awaiting != null) {
            if (now() > deadline) abort("ACK timeout: $expectedPacket")
            return
        }
        if (stage == "lookup" || stage == "details") {
            if (now() > deadline) abort("Network timeout at $stage")
            return
        }
        // The control message must be acknowledged before another AP packet.
        if (pendingWrite || awaitingCenter != null) return
        when (stage) {
            "checking" -> arm("HUD,CHECK,START", "HUD:OK")
            "scan" -> arm("SCAN", "SCAN:OK")
            "begin" -> {
                if (!watchFullSectorNamesSupported) {
                    abort("GANG96 capability lost before BEGIN; old snapshot retained")
                    return
                }
                val center = pendingSector
                val packet = if (geoCacheSupported && center != null)
                    "BEGIN,$expectedLevels,$expectedRaw,1,${center.north},${center.east}"
                else "BEGIN,$expectedLevels,$expectedRaw,1"
                arm(packet, "BEGIN:OK")
            }
            "packets" -> {
                if (packetIndex >= packetQueue.size) stage = "end"
                else {
                    val packet = packetQueue[packetIndex]
                    val ack = when {
                        packet.startsWith("CELL,") -> "CELL:OK"
                        packet.startsWith("NAME,") -> "NAME:OK"
                        packet.startsWith("LVL,") -> "LVL:OK"
                        else -> "RAW:OK"
                    }
                    val wirePacket = if (packet.startsWith("CELL,"))
                        GangWireName.forWatch(packet, watchGangNameSupported) else packet
                    arm(wirePacket, ack)
                }
            }
            "end" -> arm("END", "END:OK")
        }
    }

    private fun checkSector() {
        if (!sectorRetry.canAttempt(now())) return
        val fix = freshGps() ?: return
        if (gatt == null || command == null || mtu < 64) return
        val cell = WdgWarsGrid.sectorAt(fix.latitude, fix.longitude)
        if (geoCacheSupported && lastSector != cell) {
            // The watch-first CENTER command is always attempted before a scan.
            // An in-flight CENTER or queued phone-cache refill must finish first.
            if (lastCenterAck != cell || awaitingCenter != null || geoQueue.active ||
                geoWarmPending || lookaheadCenter != null) return
            if (lastCenterMiss == cell) {
                // Do not require all nine AP records (water/no data is OK).
                // The current geographic view already moves on CENTER;
                // fetch any missing intelligence independently.
                startBackgroundLookahead(cell)
            }
            return
        }
        if (lastSector != cell || lastScanMs == 0L || now() - lastScanMs >= 300000L) {
            // Refresh grid only on new sector or when a cache entry is stale.
            val oldWindow = lastSector == cell && currentGridKeys.size == 9
            val staleDetail = currentGridKeys.any { k ->
                details[k]?.let { now() - it.atMs >= ttl } ?: true
            }
            if (oldWindow && geoCacheSupported && staleDetail) {
                // Already displaying this center; refresh cache behind the HUD
                // instead of needlessly rebuilding the same nine-sector snapshot.
                startBackgroundLookahead(cell)
                lastScanMs = now()
            } else if (!oldWindow || staleDetail) beginScan()
            else lastScanMs = now() // health check without network traffic
        }
    }

    private fun hasCachedSector(id: SectorId, clock: Long): Boolean {
        val (lat, lon) = WdgWarsGrid.queryPoint(id)
        val record = details[key(lat, lon)] ?: return false
        if (record.atMs > clock || clock - record.atMs >= ttl) return false
        val data = record.data
        val total = data.optLong("total", -1L)
        val levels = data.optJSONArray("levels") ?: return false
        if (!data.optBoolean("ok", true) || total !in 0L..Int.MAX_VALUE.toLong()) return false
        var sum = 0L
        for (i in 0 until levels.length()) {
            val level = levels.optJSONObject(i) ?: return false
            val number = level.optInt("lvl", 0)
            val count = level.optLong("c", -1)
            if (number !in 1..255 || count !in 0L..Int.MAX_VALUE.toLong()) return false
            sum += count
        }
        return sum == total
    }

    private fun beginScan() {
        val fix = freshGps() ?: return
        if (inProgress || gatt == null || command == null || mtu < 64 ||
            !sectorRetry.canAttempt(now())) return
        // G5A36.1: never send a degraded 20-character legacy name when
        // capability probing has not confirmed the full-name protocol.
        if (!watchFullSectorNamesSupported) {
            if (watchCapsState == "LEGACY") {
                watchCapsState = "UNKNOWN"
                capsProbeAtMs = now()
            }
            log("Sector sync deferred until GANG96 capability is confirmed")
            return
        }
        val center = WdgWarsGrid.sectorAt(fix.latitude, fix.longitude)
        pendingSector = center // Fix center BEFORE async territory and detail lookup.
        val clock = now()
        val missing = SectorScanCoverage.missingIds(center) { hasCachedSector(it, clock) }
        log("SECTOR SCAN coverage missing=${missing.size}/25 center=$center")
        stopBackgroundLookahead()
        geoQueue.abort("full snapshot priority")
        inProgress = true
        awaiting = null
        lastProgress = -1
        // G5A35: acknowledge the meaningful coverage check with the watch's
        // CHECKING sweep. Only then proceed to SCAN (missing) or the cached
        // lookup route (nothing missing). The existing snapshot remains visible.
        coverageRequiresScan = missing.isNotEmpty()
        stage = "checking"
        deadline = clock + 6000
        driveTransfer()
    }

    private fun accountChangePending(): Boolean =
        getSharedPreferences("wdghud_account_fence", MODE_PRIVATE)
            .getBoolean("pending", false)

    private fun readApiKey(): String {
        if (accountChangePending()) return ""
        val encoded = getSharedPreferences("wdghud_credentials", MODE_PRIVATE)
            .getString("api_key_cipher_v1", null) ?: return ""
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > 28)
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey("wdghud_api_key_aes_v1", null) as? SecretKey ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
        } catch (e: Exception) { log("API credential unavailable: ${e.javaClass.simpleName}"); "" }
    }

    private fun fetchTerritories() {
        val key = readApiKey()
        val fix = freshGps()
        if (key.isBlank() || fix == null) { abort("Saved API key or GPS fix missing"); return }
        val target = pendingSector ?: return
        val (lat, lon) = WdgWarsGrid.queryPoint(target)
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = URL("https://wdgwars.pl/api/member-territories").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("X-API-Key", key)
                if (PortalHttpPolicy.responseCode(conn) != 200 || !conn.contentType.orEmpty().contains("json", true))
                    throw IllegalStateException("Territory API HTTP ${PortalHttpPolicy.responseCode(conn)}")
                val nearby = mutableListOf<Territory>()
                var ok = false
                conn.inputStream.bufferedReader().use { input ->
                    JsonReader(input).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) when (reader.nextName()) {
                            "ok" -> ok = reader.nextBoolean()
                            "cells" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    var y = Double.NaN; var x = Double.NaN
                                    var count = 0; var gang = 0; var color: String? = null
                                    var gangName: String? = null
                                    reader.beginObject()
                                    while (reader.hasNext()) when (reader.nextName()) {
                                        "lat" -> y = reader.nextDouble()
                                        "lng" -> x = reader.nextDouble()
                                        "count" -> count = reader.nextInt()
                                        "gang_id" -> gang = reader.nextInt()
                                        "gang" -> gangName = when (reader.peek()) {
                                            JsonToken.STRING -> reader.nextString()
                                            JsonToken.NULL -> { reader.nextNull(); null }
                                            else -> { reader.skipValue(); null }
                                        }
                                        "color" -> color = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextString().takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
                                        else -> reader.skipValue()
                                    }
                                    reader.endObject()
                                    if (y.isFinite() && x.isFinite() && abs(y - lat) <= 0.08 && abs(x - lon) <= 0.10)
                                        nearby.add(Territory(y, x, count, gang, color, gangName))
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                    }
                }
                if (!ok) throw IllegalStateException("Territory API returned ok=false")
                val grid = formGrid(nearby, lat, lon)
                main.post {
                    if (stage != "lookup" || !inProgress) return@post
                    // Keep the original GPS sector from beginScan; moving during
                    // the network request must not tag old tiles as a new center.
                    pendingGridKeys = grid.centers.map { (y, x) -> key(y, x) }
                    stage = "details"
                    deadline = now() + 60000
                    // Territory lookup does not affect AP uplink progress.
                    fetchDetails(grid)
                }
            } catch (e: Exception) {
                main.post { abort("Territory lookup: ${e.message ?: e.javaClass.simpleName}") }
            } finally { conn?.disconnect() }
        }
    }

    private var pendingSector: SectorId? = null

    private fun formGrid(nearby: List<Territory>, lat: Double, lon: Double): Grid {
        val centerId = WdgWarsGrid.sectorAt(lat, lon)
        val territoryBySector = nearby.associateBy { WdgWarsGrid.sectorAt(it.lat, it.lon) }
        val verifiedOwnership = territoryBySector.mapValues { (_, owner) ->
            GangWireName.encodeFull(true, owner.gang, owner.gangName) to owner.color
        }
        TerritoryCacheStore.merge(this, verifiedOwnership)
        territoryForCache = TerritoryCacheStore.load(this)
        val ids = WdgWarsGrid.window(centerId, 1)
        val coords = ids.map { WdgWarsGrid.queryPoint(it) }
        val cells = ids.mapIndexed { i, id ->
            val owner = territoryBySector[id]
            "CELL,$i,${owner?.count ?: 0},${owner?.gang ?: 0},${owner?.color ?: "-"},${GangWireName.encode(owner != null, owner?.gang ?: 0, owner?.gangName)}"
        }
        val names = ids.flatMapIndexed { i, id ->
            val owner = territoryBySector[id]
            GangWireName.namePackets(i, owner != null, owner?.gang ?: 0, owner?.gangName)
        }
        require(coords.size == 9 && coords.distinct().size == 9)
        return Grid(coords, cells, names)
    }

    private fun fetchDetails(grid: Grid) {
        // Same-origin JS fetch is essential: saved API key CANNOT authenticate /api/cell-detail.
        val browser = WebView(this)
        page = browser
        browser.settings.userAgentString = PortalHttpPolicy.webUserAgent(browser.settings.userAgentString)
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.addJavascriptInterface(object {
            @JavascriptInterface fun rateLimited(retryAfter: String) {
                PortalHttpPolicy.record429(retryAfter)
            }
            @JavascriptInterface fun progressStep(done: Int, total: Int) {
                // API detail fetch progress intentionally does not affect PROG.
            }
            @JavascriptInterface fun report(raw: String) {
                main.post {
                    if (page !== browser || stage != "details") return@post
                    try {
                        val result = JSONObject(raw).getJSONArray("results")
                        val seen = mutableSetOf<Int>()
                        val cells = grid.cells.toMutableList()
                        val lvls = mutableListOf<String>()
                        val raws = mutableListOf<String>()
                        val save = mutableMapOf<String, JSONObject>()
                        for (i in 0 until result.length()) {
                            val data = result.getJSONObject(i)
                            val index = data.getInt("index")
                            require(index in 0..8 && seen.add(index) && data.has("levels") && data.has("total"))
                            val total = data.getLong("total")
                            require(total in 0..Int.MAX_VALUE.toLong())
                            val levels = data.getJSONArray("levels")
                            val tierCounts = sortedMapOf<Int, Long>()
                            var sum = 0L
                            for (j in 0 until levels.length()) {
                                val level = levels.getJSONObject(j)
                                val lvl = level.getInt("lvl")
                                val count = level.getLong("c")
                                require(lvl in 1..255 && count in 0..Int.MAX_VALUE.toLong())
                                sum += count
                                if (count > 0) {
                                    val tier = ApTierBands.fromLevel(lvl)
                                    tierCounts[tier] = (tierCounts[tier] ?: 0L)+count
                                    raws.add("RAW,$index,$lvl,$count")
                                }
                            }
                            require(sum == total) { "AP sum mismatch on slot $index" }
                            val fields = cells[index].split(',', limit = 6)
                            require(fields.size == 6)
                            cells[index] = "CELL,$index,$total,${fields[3]},${fields[4]},${fields[5]}"
                            tierCounts.forEach { (tier, count) ->
                                require(count <= Int.MAX_VALUE)
                                lvls.add("LVL,$index,${tier-1},$tier,$count")
                            }
                            if (!data.optBoolean("fromCache", false)) {
                                val (y,x) = grid.centers[index]
                                save[key(y,x)] = JSONObject(data.toString()).apply { remove("index") }
                            }
                        }
                        require(seen.size == 9) { "Only ${seen.size}/9 valid sectors" }
                        val stamp = now()
                        for ((k,v) in save) details[k] = Detail(v,stamp)
                        saveSharedCache()
                        // Transient RAM eviction only; cumulative SQLite history is never pruned.
                        if (details.size > 2000) details.entries.sortedBy { it.value.atMs }
                            .take(details.size-1600).forEach { details.remove(it.key) }
                        expectedLevels = lvls.size
                        expectedRaw = raws.size
                        packetQueue = cells +
                            (if (watchFullSectorNamesSupported) grid.names else emptyList()) +
                            lvls + raws
                        apLevelPacketsTotal = ApUplinkProgress.countApLevelPackets(packetQueue)
                        acknowledgedApLevelPackets = 0
                        packetIndex = 0
                        stage = "begin"
                        deadline = 0
                        // Validated response; uplink will start at BEGIN.
                        log("Validated nine sectors; ${lvls.size} tiers, ${raws.size} RAW")
                    } catch (e: Exception) { abort("Sector validation: ${e.message}") }
                    finally { closePage() }
                }
            }
        }, "WDGHudResult")
        var requested = false
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (page !== browser || stage != "details" || requested) return
                val host = android.net.Uri.parse(url).host
                if (host != "wdgwars.pl" && host?.endsWith(".wdgwars.pl") != true) {
                    abort("Website session not on WDGWars"); return
                }
                requested = true
                val targets = JSONArray()
                val cached = JSONArray()
                val clock = now()
                grid.centers.forEachIndexed { i, (y,x) ->
                    targets.put(JSONArray().put(y).put(x))
                    details[key(y,x)]?.takeIf { clock-it.atMs in 0 until ttl }?.let { detail ->
                        cached.put(JSONObject(detail.data.toString()).put("index",i).put("fromCache",true))
                    }
                }
                val script = """(async function(){
                    const targets=$targets, cached=$cached;
                    const available=new Map(cached.map(x=>[x.index,x]));
                    const results=[];
                    for(let i=0;i<targets.length;i++){
                        if(available.has(i)){
                            results.push(available.get(i));
                            WDGHudResult.progressStep(i+1,targets.length);
                            continue;
                        }
                        const [lat,lng]=targets[i];
                        try{
                            const r=await fetch('/api/cell-detail?lat='+encodeURIComponent(lat)+'&lng='+encodeURIComponent(lng),
                                {credentials:'same-origin',headers:{'Accept':'application/json'}});
                            if(r.status === 429) {
                                WDGHudResult.rateLimited(r.headers.get('Retry-After') || '');
                                throw Error('WDGHUD_429');
                            }
                            if(!r.ok || !(r.headers.get('content-type')||'').includes('json'))
                                throw Error('HTTP '+r.status+'; sign in via WDG HUD');
                            const d=await r.json();
                            if(!d.ok || !Array.isArray(d.levels)) throw Error('Missing AP levels');
                            results.push({index:i,total:d.total,levels:d.levels});
                        }catch(e){
                            results.push({index:i,error:String(e)});
                            if(String(e).includes('WDGHUD_429')) break;
                        }
                        finally{WDGHudResult.progressStep(i+1,targets.length);}
                    }
                    WDGHudResult.report(JSON.stringify({results:results}));
                })();""".trimIndent()
                browser.evaluateJavascript(script,null)
            }
        }
        browser.loadUrl("https://wdgwars.pl/")
    }

    private fun closePage() {
        val browser = page ?: return
        page = null
        try { browser.stopLoading(); browser.destroy() } catch (_: Exception) { }
    }

    private fun abort(message: String) {
        if (!inProgress) return
        if (message != "Activity resumed" && message != "Session stopped") {
            sectorRetry.fail(now())
            lastSectorCheck = 0L
            log("Sector retry armed in ${sectorRetry.remainingMs(now())}ms")
        }
        log("Scan aborted: $message")
        inProgress = false
        awaiting = null
        packetQueue = emptyList()
        stage = "idle"
        pendingSector = null
        pendingGridKeys = emptyList()
        closePage()
        // Never publish a partial sector snapshot. Watch discards any unfinished BEGIN.
        if (!pendingWrite) send("SCANSTOP")
        notifyStatus("Scan failed: $message")
    }
    private fun finish(message: String) {
        sectorRetry.succeed()
        inProgress = false
        stage = "idle"
        awaiting = null
        packetQueue = emptyList()
        lastSector = pendingSector
        currentGridKeys = pendingGridKeys
        pendingSector = null
        warmFromCachedDetails()
        // Watch gets cached detail now; download outer-ring gaps independently.
        val lookahead = desiredWatchCenter
        if (lookahead != null) main.post { startBackgroundLookahead(lookahead) }
        pendingGridKeys = emptyList()
        log(message)
        notifyStatus("Watch connected · $message")
    }

    override fun onCreate() {
        super.onCreate()
        main.postDelayed(teamBackgroundRefresh, 15_000L)
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL,"WDG HUD driving session",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); stopSelf(); return START_NOT_STICKY }
            ACTION_UI_ACTIVE -> {
                recoveringGps.stop()
                latest = null // never replay an old location at owner handoff
                desiredWatchCenter = null
                stopBackgroundLookahead()
                saveSharedCache()
                activityVisible = true
                abort("Activity resumed")
                disconnectBle()
                notifyStatus("App visible · app controls watch")
            }
            ACTION_UI_HIDDEN -> {
                restoreSharedCache()
                log("G5A42 HISTORY_TOTAL=${SectorCacheStore.count(this)} persisted AP sectors")
                territoryForCache = TerritoryCacheStore.load(this)
                activityVisible = false
                latest = null
                desiredWatchCenter = null
                if (active) startGps()
                if (active) { lastSectorCheck = 0; startBleScan() }
            }
            ACTION_START -> {
                active = true
                val notification = notification("Starting")
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID,notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                else startForeground(NOTIF_ID,notification)
                // Activity owns GPS while visible. Service starts GPS upon UI_HIDDEN.
                if (!activityVisible) startGps()
                main.removeCallbacks(pulse)
                main.post(pulse)
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this,1,Intent(this,DrivingSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this,CHANNEL) else Notification.Builder(this)
        return n.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("WDG // HUD driving session").setContentText(detail)
            .setContentIntent(open).addAction(Notification.Action.Builder(null,"STOP SESSION",stop).build())
            .setOngoing(true).build()
    }
    private fun notifyStatus(detail: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID,notification(detail))
    }
    private fun startGps() = recoveringGps.start()

    private fun startBleScan() {
        if (!active || activityVisible || scanning || gatt != null) return
        if (Build.VERSION.SDK_INT >= 31 &&
            (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
             checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return
        try {
            val scanner = bluetooth.adapter?.bluetoothLeScanner ?: run {
                bleRetry.fail(now()); return
            }
            scanning = true
            scanner.startScan(bleScanCallback)
            main.removeCallbacks(stopScanTimer)
            main.postDelayed(stopScanTimer, 8_000L)
        } catch (_: Exception) {
            scanning = false
            bleRetry.fail(now())
        }
    }
    private fun stopBleScan() {
        main.removeCallbacks(stopScanTimer)
        if (!scanning) return
        scanning = false
        try { bluetooth.adapter?.bluetoothLeScanner?.stopScan(bleScanCallback) } catch (_: Exception) { }
    }
    private fun disconnectBle() {
        linkHealth.onDisconnected("service link closed")
        resetOperatorProfileTransfer()
        stopBleScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
        gatt = null; command = null; mtu = 23; pendingWrite = false
        gattStartedAtMs = 0L
        watchCapsState = "UNKNOWN"
        watchAccountCapable = false
        watchGangNameSupported = false
        watchFullSectorNamesSupported = false
        geoCacheSupported = false
        awaitingCenter = null
        lastCenterAck = null
        lastCenterMiss = null
        geoQueue.resetRemoteState()
        geoRetry.succeed() // new link retries from saved, validated sector data
        if (inProgress) abort("BLE disconnected")
    }
    private fun shutdown() {
        stopBackgroundLookahead()
        active = false
        main.removeCallbacks(pulse)
        main.removeCallbacks(teamBackgroundRefresh)
        abort("Session stopped")
        closePage()
        recoveringGps.stop()
        disconnectBle()
        saveSharedCache()
        getSharedPreferences("wdghud_session",MODE_PRIVATE).edit().putBoolean("active",false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() {
        shutdown()
        executor.shutdownNow()
        lookaheadExecutor.shutdownNow()
        anchorExecutor.shutdownNow()
        operatorProfileExecutor.shutdownNow()
        teamExecutor.shutdownNow()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
}
