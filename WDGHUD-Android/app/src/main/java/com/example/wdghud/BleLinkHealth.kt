package com.example.wdghud

/**
 * G5A43: BLE application-layer liveness monitor. No Android dependencies so both
 * BLE owners share exactly the same policy and it can be host-unit-tested.
 *
 * All times are monotonic milliseconds (SystemClock.elapsedRealtime on Android).
 * A received protocol reply proves the link is alive; PONG is additionally used
 * to check an otherwise quiet link. A busy command stream is never interrupted.
 */
internal class BleLinkHealth(
    private val quietMs: Long = 15_000L,
    private val pongTimeoutMs: Long = 6_000L,
    private val retryDelayMs: Long = 3_000L,
    private val maxMissedPings: Int = 2
) {
    enum class Action { NONE, PING, MISSED, RECOVER }

    var connected = false
        private set
    var missedPings = 0
        private set
    var successfulPings = 0
        private set
    var sessionCount = 0
        private set
    var recoveryCount = 0
        private set
    var lastPongRttMs: Long? = null
        private set
    var lastDisconnectReason = "Not connected"
        private set
    var lastResponseAtMs: Long = 0L
        private set

    private var awaitingPongAtMs: Long? = null
    private var nextPingAllowedAtMs: Long = 0L
    private var timedOut = false

    fun onReady(nowMs: Long) {
        require(nowMs >= 0)
        connected = true
        sessionCount++
        lastResponseAtMs = nowMs
        awaitingPongAtMs = null
        missedPings = 0
        nextPingAllowedAtMs = nowMs + quietMs
        timedOut = false
    }

    fun onDisconnected(reason: String) {
        connected = false
        awaitingPongAtMs = null
        missedPings = 0
        timedOut = false
        lastDisconnectReason = reason
    }

    /** Returns true for a PONG, so callers can consume it before batch ACKs. */
    fun onResponse(nowMs: Long, response: String): Boolean {
        if (!connected) return response == "PONG"
        lastResponseAtMs = nowMs
        val sentAt = awaitingPongAtMs
        if (response == "PONG") {
            if (sentAt != null) {
                lastPongRttMs = (nowMs - sentAt).coerceAtLeast(0L)
                successfulPings++
            }
        }
        // Any real response is proof that the watch is processing our commands.
        // An unrelated ACK may race a PONG; no reason to tear down a live link.
        awaitingPongAtMs = null
        missedPings = 0
        nextPingAllowedAtMs = nowMs + quietMs
        timedOut = false
        return response == "PONG"
    }

    fun evaluate(nowMs: Long, canSendPing: Boolean): Action {
        if (!connected) return Action.NONE
        val sentAt = awaitingPongAtMs
        if (sentAt != null) {
            if (nowMs - sentAt < pongTimeoutMs) return Action.NONE
            awaitingPongAtMs = null
            missedPings++
            nextPingAllowedAtMs = nowMs + retryDelayMs
            timedOut = true
            if (missedPings >= maxMissedPings) {
                recoveryCount++
                return Action.RECOVER
            }
            return Action.MISSED
        }
        if (!canSendPing || nowMs < nextPingAllowedAtMs ||
            nowMs - lastResponseAtMs < quietMs) return Action.NONE
        return Action.PING
    }

    /** Call only after the GATT write was accepted for transmission. */
    fun onPingSent(nowMs: Long) {
        check(connected && awaitingPongAtMs == null)
        awaitingPongAtMs = nowMs
        timedOut = false
    }

    fun diagnostic(nowMs: Long): String {
        if (!connected) return "OFFLINE | last: $lastDisconnectReason | sessions $sessionCount"
        val age = (nowMs - lastResponseAtMs).coerceAtLeast(0L) / 1000L
        val phase = if (awaitingPongAtMs != null) "PING WAIT" else if (timedOut) "RETRY" else "ACTIVE"
        return "$phase | last RX ${age}s | PONG $successfulPings | missed $missedPings | " +
            "RTT ${lastPongRttMs?.let { "${it}ms" } ?: "--"} | recoveries $recoveryCount"
    }
}
