# Security and privacy notes — WDG // HUD

This is an experimental, pre-release wardriving companion. **Do not** post API keys, Android signing keys, BLE pairing passkeys, login cookies, account identifiers, private drive tracks or unredacted logs in a public GitHub issue.

## Credentials

- WDGWars credentials are entered into Android, not the watch firmware.
- `MainActivity.kt` uses Android Keystore-backed AES-GCM encryption for its locally persisted API key. The background service accesses the same app-private credential store.
- The public profile-card SVG URL is **optional** and stored per API-key identity. The card itself may expose personal account details; share the URL only if you intend it to be public.
- This repository must not include `.env`, `local.properties`, credentials, release signing keystores, Android backups, or personal location traces. Source review alone cannot guarantee that older Git commits never contained a secret.

## BLE transport

Watch firmware requests LE Secure Connections with user-visible six-digit passkey, authenticated bonding and encrypted characteristics. It is designed to reject unauthenticated commands and prevent two concurrent Activity/Service GATT writers. **These are implementation properties, not an independent security assessment.** The v1.0 checklist requires on-device downgrade/pairing tests.

## Data retention and account switching

Validated sector history persists on the Android device, and last-good watch snapshots persist in NVS across reboot. Since G5A49.4d, the phone sends a verified-username-derived `OWNER` identifier before personal BLE payloads, and the watch stores account-scoped OPERATOR and GANG records. The maintainer reported successful same-account syncing, offline reboot, and background BLE handoff on physical hardware. **A genuine A → B → A account-switch test on hardware is still pending.** Do not claim full cross-account isolation certification until that test passes.

Geographic sector/AP caches are deliberately separate from account-personal display records. Clearing Android storage, uninstalling the app, or wiping watch NVS may destroy local history and saved settings. Test migrations intentionally; do not erase saved state to diagnose an ordinary build problem.

## Android backup and device migration

Release-hardening G5A50.1 disables Android automatic cloud backup and attempts to exclude all application-private data from device-to-device transfer as well. This is deliberate: app storage includes location-related sector history, preferences, and ciphertext tied to a device-bound Android Keystore key. These files should not silently transfer to another phone. Android documents device-manufacturer differences in backup behavior; verify the merged manifest and transfer behavior on supported devices.

**Tradeoff:** Automatic reinstall or phone migration will not recover locally accumulated AP history or preferences. There is no supported export/import workflow yet. Normal in-place APK updates preserve existing local app data; this patch does not delete existing data.

## Reporting security concerns

Until a dedicated private reporting channel is configured, **do not disclose an exploitable vulnerability or a credential in a public issue**. Use a private maintainer contact channel arranged out of band. GitHub private security advisory reporting can be added if the repository owner enables it; this file does not claim that a private report endpoint has been configured.

For harmless reproducible bugs, give build revision, steps, expected/actual behavior and sanitized logs. Avoid precise GPS tracks or account-specific card URLs.

## Pre-release policy decisions still needed

Project license, public security reporting workflow, release signing/distribution, and supported Android/board versions have **not** been finalized. See [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md).
