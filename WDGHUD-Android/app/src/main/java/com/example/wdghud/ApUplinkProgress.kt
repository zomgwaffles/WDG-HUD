package com.example.wdghud

/**
 * G5A40.1: progress tracks acknowledged AP-level data only (LVL and RAW).
 * CELL and NAME are required 9-sector metadata, not AP-level progress.
 * The watch shows 100% only after END validates and commits the snapshot.
 */
internal object ApUplinkProgress {
    fun isApLevelPacket(packet: String): Boolean =
        packet.startsWith("LVL,") || packet.startsWith("RAW,")

    fun countApLevelPackets(packets: List<String>): Int =
        packets.count(::isApLevelPacket)

    fun percent(acknowledged: Int, total: Int): Int {
        if (total <= 0) return 0
        return 99 * acknowledged.coerceIn(0, total) / total
    }
}
