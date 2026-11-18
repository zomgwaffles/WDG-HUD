# WDG // HUD

**Offline-first ESP32-S3 watch HUD + Android companion for [WDGWars](https://wdgwars.pl/).**

> **Project status:** Active development / pre-release. The project is functional on the maintainers' test hardware; it has **not** passed a clean-install, third-party v1.0 acceptance cycle. Android's current `versionName = "1.0"` is a Gradle setting, **not** a published v1.0 release.

WDG // HUD shows nearby WDGWars geographic-sector intelligence on a [Waveshare ESP32-S3 Touch AMOLED 2.06-inch](https://www.waveshare.com/esp32-s3-touch-amoled-2.06.htm) watch. An Android companion supplies phone GPS and heading, authenticated WDGWars data, persistent history, and secure Bluetooth Low Energy (BLE) synchronization. The watch can continue showing its **last validated data** when the phone, internet, or BLE link is unavailable.

This is a community project, not an official WDGWars application. Respect WDGWars service requirements and local privacy laws while collecting location/radio data.

## What it does

- **3×3 driving HUD:** north-up sector grid, nearby AP totals/levels, gang-color boundaries, and one GPS-bearing direction arrow.
- **5×5 inspection:** swipe left to view the *same GPS center* and surrounding 25-sector cache; opening it does not start another 7×7 scan.
- **Sector Intel:** inspect previously obtained sector information.
- **OPERATOR / GANG:** account information with last-good snapshots. GANG shows rank, member count, Wi-Fi, BLE, total, and credits; there is no separate user-facing MEMBERS page.
- **SETTINGS:** LOW/MED/HIGH brightness; AP staleness interval from 1–240 minutes (30 by default); optional microphone visualizer settings.
- **Automatic driving session:** starts when the watch successfully connects, with foreground/background GPS and BLE handoff on Android.
- **Offline-first retention:** cumulative Android sector history, permanent geographic anchors, and watch-side last-good persistence. Missing or failed requests must not be represented as zero APs.

## Requirements

| Component | Supported development target |
| --- | --- |
| Watch | Waveshare ESP32-S3 Touch AMOLED 2.06-inch, CO5300, 410 × 502 display |
| Watch firmware | PlatformIO, Espressif32 / Arduino framework, project-supplied pin configuration |
| Phone | Android with BLE and GPS; tested during development using a Samsung A15 5G |
| Android build | Android Studio + project Gradle wrapper; Android SDK compatible with `compileSdk 37` |
| Account/data | WDGWars account and API key; WDGWars website login may be required for authenticated sector-detail requests |

The watch and companion are **both required** for live data. XIAO ESP32-C5 wardriving nodes, if used separately, are **not** part of the watch firmware build.

## Get started

1. Follow [Installation](docs/INSTALLATION.md) for both firmware and Android.
2. Follow the [User Guide](docs/USER_GUIDE.md) for WDGWars authentication, optional public profile card, watch pairing, and navigation.
3. Run the checks in [Testing](docs/TESTING.md) before relying on offline cache behavior.

Developer resources: [Architecture](docs/ARCHITECTURE.md) · [Watch display geometry](WDGHeadsUp/docs/WATCH_DISPLAY_GEOMETRY.md) · [Watch project README](WDGHeadsUp/README.md) · [Changelog](CHANGELOG.md) · [Licensing](docs/LICENSING.md) · [Release checklist](docs/RELEASE_CHECKLIST.md) · [Security](SECURITY.md).

## Repository layout

```text
WDG-HUD/
├── WDGHeadsUp/                ESP32-S3 watch firmware (PlatformIO)
│   ├── platformio.ini
│   ├── src/                    BLE, display, touch, cache and boot logic
│   ├── include/
│   ├── lib/                    Bundled driver library (with its own license)
│   └── docs/WATCH_DISPLAY_GEOMETRY.md
├── WDGHUD-Android/            Android companion (Kotlin / Compose / Gradle)
│   ├── app/src/main/           UI, BLE service, GPS, API and storage
│   └── app/src/test/           JVM unit tests
├── docs/                       Installation, use, architecture, licensing and testing
├── LICENSE                     GPL version 3, no later-version option for original project code
├── THIRD_PARTY_NOTICES.md      Bundled and externally resolved dependencies
└── CHANGELOG.md
```

## Known constraints and publication status

- WDGWars reports **0.02° latitude × 0.02° longitude** sector spacing (verified from the live `member-territories` response during G5A49.2). The grid follows latitude/longitude lines and their intersections; extreme pole/dateline behavior has not been acceptance-tested.
- A clean-room hardware install, authenticated pairing downgrade test, account-switch watch-cache behavior, and extended offline return-trip testing are still release gates. See [Release checklist](docs/RELEASE_CHECKLIST.md).
- No public APK/firmware release binaries are published yet; build from source.
- **License:** Original WDG // HUD project code is offered under [GPL-3.0-only](LICENSE). Third-party components retain their own license terms, copyright and notices; see [Third-party notices](THIRD_PARTY_NOTICES.md) and [Licensing guide](docs/LICENSING.md). Full dependency / distribution-notice verification remains a release gate.

## Contributing / questions

For now, capture issues with a reproducible build revision, device/Android version, relevant redacted logs, expected and actual behavior. Do not include WDGWars API keys, pairing codes, authenticated cookies, precise driving routes, or personal account data in public reports. Read [Security](SECURITY.md) before sharing diagnostics.
