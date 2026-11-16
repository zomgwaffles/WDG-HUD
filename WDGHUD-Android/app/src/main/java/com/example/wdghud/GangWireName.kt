package com.example.wdghud

/** Wire-safe official gang labels. Legacy CELL names remain 20 bytes max. */
internal object GangWireName {
    const val MAX_CHARS = 20
    const val FULL_NAME_CHARS = 96
    const val CHUNK_SIZE = 24

    private fun sanitize(listed: Boolean, gangId: Int, officialName: String?): String {
        if (!listed) return "UNKNOWN"
        if (gangId <= 0) return "UNCLAIMED"
        val source = officialName?.trim().orEmpty()
        if (source.isEmpty()) return "UNKNOWN"
        val ascii = buildString {
            for (character in source) {
                append(if (character.code in 32..126 && character != ',') character else '?')
            }
        }.trim()
        return ascii.ifEmpty { "UNKNOWN" }
    }

    fun encode(listed: Boolean, gangId: Int, officialName: String?): String {
        val ascii = sanitize(listed, gangId, officialName)
        return if (ascii.length > MAX_CHARS) ascii.take(MAX_CHARS - 1) + "~" else ascii
    }

    fun encodeFull(listed: Boolean, gangId: Int, officialName: String?): String =
        sanitize(listed, gangId, officialName).take(FULL_NAME_CHARS)

    /** Send every sector name (including UNKNOWN) for atomic G5A36 transfers. */
    fun namePackets(index: Int, listed: Boolean, gangId: Int, officialName: String?): List<String> {
        require(index in 0..8)
        val chunks = encodeFull(listed, gangId, officialName).chunked(CHUNK_SIZE)
        return chunks.mapIndexed { part, text -> "NAME,$index,$part,${chunks.size},$text" }
    }

    /** Drop the optional sixth field for watches that did not advertise GANG. */
    fun forWatch(cell: String, supportsNames: Boolean): String =
        if (supportsNames) cell else cell.substringBeforeLast(',')
}
