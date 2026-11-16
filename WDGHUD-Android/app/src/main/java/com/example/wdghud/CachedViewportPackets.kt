package com.example.wdghud

import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.WdgWarsGrid

/** G4D: nine geographic tiles in north-up row-major order; null means UNKNOWN. */
internal object CachedViewportPackets {
    fun build(center: SectorId, sequence: Long, totals: Map<SectorId, Int?>): List<String> {
        require(sequence in 1..4294967295L)
        val tiles = WdgWarsGrid.window(center, 1)
        require(tiles.size == 9)
        return buildList {
            add("V,BEGIN,$sequence,${center.north},${center.east}")
            tiles.forEachIndexed { index, id ->
                val total = totals[id]
                require(total == null || total >= 0)
                add("V,TILE,$sequence,$index,${id.north},${id.east},${if (total == null) 'U' else 'P'},${total ?: 0}")
            }
            add("V,END,$sequence")
        }
    }
}
