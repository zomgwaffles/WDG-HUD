package com.example.wdghud

/**
 * Process-local generation fence for credentials and asynchronous account payloads.
 * The Android Activity persists [blocked] across restarts separately from the key.
 * No keys or fingerprints are written to logs by this class.
 */
internal class AccountChangeFence(initialKey: String = "", initiallyBlocked: Boolean = false) {
    data class Ticket internal constructor(val generation: Long, val key: String)

    private var currentKey: String = initialKey.trim()
    private var generation: Long = 0L
    var blocked: Boolean = initiallyBlocked
        private set

    fun restore(key: String, blocked: Boolean) {
        currentKey = key.trim()
        generation++
        this.blocked = blocked
    }

    /** Invalidate work even if the user eventually types the same key again (ABA). */
    fun edit(key: String): Boolean {
        val normalized = key.trim()
        if (normalized == currentKey) return false
        currentKey = normalized
        generation++
        blocked = true
        return true
    }

    fun snapshot(): Ticket = Ticket(generation, currentKey)

    fun isCurrent(ticket: Ticket): Boolean =
        ticket.generation == generation && ticket.key == currentKey

    /** Only verified current-account results may be sent to the watch. */
    fun accepts(ticket: Ticket): Boolean =
        !blocked && currentKey.isNotEmpty() && isCurrent(ticket)

    fun completeVerification(ticket: Ticket, verified: Boolean): Boolean {
        if (!isCurrent(ticket) || !verified || currentKey.isEmpty()) return false
        blocked = false
        return true
    }
}
