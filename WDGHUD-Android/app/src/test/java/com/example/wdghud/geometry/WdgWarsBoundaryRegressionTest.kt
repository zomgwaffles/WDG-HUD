package com.example.wdghud.geometry

import com.example.wdghud.SectorAnchorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * G5A49.2: regression vectors for the current WDGWars-compatible 0.02-degree grid.
 * The independent floor-based oracle is modeled on the public Warroom grid.py
 * implementation; it does not replace direct WDGWars server confirmation.
 *
 * No GPS, HTTP, watch transport, production cache or persistence is changed.
 */
class WdgWarsBoundaryRegressionTest {
    private val step = WdgWarsGrid.STEP_DEGREES

    // Warroom's public implementation uses floor(coordinate / gridStep + 1e-9).
    // Compare ordinary interior points, not the sub-millimeter epsilon band:
    // the two projects intentionally use slightly different float tolerances.
    private fun independentIndex(degrees: Double) = floor(degrees / step + 1e-9).toLong()

    @Test fun cellCornersAreLatitudeLongitudeIntersections() {
        // Every corner is an intersection of constant-latitude and
        // constant-longitude lines. No radial or distance-based sectors.
        for (id in listOf(
            SectorId(1964, -3826), SectorId(-2253, 8450),
            SectorId(-1, -1), SectorId(0, 0), SectorId(4497, 8997)
        )) {
            val b = WdgWarsGrid.bounds(id)
            assertEquals(id.north * step, b.south, 1e-10)
            assertEquals(id.east * step, b.west, 1e-10)
            assertEquals((id.north + 1) * step, b.north, 1e-10)
            assertEquals((id.east + 1) * step, b.east, 1e-10)
            assertEquals(step, b.north - b.south, 1e-10)
            assertEquals(step, b.east - b.west, 1e-10)

            // A north/south crossing changes only latitude index;
            // an east/west crossing changes only longitude index.
            val interiorLat = (b.south + b.north) / 2.0
            val interiorLon = (b.west + b.east) / 2.0
            assertEquals(id, WdgWarsGrid.sectorAt(interiorLat, interiorLon))
            assertEquals(SectorId(id.north + 1, id.east),
                WdgWarsGrid.sectorAt(b.north + 0.00001, interiorLon))
            assertEquals(SectorId(id.north, id.east + 1),
                WdgWarsGrid.sectorAt(interiorLat, b.east + 0.00001))
        }
    }

    @Test fun marylandBoundaryHasFourDistinctQuadrants() {
        val lat = 39.28
        val lon = -76.52
        val delta = 0.00001
        assertEquals(SectorId(1963, -3827), WdgWarsGrid.sectorAt(lat - delta, lon - delta))
        assertEquals(SectorId(1963, -3826), WdgWarsGrid.sectorAt(lat - delta, lon + delta))
        assertEquals(SectorId(1964, -3827), WdgWarsGrid.sectorAt(lat + delta, lon - delta))
        assertEquals(SectorId(1964, -3826), WdgWarsGrid.sectorAt(lat + delta, lon + delta))
        assertEquals(SectorId(1964, -3826), WdgWarsGrid.sectorAt(lat, lon))
    }

    @Test fun bothNegativeCoordinatesUseFloorRatherThanTruncation() {
        assertEquals(SectorId(-1, -1), WdgWarsGrid.sectorAt(-0.00001, -0.00001))
        assertEquals(SectorId(-2, -2), WdgWarsGrid.sectorAt(-0.02001, -0.02001))
        assertEquals(SectorId(-2253, -3523), WdgWarsGrid.sectorAt(-45.05, -70.45))
        assertEquals(SectorId(0, 0), WdgWarsGrid.sectorAt(0.0, 0.0))
    }

    @Test fun exactNegativeBoundariesBelongToNorthAndEast() {
        assertEquals(SectorId(-2253, 8450), WdgWarsGrid.sectorAt(-45.06, 169.0))
        assertEquals(SectorId(-2254, 8449), WdgWarsGrid.sectorAt(-45.06001, 168.99999))
        assertEquals(SectorId(-2253, 8450), WdgWarsGrid.sectorAt(-45.05999, 169.00001))
    }

