package com.example.wdghud.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live API observations recorded 2026-11-11; these are not invented grid samples. */
class WdgWarsGridTest {
    @Test fun exactBoundaryBelongsToNorthAndEast() {
        val sw = WdgWarsGrid.sectorAt(39.27995, -76.52005)
        val se = WdgWarsGrid.sectorAt(39.27995, -76.52000)
        val nw = WdgWarsGrid.sectorAt(39.28000, -76.52005)
        val ne = WdgWarsGrid.sectorAt(39.28000, -76.52000)
        assertEquals(SectorId(1963, -3827), sw)
        assertEquals(SectorId(1963, -3826), se)
        assertEquals(SectorId(1964, -3827), nw)
        assertEquals(SectorId(1964, -3826), ne)
    }

    @Test fun boundaryPerturbationsStayOnCorrectSides() {
        assertEquals(SectorId(1963, -3827), WdgWarsGrid.sectorAt(39.27995, -76.52005))
        assertEquals(SectorId(1964, -3826), WdgWarsGrid.sectorAt(39.28005, -76.51995))
        assertEquals(WdgWarsGrid.sectorAt(39.28, -76.52), WdgWarsGrid.sectorAt(39.280000000000001, -76.52000000000001))
    }

    @Test fun negativeLatitudesHaveCorrectFloorAndBorders() {
        assertEquals(SectorId(-2253, 8450), WdgWarsGrid.sectorAt(-45.06, 169.0))
        assertEquals(SectorId(-2254, 8450), WdgWarsGrid.sectorAt(-45.06005, 169.0))
        assertEquals(SectorId(-2253, 8449), WdgWarsGrid.sectorAt(-45.06, 168.99995))
    }

    @Test fun queryPointsAreInterior() {
        val id = WdgWarsGrid.sectorAt(39.28, -76.52)
        val (lat, lon) = WdgWarsGrid.queryPoint(id)
        assertEquals(39.29, lat, 1e-9)
        assertEquals(-76.51, lon, 1e-9)
        val b = WdgWarsGrid.bounds(id)
        assertTrue(b.contains(lat, lon))
        assertFalse(b.contains(b.north, lon))
    }

    @Test fun windowsRemainNorthUpAndOverlap() {
        val id = WdgWarsGrid.sectorAt(39.28, -76.52)
        val visible = WdgWarsGrid.window(id, 1)
        val cached = WdgWarsGrid.window(id, 2)
        assertEquals(9, visible.size)
        assertEquals(25, cached.size)
        assertEquals(id, visible[4])
        assertEquals(SectorId(id.north + 1, id.east - 1), visible[0])
        assertEquals(20, cached.intersect(WdgWarsGrid.window(SectorId(id.north, id.east + 1), 2).toSet()).size)
        assertEquals(16, cached.intersect(WdgWarsGrid.window(SectorId(id.north + 1, id.east + 1), 2).toSet()).size)
    }
}
