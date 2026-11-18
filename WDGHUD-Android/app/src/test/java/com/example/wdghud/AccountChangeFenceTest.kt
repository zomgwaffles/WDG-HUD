package com.example.wdghud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountChangeFenceTest {
    @Test fun unchangedKeyKeepsSessionAndOriginalTicket() {
        val f = AccountChangeFence("alpha")
        val ticket = f.snapshot()
        assertFalse(f.edit(" alpha "))
        assertFalse(f.blocked)
        assertTrue(f.accepts(ticket))
    }

    @Test fun newKeyInvalidatesOldResultsUntilVerified() {
        val f = AccountChangeFence("alpha")
        val old = f.snapshot()
        assertTrue(f.edit("beta"))
        assertTrue(f.blocked)
        assertFalse(f.accepts(old))
        val next = f.snapshot()
        assertFalse(f.accepts(next))
        assertFalse(f.completeVerification(old, true))
        assertFalse(f.completeVerification(next, false))
        assertTrue(f.blocked)
        assertTrue(f.completeVerification(next, true))
        assertTrue(f.accepts(next))
        assertFalse(f.accepts(old))
    }

    @Test fun changingAwayAndBackRejectsStaleSameKeyResult() {
        val f = AccountChangeFence("alpha")
        val old = f.snapshot()
        assertTrue(f.edit("beta"))
        assertTrue(f.edit("alpha"))
        assertFalse(f.isCurrent(old))
        assertFalse(f.completeVerification(old, true))
        assertTrue(f.blocked)
        assertTrue(f.completeVerification(f.snapshot(), true))
        assertFalse(f.accepts(old))
    }

    @Test fun emptyKeyCannotBeVerified() {
        val f = AccountChangeFence("alpha")
        assertTrue(f.edit(""))
        assertFalse(f.completeVerification(f.snapshot(), true))
        assertTrue(f.blocked)
    }

    @Test fun pendingFlagSurvivesSimulatedProcessRestart() {
        val f = AccountChangeFence("newKey", initiallyBlocked = true)
        assertTrue(f.blocked)
        assertFalse(f.accepts(f.snapshot()))
        assertTrue(f.completeVerification(f.snapshot(), true))
        assertFalse(f.blocked)
    }
}
