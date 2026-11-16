package com.example.wdghud

/** Monotonic-clock retry gate used by BOTH Android BLE owners and sector scans.
 *  A temporary failure never becomes a permanent disabled state.
 *  Caller chooses when an attempt actually starts; no hidden timers or threads.
 */
internal class RecoveryBackoff(
    private val delaysMs: List<Long> = listOf(2_500L, 5_000L, 10_000L, 20_000L, 30_000L)
) {
    init { require(delaysMs.isNotEmpty() && delaysMs.all { it > 0 }) }
    private var failures = 0
    private var retryAtMs = 0L
    val failedAttempts: Int get() = failures
    fun canAttempt(nowMs: Long): Boolean = nowMs >= retryAtMs
    fun fail(nowMs: Long) {
        val delay = delaysMs[failures.coerceAtMost(delaysMs.lastIndex)]
        if (failures < Int.MAX_VALUE) ++failures
        retryAtMs = if (nowMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMs + delay
    }
    fun succeed() { failures = 0; retryAtMs = 0L }
    fun remainingMs(nowMs: Long): Long = (retryAtMs - nowMs).coerceAtLeast(0L)
}
