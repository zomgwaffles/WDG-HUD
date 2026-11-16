#pragma once
#include <stdint.h>
#include "SectorModel.h"

// G5A38. Geographically keyed, last-known-good detailed sector cache.
// The active 3x3 changes immediately; missing sector records display UNKNOWN.
// Coordinates use the canonical WDGWars 0.02 degree grid IDs supplied by Android.
void restoreGeoSectorCache();
// A newer legacy last-good snapshot is authoritative on reboot. Keep the
// geographic cache but wait for an explicit center packet before repainting.
void geoCacheDeferDisplayUntilCenter();
void geoCacheFromValidatedGrid(int32_t centerNorth, int32_t centerEast);
bool geoCacheRecenter(int32_t centerNorth, int32_t centerEast);
// A false result indicates an UNKNOWN tile in the active geographic window.
bool geoCacheTileAvailable(int slot);
bool geoCacheCurrentCenter(int32_t& north, int32_t& east);
void tickGeoSectorCache();

// Single-sector cache-only uplink. A received validated tile updates a partial window.
bool geoCacheBegin(int32_t north, int32_t east, int32_t total, uint16_t color);
bool geoCacheName(int part, int parts, const char* text);
bool geoCacheRaw(int level, int count);
bool geoCacheCommit();
void geoCacheAbort();

bool consumeGeoCacheScreenUpdate();

// Live, geographically keyed 5x5 inspection: NW to SE, slot 12 is GPS center.
// No extra lookahead, BLE request, or refresh is started by these reads.
// Unknown cells return false. A valid zero-AP sector returns true.
bool geoCacheRead5x5(int slot, SectorData* sector = nullptr,
                     int32_t* rawLevels = nullptr, int32_t* tierCounts = nullptr);
