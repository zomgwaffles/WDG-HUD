package com.example.wdghud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApTierBandsTest {
    @Test fun eachTenLevelBoundary() {
        for (tier in 1..10) {
            assertEquals(tier, ApTierBands.fromLevel((tier - 1) * 10 + 1))
            assertEquals(tier, ApTierBands.fromLevel(tier * 10))
        }
    }

    @Test fun allLevelsAreRetainedForFutureVersions() {
        assertEquals(10, ApTierBands.fromLevel(101))
        assertEquals(10, ApTierBands.fromLevel(255))
    }

    @Test fun invalidLevelsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ApTierBands.fromLevel(0) }
        assertThrows(IllegalArgumentException::class.java) { ApTierBands.fromLevel(256) }
    }
}
