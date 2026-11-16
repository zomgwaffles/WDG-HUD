package com.example.wdghud

import org.junit.Assert.assertEquals
import org.junit.Test

class GangWireNameTest {
    @Test fun officialNameIsKept() = assertEquals("Alpha Crew", GangWireName.encode(true, 42, "Alpha Crew"))
    @Test fun missingOwnerIsUnknown() = assertEquals("UNKNOWN", GangWireName.encode(false, 0, null))
    @Test fun neutralUnclaimedIsNotFabricated() = assertEquals("UNCLAIMED", GangWireName.encode(true, 0, "Anything"))
    @Test fun commasAreSafe() = assertEquals("A?B", GangWireName.encode(true, 7, "A,B"))
    @Test fun oldWatchGetsFiveFields() = assertEquals("CELL,4,100,3,#123456", GangWireName.forWatch("CELL,4,100,3,#123456,Alpha", false))
    @Test fun newWatchGetsName() = assertEquals("CELL,4,100,3,#123456,Alpha", GangWireName.forWatch("CELL,4,100,3,#123456,Alpha", true))
}
