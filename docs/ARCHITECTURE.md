# Engineering architecture — WDG // HUD

**Source baseline for this document:** `main` at `7cd7d1c` (2026-11-17), including G5A49.1 profile-card configuration and G5A49.2 boundary tests. This document describes the observed implementation; it is not a claim that every hardware failure path has been field-certified.

## Data path

```text
WDGWars HTTPS API / authenticated website session
                 |        Phone GPS / course over ground
                 |          |
            Android companion (Kotlin)
            MainActivity <--> DrivingSessionService
                 |      (one BLE owner at a time)
          SQLite/SharedPreferences persistent data
                 |
       Authenticated BLE GATT + ACKed packet flows
                 |
        Waveshare ESP32-S3 watch (Arduino/NimBLE)
                 |
     Watch NVS snapshots --> 3x3 / 5x5 / Intel /
                             OPERATOR / GANG / SETTINGS
```

**Ownership:** Android handles account credentials, GPS, the web/API, cache persistence and transfer scheduling. The watch handles rendering, touch, BLE receiving, protocol checks and durable display snapshots. The watch does **not** store the API key or issue its own WDGWars HTTP requests.

## Core modules

| Source | Responsibility |
| --- | --- |
| `WDGHUD-Android/.../MainActivity.kt` | Compose UI, visible-Activity BLE owner, GPS, authentication/login, profile settings, sector/API flow |
| `WDGHUD-Android/.../DrivingSessionService.kt` | Foreground service, background GPS + BLE owner and resilient data transfer |
| `WdgWarsGrid.kt`, `SectorAnchorMath.kt`, `SectorAnchorRegistry.kt` | Coordinate-defined grid and permanent local anchor boundaries |
| `SectorCacheStore.kt`, `TerritoryCacheStore.kt` | Persistent historical AP-sector and ownership data |
| `SectorFiveByFivePrefetch.kt` | Authenticated 25-sector lookahead with retry/response validation |
| `TeamProfileWire.kt`, `TeamSnapshotStore.kt` | Validated GANG dossier and independent BLE packet channel; Android persistence |
| `BleLinkHealth.kt`, `RecoveryBackoff.kt` | BLE heartbeat and bounded reconnection policy |
| `WDGHeadsUp/src/BleTransport.cpp` | NimBLE GATT, secure pairing, packet staging/ACKs, profile and geographic receiver |
| `WDGHeadsUp/src/GeoSectorCache.cpp` | Watch geography/cache and sector recentering |
| `WDGHeadsUp/src/LastGoodStore.cpp` | Persist validated display snapshots across reboot |
| `WDGHeadsUp/src/Display.cpp`, `src/Touch.cpp` | Native-resolution watch screens and gestures |

## Sector coordinates, anchors and lookahead

At G5A49.2 the live WDGWars `member-territories` response was checked by the maintainer and reported `grid_lat = 0.02` and `grid_lng = 0.02`. Independent Warroom source uses latitude/longitude-aligned sectors with corners at grid intersections. Production `WdgWarsGrid` uses a **0.02° by 0.02°** step and `floor`-based half-open sector ownership:

```text
Sector i,j:
    south = i * 0.02       north = (i+1) * 0.02
    west  = j * 0.02       east  = (j+1) * 0.02
    covered coordinates: [south, north) x [west, east)
    interior API sample: (south + 0.01, west + 0.01)
```

A small numerical tolerance corrects floating-point rounding at nominal grid lines. Sector indices are floor-based for **negative** latitude/longitude. All four sector corners are intersections of the latitude/longitude lines. This angular grid is not a constant-meter grid; physical east/west width decreases toward the poles.

The permanent anchor registry stores immutable bounds in SQLite, separately from WDGWars AP statistics. Each valid GPS fix can recenter using local math. The **visible 3×3 (9 sectors)** and **inspection/lookahead 5×5 (25 sectors)** share a GPS center and cumulative history. The 5×5 screen does not initiate its own network scan or a 7×7 lookahead. Poles/dateline wrap and worldwide API acceptance vectors remain release-hardening topics.

