package com.example.wdghud.geometry

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/** Synthetic grid. These are MATH tests, not WDGWars acceptance vectors. */
class SectorGeometryTest {
    private val grid = SectorGeometry(GridDefinition(0.0, 0.0, 0.02, 0.03))

    @Test fun halfOpenBoundaryAssignment() {
        assertEquals(SectorId(0, 0), grid.sectorAt(0.01, 0.01))
        assertEquals(SectorId(1, 0), grid.sectorAt(0.02, 0.01))
        assertEquals(SectorId(0, 1), grid.sectorAt(0.01, 0.03))
        assertEquals(SectorId(-1, -1), grid.sectorAt(-0.001, -0.001))
    }

    @Test fun contiguousCornersAndCenter() {
    val a = grid.bounds(SectorId(10, -10))
    val east = grid.bounds(SectorId(10, -9))
    val north = grid.bounds(SectorId(11, -10))

    assertEquals(a.east, east.west, 0.000000001)
    assertEquals(a.north, north.south, 0.000000001)

    val (lat, lon) = grid.center(SectorId(10, -10))
    assertTrue(a.contains(lat, lon))
    assertFalse(a.contains(a.north, lon))
	}

    @Test fun windowHasCorrectOrderAndDistinctKeys() {
        val center = SectorId(10, 20)
        val visible = grid.window(center, 1)
        val cache = grid.window(center, 2)
        assertEquals(9, visible.size)
        assertEquals(25, cache.size)
        assertEquals(SectorId(11, 19), visible.first())
        assertEquals(center, visible[4])
        assertEquals(25, cache.distinct().size)
        assertTrue(cache.containsAll(visible))
        assertEquals(20, cache.intersect(grid.window(SectorId(10, 21), 2).toSet()).size)
        assertEquals(16, cache.intersect(grid.window(SectorId(11, 21), 2).toSet()).size)
    }
}
