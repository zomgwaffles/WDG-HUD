package com.example.wdghud

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Persist the last complete GANG snapshot across Activity/Service BLE ownership.
 * A successful HTTP 404 is stored as a valid null-team snapshot; a failed fetch
 * never calls save(), so it cannot erase the last good data.
 */
internal object TeamSnapshotStore {
    data class Entry(
        val team: TeamProfileWire.Team?,
        val fetchedAtEpochMs: Long
    )

    private const val TAG = "WDGHUD_TEAM_CACHE"
    private const val PREFS_NAME = "wdghud_team_snapshots_v1"
    private const val FORMAT_VERSION = 1
    private const val MAX_MEMBERS = 5000
    private val hex = "0123456789abcdef".toCharArray()

    // Do not store the API key itself, even as part of a preferences key.
    private fun accountKey(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.trim().toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    fun save(
        context: Context,
        apiKey: String,
        team: TeamProfileWire.Team?,
        fetchedAtEpochMs: Long
    ) {
        if (apiKey.isBlank() || fetchedAtEpochMs <= 0L) return
        try {
            val document = JSONObject()
                .put("version", FORMAT_VERSION)
                .put("fetchedAtEpochMs", fetchedAtEpochMs)
                .put("hasTeam", team != null)

            if (team != null) {
                require(team.members.size <= MAX_MEMBERS) { "Team roster too large" }
                val members = JSONArray()
                team.members.forEach { member ->
                    members.put(JSONObject()
                        .put("username", member.username)
                        .put("role", member.role)
                        .put("total", member.total))
                }
                document.put("team", JSONObject()
                    .put("name", team.name)
                    .put("rank", team.rank)
                    .put("wifi", team.wifi)
                    .put("ble", team.ble)
                    .put("total", team.total)
                    .put("credits", team.credits)
                    .put("color", team.color)
                    .put("members", members))
            }

            // One atomic preferences value holds the entire snapshot. commit()
            // makes it visible to the other BLE owner before save() returns.
            val stored = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(accountKey(apiKey), document.toString())
                .commit()
            if (!stored) Log.w(TAG, "Unable to persist GANG snapshot")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to persist GANG snapshot", e)
        }
    }

    fun load(context: Context, apiKey: String): Entry? {
        if (apiKey.isBlank()) return null
        return try {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(accountKey(apiKey), null) ?: return null
            val document = JSONObject(raw)
            if (document.optInt("version", -1) != FORMAT_VERSION ||
                !document.has("hasTeam")) return null
            val fetchedAt = document.optLong("fetchedAtEpochMs", -1L)
            if (fetchedAt <= 0L) return null
            if (!document.getBoolean("hasTeam")) return Entry(null, fetchedAt)

            val savedTeam = document.optJSONObject("team") ?: return null
            val savedMembers = savedTeam.optJSONArray("members") ?: return null
            if (savedMembers.length() > MAX_MEMBERS) return null
            val members = ArrayList<TeamProfileWire.Member>(savedMembers.length())
            for (index in 0 until savedMembers.length()) {
                val member = savedMembers.optJSONObject(index) ?: return null
                members.add(TeamProfileWire.Member(
                    username = member.getString("username"),
                    role = member.getInt("role"),
                    total = member.getLong("total")
                ))
            }
            Entry(
                team = TeamProfileWire.Team(
                    name = savedTeam.getString("name"),
                    rank = savedTeam.getInt("rank"),
                    wifi = savedTeam.getLong("wifi"),
                    ble = savedTeam.getLong("ble"),
                    total = savedTeam.getLong("total"),
                    credits = savedTeam.getLong("credits"),
                    members = members,
                    color = savedTeam.optString("color", "")
                ),
                fetchedAtEpochMs = fetchedAt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable GANG snapshot", e)
            null
        }
    }
}
