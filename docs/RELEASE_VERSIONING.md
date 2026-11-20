# Release version policy — G5A50.3b

**Status:** Android public release identity is configured; private candidates progressed to **RC6 (10005 / 1.0.0-rc6)**. Public v1.0 is **not** approved or published.

**G5A50.5f note:** The RC6 version is a **private signed test artifact**, not the checked-in release Gradle identity or a public version tag. The source baseline is `662f93c`; prior candidate builder scripts enforce historical Git SHA checks. Preserve the existing release signing certificate and public application ID during upgrades.

- Engineering `G5Axx` labels are not public semantic versions.
- **Debug:** `com.example.wdghud`, `versionCode=1`, `versionName=1.0`, existing debug signing. This stays on the current phone with all locally stored data.
- **Release:** `io.github.zomgwaffles.wdghud`, `versionCode=10000`, `versionName=1.0.0`, signed manually with the separate release keystore. These values are applied in `androidComponents.onVariants` to the release variant only; Kotlin source namespaces stay `com.example.wdghud`.
- First public Git tag should be `v1.0.0` only when release gates pass. Pre-release builds must be labeled as candidates and kept private until validated.
- Subsequent versions of the **same release application ID** must increment `versionCode` and must use the **same signing certificate** (or a verified supported key rotation). Do not recreate the release keystore after publication.
- Android package IDs define separate app sandboxes. The release APK cannot read the debug app's WDGWars key, profile preferences, or cumulative AP history. **No supported cross-identity migration exists.** The release may coexist with the debug app.
- Do not uninstall or clear the existing debug app to test this release variant. Fresh-install and signed-release-upgrade checks must use a separate test device/profile and test data only.
- Watch firmware and Android companion must remain protocol compatible (`ACCOUNT1` capability for personal data). Record the source SHA, firmware version and APK hash with each actual release.
- Deliverable checklist: signed APK, firmware `.bin`, SHA-256 sums, corresponding GPLv3 source, license notices, hardware compatibility, upgrade/data-retention instructions, and approved release notes.

The signing-drill script is intentionally **not** a publishing tool. It signs only an artifact whose *packaged* manifest reports the exact public ID and version. The resulting drill artifact must not be installed or published until independent acceptance is complete.
