# Release version policy — G5A50.3a proposal

**Status:** Proposed policy, not an Android or firmware version-number change.

1. `G5Axx` labels are engineering milestones, **not** public semantic releases.
2. First public tag, only after the release checklist passes: `v1.0.0`. Prior betas, if shipped, should use clearly marked prerelease tags.
3. Proposed Android public `versionName`: `1.0.0`; proposed first public `versionCode`: `10000`. Every publicly distributed update must increment `versionCode` for the same application ID. Neither value is active yet.
4. Proposed public Android application ID: `io.github.zomgwaffles.wdghud`. Freeze the application ID and signing key together **before** the first signed public installation. This is currently a proposal, not a code change.
5. Keep the existing development app ID `com.example.wdghud` and its debug signing environment intact until migration is deliberately designed. A separate public ID creates a separate installation and data sandbox; it cannot see existing AP history or encrypted API credentials.
6. Watch and Android must remain protocol-compatible. A public release should record the watch build commit SHA, a watch firmware version, the Android version, and the BLE capability minimum (`ACCOUNT1` is currently required for personal data).
7. Public deliverables should contain signed APK (when enabled), firmware `.bin`, SHA-256 checksums, GPLv3 corresponding source, license and attribution texts, compatibility notes, and clear upgrade/rollback warnings. Never publish keystore, secret signing passwords, API keys or location traces.
8. Do not tag or publish `v1.0.0` merely because the current Android development Gradle file already says `versionName="1.0"`.

**Next phase:** Configure public variant + immutable identity and implement a signed-APK upgrade acceptance test. Local testing of G5A50.3a tooling must pass first.
