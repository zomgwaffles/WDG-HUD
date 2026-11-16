package com.example.wdghud.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorScanCoverageTest {
    private val center = SectorId(1964, -3827)

    @Test fun coldStartHas25MissingIncludingOuterCorners() {
        val required = SectorScanCoverage.requiredIds(center)
        assertEquals(25, required.size)
        assertEquals(25, required.distinct().size)
        assertEquals(25, SectorScanCoverage.missingIds(center) { false }.size)
        for (dn in listOf(-2L, 2L)) for (de in listOf(-2L, 2L)) {
            assertTrue(SectorId(center.north + dn, center.east + de) in required)
        }
        assertTrue(WdgWarsGrid.window(center, 1).all { it in required })
    }

    @Test fun warmCoverageEmitsNoSectorScan() {
        val ready = SectorScanCoverage.requiredIds(center).toSet()
        assertTrue(SectorScanCoverage.missingIds(center) { it in ready }.isEmpty())
    }

    @Test fun singleMissingTileTriggersScan() {
        val missing = SectorId(center.north + 1, center.east - 2)
        val ready = SectorScanCoverage.requiredIds(center).toSet() - missing
        assertEquals(listOf(missing), SectorScanCoverage.missingIds(center) { it in ready })
    }

    @Test fun missingOuterCornerTriggersScan() {
        val corner = SectorId(center.north + 2, center.east + 2)
        val ready = SectorScanCoverage.requiredIds(center).toSet() - corner
        assertEquals(listOf(corner), SectorScanCoverage.missingIds(center) { it in ready })
    }
}
