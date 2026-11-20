# WDG // HUD — Watch Display Geometry and Layout Contract

**Document status:** Reference specification, revision 1.1 (2026-11-13)  
**Hardware:** Waveshare ESP32-S3-Touch-AMOLED-2.06, CO5300 AMOLED  
**Intended repository location:** `WDGHeadsUp/docs/WATCH_DISPLAY_GEOMETRY.md`  
**Purpose:** Keep each firmware change and screen preview consistent with the real panel and the HUD design approved by the user.

> **Authority rule:** Hardware calibration (when completed) > official Waveshare display examples > verified firmware coordinates > visual mockups. **Never treat an AI-generated illustration or a photographic perspective as pixel-exact geometry.**

**G5A50.5f documentation note (2026-11-20):** This file records the *approved HUD geometry contract* and earlier revisions, not a full rendering specification for every newer settings page. A [one-page interaction map](../../docs/images/watch-interaction-map.png) documents the current 3×3/5×5, Sector Intel, Operator, GANG and Visualizer controls. Its illustrations are not pixel-perfect substitutes for verifying actual hardware. No watch geometry/source is changed by this documentation pass.

## 1. Confirmed hardware and vendor-supplied model

| Property | Value | Confidence |
|---|---:|---|
| Logical screen size | **410 × 502 pixels** | Confirmed by Waveshare repository |
| Display controller | CO5300, QSPI AMOLED | Confirmed by Waveshare repository |
| Screen physical dimensions used by Waveshare example | 33.09 mm wide × 41.51 mm tall | In official example source |
| Corner radius used by Waveshare example | 9.2 mm | In official example source |
| **Calculated rounded-layout radius** | **approximately 111 pixels** | Derived from the vendor example; **not a measured pixel mask** |

Vendor sources:
- [Waveshare hardware/examples repository](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-2.06)
- [Official `04_Immersive_block/main/main.c`](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-2.06/blob/main/examples/esp-idf/04_Immersive_block/main/main.c): `SCREEN_WIDTH_MM`, `SCREEN_HEIGHT_MM`, `CORNER_RADIUS_MM`, and `constrain_to_rounded_rect()`.

Waveshare's own example converts radius in millimeters to pixels using the **smaller** pixel/mm scale:

```text
r = int(9.2 * min(410 / 33.09, 502 / 41.51)) = 111 pixels
```

This creates a useful **provisional safe-area model**. It does not prove that every physical AMOLED edge pixel follows an ideal circular arc. The actual visible corners must be checked on hardware before we lock the mask.

## 2. Provisional rounded-display mask

Use screen coordinates with `(0,0)` at the top left, X increasing right, Y increasing down. Approximate the usable area as a 410 × 502 rounded rectangle with radius `r = 111` px. In the corner zones, the left safe X boundary is:

```text
Top corner (0 <= y < r):
    leftX(y) = r - sqrt(r*r - (r-y)*(r-y))

Middle (r <= y <= H-r):
    leftX(y) = 0

Bottom corner (H-r < y < H):
    leftX(y) = r - sqrt(r*r - (y-(H-r))*(y-(H-r)))

rightX(y) = W - leftX(y)
```

When checking an element, test **every pixel of its bounding rectangle or silhouette**, not only its center or origin. Add a margin of at least 4–6 px near corners until calibration confirms the physical mask.

| Y (px) | Provisional visible horizontal X extent (rounded) |
|---:|---|
| 20 | 48–362 |
| 40 | 26–384 |
| 60 | 13–397 |
| 84 | 4–406 |
| 111–390 | 0–409 (full width) |
| 450 | 17–393 |
| 477 | 41–369 |

These values are **model predictions, not panel measurements**. Do not automatically clamp all drawing to this mask until the watch is calibrated. Bezel curvature, active-pixel shape, and glass reflection are different things.

## 3. Existing 3×3 grid layout — do not casually change

