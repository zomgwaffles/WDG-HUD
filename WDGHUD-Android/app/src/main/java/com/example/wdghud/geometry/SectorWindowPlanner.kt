package com.example.wdghud.geometry

/** E5.17 G2: planning only. Never performs GPS, HTTP, storage, or BLE operations. */
class SectorWindowPlanner(
    private val geometry: SectorGeometry,
    private val ttlMs: Long = 30 * 60 * 1000L,
    // Optional dynamic AP freshness age. Used by the live foreground prefetch
    // planner; geometry math and the permanent sector anchors are unaffected.
    private val dynamicTtlMs: (() -> Long)? = null
) {
    init { require(ttlMs > 0) }

    /** Metadata supplied by a cache adapter after validating a complete API response. */
    data class CacheEntry(val fetchedAtMs: Long, val valid: Boolean)

    data class Plan(
        val center: SectorId,
        /** Exactly nine, northern row first; watch order. */
        val visible: List<SectorId>,
        /** Exactly 25, northern row first; all four diagonal corners included. */
        val envelope: List<SectorId>,
        /** Existing fresh, complete records that may be reused. */
        val reusable: List<SectorId>,
        /** Unavailable/expired/invalid entries; visible missing entries FIRST. */
        val fetch: List<SectorId>,
        /** Visible data is complete only if each record is fresh and valid. */
        val visibleReady: Boolean
    ) {
        val missingVisible: List<SectorId> get() = visible.filter { it !in reusable }
    }

    fun plan(
        center: SectorId,
        nowMs: Long,
        cached: Map<SectorId, CacheEntry>
    ): Plan {
        require(nowMs >= 0)
        val effectiveTtlMs = dynamicTtlMs?.invoke()?.takeIf { it > 0L } ?: ttlMs
        val visible = geometry.window(center, 1)
        // A full 5x5 is required for diagonally crossing one sector.
        val envelope = geometry.window(center, 2)
        val reusable = envelope.filter { id ->
            val entry = cached[id]
            entry != null && entry.valid && entry.fetchedAtMs in 0..nowMs &&
                nowMs - entry.fetchedAtMs < effectiveTtlMs
        }
        val readySet = reusable.toHashSet()
        val missingVisible = visible.filterNot { it in readySet }
        val missingOuter = envelope.filterNot { it in readySet || it in visible }
        return Plan(
            center = center,
            visible = visible,
            envelope = envelope,
            reusable = reusable,
            fetch = missingVisible + missingOuter,
            visibleReady = missingVisible.isEmpty()
        )
    }
}
