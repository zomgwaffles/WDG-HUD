package com.example.wdghud.geometry

/**
 * E5.17 G3B: API request/response coordinator; no HTTP, WebView, GPS, BLE, or disk writes.
 * API location resolution MUST be supplied from verified WDGWars geometry before activation.
 * The caller owns authenticated requests and passes each COMPLETE response to complete().
 * One in-flight request at a time prevents overlapping bulk downloads.
 */
class SectorApiPreloadCoordinator(
    private val cache: SectorPreloadCache,
    private val requestCoordinates: (SectorId) -> Pair<Double, Double>?
) {
    data class ApiRequest(
        val id: SectorId,
        val latitude: Double,
        val longitude: Double,
        val sequence: Long
    )
    data class Status(
        val center: SectorId?,
        val required: Int,
        val missing: Int,
        val visibleReady: Boolean,
        val inFlight: SectorId?,
        val completedSinceStart: Int,
        val failuresSinceStart: Int
    )

    private var center: SectorId? = null
    private var pending: List<SectorId> = emptyList()
    private var current: ApiRequest? = null
    private var sequence = 0L
    private var successes = 0
    private var failures = 0
    private var blocked = mutableSetOf<SectorId>()

    /** Start/replan for a new center. Existing validated cache is retained. */
    fun begin(newCenter: SectorId, nowMs: Long): Status {
        require(nowMs >= 0)
        center = newCenter
        blocked.clear() // failed sectors can be retried on next explicit plan
        successes = 0
        failures = 0
        current = null // invalidates old request token
        pending = cache.outstanding(newCenter, nowMs)
        return status(nowMs)
    }

    /** Return the next request to hand to the caller's authenticated API transport. */
    fun next(nowMs: Long): ApiRequest? {
        require(nowMs >= 0)
        if (current != null) return null
        val target = center ?: return null
        // Replan after accepted responses, TTL changes, or a cache restore.
        val wanted = cache.outstanding(target, nowMs).toSet()
        pending = pending.filter { it in wanted && it !in blocked }
        while (pending.isNotEmpty()) {
            val id = pending.first()
            pending = pending.drop(1)
            val coordinates = try { requestCoordinates(id) } catch (_: Exception) { null }
            if (coordinates == null || !coordinates.first.isFinite() || !coordinates.second.isFinite() ||
                coordinates.first !in -90.0..90.0 || coordinates.second !in -180.0..180.0) {
                blocked += id
                failures++
                continue
            }
            val request = ApiRequest(id, coordinates.first, coordinates.second, ++sequence)
            current = request
            return request
        }
        return null
    }

    /** True only for a matching in-flight request with validated, accepted payload. */
    fun complete(request: ApiRequest, json: String, fetchedAtMs: Long): Boolean {
        if (current != request) return false // late/out-of-order completion
        current = null
        val accepted = cache.accept(request.id, json, fetchedAtMs)
        if (accepted) successes++ else {
            failures++
            blocked += request.id
        }
        return accepted
    }

    /** Mark a network/auth/timeout error. One failed request cannot spin forever. */
    fun fail(request: ApiRequest): Boolean {
        if (current != request) return false
        current = null
        failures++
        blocked += request.id
        return true
    }

    fun status(nowMs: Long): Status {
        require(nowMs >= 0)
        val id = center ?: return Status(null, 0, 0, false, null, successes, failures)
        val plan = cache.plan(id, nowMs)
        return Status(id, plan.envelope.size, plan.fetch.size, plan.visibleReady,
            current?.id, successes, failures)
    }

    fun visibleSnapshot(nowMs: Long): List<Pair<SectorId, SectorPreloadCache.Record>>? =
        center?.let { cache.visibleSnapshot(it, nowMs) }
}