| Element | Position / dimensions |
|---|---|
| First column X | 17 |
| Second column X | 147 |
| Third column X | 277 |
| First row Y | 84 |
| Second row Y | 202 |
| Third row Y | 320 |
| Each tile | **120 × 105 px** |
| Overall grid bounding extent | X 17–396, Y 84–424 (inclusive) |
| Horizontal gutters | X 137–146 and X 267–276 |
| Vertical gutters | Y 189–201 and Y 307–319 |

**Approved border requirement:** All nine tiles have continuous, closed, uniformly thick outlines of the correct gang color, including every corner. Do **not** build borders from disconnected rails with missing joins. Current G5A38.4 screenshots show gaps and irregular thickness; this is an **unresolved drawing bug**, not an intentional style. Native pixel previews must zoom in on all four corners of each tile.

Tile content is live data: AP totals, three top tiers, highest-tier marker, and gang-color border. Preview sample numbers are illustrative and must never become hardcoded firmware data.

## 4. Center-sector directional triangle — approved placement

Exactly **one** cyan triangle is displayed. Its tip points **outward from the center tile toward the geographically adjacent tile**. Do not place arrows in the middle of a neighbor tile, do not show all arrows at once on the actual HUD, and do not interpret compass direction as watch-screen rotation: the grid remains north-up.

| Bearing octant | Arrow points toward | Location relative to center tile |
|---|---|---|
| N | North tile | Centered in gap above center |
| NE | Northeast tile | Upper-right corner intersection |
| E | East tile | Centered in right gap |
| SE | Southeast tile | Lower-right corner intersection |
| S | South tile | Centered in gap below center |
| SW | Southwest tile | Lower-left corner intersection |
| W | West tile | Centered in left gap |
| NW | Northwest tile | Upper-left corner intersection |

The user's approved reference drawing shows cyan, sharp, symmetric triangles around the **center** square, with diagonal arrowheads aimed into the corresponding diagonal gutter. One arrow per screen; direction from valid GPS course over ground. Keep most pixels in the existing gutters; minimal intentional border touch may be acceptable, but **no meaningful overlap onto AP tile content**. Check all eight positions in a deterministic native-resolution test panel before flashing.

Current directional thresholds: eight 45° octants, each centered on N=0°, NE=45°, E=90°, etc. N covers 337.5°–360° and 0°–22.5°. GPS validity/speed rules belong to the position/heading logic, not this geometry document.

## 5. Header, icons and message/visualizer strip

| Element | Status / geometry |
|---|---|
| Title | `WDG // HUD`, centered at top |
| GPS status | User-provided **location-pin** silhouette, left of title |
| BLE status | User-provided **Bluetooth-rune** silhouette, right of title |
| Side indicator dots | **Removed**; icons communicate state by color |
| Status colors | Preserve connection semantics from the accepted firmware |
| Old BLE icon placement | Approximately X 351–372; may clip in rounded corner |
| Proposed safer BLE placement | Inset origin around X 330; **not hardware validated** |
| Message allocated rectangle | **X 44–365, Y 53–78** (322 × 26 px) |
| Current visualizer bar extent | Approximately X 68–341, Y 57–76 |
| Top of first tile | Y 84 |

