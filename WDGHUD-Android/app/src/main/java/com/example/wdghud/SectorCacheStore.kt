package com.example.wdghud

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray

/**
 * G5A42 cumulative, indexed AP intelligence history.
 * The 25-sector window is only an active WORKING SET; leaving an area never
 * deletes its rows. Each record stores its verified JSON and lastSyncUtc only.
 * An older Activity/Service write can never replace newer validated data.
 *
 * Entry.fetchedAtMs stays elapsedRealtime-based for older callers; the DB
 * persists UTC epoch milliseconds so results survive phone restarts.
 */
internal object SectorCacheStore {
    data class Entry(val payload: String, val fetchedAtMs: Long)
    private const val DB_NAME = "wdghud_ap_history.db"
    private const val LEGACY_PREFS = "wdghud_sector_cache"
    private const val HOT_SET_LIMIT = 1600  // RAM only; NEVER deletes history.
    const val FRESH_MS = SectorFreshness.STALE_AFTER_MS

    private class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE ap_history (cache_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, last_sync_utc INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX ap_history_updated ON ap_history(last_sync_utc DESC)")
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future schema upgrades must migrate, NEVER DROP, cumulative data.
            throw IllegalStateException("Unexpected AP history schema upgrade $oldVersion -> $newVersion")
        }
    }

    private var helper: Db? = null
    private fun db(context: Context): SQLiteDatabase {
        val h = helper ?: Db(context.applicationContext).also { helper = it }
        val database = h.writableDatabase
        migrateLegacy(context.applicationContext, database)
        return database
    }

    private fun migrateLegacy(context: Context, database: SQLiteDatabase) {
        database.rawQuery("SELECT value FROM metadata WHERE key='legacy_imported'", null).use {
            if (it.moveToFirst()) return
        }
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val oldBoot = prefs.getLong("boot_epoch", -1L)
        val oldJson = prefs.getString("items", null)
        database.beginTransaction()
        try {
            if (!oldJson.isNullOrBlank()) {
                val items = try { JSONArray(oldJson) } catch (e: Exception) {
                    Log.e("WDGHUD_CACHE", "Legacy AP cache malformed, backup kept: ${e.message}")
                    null
                }
                for (i in 0 until (items?.length() ?: 0)) {
                    val item = items?.optJSONObject(i) ?: continue
                    val key = item.optString("key")
                    val payload = item.optString("payload")
                    val utc = if (item.has("lastSyncUtc")) item.optLong("lastSyncUtc", -1L)
                        else if (oldBoot > 0L && item.optLong("at", -1L) >= 0L)
                            oldBoot + item.optLong("at") else -1L
                    if (key.isNotBlank() && payload.isNotBlank() && utc > 0L)
                        upsert(database, key, payload, utc)
                }
            }
            database.execSQL("INSERT INTO metadata(key,value) VALUES('legacy_imported','1')")
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        // Do not delete the legacy backup. A DB failure must not destroy old data.
    }

    /** Preserve newer data even when an old Activity/service finishes a delayed save. */
    private fun upsert(database: SQLiteDatabase, key: String, payload: String, utc: Long) {
        val values = android.content.ContentValues(3).apply {
            put("cache_key", key); put("payload", payload); put("last_sync_utc", utc)
        }
        val rows = database.update("ap_history", values,
            "cache_key=? AND last_sync_utc<?", arrayOf(key, utc.toString()))
        if (rows == 0) database.insertWithOnConflict("ap_history", null, values,
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun isFreshUtc(lastSyncUtc: Long?, nowUtc: Long = System.currentTimeMillis()): Boolean =
        SectorFreshness.isFresh(lastSyncUtc, nowUtc)

    private fun convert(utc: Long, payload: String, nowUtc: Long, elapsed: Long) =
        Entry(payload, SectorFreshness.fromUtc(utc, nowUtc, elapsed))

    /** RAM warm start only. Full cumulative history is retained on disk. */
    @Synchronized fun load(context: Context): Map<String, Entry> {
        val nowUtc = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val result = linkedMapOf<String, Entry>()
        db(context).rawQuery(
            "SELECT cache_key,payload,last_sync_utc FROM ap_history ORDER BY last_sync_utc DESC LIMIT $HOT_SET_LIMIT", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val utc = cursor.getLong(2)
                if (utc > 0L) result[cursor.getString(0)] = convert(utc, cursor.getString(1), nowUtc, elapsed)
            }
        }
        return result
    }

    /** Get *any* previously visited sectors by key, regardless of distance or age. */
    @Synchronized fun loadKeys(context: Context, keys: Collection<String>): Map<String, Entry> {
        if (keys.isEmpty()) return emptyMap()
        val nowUtc = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val result = linkedMapOf<String, Entry>()
        val database = db(context)
        keys.distinct().chunked(200).forEach { batch ->
            val args = batch.toTypedArray()
            val placeholders = List(batch.size) { "?" }.joinToString(",")
            database.rawQuery(
                "SELECT cache_key,payload,last_sync_utc FROM ap_history WHERE cache_key IN ($placeholders)", args
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val utc = cursor.getLong(2)
                    if (utc > 0L) result[cursor.getString(0)] = convert(utc, cursor.getString(1), nowUtc, elapsed)
                }
            }
        }
        return result
    }

    /** One verified sector at a time: avoid rewriting 1,600 RAM entries on
     * every background prefetch result. No DELETE ever occurs here. */
    @Synchronized fun saveOne(context: Context, key: String, entry: Entry) {
        if (key.isBlank() || entry.payload.isBlank()) return
        val nowUtc = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        if (entry.fetchedAtMs > elapsed) return
        val utc = SectorFreshness.toUtc(entry.fetchedAtMs, nowUtc, elapsed)
        if (utc > 0L) upsert(db(context), key, entry.payload, utc)
    }

    /** Upsert existing records; absence from the in-memory map means NOTHING. */
    @Synchronized fun save(context: Context, data: Map<String, Entry>) {
        if (data.isEmpty()) return
        val database = db(context)
        val nowUtc = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        database.beginTransaction()
        try {
            for ((key, entry) in data) {
                if (key.isBlank() || entry.payload.isBlank() || entry.fetchedAtMs > elapsed) continue
                val utc = SectorFreshness.toUtc(entry.fetchedAtMs, nowUtc, elapsed)
                if (utc > 0L) upsert(database, key, entry.payload, utc)
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    @Synchronized fun count(context: Context): Long {
        db(context).rawQuery("SELECT COUNT(*) FROM ap_history", null).use {
            return if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }
}