## Cache and timestamps — distinct responsibilities

| Store | Owner and retention | Commit behavior |
| --- | --- | --- |
| Geographic anchors | Android SQLite; permanent per sector ID | Locally derived; never overwritten by API payload |
| AP sector history | Android persistent cache, cumulative beyond current 25 | Replace matching sector only with validated, successful newer data |
| Successful sync age | Android per-sector timestamp | Failed or partial refresh must **not** mark a sector fresh |
| Active 5×5 working window | Android and watch view of history | Recenter from GPS without an HTTP prerequisite |
| Last-good HUD/OPERATOR | Watch NVS | Only complete accepted snapshots can replace prior valid view |
| Full GANG dossier | Android private preferences, scoped to a hash of the API key | Validated full response; failure preserves prior dossier |
| GANG summary | Watch NVS | Validated complete summary (checksum), survives BLE failure/reboot |
| API key | Android Keystore-backed AES-GCM protected private preferences | Not stored on watch or intentionally checked into Git |

**Account-switch limitation:** Watch GANG summary is currently stored at a single watch-local key, not tagged by API-account identity; a different user's prior summary may be shown until a new successful transfer. Do not call account migration release-ready without resolving or explicitly accepting this limitation.

**Staleness:** default 30 minutes, user-configurable 1–240 minutes; stale *data* is still useful display data. Absence, API timeout, and confirmed no-AP are separate states. Background fetches must not erase validated values.

## BLE contract (implementation overview, not a frozen wire specification)

- Advertised watch name: `WDG HUD`.
- Service: `e4c1a100-7c0d-4f91-a2d4-7a50a9d1b001`.
- Write/command characteristic: `e4c1a101-7c0d-4f91-a2d4-7a50a9d1b001`.
- Read/notify/response characteristic: `e4c1a102-7c0d-4f91-a2d4-7a50a9d1b001`.
- Firmware requests LE Secure Connections with passkey pairing, encryption, MITM authentication and bonding. It checks those properties before accepting commands; Android uses OS pairing dialogs. Hardware downgrade/security testing remains necessary.
- Android considers the link ready after negotiated ATT MTU **at least 64**; watch responds to capability negotiation. Current firmware advertises capabilities including `VIEW,ATOMIC,GANG,GANG96,GEO25`.
- Message families include `GPS`, `CENTER`, geographic cache packets, legacy `BEGIN/CELL/LVL/RAW/END`, `PROFILE`, `TEAM` (user-facing **GANG**), `TIME`, settings, and PING/PONG heartbeat. The packet receiver stages/validates complete snapshots and acknowledges writes; failed partial transfers should never replace committed display state.
- G5A47 automatically starts the driving service on a ready watch connection. On app foreground/background transitions, only **one** of the Activity or Service should own BLE/GATT at a time.

The repository still contains a **historical G4A proposal** in `WDGHUD-Android/WDG_HUD_E5_17_G4A_PROTOCOL/`. Its `not integrated` status and old 21-sector lookahead are **superseded**. Read production Kotlin/C++ as authority; do not use that proposal as an up-to-date protocol spec.

## Data credentials and privacy

Account keys are entered on Android and encrypted with the Android Keystore. The public profile-card SVG URL is an optional **per-account** setting. Avoid committing credentials, cookies, signing keys, location traces or debug logs. Code inspection and unit tests do not replace live BLE encryption, API-failure and migration tests.

## Source-of-truth hierarchy

1. A reproducibly built/tested current `main` revision.
2. Current production Kotlin/C++ interfaces and paired unit tests.
3. Current watch-display geometry specification in `WDGHeadsUp/docs`.
4. Older design/handoff notes only as historical context; never overwrite current working source from an old patch archive.
