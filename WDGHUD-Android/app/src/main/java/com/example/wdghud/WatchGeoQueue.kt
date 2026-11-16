package com.example.wdghud

import com.example.wdghud.geometry.SectorId

/** Single-main-thread BLE cache queue. A K,B ... K,E sector transaction is
 *  retained until KE:OK. Do not mark a sector delivered before that ACK.
 *  G5A40 only resends changed, non-ACKed or explicitly invalidated records.
 */
internal class WatchGeoQueue {
    private val packets = ArrayDeque<String>()
    private val queuedVersions = ArrayDeque<Pair<SectorId, String>>()
    private val deliveredVersions = mutableMapOf<SectorId, String>()
    private var awaiting: String? = null
    private var lastAttemptMs = 0L
    var lastError: String? = null
        private set
    val active: Boolean get() = packets.isNotEmpty() || awaiting != null
    val waitingForAck: Boolean get() = awaiting != null

    private fun version(json: String, gang: String?, color: String?) =
        json + "\u0000" + (gang ?: "") + "\u0000" + (color ?: "")

    fun needsDelivery(id: SectorId, json: String, gang: String? = null, color: String? = null): Boolean =
        deliveredVersions[id] != version(json, gang, color)

    fun enqueue(items: Map<SectorId, String>, meta: (SectorId) -> Pair<String?, String?> = { null to null }) {
        if (active) return // Never interrupt a sector transaction mid-flight.
        packets.clear()
        queuedVersions.clear()
        // LinkedHashMap insertion order is priority order (visible/forward first).
        items.forEach { (id, json) ->
            val (gang, color) = meta(id)
            val v = version(json, gang, color)
            if (deliveredVersions[id] != v) {
                WatchGeoPackets.build(id, json, gang, color)?.let { chunk ->
                    packets.addAll(chunk)
                    queuedVersions.add(id to v)
                }
            }
        }
        lastError = null
    }

    fun next(nowMs: Long, canSend: Boolean): String? {
        if (awaiting != null) {
            if (nowMs - lastAttemptMs >= 6000L) abort("ACK timeout")
            return null
        }
        return if (canSend) packets.firstOrNull() else null
    }

    fun sent(nowMs: Long) {
        awaiting = packets.firstOrNull()?.let(WatchGeoPackets::expectedAck)
        lastAttemptMs = nowMs
    }

    fun accept(message: String): Boolean {
        if (awaiting == null || !message.startsWith("K")) return false
        if (message != awaiting) { abort("watch rejected: $message"); return true }
        val accepted = packets.removeFirstOrNull()
        if (accepted == "K,E") {
            queuedVersions.removeFirstOrNull()?.let { (id, v) -> deliveredVersions[id] = v }
        }
        awaiting = null
        return true
    }

    /** Clear only known-evicted sectors. Re-entering an old area will resend. */
    fun retainWindow(ids: Collection<SectorId>) {
        val keys = ids.toHashSet()
        deliveredVersions.keys.retainAll(keys)
    }

    /** Call after a new BLE connection because the watch may have rebooted. */
    fun resetRemoteState() { abort("BLE session reset"); deliveredVersions.clear() }

    /** Make missing visible sectors eligible for retransmission after CENTER:MISS. */
    fun invalidate(ids: Collection<SectorId>) { ids.forEach { deliveredVersions.remove(it) } }

    fun abort(reason: String) {
        packets.clear()
        queuedVersions.clear()
        awaiting = null
        lastError = reason
    }
}
