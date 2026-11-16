package com.example.wdghud

import android.content.Context

/** Per-installation preference, shared by Activity and background BLE service. */
internal object StaleIntervalSettings {
    private const val PREFS = "wdghud_cache_settings"
    private const val KEY = "stale_minutes"

    fun minutes(context: Context): Int =
        StaleIntervalPolicy.clamp(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY, StaleIntervalPolicy.DEFAULT_MINUTES))

    fun ttlMs(context: Context): Long = StaleIntervalPolicy.ttlMs(minutes(context))

    /** Only authenticated notifications enter through an established GATT link. */
    fun applyWatchMessage(context: Context, message: String): Int? {
        val value = StaleIntervalPolicy.parseWatchMessage(message) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY, StaleIntervalPolicy.DEFAULT_MINUTES) != value) {
            prefs.edit().putInt(KEY, value).apply()
        }
        return value
    }
}
