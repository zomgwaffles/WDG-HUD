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

Validated sector history persists on the Android device, and last-good watch snapshots persist in NVS across reboot. **Known limitation:** the watch's saved GANG summary is currently not tagged by account, so it can display a prior user's summary until a complete new transfer arrives after an account switch. Do not share a watch between WDGWars accounts without understanding this limitation.

Clearing Android storage, uninstalling the app or wiping watch NVS may destroy locally cached history and stored account settings. Test data migrations intentionally, with backups if appropriate.

## Reporting security concerns

Until a dedicated private reporting channel is configured, **do not disclose an exploitable vulnerability or a credential in a public issue**. Use a private maintainer contact channel arranged out of band. GitHub private security advisory reporting can be added if the repository owner enables it; this file does not claim that a private report endpoint has been configured.

For harmless reproducible bugs, give build revision, steps, expected/actual behavior and sanitized logs. Avoid precise GPS tracks or account-specific card URLs.

## Pre-release policy decisions still needed

Project license, public security reporting workflow, release signing/distribution, and supported Android/board versions have **not** been finalized. See [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md).
