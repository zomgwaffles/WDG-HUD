package com.example.wdghud.geometry

/**
 * E5.17 G3A: payload-aware 5x5 preload staging layer.
 * Deliberately contains NO GPS, HTTP, Android, BLE, or production side effects.
 * Never treat a cached response as valid without the caller's full response validator.
 */
class SectorPreloadCache(
    private val planner: SectorWindowPlanner,
    private val validate: (String) -> Boolean
) {
    data class Record(val payload: String, val fetchedAtMs: Long)

    /** Importing from disk must revalidate every payload; stale records are kept only as metadata
     * until the planner rejects them by TTL. Calling code must supply monotonic timestamps.
     */
    private val records = mutableMapOf<SectorId, Record>()

    fun restore(source: Map<SectorId, Record>) {
        records.clear()
        source.forEach { (id, record) ->
            if (safeValidate(record.payload)) records[id] = record
        }
    }

    /** Return false and preserve any existing valid entry on an invalid response. */
    fun accept(id: SectorId, payload: String, fetchedAtMs: Long): Boolean {
        if (fetchedAtMs < 0 || !safeValidate(payload)) return false
        val previous = records[id]
        // Prevent a delayed older network result from overwriting newer information.
        if (previous != null && previous.fetchedAtMs > fetchedAtMs) return false
        records[id] = Record(payload, fetchedAtMs)
        return true
    }

    fun plan(center: SectorId, nowMs: Long): SectorWindowPlanner.Plan =
        planner.plan(center, nowMs, records.mapValues { (_, record) ->
            SectorWindowPlanner.CacheEntry(record.fetchedAtMs, true)
        })

    /** Returns all 9 visible records only when EVERY one is valid and fresh. */
    fun visibleSnapshot(center: SectorId, nowMs: Long): List<Pair<SectorId, Record>>? {
        val plan = plan(center, nowMs)
        if (!plan.visibleReady) return null
        return plan.visible.map { id -> id to records.getValue(id) }
    }

    /** Ordered fetching: missing visible first, then missing 5x5 perimeter. */
    fun outstanding(center: SectorId, nowMs: Long): List<SectorId> = plan(center, nowMs).fetch

    /** Make a defensive copy for the external persistent storage adapter. */
    fun export(): Map<SectorId, Record> = records.toMap()

    private fun safeValidate(payload: String): Boolean =
        payload.isNotBlank() && try { validate(payload) } catch (_: Exception) { false }
}
