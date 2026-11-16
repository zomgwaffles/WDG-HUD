package com.example.wdghud

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Optional 30-day activity rank. Never infer it from numeric leaderboard rank. */
internal object ActivityRankProbe {
    // Account-specific user preference; do not treat the public URL as an API key.
    const val CARD_URL_KEY = "wdgwars_public_card_url"
    const val CARD_CACHE_MS = 10 * 60 * 1000L
    private val rankNames = listOf("MACHINE", "RELENTLESS", "COMMITTED", "REGULAR", "CASUAL", "DORMANT")
    data class Activity(val rank: String?, val days: Int?)
    @Volatile private var lastUrl: String = ""
    @Volatile private var lastFetchAt: Long = 0L
    @Volatile private var lastActivity: Activity = Activity(null, null)
    @Synchronized fun fetchCardCached(url: String): Activity {
        val now = System.currentTimeMillis()
        if (url == lastUrl && now - lastFetchAt in 0 until CARD_CACHE_MS) return lastActivity
        val result = try { fetchCard(url) } catch (_: Exception) { Activity(null, null) }
        lastUrl = url
        lastFetchAt = now
        lastActivity = result
        return result
    }


    fun fromProfile(profile: JSONObject): Activity {
        val rank = profile.optString("activity_rank", "").uppercase().takeIf { it in rankNames }
        val days = listOf("active_days_30", "active_days_30d", "days_active_30")
            .firstNotNullOfOrNull { key ->
                if (!profile.has(key)) null else profile.optInt(key, -1).takeIf { it in 0..30 }
            }
        return Activity(rank, days)
    }

    /** Best-effort text extraction from a public SVG. Never use OCR or guess a value. */
    fun fromSvg(svg: String): Activity {
        if (!svg.contains("<svg", ignoreCase = true) || svg.length > 2_000_000) return Activity(null, null)
        // Extract human-readable text; numbers elsewhere in the SVG are not an active-day count.
        val xmlText = Regex("<text\\b[^>]*>(.*?)</text>", RegexOption.IGNORE_CASE)
            .findAll(svg.replace('\n', ' '))
            .map { it.groupValues[1].replace(Regex("<[^>]*>"), " ") }
            .joinToString(" ")
            .replace(Regex("&#(?:x([0-9a-fA-F]+)|([0-9]+));")) { m ->
                val cp = m.groups[1]?.value?.toIntOrNull(16) ?: m.groups[2]?.value?.toIntOrNull()
                cp?.takeIf { it in 32..126 }?.toChar()?.toString() ?: " "
            }
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
        val upper = xmlText.uppercase()
        val rank = rankNames.firstOrNull { Regex("\\b$it\\b").containsMatchIn(upper) }
        val days = listOf(
            Regex("(?:ACTIVE\\s+DAYS?|DAYS?\\s+ACTIVE)\\s*[:=]?\\s*(\\d{1,2})\\s*(?:/|OF)\\s*30", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d{1,2})\\s*/\\s*30\\s*(?:DAYS?)?\\b", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { pattern ->
            pattern.find(xmlText)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..30 }
        }
        return Activity(rank, days)
    }

    fun fetchCard(cardUrl: String): Activity {
        if (!cardUrl.startsWith("https://wdgwars.pl/card/") || !cardUrl.endsWith(".svg")) return Activity(null, null)
        val conn = URL(cardUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = false
            if (conn.responseCode != 200) return Activity(null, null)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return fromSvg(text)
        } finally { conn.disconnect() }
    }

    fun packet(activity: Activity): String? {
        if (activity.rank == null && activity.days == null) return null
        return "PROFILE,A,${activity.rank ?: "UNKNOWN"},${activity.days ?: -1}"
    }
}
