package com.example.wdghud.geometry

import org.junit.Assert.*
import org.junit.Test

class SectorApiPreloadCoordinatorTest {
    private val geometry = SectorGeometry(GridDefinition(0.0, 0.0, 0.02, 0.03))
    private val center = SectorId(10, -10)
    private val now = 1_000_000L
    private fun cache() = SectorPreloadCache(SectorWindowPlanner(geometry)) { it.startsWith("VALID:") }
    private fun coordinator(c: SectorPreloadCache) = SectorApiPreloadCoordinator(c) { geometry.center(it) }

    @Test fun coldStartQueues25VisibleFirst() {
        val c = coordinator(cache())
        val state = c.begin(center, now)
        assertEquals(25, state.required)
        assertEquals(25, state.missing)
        assertEquals(geometry.window(center, 1).first(), c.next(now)!!.id)
    }

    @Test fun oneAtATimeAndSuccessfulCompletion() {
        val c = coordinator(cache())
        c.begin(center, now)
        val r = c.next(now)!!
        assertNull(c.next(now))
        assertTrue(c.complete(r, "VALID:response", now))
        assertEquals(24, c.status(now).missing)
        assertNotNull(c.next(now))
    }

    @Test fun invalidPayloadBlockedWithoutEndlessRetry() {
        val c = coordinator(cache())
        c.begin(center, now)
        val first = c.next(now)!!
        assertFalse(c.complete(first, "invalid", now))
        assertEquals(1, c.status(now).failuresSinceStart)
        repeat(24) {
            val r = c.next(now)!!
            assertTrue(c.complete(r, "VALID:data", now))
        }
        assertNull(c.next(now))
        assertEquals(1, c.status(now).missing)
    }

    @Test fun staleResponseAfterReplanIgnored() {
        val c = coordinator(cache())
        c.begin(center, now)
        val old = c.next(now)!!
        c.begin(SectorId(10, -9), now)
        assertFalse(c.complete(old, "VALID:late", now))
        assertEquals(25, c.status(now).missing)
    }

    @Test fun warmFullCacheCreatesNoRequestsAndCompleteVisible() {
        val backing = cache()
        SectorWindowPlanner(geometry).plan(center, now, emptyMap()).envelope.forEach { backing.accept(it, "VALID:cached", now) }
        val c = coordinator(backing)
        assertTrue(c.begin(center, now).visibleReady)
        assertEquals(0, c.status(now).missing)
        assertNull(c.next(now))
        assertEquals(9, c.visibleSnapshot(now)!!.size)
    }

    @Test fun eastMovementRequestsOnlyFive() {
        val backing = cache()
        SectorWindowPlanner(geometry).plan(center, now, emptyMap()).envelope.forEach { backing.accept(it, "VALID:cached", now) }
        val c = coordinator(backing)
        val shift = SectorId(10, -9)
        assertEquals(5, c.begin(shift, now).missing)
        assertTrue(c.status(now).visibleReady)
        assertEquals(9, c.visibleSnapshot(now)!!.size)
    }

    @Test fun missingCoordinateSkipsRequestSafely() {
        val backing = cache()
        val c = SectorApiPreloadCoordinator(backing) { null }
        c.begin(center, now)
        assertNull(c.next(now))
        assertEquals(25, c.status(now).failuresSinceStart)
        assertEquals(25, c.status(now).missing)
    }

    @Test fun failedRequestRetriesOnExplicitRestart() {
        val c = coordinator(cache())
        c.begin(center, now)
        val first = c.next(now)!!
        assertTrue(c.fail(first))
        assertFalse(c.fail(first))
        c.begin(center, now)
        assertEquals(first.id, c.next(now)!!.id)
    }
}
