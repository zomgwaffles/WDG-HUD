package com.example.wdghud

/** G5A38.1: pure recovery rules shared by foreground and driving-service GPS owners. */
internal object GpsRecoveryPolicy {
    enum class Phase { STOPPED, PERMISSION_REQUIRED, PROVIDERS_OFF, WAITING, READY, STALE }

    const val STALE_AFTER_MS = 30_000L
    const val MAX_NETWORK_ACCURACY_METERS = 100f

    fun phase(started: Boolean, permission: Boolean, providerCount: Int,
              lastFixAgeMs: Long?): Phase = when {
        !started -> Phase.STOPPED
        !permission -> Phase.PERMISSION_REQUIRED
        providerCount <= 0 -> Phase.PROVIDERS_OFF
        lastFixAgeMs == null -> Phase.WAITING
        lastFixAgeMs < 0 || lastFixAgeMs > STALE_AFTER_MS -> Phase.STALE
        else -> Phase.READY
    }

    fun acceptFix(provider: String, ageMs: Long, hasAccuracy: Boolean,
                  accuracyMeters: Float): Boolean {
        if (ageMs < 0 || ageMs > 15_000L) return false
        return when (provider) {
            "gps" -> true
            "network" -> hasAccuracy && accuracyMeters.isFinite() &&
                accuracyMeters in 0f..MAX_NETWORK_ACCURACY_METERS
            else -> false
        }
    }
}