    @Test fun subDegreePerturbationsDoNotCausePrematureSectorChanges() {
        val d = 0.00000001 // 1e-8 degrees, outside 1e-9-degree snapping band
        assertEquals(SectorId(1963, -3827), WdgWarsGrid.sectorAt(39.28 - d, -76.52 - d))
        assertEquals(SectorId(1964, -3826), WdgWarsGrid.sectorAt(39.28 + d, -76.52 + d))
    }

    @Test fun independentFloorOracleAgreesAcrossBothHemispheres() {
        val samples = listOf(
            39.291 to -76.509,
            -45.051 to 169.011,
            -33.867 to 151.209,
            51.507 to -0.127,
            0.011 to -0.011,
            -0.011 to 0.011,
            64.127 to -21.817,
            35.689 to 139.691,
            89.951 to 179.951,
            -89.951 to -179.951
        )
        for ((lat, lon) in samples) {
            val expected = SectorId(independentIndex(lat), independentIndex(lon))
            assertEquals("Independent index mismatch at $lat,$lon", expected, WdgWarsGrid.sectorAt(lat, lon))
        }
    }

    @Test fun queryPointsStayInsideTheirOwnSector() {
        val samples = listOf(
            SectorId(1964, -3826), SectorId(-2253, 8450),
            SectorId(-1, -1), SectorId(0, 0),
            SectorId(4497, 8997), SectorId(-4498, -8998)
        )
        for (id in samples) {
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            assertEquals("Query point must return its own sector", id, WdgWarsGrid.sectorAt(lat, lon))
            assertTrue(WdgWarsGrid.bounds(id).contains(lat, lon))
        }
    }

    @Test fun neighboringSectorBoundsHaveNoGaps() {
        for (id in listOf(SectorId(1964, -3826), SectorId(-2253, 8450), SectorId(-1, -1))) {
            val here = WdgWarsGrid.bounds(id)
            val north = WdgWarsGrid.bounds(SectorId(id.north + 1, id.east))
            val east = WdgWarsGrid.bounds(SectorId(id.north, id.east + 1))
            assertEquals(here.north, north.south, 1e-10)
            assertEquals(here.east, east.west, 1e-10)
            assertFalse(here.contains(here.north, (here.west + here.east) / 2.0))
            assertFalse(here.contains((here.south + here.north) / 2.0, here.east))
        }
    }

    @Test fun derivedAnchorsCoverTheSameOrdinaryPointsAsTheClassifier() {
        val ids = listOf(SectorId(1964, -3826), SectorId(-2253, 8450), SectorId(-1, -1), SectorId(0, 0))
        for (id in ids) {
            val anchor = SectorAnchorMath.derive(id)
            val bounds = WdgWarsGrid.bounds(id)
            // Anchor derivation follows the classifier's 1e-9-degree snapping zone.
            assertEquals(bounds.south, anchor.south, 2e-9)
            assertEquals(bounds.west, anchor.west, 2e-9)
            assertEquals(bounds.north, anchor.north, 2e-9)
            assertEquals(bounds.east, anchor.east, 2e-9)
            assertTrue(anchor.contains(anchor.centerLat, anchor.centerLon))
            val (lat, lon) = WdgWarsGrid.queryPoint(id)
            assertTrue(anchor.contains(lat, lon))
            assertEquals(id, WdgWarsGrid.sectorAt(anchor.centerLat, anchor.centerLon))
            assertFalse(anchor.contains(anchor.north + 0.00001, anchor.centerLon))
            assertFalse(anchor.contains(anchor.centerLat, anchor.east + 0.00001))
        }
    }

    @Test fun movingWindowRetainsOverlapWithoutReversingNorthUpRows() {
        val id = SectorId(1964, -3826)
        val visible = WdgWarsGrid.window(id, 1)
        val five = WdgWarsGrid.window(id, 2)
        assertEquals(9, visible.size)
        assertEquals(25, five.size)
        assertEquals(id, visible[4])
        assertEquals(SectorId(id.north + 1, id.east - 1), visible.first())
        assertEquals(25, five.distinct().size)
        assertEquals(20, five.intersect(WdgWarsGrid.window(SectorId(id.north, id.east + 1), 2).toSet()).size)
        assertEquals(16, five.intersect(WdgWarsGrid.window(SectorId(id.north + 1, id.east + 1), 2).toSet()).size)
    }
}
