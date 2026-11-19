package com.example.wdghud

/** Strict, backwards-compatible watch capability negotiation. */
internal object WatchCapabilities {
    const val QUERY = "CAPS?"
    const val BASE_RESPONSE = "CAPS,2,BASE"
    const val ATOMIC_RESPONSE = "CAPS,2,VIEW,ATOMIC"
    const val GANG_RESPONSE = "CAPS,3,VIEW,ATOMIC,GANG"
    const val FULL_NAMES_RESPONSE = "CAPS,3,VIEW,ATOMIC,GANG,GANG96"
    const val GEO_CACHE_RESPONSE = "CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25"
    // G5A50.3e: current account-scoped watch adds ACCOUNT1 to GEO25.
    // Match the entire known response so unsupported/malformed capabilities
    // do not accidentally authorize personal or geographic transfers.
    const val GEO_ACCOUNT_RESPONSE = "CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25,ACCOUNT1"

    fun classify(message: String): String = when (message) {
        BASE_RESPONSE -> "BASE"
        ATOMIC_RESPONSE, GANG_RESPONSE, FULL_NAMES_RESPONSE,
        GEO_CACHE_RESPONSE, GEO_ACCOUNT_RESPONSE -> "V2"
        else -> "LEGACY"
    }

    fun supportsGangNames(message: String): Boolean =
        message == GANG_RESPONSE || message == FULL_NAMES_RESPONSE ||
            message == GEO_CACHE_RESPONSE || message == GEO_ACCOUNT_RESPONSE

    fun supportsFullSectorNames(message: String): Boolean =
        message == FULL_NAMES_RESPONSE || message == GEO_CACHE_RESPONSE ||
            message == GEO_ACCOUNT_RESPONSE

    fun supportsGeoCache(message: String): Boolean =
        message == GEO_CACHE_RESPONSE || message == GEO_ACCOUNT_RESPONSE
}
