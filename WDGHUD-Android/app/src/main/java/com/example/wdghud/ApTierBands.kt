package com.example.wdghud

/** Ten display tiers, ten AP levels per tier. Keep raw 1..255 levels. */
internal object ApTierBands {
    const val MAX_TIER = 10

    fun fromLevel(level: Int): Int {
        require(level in 1..255) { "AP level must be between 1 and 255" }
        // Future levels >100 are included in the highest tier, never discarded.
        return ((level - 1) / 10 + 1).coerceAtMost(MAX_TIER)
    }
}
