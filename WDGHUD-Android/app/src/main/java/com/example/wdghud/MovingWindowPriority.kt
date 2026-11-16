package com.example.wdghud

import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.WdgWarsGrid
import kotlin.math.abs

/** G5A40: pure, OFFLINE 5x5 fetch/transfer priority. No GPS/API calls here.
 * Current 3x3 missing cells always precede the outer 5x5 ring. If the
 * user moved, newly exposed cells in that direction precede trailing cells.
 */
internal object MovingWindowPriority {
    fun ordered(center: SectorId, previous: SectorId?, needsWork: (SectorId) -> Boolean): List<SectorId> {
        val dn = ((center.north.toLong() - (previous?.north ?: center.north).toLong()).coerceIn(-1, 1)).toInt()
        val de = ((center.east.toLong() - (previous?.east ?: center.east).toLong()).coerceIn(-1, 1)).toInt()
        return WdgWarsGrid.window(center, 2).filter(needsWork).sortedWith(
            compareBy<SectorId> {
                if (abs(it.north.toLong() - center.north.toLong()) <= 1L &&
                    abs(it.east.toLong() - center.east.toLong()) <= 1L) 0 else 1
            }.thenByDescending {
                (it.north.toLong() - center.north.toLong()) * dn +
                    (it.east.toLong() - center.east.toLong()) * de
            }.thenBy {
                abs(it.north.toLong() - center.north.toLong()) + abs(it.east.toLong() - center.east.toLong())
            }.thenByDescending { it.north }.thenBy { it.east }
        )
    }
}
