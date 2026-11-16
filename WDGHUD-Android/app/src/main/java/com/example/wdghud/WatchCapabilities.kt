package com.example.wdghud

/** Backwards-compatible feature negotiation. Only the G5A6 watch accepts a name field. */
internal object WatchCapabilities {
    const val QUERY = "CAPS?"
    const val BASE_RESPONSE = "CAPS,2,BASE"
    const val ATOMIC_RESPONSE = "CAPS,2,VIEW,ATOMIC"
    const val GANG_RESPONSE = "CAPS,3,VIEW,ATOMIC,GANG"
    const val FULL_NAMES_RESPONSE = "CAPS,3,VIEW,ATOMIC,GANG,GANG96"
    const val GEO_CACHE_RESPONSE = "CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25"

    fun classify(message: String): String = when (message) {
        BASE_RESPONSE -> "BASE"
        ATOMIC_RESPONSE, GANG_RESPONSE, FULL_NAMES_RESPONSE, GEO_CACHE_RESPONSE -> "V2"
        else -> "LEGACY"
    }

    fun supportsGangNames(message: String): Boolean =
        message == GANG_RESPONSE || message == FULL_NAMES_RESPONSE || message == GEO_CACHE_RESPONSE

    fun supportsFullSectorNames(message: String): Boolean = message == FULL_NAMES_RESPONSE || message == GEO_CACHE_RESPONSE

    fun supportsGeoCache(message: String): Boolean = message == GEO_CACHE_RESPONSE
}
