package com.example.wdghud.geometry

import kotlin.math.floor

/**
 * Geometry math for a rectangular latitude/longitude grid.
 * IMPORTANT: The grid origin, step and boundary ownership must be verified from
 * WDGWars before integrating this with live GPS or requests.
 *
 * Convention implemented for TESTING ONLY: origin is the southwest CORNER of
 * sector (0,0), sectors are half-open [south,north) × [west,east), and sector
 * coordinates increase north and east. Not a claim about WDGWars internals.
 */
data class GridDefinition(
    val originLatitude: Double,
    val originLongitude: Double,
    val latitudeStep: Double,
    val longitudeStep: Double
) {
    init {
        require(originLatitude.isFinite() && originLongitude.isFinite())
        require(latitudeStep.isFinite() && latitudeStep > 0)
        require(longitudeStep.isFinite() && longitudeStep > 0)
    }
}

data class SectorId(val north: Long, val east: Long)
data class SectorBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat >= south && lat < north && lon >= west && lon < east
}

class SectorGeometry(private val grid: GridDefinition) {
    fun sectorAt(lat: Double, lon: Double): SectorId {
        require(lat.isFinite() && lon.isFinite())
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        return SectorId(
            floor((lat - grid.originLatitude) / grid.latitudeStep).toLong(),
            floor((lon - grid.originLongitude) / grid.longitudeStep).toLong()
        )
    }

    fun bounds(id: SectorId): SectorBounds {
        val south = grid.originLatitude + id.north * grid.latitudeStep
        val west = grid.originLongitude + id.east * grid.longitudeStep
        return SectorBounds(south, west, south + grid.latitudeStep, west + grid.longitudeStep)
    }

    fun center(id: SectorId): Pair<Double, Double> = bounds(id).let {
        ((it.south + it.north) / 2) to ((it.west + it.east) / 2)
    }

    /** Row major, northernmost first (correct for fixed north-up watch presentation). */
    fun window(center: SectorId, radius: Int): List<SectorId> {
        require(radius in 0..100)
        return (radius downTo -radius).flatMap { northOffset ->
            (-radius..radius).map { eastOffset ->
                SectorId(center.north + northOffset, center.east + eastOffset)
            }
        }
    }
}
