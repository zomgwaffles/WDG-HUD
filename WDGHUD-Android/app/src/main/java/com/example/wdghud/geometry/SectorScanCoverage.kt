package com.example.wdghud.geometry

/**
 * G5A38 local AP coverage: full 25-sector lookahead, including diagonal corners. This class never fetches data.
 */
object SectorScanCoverage {
    const val REQUIRED = 25

    fun requiredIds(center: SectorId): List<SectorId> =
        WdgWarsGrid.window(center, 2)

    /** Missing, expired or invalid cached details are reported as unscanned. */
    fun missingIds(center: SectorId, isStoredAndValid: (SectorId) -> Boolean): List<SectorId> =
        requiredIds(center).filterNot(isStoredAndValid)
}
