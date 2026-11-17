# Testing and regression guide

**Rule:** Separate (1) source review, (2) host unit tests, (3) complete compilation, (4) installed-phone/watch hardware tests and (5) field tests. A Gradle or PlatformIO PASS is not a radio-security or offline-cache acceptance result.

## Record a reproducible baseline

```powershell
# Run these commands from the repository root:
git status -sb
git rev-parse --short HEAD
```

Do not reset/force-push/clear data to resolve a basic test failure. If you have local modifications, inspect them and back up the working state first.

## Android host build and tests

From `WDGHUD-Android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

To install on a connected, authorized debug phone:

```powershell
.\gradlew.bat :app:installDebug
```

The Gradle daemon requests Java 25. Point `JAVA_HOME` to your installed JDK or choose the proper Gradle JDK in Android Studio. Unit tests cover grid boundaries, window planning, cache freshness, BLE helpers and other pure logic. **Unit tests do not execute the complete BLE/GPS/HTTPS workflow on physical devices.**

## Watch compilation and native tests

From `WDGHeadsUp`:

```powershell
pio run -e esp32-s3-devkitc-1
pio test -e native
```

Native tests need an installed host C++ compiler. To upload to hardware:

```powershell
pio run -e esp32-s3-devkitc-1 -t upload
```

Use the actual detected COM port if the uploader cannot find the board. Preserve watch NVS for normal update testing.

## Hardware smoke checks (phone + watch)

- [ ] Boot watch with its existing snapshots intact; 3×3 opens correctly on the 410×502 panel.
- [ ] A new Bluetooth bond requires a six-digit watch-displayed PIN and Android system pairing; no silent/insecure fallback.
- [ ] A ready connection automatically shows **SESSION ACTIVE** on Android (G5A47).
- [ ] BLE link health responds to idle PING/PONG and reconnects after a radio drop.
- [ ] Minimize Android: its service takes over; reopen: Activity retakes BLE; exactly one GATT writer.
- [ ] Phone GPS fix and course-over-ground reach watch and update center/heading when valid.
- [ ] 3×3 borders, arrow, status icons and progress bar have no visual regressions.
- [ ] 5×5 uses the same header/footer/GPS center and cached data; opening it does not trigger a new 7×7 scan.
- [ ] Sector Intel opens/back without clearing any adjacent cached tile.
- [ ] OPERATOR and GANG show correct stored values; GANG has RANK/MEMBERS counts, no separate MEMBERS page.
- [ ] SETTINGS brightness and 1–240-minute stale-age choices survive watch restart.
- [ ] WDGWars website login no longer shows retired AP-level diagnostic buttons (G5A48).
- [ ] A saved profile card displays the chosen user's card, persists after app restart and can be removed (G5A49.1).

## Data-integrity and field scenarios

- [ ] With mobile data disabled, GPS crossing into adjacent coordinate sectors recenters using local geometry; no network request is required for the crossing decision.
- [ ] Drive/record multiple 25-sector windows, return to an old region, verify historical AP data is immediately restored.
- [ ] Interrupted sync, failed API, missing/water sector or no APs does **not** erase a different valid sector.
- [ ] Stale settings changed 30 → 5 → 60 minutes affect eligibility, not original timestamps or historical data.
- [ ] API successful timestamps advance only after validated complete responses; failures do not mark data fresh.
- [ ] Watch offline after power loss/reboot still shows last-good 3×3, OPERATOR and GANG summaries.
- [ ] GANG data refreshes while Android is running, without a partial response overwriting the complete saved state.
- [ ] Switching WDGWars accounts does not expose the prior user's watch-side GANG summary; **currently a known release gap**.
- [ ] Sustained outdoor tests validate GPS direction slices near all eight headings and repeated BLE recovery.
- [ ] Explicit dateline/pole and international coverage acceptance scenarios are recorded before claiming worldwide robustness.

## G5A49.2 verified geometry baseline

Maintainer-reported live `/api/member-territories` metadata: `grid_lat = 0.02`, `grid_lng = 0.02` (2026-11-17). The added `WdgWarsBoundaryRegressionTest.kt` passed locally along with the Gradle test suite. Keep the production coordinate model unchanged absent a reproducible mismatch. **Do not confuse passing unit tests with a GPS field track at exact physical boundaries.**

## Good bug reports

Include commit hash, Android version/model, watch board/firmware build, exact steps, expected vs actual, and sanitized build output. Relevant Android Logcat categories include `WDGHUD_BLE`, `WDGHUD_GEO`, `WDGHUD_CACHE` and `WDGHUD_PREFETCH`. Never share API keys, passkeys, session cookies, full coordinates, personal routes or unredacted account data.

**Release gate:** Use [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) and document any failures before tagging a release.
