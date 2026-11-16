package com.example.wdghud

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.wdghud.geometry.SectorId
import org.json.JSONArray
import android.util.Log

/** Independently verified WDGWars territory metadata, cumulative and unpruned.
 * Missing ownership must never invalidate authenticated AP detail.
 */
internal object TerritoryCacheStore {
    private const val DB_NAME = "wdghud_territory_history.db"
    private const val LEGACY = "wdghud_territory_cache_v1"
    private val hexColor = Regex("#[0-9a-fA-F]{6}")

    private class Db(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE owners (north INTEGER NOT NULL, east INTEGER NOT NULL, name TEXT NOT NULL, color TEXT NOT NULL, PRIMARY KEY(north,east))")
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw IllegalStateException("Unexpected ownership schema upgrade $oldVersion -> $newVersion")
        }
    }
    private var helper: Db? = null
    private fun valid(name: String?, color: String?): Boolean {
        if (name == null || name.isBlank() || name == "UNKNOWN" ||
            name.length > 96 || name.any { it.code !in 32..126 || it == ',' }) return false
        return name == "UNCLAIMED" || (color != null && hexColor.matches(color))
    }
    private fun db(context: Context): SQLiteDatabase {
        val h = helper ?: Db(context.applicationContext).also { helper = it }
        val database = h.writableDatabase
        database.rawQuery("SELECT value FROM metadata WHERE key='legacy_imported'", null).use {
            if (it.moveToFirst()) return database
        }
        database.beginTransaction()
        try {
            val raw = context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).getString("owners", null)
            if (!raw.isNullOrEmpty()) {
                val array = try { JSONArray(raw) } catch (e: Exception) {
                    Log.e("WDGHUD_CACHE", "Legacy gang cache malformed, backup kept: ${e.message}")
                    null
                }
                for (i in 0 until (array?.length() ?: 0)) {
                    val item = array?.optJSONObject(i) ?: continue
                    if (!item.has("north") || !item.has("east")) continue
                    val name = item.optString("name")
                    val color = item.optString("color", "-")
                    if (valid(name, color)) insert(database, SectorId(item.getLong("north"), item.getLong("east")), name, color)
                }
            }
            database.execSQL("INSERT INTO metadata(key,value) VALUES('legacy_imported','1')")
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        return database
    }
    private fun insert(database: SQLiteDatabase, id: SectorId, name: String, color: String) {
        val fields = android.content.ContentValues(4).apply {
            put("north", id.north); put("east", id.east); put("name", name); put("color", color)
        }
        database.insertWithOnConflict("owners", null, fields, SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun load(context: Context): Map<SectorId, Pair<String?, String?>> {
        val result = linkedMapOf<SectorId, Pair<String?, String?>>()
        db(context).rawQuery("SELECT north,east,name,color FROM owners", null).use { c ->
            while (c.moveToNext()) result[SectorId(c.getLong(0), c.getLong(1))] = c.getString(2) to c.getString(3)
        }
        return result
    }
    @Synchronized fun loadWindow(context: Context, ids: Collection<SectorId>): Map<SectorId, Pair<String?, String?>> {
        if (ids.isEmpty()) return emptyMap()
        val result = linkedMapOf<SectorId, Pair<String?, String?>>()
        val database = db(context)
        ids.forEach { id ->
            database.rawQuery("SELECT name,color FROM owners WHERE north=? AND east=?",
                arrayOf(id.north.toString(), id.east.toString())).use { c ->
                if (c.moveToFirst()) result[id] = c.getString(0) to c.getString(1)
            }
        }
        return result
    }
    @Synchronized fun merge(context: Context, verified: Map<SectorId, Pair<String?, String?>>) {
        if (verified.isEmpty()) return
        val database = db(context)
        database.beginTransaction()
        try {
            verified.forEach { (id, value) ->
                val name = value.first
                val color = value.second
                if (valid(name, color)) insert(database, id, name!!, color ?: "-")
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
}
