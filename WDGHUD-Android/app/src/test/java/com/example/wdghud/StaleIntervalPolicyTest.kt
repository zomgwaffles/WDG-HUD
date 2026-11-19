package com.example.wdghud

import org.junit.Assert.*
import org.junit.Test

class StaleIntervalPolicyTest {
    @Test fun acceptsOnlyWatchPresets() {
        assertEquals(30, StaleIntervalPolicy.parseWatchMessage("CFG:STALE,30"))
        assertEquals(60, StaleIntervalPolicy.parseWatchMessage("CFG:STALE,60"))
        assertEquals(240, StaleIntervalPolicy.parseWatchMessage("CFG:STALE,240"))
        assertNull(StaleIntervalPolicy.parseWatchMessage("CFG:STALE,45"))
        assertNull(StaleIntervalPolicy.parseWatchMessage("CFG:STALE,0"))
        assertNull(StaleIntervalPolicy.parseWatchMessage("CFG:STALE,030"))
        assertNull(StaleIntervalPolicy.parseWatchMessage("CFG:STALE,300"))
    }

    @Test fun detailTtlPresetsDoNotModifyHourlyMetadataPolicy() {
        assertEquals(1_800_000L, StaleIntervalPolicy.ttlMs(30))
        assertEquals(3_600_000L, StaleIntervalPolicy.ttlMs(60))
        assertEquals(14_400_000L, StaleIntervalPolicy.ttlMs(240))
        assertEquals(60, StaleIntervalPolicy.clamp(45))
        assertEquals(3_600_000L, HourlyRefreshPolicy.TTL_MS)
    }
}
