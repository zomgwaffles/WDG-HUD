package com.example.wdghud

/** Shared rule for phone freshness and the watch's G5A45.4 setting. */
internal object StaleIntervalPolicy {
    const val DEFAULT_MINUTES = 60
    const val MIN_MINUTES = 30
    const val MAX_MINUTES = 240
    private const val PREFIX = "CFG:STALE,"
    private val PRESETS = setOf(30, 60, 240)

    fun clamp(minutes: Int): Int = if (minutes in PRESETS) minutes else DEFAULT_MINUTES
    fun ttlMs(minutes: Int): Long = clamp(minutes) * 60_000L

    fun parseWatchMessage(message: String): Int? {
        if (!message.startsWith(PREFIX)) return null
        val raw = message.substring(PREFIX.length)
        if (raw.isEmpty() || raw.length > 3 || raw[0] == '0' ||
            raw.any { it !in '0'..'9' }) return null
        return raw.toIntOrNull()?.takeIf { it in PRESETS }
    }
}
