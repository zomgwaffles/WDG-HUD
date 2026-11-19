package com.example.wdghud

/** All long-lived portal intelligence has a one-hour minimum freshness interval. */
internal object HourlyRefreshPolicy {
    const val MINUTES = 60
    const val TTL_MS = 60L * 60L * 1000L

    fun fresh(fetchedAtEpochMs: Long, nowEpochMs: Long): Boolean =
        fetchedAtEpochMs > 0L && nowEpochMs >= fetchedAtEpochMs &&
            nowEpochMs - fetchedAtEpochMs < TTL_MS
}
