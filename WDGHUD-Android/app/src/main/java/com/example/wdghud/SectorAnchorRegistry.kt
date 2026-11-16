package com.example.wdghud

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.WdgWarsGrid

/** Persistent geographic anchor registry. INSERT OR IGNORE: an AP sync can
 *  never overwrite an anchor or its four boundaries. No expiry/TTL; no HTTP.
 */
internal class SectorAnchorRegistry(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "wdghud_sector_anchors.db", null, 1
) {
    private var last: SectorAnchor? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE anchors (
            grid_north INTEGER NOT NULL,
            grid_east INTEGER NOT NULL,
            center_lat REAL NOT NULL, center_lon REAL NOT NULL,
            bound_south REAL NOT NULL, bound_north REAL NOT NULL,
            bound_west REAL NOT NULL, bound_east REAL NOT NULL,
            PRIMARY KEY(grid_north,grid_east)
        )""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Geometry must never be dropped or replaced by a routine schema update.
        throw IllegalStateException("Sector-anchor migration must preserve existing rows")
    }

    private fun stored(id: SectorId): SectorAnchor? = readableDatabase.query(
        "anchors", arrayOf("center_lat","center_lon","bound_south","bound_north","bound_west","bound_east"),
        "grid_north=? AND grid_east=?", arrayOf(id.north.toString(), id.east.toString()),
        null, null, null, "1"
    ).use { c ->
        if (!c.moveToFirst()) null else SectorAnchor(id,
            c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getDouble(5))
    }

    fun ensure(id: SectorId): SectorAnchor {
        last?.takeIf { it.id == id }?.let { return it }
        stored(id)?.let { return it }
        val candidate = SectorAnchorMath.derive(id)
        val values = ContentValues().apply {
            put("grid_north", id.north); put("grid_east", id.east)
            put("center_lat", candidate.centerLat); put("center_lon", candidate.centerLon)
            put("bound_south", candidate.south); put("bound_north", candidate.north)
            put("bound_west", candidate.west); put("bound_east", candidate.east)
        }
        writableDatabase.insertWithOnConflict("anchors", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return stored(id) ?: candidate
    }

    companion object {
        // One async worker per app process; geographic persistence never stalls
        // GPS callbacks, CENTER control packets or watch recentering.
        private val geometryWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    fun ensureWindowAsync(center: SectorId) {
        geometryWorker.execute { ensureWindow(center) }
    }

    fun ensureWindow(center: SectorId) {
        WdgWarsGrid.window(center, 2).forEach { id ->
            try { ensure(id) }
            catch (e: Exception) { Log.w("WDGHUD_ANCHOR", "Could not derive anchor $id: ${e.message}") }
        }
    }

    /** First check retained bounds, then eight neighboring anchors, then derive
     *  the exact target directly. A GPS jump can never be trapped in the old 5x5.
     */
    fun locate(lat: Double, lon: Double): SectorId? {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
            return null
        // First test the immutable bounds of the last GPS center. Most fixes
        // need no new row/column calculation or database operation at all.
        val old = last
        if (old != null && old.contains(lat, lon)) return old.id
        val actual = WdgWarsGrid.sectorAt(lat, lon) // offline only; never HTTP
        val candidate = if (old != null) {
            WdgWarsGrid.window(old.id, 1).firstOrNull { it == actual } ?: actual
        } else actual
        val anchor = try { ensure(candidate) }
            catch (e: Exception) {
                Log.w("WDGHUD_ANCHOR", "Anchor derivation failed for $actual; using local grid key: ${e.message}")
                last = null
                return actual
            }
        last = anchor
        Log.i("WDGHUD_ANCHOR", "GPS_CENTER=$actual anchor=${anchor.centerLat},${anchor.centerLon} " +
            "bounds=[${anchor.south},${anchor.north})x[${anchor.west},${anchor.east})")
        return actual
    }
}
