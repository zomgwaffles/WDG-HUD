package com.example.wdghud

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchCapabilitiesTest {
    @Test fun negotiationNeverMistakesBaseForAtomicViewport() {
        assertEquals("BASE", WatchCapabilities.classify("CAPS,2,BASE"))
        assertEquals("V2", WatchCapabilities.classify("CAPS,2,VIEW,ATOMIC"))
        listOf("", "PONG", "GPS:OK", "ERR:UNKNOWN_COMMAND", "CAPS,1", "CAPS,2,VIEW", "CAPS,3,VIEW,ATOMIC").forEach {
            assertEquals("LEGACY", WatchCapabilities.classify(it))
        }
    }
}
