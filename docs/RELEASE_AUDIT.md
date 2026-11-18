# WDG // HUD — G5A50.3b Pre-v1.0 release audit

**Current source baseline:** GitHub `main` at `236657824ac3c672d3f489441ab8bd203e4e4953` (G5A50.3a, 2026-11-18 UTC). Source review, not a clean checkout or independently reproduced release build.
**Scope:** Source, packaging configuration, public docs, Android backup defaults. No clean-room installation, secret-history scan, independent penetration test, or external device validation was performed as part of this source audit.

## Release decision

**Not ready to tag/publicize v1.0.** The existing hardware and test reports establish a working development baseline, not public release acceptance.

## Confirmed gaps / required actions

| Priority | Area | Source observation | Release action |
| --- | --- | --- | --- |
| Addressed | Android backup and transfer | G5A50.1 added explicit backup/transfer opt-out and maintainer reported successful build/manifest review and in-place upgrade | Retain privacy behavior in fresh-install / D2D acceptance tests |
| Addressed in G5A50.2 package | Public license | Maintainer selected GPL-3.0-only for original WDG // HUD code | Add root `LICENSE`; retain independent upstream licensing; complete resolved/transitive dependency and distribution-notice review |
| High | Release signing | G5A50.3a signing drill reported successful; G5A50.3b updates it to verify a **distinct** public application ID and version; full public build remains untested | Run local drill, record certificate fingerprint privately, then configure and verify a distinct public signed build |
| High | Account switching | G5A49.4d account OWNER + scoped NVS merged; real A→B→A hardware test not recorded | Exercise two-account switch including reboot during incomplete transfer; do not clear NVS to make tests pass |
| High | New-user install | Existing phone/watch were used for most development tests | Independent fresh install/pairing/login on supported hardware, with no retained cookies, bonds or cache |
| Medium | Documentation | Security and release checklist still describe the now-fixed unscoped GANG defect; G5A49.3 evidence outdated | Update to describe implementation versus unverified two-account hardware acceptance |
| Medium | CI and dependency pinning | No checked-in GitHub Actions workflows; watch PlatformIO dependencies are partly floating | Pick supported CI toolchain and add repeatable builds/tests; lock dependency resolution before publishing binaries |
| Medium | Distribution metadata | G5A50.3b preserves debug ID `com.example.wdghud` (`1` / `1.0`) and configures release ID `io.github.zomgwaffles.wdghud` (`10000` / `1.0.0`) via variant API | Freeze new public ID and version before first public build; preserve existing debug installation/data |
| Medium | Repository hygiene | `.idea` IDE files and historical 21-sector protocol drafts remain tracked | Determine what belongs in release source vs historical archive; historical docs must be clearly labeled |
| Medium | Privacy/history scan | No comprehensive Git history/signing-key/credential scan completed | Inspect *history* and binaries; sanitize any real leaked secret and rotate it rather than merely deleting the current file |
| Medium | Geographic edge cases | 0.02-degree grid verified; polar/dateline/exact-boundary field testing not complete | Document unsupported edges; validate test vectors and GPS headings |

## G5A50.2 licensing scope

- Selected license for original project code: GNU GPL version 3 only, with unmodified license text in root `LICENSE`.
- Preserve separate copyrights and terms for bundled Arduino DriveBus (GPLv3), Arduino_GFX (`license.txt`, BSD-style), NimBLE-Arduino (Apache-2.0), AndroidX and other platform dependencies.
- `THIRD_PARTY_NOTICES.md` records primary dependencies and source locations, **not** a complete final resolved dependency bill of materials. The latter is still required before public binary distribution.
- No Android `applicationId` change, no signing key, no build or runtime changes, no declaration that public v1.0 is ready.

## G5A50.1 patch boundaries

Changed: AndroidManifest.xml, XML cloud/transfer backup rules, SECURITY.md, TESTING.md, RELEASE_CHECKLIST.md; new RELEASE_AUDIT.md.

Unchanged: all Kotlin source, watch C++, account-scoped NVS, BLE protocol, GPS/grid math, AP history, UI layout, local databases, version numbers and signing.

**Privacy tradeoff:** Automatic cloud/D2D backup is intentionally disabled for app data. This protects locally accumulated location-related sectors and device-bound credential blobs from unintended migration, but users cannot rely on Google backup or phone migration to carry their AP history. A future opt-in export/import would need explicit encryption, consent and compatibility rules.

## Test sequence and acceptance

1. Run `:app:testDebugUnitTest` and `:app:assembleDebug`.
2. Verify the *merged* debug manifest has `android:allowBackup="false"`, `@xml/backup_rules` and `@xml/data_extraction_rules` (the source manifest alone does not prove the manifest merger result).
3. Update the existing Android installation **without uninstalling or clearing data**. Verify API credential, sector cache count and profile-card preference remain, and watch reconnect/offline preservation still work.
4. Run one fresh-install test later on a secondary Android device; grant permissions and establish a new secure bond.
5. Do not tag v1.0 until the final third-party audit, signing, two-account switch and fresh-install criteria are resolved.

## Reference

Android's official Auto Backup documentation says cloud backup generally includes application shared preferences and private files by default; on some devices `allowBackup=false` does not block device-to-device transfer, requiring explicit extraction rules. See https://developer.android.com/identity/data/autobackup .

## G5A50.3a signing/identity boundary

G5A50.3a adds docs and PowerShell tooling only: `tools/release/New-ReleaseKey.ps1` and `tools/release/Build-SigningDrill.ps1`. It does **not** change Gradle, source code, APK applicationId, UI, BLE, GPS, AP cache, watch firmware or signing configuration. Never publish/install the drill artifact. See `docs/RELEASE_SIGNING.md` and `docs/RELEASE_VERSIONING.md`.

## G5A50.3b public-identity configuration

- Production source/manifest/UI/watch remain untouched; only the Android Gradle release variant's identity/version changes.
- Existing debug ID `com.example.wdghud` and installed data remain intact. The separate public ID has its own sandbox and credentials; no migration is provided.
- Updated `Build-SigningDrill.ps1` checks exact packaged release ID/version with Android SDK `aapt` before signing, then verifies signing and emits SHA-256 hashes. No device installation, publishing, or credentials in Git.
- Actual Gradle build, signing-drill rerun, fresh-device setup, and signed upgrade are still **pending**; do not mark v1.0 ready.
