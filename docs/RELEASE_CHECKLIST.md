# Pre-v1.0 release checklist

**Status:** Not yet satisfied. This file is a release gate, not an announcement. Do not publish a v1.0 tag or binaries just because Android currently reports `versionName = "1.0"`.

## A. Source and reproducibility

- [x] Latest release-audit `main` SHA recorded: `6885e6dfc994b755761e15f2781e9092d1565e85` (G5A49.4d, 2026-11-18 UTC). This records GitHub baseline, not a fresh-checkout build.
- [ ] Android unit suite and `:app:assembleDebug` pass on a clean checkout.
- [ ] Watch PlatformIO build and native logic tests pass on a clean checkout.
- [ ] Fresh firmware flashing and fresh Android install are demonstrated on compatible, independent hardware (not only the maintainer's existing caches and bonds).
- [ ] Release build/signing configuration, artifact hashes and versionCode/versionName policy are chosen and tested.
- [ ] A public license is chosen for the project; bundled third-party components' licenses and notices are reviewed.
- [ ] CI/build automation and dependency-pinning approach are decided (CI is not yet part of the checked-in repository).

## B. End-to-end usability

- [ ] A first-time user can follow INSTALLATION.md without help from the developer.
- [ ] Android permissions, WDGWars API authentication, website login and BLE PIN pairing work from a clean state.
- [ ] Automatic session startup, background BLE/GPS handoff and recovery pass on more than one Android version/model.
- [ ] OPERATOR, GANG, 3×3, 5×5, Intel and SETTINGS display correct data and preserve approved layout.
- [ ] Saved profile-card URL is optional, user-configurable and does not leak across API accounts.

## C. Data integrity and security

- [ ] Clear separation verified between **unknown**, **stale-but-valid**, and **confirmed empty** sectors.
- [ ] Existing 3×3, OPERATOR and GANG last-good snapshots survive watch restart, mobile-data loss, app switching and repeated BLE reconnects.
- [ ] Long offline drive and return trip demonstrate cumulative AP history, proper freshness age and 5×5 window reuse.
- [ ] Failed API fetch never writes a new successful-sync timestamp or erases valid historical AP/GANG state.
- [ ] Real A → B → A switch, including reboot and interrupted transfers, cannot expose the previous account's GANG/OPERATOR data. G5A49.4d implemented scoped NVS and authenticated OWNER handshake; maintainer's single-account offline test passed, but two-account hardware acceptance remains pending.
- [ ] Physical secure pairing and downgrade rejection confirmed; no unauthenticated command acceptance.
- [ ] Credentials, API keys, signing material, cookies and private location traces are absent from the released source and binaries; repository *history* is also reviewed.
- [ ] Android Auto Backup / device-to-device transfer behavior reviewed. G5A50.1 supplies explicit opt-out rules; verify the merged manifest on physical supported devices and document loss of cross-device automatic migration.

## D. Geographic accuracy

- [x] Maintainer reported current WDGWars `grid_lat = grid_lng = 0.02` on 2026-11-17.
- [x] G5A49.2 coordinate/boundary regression tests passed locally.
- [ ] Validate exact-boundary movement tracks, negative coordinates, dateline and polar behavior on representative data; document unsupported edge cases explicitly.
- [ ] Verify 8-way GPS course-over-ground arrows across field headings without stationary-bearing assumptions.

## E. Documentation and distribution

- [ ] Review README, installation instructions, troubleshooting and user guide on a **fresh checkout** after merging documentation.
- [ ] Ensure older G4A protocol-design docs are identified as historical (they currently contain superseded 21-sector references).
- [ ] Choose how to handle third-party contributions, issue reports and responsible security disclosures.
- [ ] Publish signed APK and watch firmware artifacts if distribution is intended, with checksums, supported-hardware list, release notes and upgrade/data-retention instructions.
- [ ] Write a real GitHub Release only when above gates are satisfied or explicitly list beta limitations.

## Evidence log

| Milestone | Evidence |
| --- | --- |
| G5A47 | Maintainer reported Android build plus automatic start and background BLE/GPS hardware tests passing |
| G5A48 | Maintainer reported diagnostic login button removal working |
| G5A49.1 | Maintainer reported unit and local app tests passing after profile-card change |
| G5A49.2 | Maintainer reported tests passing; live grid metadata 0.02 × 0.02 |
| G5A49.3 | Documentation committed on `main` |
| G5A49.4b/c | Maintainer reported Android/watch builds and test suites passing; legacy cleanup and canonical grid planner merged |
| G5A49.4d | Maintainer reported Android/watch builds and native tests passing; account-scoped protocol installed and same-account/offline reboot/background BLE hardware smoke checks passed; real A → B → A remains pending |
| G5A50.1 | Privacy backup hardening proposed for verification; not yet built, merged, or device-tested |

This table records reported evidence, **not** an external laboratory certification.
