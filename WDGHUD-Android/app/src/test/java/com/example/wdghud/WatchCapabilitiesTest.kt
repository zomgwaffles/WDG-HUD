package com.example.wdghud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCapabilitiesTest {
    @Test fun negotiationNeverMistakesBaseForAtomicViewport() {
        assertEquals("BASE", WatchCapabilities.classify("CAPS,2,BASE"))
        assertEquals("V2", WatchCapabilities.classify("CAPS,2,VIEW,ATOMIC"))
        listOf("", "PONG", "GPS:OK", "ERR:UNKNOWN_COMMAND", "CAPS,1", "CAPS,2,VIEW", "CAPS,3,VIEW,ATOMIC").forEach {
            assertEquals("LEGACY", WatchCapabilities.classify(it))
        }
    }

    @Test fun currentAccountScopedWatchNegotiatesFullGeographicCache() {
        // Exact response produced by the G5A49 watch BLE transport.
        val response = "CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25,ACCOUNT1"
        assertEquals("V2", WatchCapabilities.classify(response))
        assertTrue(WatchCapabilities.supportsGangNames(response))
        assertTrue(WatchCapabilities.supportsFullSectorNames(response))
        assertTrue(WatchCapabilities.supportsGeoCache(response))
        assertTrue(AccountOwnerWire.supported(response))
    }

    @Test fun olderSupportedWatchCapabilitiesRemainRecognized() {
        assertEquals("V2", WatchCapabilities.classify(WatchCapabilities.GEO_CACHE_RESPONSE))
        assertTrue(WatchCapabilities.supportsGeoCache(WatchCapabilities.GEO_CACHE_RESPONSE))
        assertFalse(AccountOwnerWire.supported(WatchCapabilities.GEO_CACHE_RESPONSE))
        assertEquals("V2", WatchCapabilities.classify(WatchCapabilities.FULL_NAMES_RESPONSE))
        assertTrue(WatchCapabilities.supportsFullSectorNames(WatchCapabilities.FULL_NAMES_RESPONSE))
        assertFalse(WatchCapabilities.supportsGeoCache(WatchCapabilities.FULL_NAMES_RESPONSE))
        assertEquals("V2", WatchCapabilities.classify(WatchCapabilities.GANG_RESPONSE))
        assertTrue(WatchCapabilities.supportsGangNames(WatchCapabilities.GANG_RESPONSE))
        assertFalse(WatchCapabilities.supportsFullSectorNames(WatchCapabilities.GANG_RESPONSE))
    }

    @Test fun rejectsTruncatedOrUnexpectedCapabilityMessages() {
        val malformed = listOf(
            "CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25,ACCOUNT1,UNKNOWN",
            "CAPS,3,VIEW,ATOMIC,GANG,GANG96,ACCOUNT1",
            "CAPS,3,VIEW,ATOMIC,GANG,GEO25,ACCOUNT1",
            "CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25,ACCOUNT1 ",
            "CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25,ACCOUNT",
            "CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25,ACCOUNT1X"
        )
        malformed.forEach { response ->
            assertEquals("LEGACY", WatchCapabilities.classify(response))
            assertFalse(WatchCapabilities.supportsGangNames(response))
            assertFalse(WatchCapabilities.supportsFullSectorNames(response))
            assertFalse(WatchCapabilities.supportsGeoCache(response))
        }
    }
}
