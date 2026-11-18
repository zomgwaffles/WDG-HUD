# Changelog — WDG // HUD

This is a **development-history summary**, not a claim of published GitHub releases. Version identifiers `G5Axx` are internal accepted milestones. Android's current `versionName = "1.0"` should not be treated as proof of a published public v1.0. Entries through G5A46.2 derive from the engineering handoff and were checked against the current project structure; later milestones also reflect maintainer-reported local tests.

## Unreleased — G5A50.2 (licensing and release metadata, 2026-11-18)

- Selected GPL-3.0-only for original project code; added unmodified root GPLv3 text and third-party dependency notices.
- Updated release/security/architecture documentation without changing app ID, signing, firmware, Android behavior or UI.
- Remaining gates: final resolved third-party audit, release signing, fresh install and two-account switch.

## G5A50.1 — Android privacy hardening (2026-11-18)

- Disabled automatic cloud backup and attempted to exclude app-private data from device-to-device transfer.
- Updated security and release acceptance documents; maintainer reported Android tests/build and in-place upgrade passing.

## G5A49.4d — Account-scoped BLE and NVS (2026-11-18)

- Added `ACCOUNT1` / `OWNER` BLE handshake, account-fenced Android transfers, account-scoped watch OPERATOR/GANG snapshots and corresponding host tests.
- Maintainer reported successful same-account synchronization, offline reboot and background BLE tests; real A → B → A hardware test remains pending.

## G5A49.3 — Documentation (2026-11-17)

- Replaced the two-line root README and obsolete firmware-overlay README with current source-build guides.
- Added installation, user, architecture, testing, security, and release-preparation documentation.
- **Documentation only**; no Kotlin, C++, Gradle, device configuration, or application-version changes.

## G5A49.2 — Sector-boundary verification (2026-11-17)

- Added boundary regression tests covering corners, negative coordinates, numeric tolerance and 3×3/5×5 relationships.
- Maintainer confirmed live WDGWars `grid_lat = 0.02` and `grid_lng = 0.02` values.
- Production coordinate geometry left unchanged; worldwide polar/dateline cases remain release checks.

## G5A49.1 — Public profile-card portability (2026-11-17)

- Removed an embedded developer-specific public profile-card URL.
- Added optional per-account profile-card URL save/remove in Android WDGWars access UI.
- Updated stale-cache expiration regression tests for 30-minute default.
- Maintainer reported unit tests and local verification passing.

## G5A48 — WDGWars login cleanup (2026-11-17)

- Removed obsolete logged-in AP-level test controls from the WDGWars login UI.
- Kept sector queries, app authentication, and watch transfer logic.

## G5A47 — Driving-session automation (2026-11-17)

- Automatically starts the Android driving service once the watch BLE connection is ready.
- Maintainer confirmed compilation and phone/watch tests for startup and background BLE/GPS handoff.

## G5A46.2 — Accepted GANG display alignment (2026-11-16)

- Inward-aligned GANG identity and lower statistic columns without changing overall screen design.
- Protected existing OPERATOR and HUD layouts.

## G5A45–G5A46 (development milestones)

- Introduced authenticated BLE account/profile/GANG transfers and persistent last-good GANG state.
- Restored the accepted OPERATOR screen after an earlier redesign was rejected.
- Replaced visible TEAM/MEMBERS navigation with one **GANG** screen.
- Added LOW/MED/HIGH brightness and configurable stale-age settings.

## G5A39–G5A44 (development milestones)

- Added GPS-driven offline sector recentering and shared 25-sector lookahead/inspection.
- Added cumulative Android historical AP storage and permanent sector anchors.
- Added BLE heartbeat/recovery, foreground/background ownership discipline and authenticated pairing/bonding.
- Improved progress signaling and protected validated snapshots from partial updates.

## G5A30–G5A38 (early accepted milestones)

- Established 3×3 cyan HUD, consistent gang-color tile borders, 8-way bearing arrow, scan ticker, operator profile display, visualizer, and last-good watch NVS snapshots.

## Release status

No public v1.0 tag or release artifacts have been issued as part of this documentation pass. See [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md) for unresolved pre-release acceptance criteria.
