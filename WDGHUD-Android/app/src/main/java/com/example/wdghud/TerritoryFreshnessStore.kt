package com.example.wdghud

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.wdghud.geometry.SectorId
import com.example.wdghud.geometry.WdgWarsGrid
import java.security.MessageDigest

/** Independent, cumulative coverage timestamps for official ownership checks.
 * Does not alter the existing AP or ownership database and never deletes records.
 * A successful worldwide territory response covers the nearby 7x7 sector window,
 * including sectors that have no listed gang yet.
 */
internal object TerritoryFreshnessStore {
    private class Db(context: Context) : SQLiteOpenHelper(
        context.applicationContext, "wdghud_territory_checked_v1.db", null, 1
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE checked (account TEXT NOT NULL, north INTEGER NOT NULL, " +
                "east INTEGER NOT NULL, at_utc_ms INTEGER NOT NULL, " +
                "PRIMARY KEY(account,north,east))")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw IllegalStateException("Unexpected coverage schema upgrade $oldVersion -> $newVersion")
        }
    }

    private var helper: Db? = null

    private fun database(context: Context): SQLiteDatabase =
        (helper ?: Db(context).also { helper = it }).writableDatabase

    private fun account(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 0xff) }

    @Synchronized
    fun currentWindowFresh(
        context: Context, apiKey: String, center: SectorId, nowUtcMs: Long
    ): Boolean {
        if (apiKey.isBlank()) return false

        val db = database(context)
        val accountId = account(apiKey)

        // Every one of the 25 currently relevant cells must have a recent check.
        return WdgWarsGrid.window(center, 2).all { id ->
            db.rawQuery(
                "SELECT at_utc_ms FROM checked WHERE account=? AND north=? AND east=?",
                arrayOf(accountId, id.north.toString(), id.east.toString())
            ).use { c ->
                c.moveToFirst() && HourlyRefreshPolicy.fresh(c.getLong(0), nowUtcMs)
            }
        }
    }

    @Synchronized
    fun markNearbyChecked(
        context: Context, apiKey: String, center: SectorId, nowUtcMs: Long
    ) {
        if (apiKey.isBlank() || nowUtcMs <= 0L) return

        val db = database(context)
        val accountId = account(apiKey)

        db.beginTransaction()
        try {
            // The worldwide ownership response covers this 7x7 neighborhood.
            //
            // WdgWarsGrid.window() supports only radius 0..2 because
            // the AP prefetch/viewport is limited to 5x5.
            //
            // Generate the coverage IDs directly. This does NOT initiate
            // AP prefetches or make additional HTTP requests.
            for (northOffset in -3L..3L) {
                for (eastOffset in -3L..3L) {
                    val id = SectorId(
                        center.north + northOffset,
                        center.east + eastOffset
                    )

                    val row = ContentValues(4).apply {
                        put("account", accountId)
                        put("north", id.north)
                        put("east", id.east)
                        put("at_utc_ms", nowUtcMs)
                    }

                    db.insertWithOnConflict(
                        "checked",
                        null,
                        row,
                        SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
