package com.example.wdghud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/** Account-bound, last-verified owner+operator wire snapshot.
 * Replaying a valid snapshot still sends OWNER first; it never bypasses the
 * watch's account-binding handshake and never stores the API key itself.
 */
internal object OperatorProfileSnapshotStore {
    private const val PREFS = "wdghud_operator_verified_v1"

    private fun accountId(apiKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(apiKey.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private fun valid(packets: List<String>): Boolean =
        packets.size in 4..32 &&
        Regex("^OWNER,[a-f0-9]{32}$").matches(packets.firstOrNull().orEmpty()) &&
        packets.any { it.startsWith("PROFILE,S,") } &&
        packets.any { it.startsWith("PROFILE,N,") } &&
        packets.lastOrNull() == "PROFILE,E" &&
        packets.all { it.toByteArray(Charsets.UTF_8).size <= 61 &&
            (it.startsWith("OWNER,") || it.startsWith("PROFILE,")) &&
            it.all { c -> c.code in 32..126 } }

    @Synchronized fun loadFresh(context: Context, apiKey: String): List<String>? {
        if (apiKey.isBlank()) return null
        return try {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(accountId(apiKey), null) ?: return null
            val data = JSONObject(raw)
            if (data.optInt("version", -1) != 1 ||
                !HourlyRefreshPolicy.fresh(data.optLong("at_utc_ms", -1L), System.currentTimeMillis())) return null
            val array = data.optJSONArray("packets") ?: return null
            val packets = (0 until array.length()).map { array.getString(it) }
            if (valid(packets)) packets else null
        } catch (_: Exception) { null }
    }

    @Synchronized fun saveVerified(context: Context, apiKey: String, packets: List<String>) {
        if (apiKey.isBlank() || !valid(packets)) return
        val payload = JSONObject().put("version", 1)
            .put("at_utc_ms", System.currentTimeMillis())
            .put("packets", JSONArray(packets)).toString()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(accountId(apiKey), payload).commit()
    }
}