**Important:** 322 × 26 is the **currently allocated message strip**, **not** the physical maximum available display area. Since decorative brackets have been removed, keep the strip black except for CHECKING/SCAN/SYNC indicators or actual microphone bars. Do not reintroduce `IDLE //` labels. Check for clipping at icon silhouettes (especially the right icon's topmost pixels) against the rounded mask.

## 6. What is confirmed versus pending

**Confirmed/reference-backed**
- Panel resolution and controller from Waveshare.
- Waveshare example source defines physical dimensions and rounded-corner radius.
- Current HUD grid tile size, placement, message strip and visualizer extents from the existing firmware inspection.
- User-approved arrow semantics and status-icon shapes.

**Not yet hardware-verified or not yet fixed**
- Exact physical AMOLED pixel clipping boundary and corner mask.
- Proposed BLE icon inset of X≈330 and its final bounding box.
- Complete, uniform tile border rendering on the physical CO5300 panel.
- Final eight triangle shapes, symmetry, and display hardware rasterization.
- Pixel-perfect correspondence between previews and the photographed screen.

## 7. Mandatory future-update workflow

1. **Read this file before modifying watch display drawing code.** If geometry changes, revise this document in the same release.
2. Build a **deterministic 410 × 502 pixel render** from the same coordinates, masks, fonts and drawing primitives used by firmware. Don't use an image-generation interpretation to validate pixel placement.
3. Overlay the **provisional corner mask** and safe margins. Check every icon/glyph bounding box and line extent.
4. Preview **all eight triangle positions** separately; verify direction and location against the user-approved center-square arrow reference.
5. Inspect all 9 tile borders for closed loops, uniform thickness and correct corner joins at native zoom.
6. Confirm the message strip (x44..365, y53..78) does not erase grid content or intrude on the first row at y84.
7. If previews change title, status symbols, grid geometry or other approved content, explicitly mark that change and get approval **before code replacement**.
8. Test the changed drawing on the physical CO5300 panel; capture a straight-on photo. If the real mask differs from the mathematical model, **record corrected values here** and update the preview generator.
9. Do not alter phone-side cache/BLE logic for a geometry-only task. Preserve snapshot persistence and existing 3×3 data rules.
10. Mark proposed vs compile-tested vs watch-tested clearly in release notes.

## 8. Pending hardware calibration record

| Calibration item | Result |
|---|---|
| Test pattern / firmware revision | Pending |
| Top-left visible pixel contour | Pending |
| Top-right visible pixel contour | Pending |
| Bottom-left / bottom-right contour | Pending |
| Effective safe bounds for status icons | Pending |
| Effective safe bounds for bottom legend | Pending |
| Reference front-on photo | Pending |
| Approved final mask revision | Pending |

**The vendor-backed 111px model is adopted for normal design and preview work.** Physical verification remains useful if a real clipping issue is observed; a separate calibration exercise is not required before ordinary updates. Do not call this a manufacturer-measured pixel mask.


## 9. G5A38.6 approved layout (2026-11-13)

**Status:** User approved the corrected eight-direction preview. Firmware replacement is built but hardware validation is pending.

- Screen: 410x502 native pixels, Waveshare example's approximate 111px rounded corner mask (model, not calibrated hardware data).
- Tiles: x=17,147,277; y=84,202,320, with unchanged 120x105 pixel footprints.
- Borders: closed, 3px wide, radius-5 rounded outside shape from x+1..x+118 and y+1..y+103. All lines meet without gaps. Pixel colors use each sector's live gang color; no green value is hardcoded.
- Arrow: one cyan 22x22 bitmap at a time, using the exact eight cropped masks from the approved Paint reference; center points given below. Pixel masks are stored in `Display.cpp`.
- Arrow update: restore old 22x22 background with the exact border geometry; then draw the new arrow. Do not erase gang-border pixels to black. Draw through `draw16bitRGBBitmap` to avoid sparse 1px command dropouts.
- Header: left GPS pin at x=66, y=12 (20x32); right Bluetooth rune at x=319, y=12 (22x32). No side status dots. Each is inside the vendor-derived corner mask.
- Message strip: x=44..365, y=53..78; unchanged, no decorative brackets.
- Grid, heading thresholds, scanning, equalizer, BLE, and flash cache logic unchanged.

| Direction | 22x22 bitmap center (x,y) |
|---|---|
| N | (207,195) |
| NE | (272,195) |
| E | (272,254) |
| SE | (272,313) |
| S | (207,313) |
| SW | (142,313) |
| W | (142,254) |
| NW | (142,195) |

**Pixel reference:** `G5A38_6_CORRECTED_8_DIRECTIONS.png` in the review conversation. The pixel masks in `Display.cpp` are generated from those exact 22x22 arrows, not reinterpreted or resized at runtime.

**Deployment:** Replace `src/Display.cpp`; update `docs/WATCH_DISPLAY_GEOMETRY.md`. Watch-build and on-device verification are required before marking the change accepted.
