package com.example.wdghud.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorWindowPlannerTest {
    private val geometry = SectorGeometry(GridDefinition(0.0, 0.0, 0.02, 0.03))
    private val planner = SectorWindowPlanner(geometry)
    private val origin = SectorId(10, -10)
    private val now = 3_600_000L
    private fun filled(center: SectorId) = planner.plan(center, now, emptyMap()).envelope.associateWith {
        SectorWindowPlanner.CacheEntry(now, true)
    }

    @Test fun coldStartFetches25VisibleFirst() {
        val p = planner.plan(origin, now, emptyMap())
        assertEquals(9, p.visible.size)
        assertEquals(25, p.envelope.size)
        assertEquals(25, p.fetch.size)
        assertEquals(p.visible, p.fetch.take(9))
        assertTrue(SectorId(origin.north + 2, origin.east + 2) in p.envelope)
        assertTrue(SectorId(origin.north + 2, origin.east - 2) in p.envelope)
        assertTrue(SectorId(origin.north - 2, origin.east + 2) in p.envelope)
        assertTrue(SectorId(origin.north - 2, origin.east - 2) in p.envelope)
        assertFalse(p.visibleReady)
    }

    @Test fun warmStartNoNetworkRequests() {
        val p = planner.plan(origin, now, filled(origin))
        assertEquals(25, p.reusable.size)
        assertTrue(p.fetch.isEmpty())
        assertTrue(p.visibleReady)
    }

    @Test fun movingEastReuses16AndNeeds5WithVisibleReady() {
        val p = planner.plan(SectorId(10, -9), now, filled(origin))
        assertEquals(20, p.reusable.size)
        assertEquals(5, p.fetch.size)
        assertTrue(p.visibleReady)
    }

    @Test fun diagonalMoveReuses16AndNeeds9WithVisibleReady() {
        val p = planner.plan(SectorId(11, -9), now, filled(origin))
        assertEquals(16, p.reusable.size)
        assertEquals(9, p.fetch.size)
        assertTrue(p.visibleReady) // a full 5x5 includes the diagonal sector.
        assertTrue(p.missingVisible.isEmpty())
    }

    @Test fun twoCellsAwayRequiresDataBeforeDisplay() {
        val p = planner.plan(SectorId(10, -8), now, filled(origin))
        assertFalse(p.visibleReady)
        assertEquals(3, p.missingVisible.size)
        assertEquals(p.missingVisible, p.fetch.take(3))
    }

    @Test fun ttlAndInvalidDataMustBeFetchedAgain() {
        val cached = filled(origin).toMutableMap()
        val expired = planner.plan(origin, now, filled(origin)).envelope.first()
        val invalid = origin
        cached[expired] = SectorWindowPlanner.CacheEntry(now - 30 * 60 * 1000L, true)
        cached[invalid] = SectorWindowPlanner.CacheEntry(now, false)
        val p = planner.plan(origin, now, cached)
        assertEquals(2, p.fetch.size)
        assertEquals(invalid, p.fetch[0]) // visible work first
        assertTrue(expired in p.fetch)
        assertFalse(p.visibleReady)
    }

    @Test fun twentyNineMinutesStillFresh() {
        val cached = filled(origin).toMutableMap()
        val older = planner.plan(origin, now, filled(origin)).envelope.first()
        cached[older] = SectorWindowPlanner.CacheEntry(now - (29 * 60 + 59) * 1000L, true)
        val plan = planner.plan(origin, now, cached)
        assertTrue(plan.fetch.isEmpty())
        assertTrue(plan.visibleReady)
    }

    @Test fun futureTimestampMustNeverCountAsFresh() {
        val cached = mapOf(origin to SectorWindowPlanner.CacheEntry(now + 1, true))
        val p = planner.plan(origin, now, cached)
        assertTrue(origin in p.fetch)
    }
}
