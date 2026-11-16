package com.example.wdghud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * G5A38.1: one recoverable location subscription per active BLE owner.
 * No API fetch, watch-grid mutation, or BLE writes are performed here.
 * Checks provider availability every 5s, also responding to provider callbacks.
 * Accepts sufficiently accurate network fixes while satellites are unavailable.
 */
internal class RecoveringGpsSource(
    private val context: Context,
    private val onFix: (Location) -> Unit,
    private val onPhase: (GpsRecoveryPolicy.Phase, String) -> Unit
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private val activeProviders = linkedSetOf<String>()
    private var started = false
    private var lastAcceptedFixAt: Long? = null
    private var lastGpsFixAt: Long? = null
    private var lastSubscriptionAt = 0L
    private var phase: GpsRecoveryPolicy.Phase? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!started) return
            val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            if (!GpsRecoveryPolicy.acceptFix(location.provider ?: "", age,
                    location.hasAccuracy(), location.accuracy)) return
            val now = SystemClock.elapsedRealtime()
            // A recent satellite fix takes priority over network positioning.
            if (location.provider == LocationManager.NETWORK_PROVIDER &&
                lastGpsFixAt != null && now - lastGpsFixAt!! < 10_000L) return
            if (location.provider == LocationManager.GPS_PROVIDER) lastGpsFixAt = now
            lastAcceptedFixAt = now
            updatePhase(GpsRecoveryPolicy.Phase.READY,
                if (location.provider == LocationManager.GPS_PROVIDER) "GPS fix" else "Network fix")
            onFix(location)
        }

        override fun onProviderEnabled(provider: String) {
            Log.i("WDGHUD_GPS", "Provider enabled: $provider")
            main.post { reconcile() }
        }

        override fun onProviderDisabled(provider: String) {
            Log.w("WDGHUD_GPS", "Provider disabled: $provider")
            main.post { reconcile() }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            reconcile()
            main.postDelayed(this, 5_000L)
        }
    }

    fun start() {
        if (!started) {
            started = true
            lastAcceptedFixAt = null
            lastGpsFixAt = null
            lastSubscriptionAt = 0L
            main.removeCallbacks(watchdog)
            main.post(watchdog)
        } else reconcile()
    }

    fun stop() {
        started = false
        main.removeCallbacks(watchdog)
        clearSubscriptions()
        lastAcceptedFixAt = null
        lastGpsFixAt = null
        updatePhase(GpsRecoveryPolicy.Phase.STOPPED, "Stopped")
    }

    private fun permissionGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(): Set<String> = listOf(
        LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER
    ).filter { provider ->
        try { locationManager.isProviderEnabled(provider) }
        catch (_: Exception) { false }
    }.toSet()

    private fun clearSubscriptions() {
        if (activeProviders.isNotEmpty()) {
            try { locationManager.removeUpdates(listener) }
            catch (e: Exception) { Log.w("WDGHUD_GPS", "Stop subscription: ${e.javaClass.simpleName}") }
            activeProviders.clear()
        }
    }

    private fun reconcile() {
        if (!started) return
        if (!permissionGranted()) {
            clearSubscriptions()
            updatePhase(GpsRecoveryPolicy.Phase.PERMISSION_REQUIRED, "Precise location permission required")
            return
        }
        val enabled = enabledProviders()
        if (enabled.isEmpty()) {
            clearSubscriptions()
            updatePhase(GpsRecoveryPolicy.Phase.PROVIDERS_OFF,
                "Location providers unavailable — retrying automatically")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val ageBeforeRecovery = lastAcceptedFixAt?.let { now - it }
        val resubscribe = activeProviders != enabled ||
            (now - lastSubscriptionAt >= 45_000L &&
                (ageBeforeRecovery == null || ageBeforeRecovery > 45_000L))
        if (resubscribe) {
            // Provider changes invalidate the old fix until an actual new fix arrives.
            if (activeProviders != enabled) {
                lastAcceptedFixAt = null
                lastGpsFixAt = null
            }
            clearSubscriptions()
            for (provider in enabled) {
                try {
                    locationManager.requestLocationUpdates(provider, 1_000L, 0f,
                        listener, Looper.getMainLooper())
                    activeProviders.add(provider)
                    Log.i("WDGHUD_GPS", "Subscribed: $provider")
                } catch (e: SecurityException) {
                    Log.w("WDGHUD_GPS", "Location permission denied: $provider")
                } catch (e: Exception) {
                    Log.w("WDGHUD_GPS", "Subscribe $provider: ${e.javaClass.simpleName}")
                }
            }
            lastSubscriptionAt = now
        }
        if (activeProviders.isEmpty()) {
            updatePhase(GpsRecoveryPolicy.Phase.WAITING, "Location registration failed — retrying")
            return
        }
        // Re-evaluate after registration: a provider switch invalidates prior fixes.
        val currentAge = lastAcceptedFixAt?.let { now - it }
        when (GpsRecoveryPolicy.phase(true, true, activeProviders.size, currentAge)) {
            GpsRecoveryPolicy.Phase.READY -> {
                // Don't overwrite coordinate telemetry with a generic status.
                updatePhase(GpsRecoveryPolicy.Phase.READY, "Location ready")
            }
            GpsRecoveryPolicy.Phase.STALE -> updatePhase(GpsRecoveryPolicy.Phase.STALE,
                "GPS stale — reconnecting to location providers")
            else -> updatePhase(GpsRecoveryPolicy.Phase.WAITING,
                "Waiting for GPS / accurate network fix...")
        }
    }

    private fun updatePhase(new: GpsRecoveryPolicy.Phase, message: String) {
        if (phase == new) return
        phase = new
        Log.i("WDGHUD_GPS", "GPS state=$new: $message")
        onPhase(new, message)
    }
}
