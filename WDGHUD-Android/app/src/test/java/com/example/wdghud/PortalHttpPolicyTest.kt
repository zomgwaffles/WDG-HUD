package com.example.wdghud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class PortalHttpPolicyTest {
    @Test fun userAgentIdentifiesSeparateAndroidCompanion() {
        assertTrue(PortalHttpPolicy.USER_AGENT.startsWith("WDGHUD/"))
        assertFalse(PortalHttpPolicy.USER_AGENT.startsWith("WatchDogsGo/"))
        assertTrue(PortalHttpPolicy.webUserAgent("Mozilla/5.0").startsWith("WDGHUD/1.0.0 "))
    }

    @Test fun retryAfterAcceptsSecondsAndHttpDate() {
        val t = 1_784_000_000_000L
        assertEquals(120_000L, PortalHttpPolicy.retryAfterMillis("120", t))
        assertEquals(60_000L, PortalHttpPolicy.retryAfterMillis("invalid", t))
        assertEquals(60_000L, PortalHttpPolicy.retryAfterMillis(null, t))
        assertEquals(0L, PortalHttpPolicy.retryAfterMillis("0", t))
        assertEquals(60_000L, PortalHttpPolicy.retryAfterMillis("-4", t))
        val date = java.time.Instant.ofEpochMilli(t + 90_000L)
            .atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(90_000L, PortalHttpPolicy.retryAfterMillis(date, t))
    }

    @Test fun neverSendCredentialsToOtherOrigins() {
        val bad = URL("https://example.org/api/me").openConnection() as HttpURLConnection
        try {
            var rejected = false
            try { PortalHttpPolicy.responseCode(bad) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        } finally { bad.disconnect() }
    }
}
