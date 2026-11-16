package com.example.wdghud.geometry

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * E5.17 G1.3: boundary-tested WDGWars 0.02° x 0.02° global grid.
 * Verified at 39.28, -76.52 using authenticated cell-detail queries.
 * Does not issue HTTP requests or change existing Android scan logic.
 *
 * Sector index is the south/west corner divided by 0.02; sector ownership is
 * half-open: [south, north) x [west, east). Each sector query uses its center.
 */
object WdgWarsGrid {
    const val STEP_DEGREES = 0.02
    // Tolerance corrects IEEE-754 division noise on exactly aligned boundaries.
    // 1e-9 degrees is far smaller than the uncertainty of ordinary GPS fixes.
    private const val SNAP_DEGREES = 1e-9

    private fun sectorIndex(degrees: Double): Long {
        require(degrees.isFinite()) { "Non-finite GPS coordinate" }
        val raw = degrees / STEP_DEGREES
        val nearest = round(raw)
        val normalized = if (abs(degrees - nearest * STEP_DEGREES) <= SNAP_DEGREES) nearest else raw
        return floor(normalized).toLong()
    }

    fun sectorAt(latitude: Double, longitude: Double): SectorId {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "GPS coordinates out of range"
        }
        return SectorId(sectorIndex(latitude), sectorIndex(longitude))
    }

    fun bounds(id: SectorId): SectorBounds {
        val south = id.north * STEP_DEGREES
        val west = id.east * STEP_DEGREES
        return SectorBounds(south, west, south + STEP_DEGREES, west + STEP_DEGREES)
    }

    /** Always query the unambiguous interior, never an edge. */
    fun queryPoint(id: SectorId): Pair<Double, Double> {
        val b = bounds(id)
        return (b.south + STEP_DEGREES / 2.0) to (b.west + STEP_DEGREES / 2.0)
    }

    /** Fixed north-up order, reading left-to-right; 9 (radius=1) or 25 (radius=2). */
    fun window(center: SectorId, radius: Int): List<SectorId> {
        require(radius in 0..2)
        return (radius downTo -radius).flatMap { row ->
            (-radius..radius).map { column -> SectorId(center.north + row, center.east + column) }
        }
    }
}
