# WDG // HUD E5.17 — G4A BLE protocol design (reviewed baseline)

Status: **protocol design only**, not integrated or tested on phone/watch.
Date: 2026-11-11

## Existing verified-in-source behavior

Android sends `BEGIN,<tierCount>,<rawCount>`, nine `CELL,<idx>,<total>,<gang>,<color>` records, corresponding `LVL` and `RAW` records, and `END`, with acknowledgement of each write. Watch firmware stages all nine cells, checks a 9-bit mask, tier/raw counts, each cell's totals and raw/tier consistency, then atomically commits and acknowledges `END:OK`. Invalid batches return `END:INCOMPLETE`. `GPS` packets are independent. The watch's current `SCAN`, `PROG`, `SCANSTOP` paths drive activity UI. **Do not weaken any of these safeguards.**

## G4 goals

1. An immediate geographic 3x3 viewport centered on the current confirmed sector, north-up, even when some details are unavailable.
2. Every visible tile identifies its correct canonical sector ID. Unknown data is explicitly unknown, never AP count zero.
3. Cached validated records can be displayed without making a network request or showing `SCANNING`.
4. New/updated nine-cell data remains atomically validated; a stale or interrupted transfer cannot replace a more recent viewport.
5. GPS telemetry and service/Activity BLE handoff remain functional.

## Proposed coordinated protocol, version 2 (not shipped)

Use a *new* protocol capability handshake, leaving legacy commands unchanged for old devices:

- Android sends `CAPS?`; watch replies `CAPS,2,VIEW,ATOMIC`. Failure/no response => legacy behavior only, with no optimistic viewport commands.
- A viewport update is a staged transaction (`VBEGIN,2,<txn>,<centerNorth>,<centerEast>`, nine `VTILE` records and `VEND,<txn>`); every tile slot 0..8 must be explicitly included and identity-checked against the center and slot offset. Each `VTILE` carries *either* a cached validated record reference/payload or an explicit `UNKNOWN` marker. **Actual final packet format, size/chunking, and ACK behavior must be designed against negotiated ATT MTU and source sender queue limits before implementation.**
- On `VEND`, watch validates exactly nine distinct geographically correct tile IDs, validates all present totals/levels/raw data, and swaps the viewport in one commit. Aborted, truncated, duplicate, or stale transactions do not commit.
- A fresh center is tracked by transaction/sequence (monotonic within a BLE session). Late responses cannot roll back a newer center. Reconnection resets sequence session and performs a fresh capability exchange.
- Activity states are separately versioned `ACT,2,IDLE|CHECKING|SCANNING,<progress?>`, not overloaded onto `GPS`. `CHECKING` is a short coverage-evaluation state, never periodic GPS blinking; `SCANNING` and progress only for actual API work.

**This is a design outline, not a wire specification ready for implementation.** Resolve per-tile detail payload sizes and sender/receiver queue limits before encoding. The primary acceptance gate is that no unknown tile can be treated as a valid zero-AP tile.

## Proposed incremental implementation sequence

- **G4B:** Add watch and Android capability negotiation with unit tests; legacy `BEGIN/CELL/LVL/RAW/END` and `GPS` unchanged. Test old/new version fallback and BLE reconnect.
- **G4C:** Implement and test atomic nine-tile geographic viewport transaction; stage and validate in a separate shadow model, including explicit unknown tiles and viewport identity. Test duplicate/out-of-order/missing records and abort.
- **G4D:** Connect the validated Android geographic cache to viewport transmission and mark which tiles are unknown or stale; add foreground-service integration without a second GATT owner.
- **G5:** Implement accurate `IDLE`, `CHECKING` bouncing line, and genuine `SCANNING` progress. Never animate on a no-op GPS check.
- **G6:** Phone + watch driving regression, including app hidden/destroyed, reconnect, offline, GPS jumps, and stale-packet rejection.

## Current G3 exception relevant to G4

The approved prefetch window is **21 sectors** (5x5 excluding four outer corners), not the previous 25. The watch still displays 3x3, and its north-up slot IDs are unaffected. G4 must not rely on a guaranteed 25-sector warm cache or assume the four excluded corners are filled.
