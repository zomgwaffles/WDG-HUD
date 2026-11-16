# WDG // HUD E5.17 — Known bugs / deferred

## BUG-G3C5 — intermittent Android WebView sector-detail timeout

- **Status:** Deferred by user; non-blocking. No code changes authorized by this note.
- **Component:** Android foreground-only authenticated 21-sector prefetch.
- **Observed:** `20/21` validated; sector `SectorId(north=1964,east=-3827)` (`39.29,-76.53`) timed out after first 12-second attempt and 20-second retry.
- **Contrast:** The same exact URL loaded in Firefox Network tools in approximately **171 ms**: `https://wdgwars.pl/api/cell-detail?lat=39.29&lng=-76.53`.
- **Known working:** WebView bridge, serial requests, retry and cache validation; Android unit tests and assembleDebug were successful on user's machine.
- **Impact:** Some background prefetch sectors occasionally remain unknown. Do not invent AP totals for missing sectors.
- **Possible later experiment:** introduce a 1-second pause between sequential requests; investigate WebView cookie/session handling and request/response callbacks if needed. Note requests are *already sequential*; delay is between requests, not a concurrency fix.
- **Exit criteria:** repeated coverage runs achieve valid 21/21, or failures produce independently diagnosable status without delaying 3x3 operation. Revisit after BLE viewport work.
