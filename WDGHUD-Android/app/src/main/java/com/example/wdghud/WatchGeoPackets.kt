package com.example.wdghud

import com.example.wdghud.geometry.SectorId
import org.json.JSONObject

/** G5A38: validated AP detail -> atomic single-sector watch-cache transaction.
 *  No packet is generated from failed/incomplete detail or a fabricated sector.
 *  Gang metadata comes from the authenticated territory response where available.
 */
internal object WatchGeoPackets {
    fun build(
        id: SectorId,
        rawJson: String,
        gangName: String? = null,
        officialColor: String? = null
    ): List<String>? {
        return try {
        val data = JSONObject(rawJson)
        if (!data.optBoolean("ok", true)) return null
        val total = data.optLong("total", -1)
        if (total !in 0..Int.MAX_VALUE.toLong()) return null
        val levels = data.optJSONArray("levels") ?: return null
        var sum = 0L
        val hist = sortedMapOf<Int, Int>()
        for (index in 0 until levels.length()) {
            val entry = levels.optJSONObject(index) ?: return null
            val level = entry.optInt("lvl", 0)
            val count = entry.optLong("c", -1)
            if (level !in 1..255 || count !in 0..Int.MAX_VALUE.toLong() || hist.containsKey(level)) return null
            sum += count
            if (sum > total) return null
            if (count > 0) hist[level] = count.toInt()
        }
        if (sum != total) return null
        // G5A42: authenticated AP counts and gang ownership are independent.
        // Missing/invalid ownership must NEVER discard otherwise valid AP data.
        // Use explicit UNKNOWN + neutral grey until official gang data arrives.
        val suppliedName = gangName?.trim()
        val isUnclaimed = suppliedName == "UNCLAIMED"
        val officialColorValid = officialColor != null &&
            officialColor.matches(Regex("#[0-9a-fA-F]{6}"))
        val verifiedName = when {
            isUnclaimed -> "UNCLAIMED"
            suppliedName != null && suppliedName.isNotBlank() && suppliedName != "UNKNOWN" &&
                suppliedName.length <= 96 && officialColorValid -> suppliedName
            else -> "UNKNOWN"
        }
        val color = if (isUnclaimed || verifiedName == "UNKNOWN") 0x8410 else {
            val hex = officialColor ?: return null
            val rgb = hex.substring(1).toInt(16)
            (((rgb shr 19) and 31) shl 11) or
                (((rgb shr 10) and 63) shl 5) or
                ((rgb shr 3) and 31)
        }
        val ascii = verifiedName.map { c ->
            if (c.code in 32..126 && c != ',') c else '?'
        }.joinToString("").ifEmpty { "UNKNOWN" }.take(96)
        val chunks = ascii.chunked(24)
        buildList {
            add("K,B,${id.north},${id.east},$total,${color.toString(16).padStart(4, '0')}")
            chunks.forEachIndexed { part, text -> add("K,N,$part,${chunks.size},$text") }
            hist.forEach { (level, count) -> add("K,R,$level,$count") }
            add("K,E")
        }
    } catch (_: Exception) { null }
    }

    fun expectedAck(packet: String): String = when {
        packet.startsWith("K,B,") -> "KB:OK"
        packet.startsWith("K,N,") -> "KN:OK"
        packet.startsWith("K,R,") -> "KR:OK"
        packet == "K,E" -> "KE:OK"
        else -> "K:INVALID"
    }
}
