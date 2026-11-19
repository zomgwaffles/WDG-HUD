package com.example.wdghud

import org.junit.Assert.*
import org.junit.Test

class HourlyRefreshPolicyTest {
    @Test fun freshForJustUnderAnHour() {
        assertTrue(HourlyRefreshPolicy.fresh(10_000L, 10_000L + 3_599_999L))
    }
    @Test fun staleAtExactlyOneHour() {
        assertFalse(HourlyRefreshPolicy.fresh(10_000L, 10_000L + 3_600_000L))
    }
    @Test fun missingOrFutureTimestampsAreNotFresh() {
        assertFalse(HourlyRefreshPolicy.fresh(0L, 12_000L))
        assertFalse(HourlyRefreshPolicy.fresh(15_000L, 14_000L))
    }
}
