package com.example.wdghud

/** Shared rule for phone freshness and the watch's G5A45.4 setting. */
internal object StaleIntervalPolicy {
    const val DEFAULT_MINUTES = 30
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 240
    private const val PREFIX = "CFG:STALE,"

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
    fun ttlMs(minutes: Int): Long = clamp(minutes) * 60_000L

    fun parseWatchMessage(message: String): Int? {
        if (!message.startsWith(PREFIX)) return null
        val raw = message.substring(PREFIX.length)
        if (raw.isEmpty() || raw.length > 3 || raw[0] == '0' ||
            raw.any { it !in '0'..'9' }) return null
        return raw.toIntOrNull()?.takeIf { it in MIN_MINUTES..MAX_MINUTES }
    }
}
