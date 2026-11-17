package com.example.wdghud.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** G5A49.4c: production prefetch and GPS share the canonical WDGWars sector windows. */
class GridPlannerParityTest {
    private fun productionPlanner(): SectorWindowPlanner = SectorWindowPlanner()

    @Test fun plannedWindowsExactlyMatchCanonicalGpsWindows() {
        val planner = productionPlanner()
        val centers = listOf(
            SectorId(1964, -3826),
            SectorId(-2253, 8450),
            SectorId(-1, -1),
            SectorId(0, 0),
            SectorId(4495, -8995)
        )
        for (center in centers) {
            val plan = planner.plan(center, 1_000_000L, emptyMap())
            assertEquals(WdgWarsGrid.window(center, 1), plan.visible)
            assertEquals(WdgWarsGrid.window(center, 2), plan.envelope)
            assertEquals(9, plan.visible.size)
            assertEquals(25, plan.envelope.size)
            assertEquals(center, plan.visible[4])
            assertEquals(center, plan.envelope[12])
            assertEquals(plan.visible, plan.fetch.take(9))
            assertEquals(25, plan.fetch.distinct().size)
            assertFalse(plan.visibleReady)
        }
    }

    @Test fun canonicalPlannerRetainsOneSectorLookaheadWhenAllDataIsFresh() {
        val center = SectorId(1964, -3826)
        val planner = productionPlanner()
        val now = 2_000_000L
        val cached = WdgWarsGrid.window(center, 2).associateWith {
            SectorWindowPlanner.CacheEntry(now, true)
        }
        val plan = planner.plan(center, now, cached)
        assertTrue(plan.visibleReady)
        assertTrue(plan.fetch.isEmpty())
        assertEquals(25, plan.reusable.size)
    }

    @Test fun canonicalPlannerRespondsToConfiguredFreshnessWithoutDeletingRecords() {
        val center = SectorId(-2253, 8450)
        val now = 3_000_000L
        var ttl = 30L * 60_000L
        val planner = SectorWindowPlanner(dynamicTtlMs = { ttl })
        val existing = WdgWarsGrid.window(center, 2).associateWith {
            SectorWindowPlanner.CacheEntry(now - 15L * 60_000L, true)
        }
        assertTrue(planner.plan(center, now, existing).visibleReady)
        ttl = 10L * 60_000L
        val expired = planner.plan(center, now, existing)
        assertFalse(expired.visibleReady)
        assertEquals(25, expired.fetch.size)
        // Stale data is still present in the caller-owned cache; plan() never evicts.
        assertEquals(25, existing.size)
    }

    @Test fun syntheticGridPlannerIsStillAvailableForIndependentMathTests() {
        val synthetic = SectorGeometry(GridDefinition(12.5, -34.2, 0.02, 0.03))
        val center = synthetic.sectorAt(12.55, -34.12)
        val plan = SectorWindowPlanner(synthetic).plan(center, 20_000L, emptyMap())
        assertEquals(synthetic.window(center, 1), plan.visible)
        assertEquals(synthetic.window(center, 2), plan.envelope)
        assertEquals(25, plan.fetch.size)
    }
}
