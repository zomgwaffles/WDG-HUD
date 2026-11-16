package com.example.wdghud

import com.example.wdghud.geometry.SectorId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedViewportPacketsTest {
    private val center = SectorId(1964, -3827)

    @Test fun northUpGridUsesNineGeographicTiles() {
        val packets = CachedViewportPackets.build(center, 1L, emptyMap())
        assertEquals(11, packets.size)
        assertEquals("V,BEGIN,1,1964,-3827", packets.first())
        assertEquals("V,TILE,1,0,1965,-3828,U,0", packets[1])
        assertEquals("V,TILE,1,4,1964,-3827,U,0", packets[5])
        assertEquals("V,TILE,1,8,1963,-3826,U,0", packets[9])
        assertEquals("V,END,1", packets.last())
    }

    @Test fun presentAndUnknownAreDifferent() {
        val packets = CachedViewportPackets.build(center, 12L, mapOf(center to 13456))
        assertEquals("V,TILE,12,4,1964,-3827,P,13456", packets[5])
        assertEquals("V,TILE,12,0,1965,-3828,U,0", packets[1])
    }

    @Test fun packetsFitBleMtu64() {
        val packets = CachedViewportPackets.build(center, 100L, mapOf(center to Int.MAX_VALUE))
        assertTrue(packets.all { it.toByteArray(Charsets.UTF_8).size <= 61 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTotalRejected() {
        CachedViewportPackets.build(center, 1L, mapOf(center to -1))
    }
}
