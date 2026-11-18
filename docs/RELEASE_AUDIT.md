# WDG // HUD — G5A50.1 Pre-v1.0 release audit

**Baseline reviewed:** GitHub `main` at `6885e6dfc994b755761e15f2781e9092d1565e85` (G5A49.4d, 2026-11-18 UTC).  
**Scope:** Source, packaging configuration, public docs, Android backup defaults. No clean-room installation, secret-history scan, independent penetration test, or external device validation was performed as part of this source audit.

## Release decision

**Not ready to tag/publicize v1.0.** The existing hardware and test reports establish a working development baseline, not public release acceptance.

## Confirmed gaps / required actions

| Priority | Area | Source observation | Release action |
| --- | --- | --- | --- |
| High | Android backup and transfer | Manifest has `allowBackup=true`; `backup_rules.xml` and `data_extraction_rules.xml` contain only unconfigured templates | G5A50.1 opt-out patch; build/manifest verification and in-place upgrade test |
| High | Public license | No project-wide `LICENSE` in GitHub tree | Maintainer chooses a license and reviews bundled Driver Bus library notices; do not infer permissions from repository visibility |
| High | Release signing | Debug build tested; no finalized signing workflow or publicly verifiable APK/checksums | Set signing policy, protect private key, reproducible release build and hashes |
| High | Account switching | G5A49.4d account OWNER + scoped NVS merged; real A→B→A hardware test not recorded | Exercise two-account switch including reboot during incomplete transfer; do not clear NVS to make tests pass |
| High | New-user install | Existing phone/watch were used for most development tests | Independent fresh install/pairing/login on supported hardware, with no retained cookies, bonds or cache |
| Medium | Documentation | Security and release checklist still describe the now-fixed unscoped GANG defect; G5A49.3 evidence outdated | Update to describe implementation versus unverified two-account hardware acceptance |
| Medium | CI and dependency pinning | No checked-in GitHub Actions workflows; watch PlatformIO dependencies are partly floating | Pick supported CI toolchain and add repeatable builds/tests; lock dependency resolution before publishing binaries |
| Medium | Distribution metadata | Android package is `com.example.wdghud`, `versionCode=1`, `versionName=1.0`; no releases listed | Decide permanent application ID and semver/version policy before public distribution (package rename later loses upgrade continuity) |
| Medium | Repository hygiene | `.idea` IDE files and historical 21-sector protocol drafts remain tracked | Determine what belongs in release source vs historical archive; historical docs must be clearly labeled |
| Medium | Privacy/history scan | No comprehensive Git history/signing-key/credential scan completed | Inspect *history* and binaries; sanitize any real leaked secret and rotate it rather than merely deleting the current file |
| Medium | Geographic edge cases | 0.02-degree grid verified; polar/dateline/exact-boundary field testing not complete | Document unsupported edges; validate test vectors and GPS headings |

## G5A50.1 patch boundaries

Changed: AndroidManifest.xml, XML cloud/transfer backup rules, SECURITY.md, TESTING.md, RELEASE_CHECKLIST.md; new RELEASE_AUDIT.md.

Unchanged: all Kotlin source, watch C++, account-scoped NVS, BLE protocol, GPS/grid math, AP history, UI layout, local databases, version numbers and signing.

**Privacy tradeoff:** Automatic cloud/D2D backup is intentionally disabled for app data. This protects locally accumulated location-related sectors and device-bound credential blobs from unintended migration, but users cannot rely on Google backup or phone migration to carry their AP history. A future opt-in export/import would need explicit encryption, consent and compatibility rules.

## Test sequence and acceptance

1. Run `:app:testDebugUnitTest` and `:app:assembleDebug`.
2. Verify the *merged* debug manifest has `android:allowBackup="false"`, `@xml/backup_rules` and `@xml/data_extraction_rules` (the source manifest alone does not prove the manifest merger result).
3. Update the existing Android installation **without uninstalling or clearing data**. Verify API credential, sector cache count and profile-card preference remain, and watch reconnect/offline preservation still work.
4. Run one fresh-install test later on a secondary Android device; grant permissions and establish a new secure bond.
5. Do not tag v1.0 until license, signing, two-account switch and fresh-install criteria are resolved.

## Reference

Android's official Auto Backup documentation says cloud backup generally includes application shared preferences and private files by default; on some devices `allowBackup=false` does not block device-to-device transfer, requiring explicit extraction rules. See https://developer.android.com/identity/data/autobackup .
