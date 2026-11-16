package com.example.wdghud

import com.example.wdghud.geometry.SectorId

/** No-AP and temporarily unavailable are different.
 *  Zero-AP with a valid JSON payload is a successful sync and is cached.
 *  Missing/invalid response stays UNKNOWN and is retried later; it must not
 *  hold up any other sector or GPS-controlled grid transition.
 */
internal object SectorUnavailableBackoff {
    private val retryUtc = mutableMapOf<SectorId, Long>()
    private const val UNKNOWN_COOLDOWN_MS = 10L * 60L * 1000L
    private const val TRANSIENT_COOLDOWN_MS = 60_000L

    @Synchronized fun canRetry(id: SectorId, nowUtc: Long = System.currentTimeMillis()): Boolean =
        nowUtc >= (retryUtc[id] ?: 0L)

    @Synchronized fun unavailable(id: SectorId, nowUtc: Long = System.currentTimeMillis()) {
        retryUtc[id] = nowUtc + UNKNOWN_COOLDOWN_MS
    }
    @Synchronized fun transientFailure(id: SectorId, nowUtc: Long = System.currentTimeMillis()) {
        retryUtc[id] = nowUtc + TRANSIENT_COOLDOWN_MS
    }
    @Synchronized fun succeeded(id: SectorId) { retryUtc.remove(id) }
}
