package com.example.wdghud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryBackoffTest {
    @Test fun retriesAreBoundedAndNoImmediateLoop() {
        val retry = RecoveryBackoff(listOf(2_000L, 5_000L, 10_000L))
        assertTrue(retry.canAttempt(100L))
        retry.fail(100L)
        assertFalse(retry.canAttempt(2_099L))
        assertTrue(retry.canAttempt(2_100L))
        retry.fail(2_100L)
        assertFalse(retry.canAttempt(7_099L))
        assertTrue(retry.canAttempt(7_100L))
        retry.fail(7_100L)
        assertFalse(retry.canAttempt(17_099L))
        assertTrue(retry.canAttempt(17_100L))
        retry.fail(17_100L)
        assertEquals(10_000L, retry.remainingMs(17_100L))
    }

    @Test fun successClearsFailuresAndAllowsImmediateRetry() {
        val retry = RecoveryBackoff()
        retry.fail(500L)
        retry.fail(1000L)
        assertEquals(2, retry.failedAttempts)
        retry.succeed()
        assertEquals(0, retry.failedAttempts)
        assertTrue(retry.canAttempt(1000L))
        retry.fail(1000L)
        assertEquals(2500L, retry.remainingMs(1000L))
    }

    @Test fun bleAndSectorRetriesAreIndependent() {
        val ble = RecoveryBackoff()
        val sector = RecoveryBackoff(listOf(5000L,15000L))
        ble.fail(100L)
        assertTrue(sector.canAttempt(100L))
        sector.fail(100L)
        assertFalse(sector.canAttempt(3000L))
        ble.succeed()
        assertFalse(sector.canAttempt(3000L))
    }

    @Test fun timeDelayDoesNotOverflowLong() {
        val retry = RecoveryBackoff(listOf(1000L))
        retry.fail(Long.MAX_VALUE - 100L)
        assertFalse(retry.canAttempt(Long.MAX_VALUE - 1L))
        assertTrue(retry.canAttempt(Long.MAX_VALUE))
    }
}
