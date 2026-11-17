# User guide — WDG // HUD

## What you are looking at

The watch is a **snapshot HUD**, not a stand-alone scanner or WDGWars internet client. The Android companion obtains phone GPS and WDGWars data, maintains persistent history, and securely sends validated updates to the watch. Displayed AP counts are **WDGWars-reported sector intelligence**, not a claim that the watch independently detected radio access points.

The grid remains **north-up**. The cyan direction triangle uses valid phone GPS course-over-ground information; if GPS bearing is unavailable, the firmware should not fabricate a heading.

## Android companion

| Panel/control | What it does |
| --- | --- |
| WATCH UPLINK | BLE connection status, link health, GPS packet statistics and reconnect control when offline |
| DRIVING SESSION | Shows active/inactive driving-service state; starts automatically on successful watch connection; STOP remains available |
| SECTOR MATRIX / 3×3 | Displays automatic refresh status and allows a manual 3×3 sync for troubleshooting |
| GPS TELEMETRY | Phone GPS source, coordinates, speed and last valid bearing |
| WDGWARS ACCESS | Enter and verify your own API key; launch WDGWars website sign-in for authenticated data requests |
| OPTIONAL // PUBLIC PROFILE CARD | Save/remove your own public WDGWars SVG card URL; not required for BLE or GPS |

**First use:** enter and verify the WDGWars API key, log in through the WDGWars website dialog if needed for sector-detail fetching, pair the watch, and verify **SESSION ACTIVE**. The login window should **not** contain older AP-level test buttons.

**Background behavior:** When the Android app is minimized, the foreground service owns GPS/BLE; when you return to the app, the Activity becomes the BLE owner. Some Android power-management settings can interrupt an app or service. Force-stopping the app is **not** a supported way to keep the driving session running.

## Watch touch navigation

| Screen | Touch action |
| --- | --- |
| 3×3 driving HUD | Default. Swipe **left** → 5×5; swipe **up** → OPERATOR; tap a sector → Sector Intel |
| 5×5 inspection | Swipe **right** → 3×3; tap sector → Sector Intel |
| Sector Intel | Inspect cached per-sector information; navigate back via touch (or swipe through supported detail pages) |
| OPERATOR | Tap gang name → GANG; tap gear → SETTINGS; navigate back to grid |
| GANG | Displays gang name, RANK, MEMBERS count, WI-FI, BLE, TOTAL, CREDITS; BACK → OPERATOR |
| SETTINGS | LOW/MED/HIGH brightness; AP stale-age controls; visualizer settings |

There is **no separate MEMBERS screen** in the intended touch interface. A 5×5 inspection action does **not** start a second scan or expand the lookahead to 7×7.

## What persists and what refreshes

- The current **3×3** grid uses the GPS-selected sector. The **5×5** window inspects nearby historical records around the *same* center.
- WDGWars reports sector spacing of **0.02° latitude × 0.02° longitude**, aligned to coordinate intersections. The phone can classify a sector crossing from coordinates without an internet query.
- The active 25-sector window is a view into **cumulative** Android history; returning to a previously visited area should reuse cached data while new information loads.
- Default AP **stale threshold is 30 minutes**; SETTINGS can change it from 1 to 240 minutes. Staleness determines refresh eligibility, not deletion. A failed refresh must not zero a valid count.
- The Android companion stores a last successful per-sector update time; the watch stores validated last-good data in nonvolatile flash. GANG information is refreshed about every five minutes while the companion is active, when connectivity permits.
- First-time unknown, stale-but-valid, and confirmed empty are **different** states.

## Pairing and reconnection

Watch BLE name: **WDG HUD**. On a new bond, the watch displays a temporary six-digit authentication code. Use the **Android system pairing** prompt, not an in-app hardcoded PIN. Existing bonds should usually reconnect without repeating the code. The firmware is designed to reject unauthenticated command traffic.

If disconnected: keep the phone nearby, verify Bluetooth and app permissions, use the Android **RECONNECT WATCH** control, or make a short BOOT button press on the *disconnected* watch to restart advertising. The BOOT behavior is not a general reset/unpair function.

## Troubleshooting

- **No account data:** verify API key; open WDGWars website login in the companion if authenticated cell-detail requests fail; inspect **API STATUS**. Avoid pasting credentials into screenshots/logs.
- **BLE connected but no updates:** confirm Android **SESSION ACTIVE**, fresh GPS fix, and BLE link health. A watch may intentionally preserve cached display while new records are unavailable.
- **Wrong direction:** heading is based on course over ground, not a stationary magnetic compass; test while moving safely.
- **Unexpected GANG values after switching accounts:** watch GANG NVS is not yet account-scoped; the previous summary may remain until a successful new profile transfer. This is a known pre-v1.0 limitation.
- **An app reinstall seems to lose history:** uninstalling/clearing data is destructive. Updating over an installed app is safer for migration tests.

**Driving safety:** Only glance at the watch while it is safe to do so. Stop the vehicle before navigating screens, pairing, logging in or troubleshooting.
