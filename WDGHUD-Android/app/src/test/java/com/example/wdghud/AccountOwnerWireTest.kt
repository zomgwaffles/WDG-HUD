package com.example.wdghud

import org.junit.Assert.*
import org.junit.Test

class AccountOwnerWireTest {
    @Test fun capabilityMustBeExplicit() {
        assertTrue(AccountOwnerWire.supported("CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25,ACCOUNT1"))
        assertFalse(AccountOwnerWire.supported("CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25"))
        assertFalse(AccountOwnerWire.supported("ERROR,ACCOUNT1"))
        assertFalse(AccountOwnerWire.supported("CAPS,3,NOTACCOUNT1"))
    }
    @Test fun ownerIsDeterministicAndExactly128Bits() {
        val a = AccountOwnerWire.ownerPacket("OperatorA")
        assertTrue(a.matches(Regex("OWNER,[0-9a-f]{32}")))
        assertEquals(a, AccountOwnerWire.ownerPacket("OperatorA"))
        assertNotEquals(a, AccountOwnerWire.ownerPacket("OperatorB"))
        assertEquals("OWNER:OK", AccountOwnerWire.expectedAck(a))
    }
    @Test fun unsafeUsernamesCannotBeClaimed() {
        for (bad in listOf("", "  ", "x,y", "a\nb", "ABCDEFGHIJKLMNOPQRSTU")) {
            try { AccountOwnerWire.ownerPacket(bad); fail("Accepted invalid identity") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun tokenDerivationDoesNotDependOnAnApiKey() {
        assertEquals(AccountOwnerWire.ownerPacket("OperatorA"),
            AccountOwnerWire.ownerPacket("OperatorA"))
    }
}
