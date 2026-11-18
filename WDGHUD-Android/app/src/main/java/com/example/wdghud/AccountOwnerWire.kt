package com.example.wdghud

import java.security.MessageDigest
import java.util.Locale

/** Account label derived ONLY from username returned by a successful /api/me. */
internal object AccountOwnerWire {
    const val CAPABILITY = "ACCOUNT1"
    private const val DOMAIN = "wdghud.account.username.v1:"
    private val safeUsername = Regex("""^[\x20-\x2B\x2D-\x7E]{1,20}$""")

    fun supported(caps: String): Boolean = caps.startsWith("CAPS,") &&
        caps.split(',').contains(CAPABILITY)

    fun claimedUsername(raw: String): String {
        val username = raw.trim()
        require(safeUsername.matches(username)) { "Invalid verified account username" }
        return username
    }

    fun ownerPacket(verifiedUsername: String): String {
        val username = claimedUsername(verifiedUsername)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((DOMAIN + username).toByteArray(Charsets.UTF_8))
        val token = digest.take(16).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
        return "OWNER,$token"
    }
    fun expectedAck(packet: String): String? = if (packet.startsWith("OWNER,")) "OWNER:OK" else null
}
