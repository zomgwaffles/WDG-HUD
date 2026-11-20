# G5A50.5f — Pre-release notes (not a public v1.0 release)

**Source checkpoint:** [`662f93c`](https://github.com/zomgwaffles/WDG-HUD/commit/662f93c), 2026-11-19.  
**Documentation:** Release-preparation pass, 2026-11-20.  
**Hardware:** Waveshare ESP32-S3 Touch AMOLED 2.06, 410 × 502, and Android companion.  
**Public release status:** **NOT RELEASED.** These notes do not approve publishing a v1.0 tag or private RC APK.

## What changed since the older documentation

- **Watch HUD:** approved 3×3 live sector grid; 5×5 is a 25-sector inspection of the **same** GPS-selected center, without triggering 7×7 lookahead. The visible 5×5 watch layout shows **AP totals only**. Validated last-good sector snapshots remain visible on offline reboot.
- **Settings:** LOW / MED / HIGH brightness and AP-detail freshness selections **30M / 60M / 240M**. The default is **60M** in G5A50.5f. A selection makes an old record *eligible* for refresh; it does not issue a periodic request unconditionally.
- **Refresh separation:** ownership, GANG, and Operator/profile use independent **at least hourly** refresh rules. Failed refreshes do not erase last-good values or mark them newly fresh.
- **Visualizer:** dedicated three-state settings page: OFF hides mode/color, SOLID shows eight swatches and a visible cyan/white selected outline, RAINBOW hides swatches and displays its equalizer preview. Font colors remain fixed; selected buttons have dark text on cyan.
- **Navigation:** swipe left from 3×3 to 5×5 and swipe right back; tap sector for Intel; swipe up for OPERATOR; gang-name tap for GANG; gear for SETTINGS; tap VISUALIZER button for its page; tap anywhere in GANG to return to OPERATOR.
- **BLE:** disconnected-only short BOOT press restarts watch advertising, not a reset/unpair. Android uses a six-digit system pairing dialog for new bonds.
- **Android:** private signed RC6 (`io.github.zomgwaffles.wdghud`, `versionCode=10005`, `versionName=1.0.0-rc6`) was used to verify watch BLE settings; both 30M and 60M notifications were confirmed in maintainer logs. 240M appeared functional but the explicit log check was not recorded. RC5 ownership-refresh radius issue was fixed before RC6 testing.
- **Developer checkpoint:** the working watch/Android tree was committed as `662f93c`; no new code behavior was introduced by this documentation package.

## Release artifacts and acceptance status

- The **illustration** is in [`images/watch-interaction-map.png`](images/watch-interaction-map.png), with a [single-page PDF](images/watch-interaction-map.pdf). It is not a screenshot and does not expose private telemetry or signing credentials.
- **Not yet independently accepted:** clean installation/pairing on another supported phone/watch, two-account **A → B → A** privacy isolation test, hours-long real interval cadence/240M log evidence, extended return-trip/offline behavior, dependency/notice audit, signed public artifact verification, secret-history check, and packaging checksums.
- **Distribution plan:** direct APK/firmware sideloading only (not Play Store). Do not publish the private test-only RC APKs as a release.

See [`USER_GUIDE.md`](USER_GUIDE.md), [`INSTALLATION.md`](INSTALLATION.md), [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md), and [`RC_ACCEPTANCE.md`](RC_ACCEPTANCE.md). This is developer/maintainer-reported evidence, **not independent certification**.
