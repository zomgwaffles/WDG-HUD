package com.example.wdghud

import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.WdgWarsGrid

/** Geometry is immutable; AP data and sync timestamps are NOT part of this record.
 *  Derive actual boundaries from the existing project's sectorAt() function,
 *  instead of assuming 0.02/0.03 degrees or confusing an API query point with
 *  the physical center. This preserves the existing WdgWarsGrid convention.
 */
internal data class SectorAnchor(
    val id: SectorId,
    val centerLat: Double,
    val centerLon: Double,
    val south: Double,
    val north: Double,
    val west: Double,
    val east: Double
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat >= south && lat < north && lon >= west && lon < east
}

internal object SectorAnchorMath {
    /** Binary search for transition from this sector to its adjacent neighbor.
     *  The project grid classifier is the single source of truth; no network I/O.
     */
    private fun edge(id: SectorId, start: Double, neighbor: Double, classify: (Double) -> SectorId): Double {
        require(classify(start) == id) { "Sector query point is not inside its own sector: $id" }
        require(classify(neighbor) != id) { "Neighbor query point did not cross sector boundary: $id" }
        var inside = start
        var outside = neighbor
        repeat(45) {
            val mid = (inside + outside) / 2.0
            if (classify(mid) == id) inside = mid else outside = mid
        }
        return (inside + outside) / 2.0
    }

    fun derive(id: SectorId): SectorAnchor {
        val (lat, lon) = WdgWarsGrid.queryPoint(id)
        require(lat.isFinite() && lon.isFinite() && WdgWarsGrid.sectorAt(lat, lon) == id) {
            "Unverified WDGWars sector query point for $id"
        }
        val (nextN, _) = WdgWarsGrid.queryPoint(SectorId(id.north + 1, id.east))
        val (prevN, _) = WdgWarsGrid.queryPoint(SectorId(id.north - 1, id.east))
        val (_, nextE) = WdgWarsGrid.queryPoint(SectorId(id.north, id.east + 1))
        val (_, prevE) = WdgWarsGrid.queryPoint(SectorId(id.north, id.east - 1))
        val n = edge(id, lat, nextN) { y -> WdgWarsGrid.sectorAt(y, lon) }
        val s = edge(id, lat, prevN) { y -> WdgWarsGrid.sectorAt(y, lon) }
        val e = edge(id, lon, nextE) { x -> WdgWarsGrid.sectorAt(lat, x) }
        val w = edge(id, lon, prevE) { x -> WdgWarsGrid.sectorAt(lat, x) }
        require(s < lat && lat < n && w < lon && lon < e) { "Invalid sector bounds for $id" }
        return SectorAnchor(id, (s + n) / 2.0, (w + e) / 2.0, s, n, w, e)
    }
}
