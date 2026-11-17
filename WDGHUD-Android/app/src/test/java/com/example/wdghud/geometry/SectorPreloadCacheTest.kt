package com.example.wdghud.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorPreloadCacheTest {
    private val geometry = SectorGeometry(GridDefinition(0.0, 0.0, 0.02, 0.03))
    private val center = SectorId(10, -10)
    private val now = 1_000_000L
    private fun cache() = SectorPreloadCache(SectorWindowPlanner(geometry)) { it.startsWith("VALID:") }

    @Test fun firstStartupRequests25WithVisibleFirst() {
        val cache = cache()
        val plan = cache.plan(center, now)
        assertEquals(25, plan.fetch.size)
        assertEquals(plan.visible, plan.fetch.take(9))
        assertNull(cache.visibleSnapshot(center, now))
    }

    @Test fun preloadMakesVisibleSnapshotAtomic() {
        val cache = cache()
        val visible = geometry.window(center, 1)
        visible.dropLast(1).forEach { cache.accept(it, "VALID:data", now) }
        assertNull(cache.visibleSnapshot(center, now))
        assertTrue(cache.accept(visible.last(), "VALID:last", now))
        val snapshot = cache.visibleSnapshot(center, now)
        assertNotNull(snapshot)
        assertEquals(9, snapshot!!.size)
        assertEquals(visible, snapshot.map { it.first })
    }

    @Test fun fullPreloadMoveEastRequestsOnlyFiveMore() {
        val cache = cache()
        SectorWindowPlanner(geometry).plan(center, now, emptyMap()).envelope.forEach { cache.accept(it, "VALID:data", now) }
        val shifted = SectorId(center.north, center.east + 1)
        val plan = cache.plan(shifted, now)
        assertTrue(plan.visibleReady)
        assertEquals(5, plan.fetch.size)
        assertEquals(9, cache.visibleSnapshot(shifted, now)!!.size)
    }

    @Test fun invalidPayloadNeverDisplacesGoodRecord() {
        val cache = cache()
        assertTrue(cache.accept(center, "VALID:good", now))
        assertFalse(cache.accept(center, "bad", now + 100))
        assertFalse(cache.accept(center, "VALID:older", now - 1))
        assertEquals("VALID:good", cache.export().getValue(center).payload)
    }

    @Test fun restoredDataIsValidatedAgain() {
        val cache = cache()
        cache.restore(mapOf(center to SectorPreloadCache.Record("invalid", now)))
        assertTrue(center in cache.outstanding(center, now))
        cache.restore(mapOf(center to SectorPreloadCache.Record("VALID:restored", now)))
        assertFalse(center in cache.outstanding(center, now))
    }

    @Test fun fifteenMinuteOldRecordsRemainFreshUnderThirtyMinuteDefault() {
        val cache = cache()
        val fresh = now - 15L * 60 * 1000L
        geometry.window(center, 2).forEach { assertTrue(cache.accept(it, "VALID:fresh", fresh)) }
        assertTrue(cache.outstanding(center, now).isEmpty())
        assertEquals(9, cache.visibleSnapshot(center, now)!!.size)
    }

    @Test fun expiredRecordsAreNotUsedAsVisibleData() {
        val cache = cache()
        // Use a clock beyond the 30-minute default TTL so the timestamp is valid.
        val referenceNow = 2_000_000L
        val expired = referenceNow - 30L * 60 * 1000L
        geometry.window(center, 2).forEach { assertTrue(cache.accept(it, "VALID:old", expired)) }
        assertEquals(25, cache.outstanding(center, referenceNow).size)
        assertNull(cache.visibleSnapshot(center, referenceNow))
        // Expiration makes records ineligible for reuse; it must not erase history.
        assertEquals(25, cache.export().size)
    }

    @Test fun defensiveCopyCannotMutateCache() {
        val cache = cache()
        cache.accept(center, "VALID:data", now)
        val saved = cache.export().toMutableMap()
        saved.clear()
        assertTrue(center in cache.export())
    }
}
