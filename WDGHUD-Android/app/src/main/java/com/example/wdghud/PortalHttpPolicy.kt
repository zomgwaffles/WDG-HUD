package com.example.wdghud

import java.net.HttpURLConnection
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.WeakHashMap

/** WDGWars portal identity and shared, non-persistent HTTP 429 cooldown.
 * No API key, cookie, or account identifier is stored here.
 */
internal object PortalHttpPolicy {
    const val USER_AGENT = "WDGHUD/1.0.0 (Android; Watch companion)"
    private const val HOST = "wdgwars.pl"
    private const val FALLBACK_RETRY_MS = 60_000L
    private val prepared = Collections.newSetFromMap(WeakHashMap<HttpURLConnection, Boolean>())
    private var pauseUntilMonotonicMs = 0L

    /** Relative seconds or RFC 7231 / RFC 9110 HTTP-date; fallback is conservative. */
    internal fun retryAfterMillis(value: String?, wallClockMs: Long): Long {
        val raw = value?.trim().orEmpty()
        val seconds = raw.toLongOrNull()
        if (seconds != null && seconds >= 0) {
            return if (seconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE
                   else seconds * 1000L
        }
        val timestamp = try {
            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli()
        } catch (_: Exception) { null }
        return if (timestamp == null) FALLBACK_RETRY_MS
               else if (timestamp <= wallClockMs) 0L
               else timestamp - wallClockMs
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    @Synchronized fun record429(retryAfter: String?) {
        val delay = retryAfterMillis(retryAfter, System.currentTimeMillis())
        val now = monotonicMs()
        val until = if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay
        if (until > pauseUntilMonotonicMs) pauseUntilMonotonicMs = until
    }

    @Synchronized fun remainingMs(): Long =
        (pauseUntilMonotonicMs - monotonicMs()).coerceAtLeast(0L)

    fun isLimited(): Boolean = remainingMs() > 0L

    fun webUserAgent(defaultAgent: String?): String =
        "$USER_AGENT ${defaultAgent.orEmpty().trim()}".trim()

    /** Must be called for every native WDGWars HTTP status, before body consumption. */
    fun responseCode(connection: HttpURLConnection): Int {
        val url = connection.url
        require(url.protocol.equals("https", ignoreCase = true) &&
                url.host.equals(HOST, ignoreCase = true) &&
                (url.port == -1 || url.port == 443) &&
                url.userInfo == null) { "Refusing non-WDGWars API request" }
        val remaining = remainingMs()
        if (remaining > 0L) throw PortalRateLimitedException(remaining)
        synchronized(prepared) {
            if (prepared.add(connection)) {
                // Never forward the player's API key or website cookies via redirects.
                connection.instanceFollowRedirects = false
                // Authenticated website-cookie requests may append the genuine
                // WebView engine User-Agent; preserve that if already branded.
                val existing = connection.getRequestProperty("User-Agent").orEmpty()
                if (!existing.startsWith("WDGHUD/"))
                    connection.setRequestProperty("User-Agent", USER_AGENT)
            }
        }
        val status = connection.responseCode
        if (status == 429) {
            record429(connection.getHeaderField("Retry-After"))
            throw PortalRateLimitedException(remainingMs())
        }
        return status
    }
}

internal class PortalRateLimitedException(val waitMs: Long) :
    IllegalStateException("WDGWars rate limit; retry after ${waitMs} ms")
