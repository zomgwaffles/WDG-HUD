package com.example.wdghud

/** Computed on demand; only last successful UTC timestamp is persisted. */
internal object SectorFreshness {
    const val STALE_AFTER_MS = 30L * 60L * 1000L
    fun fromUtc(utc: Long, nowUtc: Long, elapsedNow: Long): Long =
        elapsedNow - (nowUtc - utc)
    fun toUtc(elapsedStamp: Long, nowUtc: Long, elapsedNow: Long): Long =
        nowUtc - (elapsedNow - elapsedStamp)
    fun isFresh(lastSyncUtc: Long?, nowUtc: Long): Boolean =
        lastSyncUtc != null && lastSyncUtc > 0L && nowUtc >= lastSyncUtc &&
            nowUtc - lastSyncUtc < STALE_AFTER_MS
}
