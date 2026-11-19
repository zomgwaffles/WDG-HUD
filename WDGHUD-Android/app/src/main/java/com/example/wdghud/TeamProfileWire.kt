package com.example.wdghud

import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** G5A45: watch TEAM snapshots; never participates in the GPS/AP sector cache. */
internal object TeamProfileWire {
    const val CACHE_MS = 60 * 60 * 1000L
    const val PAGE_SIZE = 4
    const val MAX_BYTES = 61
    data class Member(val username: String, val role: Int, val total: Long)
    data class Team(
        val name: String, val rank: Int, val wifi: Long, val ble: Long,
        val total: Long, val credits: Long, val members: List<Member>,
        val color: String = ""
    )

    fun safeAscii(text: String, limit: Int): String = text.map { ch ->
        if (ch.code in 32..126 && ch != ',') ch else '?'
    }.joinToString("").trim().take(limit)

    private fun count(obj: JSONObject?, key: String): Long {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return -1L
        return obj.optLong(key, -1L).takeIf { it in 0L..999_999_999L } ?: -1L
    }

    fun parse(json: JSONObject): Team {
        val raw = json.optJSONArray("members") ?: JSONArray()
        val members = ArrayList<Member>(raw.length().coerceAtMost(5000))
        for (i in 0 until raw.length().coerceAtMost(5000)) {
            val member = raw.optJSONObject(i) ?: continue
            val role = when(member.optString("role", "").lowercase()) {
                "owner" -> 2; "officer" -> 1; else -> 0
            }
            val username = safeAscii(member.optString("username", "UNKNOWN"), 20).ifEmpty { "UNKNOWN" }
            members += Member(username, role, count(member, "total"))
        }
        val totals = json.optJSONObject("totals")
        val credits = json.optJSONObject("credits")
        return Team(
            name = safeAscii(json.optString("name", "NO TEAM"), 96).ifEmpty { "NO TEAM" },
            rank = json.optInt("rank", -1).takeIf { it in 0..999999 } ?: -1,
            wifi = count(totals, "wifi"),
            ble = count(totals, "ble"),
            total = count(totals, "total"),
            credits = count(credits, "balance"),
            members = members,
            color = json.optString("color", "").takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) } ?: ""
        )
    }

    /** HTTP 404 means no team, not a failed connection. Other errors throw. */
    fun fetch(apiKey: String): Team? {
        require(apiKey.isNotBlank()) { "Missing API key" }
        val connection = URL("https://wdgwars.pl/api/team/me").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("X-API-Key", apiKey)
            connection.setRequestProperty("Accept", "application/json")
            val status = PortalHttpPolicy.responseCode(connection)
            if (status == 404) return null
            require(status == 200 && connection.contentType.orEmpty().contains("json", ignoreCase = true)) {
                "HTTP $status or unexpected content type"
            }
            return parse(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally { connection.disconnect() }
    }

    private fun validate(packets: List<String>): List<String> {
        require(packets.all { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }) { "Team packet exceeds BLE MTU" }
        return packets
    }

    fun summary(team: Team?): List<String> {
        if (team == null) return listOf("TEAM,EMPTY")
        val chunks = team.name.chunked(24)
        val packets = mutableListOf(
            "TEAM,S,${team.rank},${team.members.size},${team.credits}",
            "TEAM,T,${team.wifi},${team.ble},${team.total}",
            "TEAM,C,${team.color.ifBlank { "NONE" }}"
        )
        chunks.forEachIndexed { index, value -> packets += "TEAM,N,$index,${chunks.size},$value" }
        packets += "TEAM,E"
        return validate(packets)
    }

    fun page(team: Team?, page: Int): List<String> {
        if (team == null) return listOf("TEAM,EMPTY")
        val pages = ((team.members.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        if (page !in 0 until pages) return listOf("TEAM,ERR")
        val packets = mutableListOf("TEAM,P,$page,$pages")
        team.members.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { slot, m ->
            packets += "TEAM,R,$slot,${m.role},${m.total},${m.username}"
        }
        packets += "TEAM,Q"
        return validate(packets)
    }

    fun expectedAck(packet: String): String = when {
        packet.startsWith("TEAM,S,") -> "TEAM:S:OK"
        packet.startsWith("TEAM,T,") -> "TEAM:T:OK"
        packet.startsWith("TEAM,C,") -> "TEAM:C:OK"
        packet.startsWith("TEAM,N,") -> "TEAM:N:${packet.substringAfter("TEAM,N,").substringBefore(',')}:OK"
        packet == "TEAM,E" -> "TEAM:E:OK"
        packet.startsWith("TEAM,P,") -> "TEAM:P:OK"
        packet.startsWith("TEAM,R,") -> "TEAM:R:${packet.substringAfter("TEAM,R,").substringBefore(',')}:OK"
        packet == "TEAM,Q" -> "TEAM:Q:OK"
        packet == "TEAM,EMPTY" -> "TEAM:EMPTY:OK"
        else -> "TEAM:ERR:OK"
    }
}
