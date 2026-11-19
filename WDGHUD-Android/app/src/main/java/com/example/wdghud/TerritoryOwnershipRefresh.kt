package com.example.wdghud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.WdgWarsGrid
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

/** Refreshes official gang ownership independently of AP detail freshness.
 *
 * A GPS/cached watch can stay centered and receive new AP levels without ever
 * entering the legacy full nine-sector upload path. That path previously was
 * the only place which fetched /api/member-territories. Keep its ownership
 * metadata current without erasing AP history or invalidating watch snapshots.
 */
internal object TerritoryOwnershipRefresh {
    private const val TAG = "WDGHUD_TERRITORY"
    private const val INTERVAL_MS = HourlyRefreshPolicy.TTL_MS
    private const val MIN_REQUEST_GAP_MS = 60_000L
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val hexColor = Regex("#[0-9a-fA-F]{6}")

    private var inFlight = false
    private var lastAttemptMs = 0L
    private var lastSuccessMs = 0L
    private var lastCenter: SectorId? = null

    @Synchronized
    fun requestIfDue(
        context: Context,
        key: String,
        latitude: Double,
        longitude: Double,
        onChanged: (Int) -> Unit
    ) {
        if (key.isBlank() || !latitude.isFinite() || !longitude.isFinite() ||
            PortalHttpPolicy.isLimited() || inFlight) return
        val now = SystemClock.elapsedRealtime()
        val center = WdgWarsGrid.sectorAt(latitude, longitude)
        if (lastAttemptMs != 0L && now - lastAttemptMs < MIN_REQUEST_GAP_MS) return
        if (lastSuccessMs != 0L && now - lastSuccessMs < INTERVAL_MS &&
            center == lastCenter) return
        val app = context.applicationContext
        val apiKey = key.trim()
        // Shared, persistent, account-bound coverage survives BLE ownership
        // transfers and app restarts; never refetch known nearby sectors early.
        if (TerritoryFreshnessStore.currentWindowFresh(
                app, apiKey, center, System.currentTimeMillis())) return
        inFlight = true
        lastAttemptMs = now
        executor.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = URL("https://wdgwars.pl/api/member-territories")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 45_000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("X-API-Key", apiKey)
                val status = PortalHttpPolicy.responseCode(conn)
                require(status == 200 && conn.contentType.orEmpty().contains("json", ignoreCase = true)) {
                    "Ownership API HTTP $status"
                }

                val rows = linkedMapOf<SectorId, Pair<String?, String?>>()
                var ok = false
                conn.inputStream.bufferedReader().use { stream ->
                    JsonReader(stream).use { reader ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "ok" -> ok = reader.nextBoolean()
                                "cells" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        var lat = Double.NaN
                                        var lon = Double.NaN
                                        var gangId = 0
                                        var gangName: String? = null
                                        var color: String? = null
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "lat" -> lat = reader.nextDouble()
                                                "lng" -> lon = reader.nextDouble()
                                                "gang_id" -> gangId = reader.nextInt()
                                                "gang" -> gangName = when (reader.peek()) {
                                                    JsonToken.STRING -> reader.nextString()
                                                    JsonToken.NULL -> { reader.nextNull(); null }
                                                    else -> { reader.skipValue(); null }
                                                }
                                                "color" -> {
                                                    color = when (reader.peek()) {
                                                        JsonToken.STRING -> reader.nextString().takeIf { hexColor.matches(it) }
                                                        JsonToken.NULL -> { reader.nextNull(); null }
                                                        else -> { reader.skipValue(); null }
                                                    }
                                                }
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                        if (lat.isFinite() && lon.isFinite() &&
                                            abs(lat - latitude) <= 0.08 &&
                                            abs(lon - longitude) <= 0.10) {
                                            val id = WdgWarsGrid.sectorAt(lat, lon)
                                            rows[id] = GangWireName.encodeFull(true, gangId, gangName) to color
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
                require(ok) { "Ownership API returned ok=false" }
                // Missing/unlisted records are NOT evidence of empty ownership.
                // TerritoryCacheStore.merge accepts only validated official values.
                val before = TerritoryCacheStore.loadWindow(app, rows.keys)
                TerritoryCacheStore.merge(app, rows)
                // Mark coverage ONLY after a complete, valid server response.
                // No gang listed in a cell is still an explicitly checked cell.
                TerritoryFreshnessStore.markNearbyChecked(
                    app, apiKey, center, System.currentTimeMillis())
                val after = TerritoryCacheStore.loadWindow(app, rows.keys)
                val changed = after.count { (id, value) -> before[id] != value }
                synchronized(this) {
                    lastSuccessMs = SystemClock.elapsedRealtime()
                    lastCenter = center
                }
                Log.i(TAG, "REFRESH_OK near=${rows.size} changed=$changed center=$center")
                if (changed > 0) main.post { onChanged(changed) }
            } catch (e: PortalRateLimitedException) {
                Log.w(TAG, "Portal cooldown: ${e.waitMs} ms")
            } catch (e: Exception) {
                Log.w(TAG, "Ownership refresh failed; cached gang metadata retained: ${e.message}")
            } finally {
                conn?.disconnect()
                synchronized(this) { inFlight = false }
            }
        }
    }
}
