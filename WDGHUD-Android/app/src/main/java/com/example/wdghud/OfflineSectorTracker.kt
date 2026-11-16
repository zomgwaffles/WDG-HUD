package com.example.wdghud

import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.WdgWarsGrid

/**
 * G5A39: GPS-to-sector navigation is math only (zero HTTP/WDGWars calls).
 *
 * Keep the current canonical sector key and its surrounding eight keys in
 * memory. Every fresh GPS fix is classified by the EXISTING WdgWarsGrid
 * canonical boundary/index convention. Do not use distance in meters to an
 * arbitrary API query point: that can classify neighboring cells incorrectly.
 *
 * WdgWarsGrid.queryPoint(id) is a safe API interior sample, not necessarily
 * the geometrical center. This tracker deliberately doesn't relabel it a
 * verified center coordinate. Adjacent keys share canonical boundaries.
 */
internal class OfflineSectorTracker {
    private var last: SectorId? = null
    var surroundingKeys: List<SectorId> = emptyList()
        private set

    fun evaluate(latitude: Double, longitude: Double): SectorId? {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val current = WdgWarsGrid.sectorAt(latitude, longitude)
        if (current != last) {
            last = current
            surroundingKeys = WdgWarsGrid.window(current, 1)
        }
        return current
    }

    fun reset() {
        last = null
        surroundingKeys = emptyList()
    }
}
