package com.example.wdghud

import org.junit.Assert.*
import org.junit.Test

class GpsRecoveryPolicyTest {
    @Test fun startupWithoutProviderRetriesInsteadOfAssumingGpsDisabled() {
        assertEquals(GpsRecoveryPolicy.Phase.PROVIDERS_OFF,
            GpsRecoveryPolicy.phase(true, true, 0, null))
        assertEquals(GpsRecoveryPolicy.Phase.WAITING,
            GpsRecoveryPolicy.phase(true, true, 1, null))
        assertEquals(GpsRecoveryPolicy.Phase.READY,
            GpsRecoveryPolicy.phase(true, true, 1, 1000L))
    }

    @Test fun oldFixTransitionsToStaleAndCanRecover() {
        assertEquals(GpsRecoveryPolicy.Phase.STALE,
            GpsRecoveryPolicy.phase(true, true, 2, 31_000L))
        assertEquals(GpsRecoveryPolicy.Phase.READY,
            GpsRecoveryPolicy.phase(true, true, 2, 0L))
    }

    @Test fun missingPermissionDoesNotMasqueradeAsGpsBeingOff() {
        assertEquals(GpsRecoveryPolicy.Phase.PERMISSION_REQUIRED,
            GpsRecoveryPolicy.phase(true, false, 2, null))
        assertEquals(GpsRecoveryPolicy.Phase.STOPPED,
            GpsRecoveryPolicy.phase(false, true, 2, null))
    }

    @Test fun networkFallbackRequiresUsableAccuracyAndFreshness() {
        assertTrue(GpsRecoveryPolicy.acceptFix("network", 1000L, true, 40f))
        assertFalse(GpsRecoveryPolicy.acceptFix("network", 1000L, true, 250f))
        assertFalse(GpsRecoveryPolicy.acceptFix("network", 1000L, false, 0f))
        assertFalse(GpsRecoveryPolicy.acceptFix("network", 16000L, true, 20f))
        assertTrue(GpsRecoveryPolicy.acceptFix("gps", 2000L, true, 10f))
    }
}
