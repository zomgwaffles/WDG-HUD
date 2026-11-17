package com.example.wdghud.geometry

/**
 * Planning only: no GPS, HTTP, storage, BLE or network side effects.
 *
 * Production defaults to the canonical WdgWarsGrid windows also used for
 * GPS sector identification, API query points and watch recentering.
 *
 * An explicit SectorGeometry remains supported for independent synthetic-grid
 * unit tests; no source or persisted sector-ID format is changed.
 */
class SectorWindowPlanner(
    private val geometry: SectorGeometry? = null,
    private val ttlMs: Long = 30 * 60 * 1000L,
    // Optional dynamic AP freshness age, controlled by the watch setting.
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

    private fun window(center: SectorId, radius: Int): List<SectorId> =
        geometry?.window(center, radius) ?: WdgWarsGrid.window(center, radius)

    fun plan(
        center: SectorId,
        nowMs: Long,
        cached: Map<SectorId, CacheEntry>
    ): Plan {
        require(nowMs >= 0)
        val effectiveTtlMs = dynamicTtlMs?.invoke()?.takeIf { it > 0L } ?: ttlMs
        val visible = window(center, 1)
        // Full 5x5 required for diagonally crossing one sector.
        val envelope = window(center, 2)
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
