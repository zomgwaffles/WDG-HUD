
    
  
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <esp_system.h>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <string>
#include "BleTransport.h"
#include "BleSecurityPolicy.h"
#include "AccountScopedStore.h"
#include "Display.h"
#include "SectorModel.h"
#include "LastGoodStore.h"
#include "GeoSectorCache.h"
#include "Display.h"
#include "ViewportTransaction.h"
#include <limits>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

bool live3x3Data = false;
// HUD: three most populated tiers, sorted by descending AP count.
int liveLevelNumbers[9][3] = {};
int liveLevelCounts[9][3] = {};
// Ten ten-level bands. Levels >100 are safely included in T10 so
// raw histogram counts and AP totals remain lossless.
constexpr int MAX_HARDNESS_TIERS = 10;
int liveTierCounts[9][MAX_HARDNESS_TIERS + 1] = {};
int liveRawLevels[9][256] = {};

namespace {
constexpr const char* SERVICE_UUID = "e4c1a100-7c0d-4f91-a2d4-7a50a9d1b001";
constexpr const char* COMMAND_UUID = "e4c1a101-7c0d-4f91-a2d4-7a50a9d1b001";
constexpr const char* RESPONSE_UUID = "e4c1a102-7c0d-4f91-a2d4-7a50a9d1b001";

NimBLECharacteristic* response = nullptr;
volatile bool connected = false;
// G5A44: a GATT connection is not trusted until NimBLE confirms encryption,
// MITM authentication, and a persisted bond. No BLE command is accepted earlier.
volatile bool linkAuthenticated = false;
volatile bool pairingPromptPending = false;
volatile bool pairingPromptActive = false;
volatile bool pairingPromptFinished = false;
volatile uint32_t pairingPromptCode = 0;

volatile int lastScanProgress = 0; // 0..100; received from phone, no sector redraw

// Latest GPS fix received from Android. Kept private to this module for now.
struct GpsFix {
  float latitude = 0.0f;
  float longitude = 0.0f;
  float speedMph = 0.0f;
  float bearingDegrees = 0.0f;
  bool bearingValid = false;
  bool valid = false;
  uint32_t updatedMs = 0;
  float bearingSpeedMph = 0.0f; // speed from the SAME valid bearing packet
  uint32_t bearingSerial = 0;   // incremented only when bearingValid==1 on wire
};
GpsFix latestGps;
struct CellUpdate { int index; int count; int gang; bool hasOfficialColor; uint16_t officialColor; char gangName[24] = {}; };
struct LevelUpdate { int index; int slot; int level; int count; };
struct RawUpdate { int index; int level; int count; };
// G5A36: acknowledged, ordered 24-byte ASCII name chunks (96 chars max).
struct NameUpdate { uint8_t index, part, parts; char text[25] = {}; };
QueueHandle_t nameQueue = nullptr;

// G4C2: viewport BLE packets are staged on the main loop, never in NimBLE callbacks.
wdg::ViewportTransaction viewportTransaction;
struct ViewportCommand { char value[112]; };
struct GeoCommand { char value[112]; };
QueueHandle_t geoQueue = nullptr;
QueueHandle_t viewportQueue = nullptr;
struct ClockSync {
  uint16_t year;
  uint8_t month, day, hours, minutes, seconds, weekday;
};
QueueHandle_t clockQueue = nullptr;
bool viewportDirty = false;
// Separate acknowledged OPERATOR profile channel; never touches sector batches.
struct ProfilePacket { char value[96]; };
QueueHandle_t profileQueue = nullptr;
// OWNER is staged only on the Arduino main loop, after authenticated BLE.
struct OwnerPacket { char value[40] = {}; };
QueueHandle_t ownerQueue = nullptr;
volatile bool ownerLinkResetPending = true;
bool profileRequestPending = false;
volatile bool profileClientReady = false; // set after Android writes a command post-subscription
volatile bool staleIntervalSyncPending = false;
volatile uint16_t staleIntervalToSync = 30;
struct ProfileStage {
  uint32_t wifi = 0, ble = 0, total = 0, badges = 0;
  char username[21] = {};
  char gang[97] = {};  // G5A22: bounded full name, 4 x 24-character packets.
  size_t gangLength = 0;
  int nextGangChunk = 0, expectedGangChunks = 0;
  char activityRank[16]="UNKNOWN";
  int activeDays=-1;
  bool haveActivity=false;
  bool haveStats = false, haveName = false;
};
ProfileStage profileStage;
// G5A45: independent acknowledged TEAM/roster channel. No sector batch mutations.
struct TeamPacket {char value[96];};
QueueHandle_t teamQueue = nullptr;
volatile bool teamSummaryRequestPending = false;
volatile int teamPageRequestPending = -1;
struct TeamStage {
  int32_t rank=-1, wifi=-1, ble=-1, total=-1, credits=-1;
  uint16_t color=0x07FF;
  uint32_t members=0;
  char name[97]={};
  size_t nameLength=0;
  int nextChunk=0, chunkCount=0;
  bool haveSummary=false,haveTotals=false;
  int page=-1, pages=1, nextMember=0;
  TeamMemberRow rows[4]={};
};
TeamStage teamStage;


bool viewportRenderActive = false;
uint32_t viewportStartedMs = 0;
volatile bool viewportDisconnectPending = false;
constexpr uint32_t VIEWPORT_TIMEOUT_MS = 30000UL;
QueueHandle_t rawQueue = nullptr;
struct LevelEntry { int level; int count; };
LevelEntry receivedLevels[9][3] = {};
QueueHandle_t levelQueue = nullptr;
QueueHandle_t cellQueue = nullptr;
bool sectorDirty = false;
int expectedRaw = 0;
int stagedRaw = 0;
// E3: A full uplink is staged until END. The live screen is never partly updated.
struct StagedSector {
  int total = 0;
  uint16_t color = 0x8410;
  char gangName[97] = {};
  uint8_t nameParts = 0;
  uint8_t nameNextPart = 0;
  int count[MAX_HARDNESS_TIERS + 1] = {};
  int raw[256] = {};
};
StagedSector staged[9];
bool batchActive = false;
bool batchGeoTagged = false;
int32_t batchCenterNorth = 0, batchCenterEast = 0;
bool batchRequiresNames = false;
bool batchNamesInvalid = false;
volatile bool endRequested = false;
int expectedLevels = 0;
int stagedLevels = 0;
volatile bool tickerStartRequested = false;
volatile bool tickerSyncRequested = false;
volatile bool checkingStartRequested = false;
volatile bool checkingStopRequested = false;
volatile bool tickerStopRequested = false;
uint16_t stagedCellsMask = 0;
uint32_t batchStartMs = 0;
void clearBatch() {
  batchActive = false;
  batchGeoTagged = false;
  batchRequiresNames = false;
  batchNamesInvalid = false;
  endRequested = false;
  expectedLevels = 0;
  expectedRaw = 0;
  stagedRaw = 0;
  stagedLevels = 0;
  stagedCellsMask = 0;
  for (auto &entry : staged) entry = StagedSector{};
  if (cellQueue) xQueueReset(cellQueue);
  if (levelQueue) xQueueReset(levelQueue);
  if (rawQueue) xQueueReset(rawQueue);
  if (nameQueue) xQueueReset(nameQueue);
}

uint16_t gangColor(int gang) {
  if (gang <= 0) return 0x8410;
  // Stable, high-contrast placeholder color derived from gang ID.
  // These are NOT official WDGWars gang colors.
  static constexpr uint16_t palette[] = {
    0x07FF, // cyan
    0xF81F, // magenta
    0xFFE0, // yellow
    0x07E0, // green
    0xFD20, // orange
    0x001F, // blue
    0xF800, // red
    0xFFFF  // white
  };
  uint32_t v = static_cast<uint32_t>(gang) * 2654435761UL;
  return palette[(v >> 16) % (sizeof(palette) / sizeof(palette[0]))];
}

// Convert an official #RRGGBB WDGWars color to RGB565 for the AMOLED.
bool parseOfficialColor(const char* value, uint16_t& result) {
  if (std::strlen(value) != 7 || value[0] != '#') return false;
  unsigned int rgb = 0;
  int consumed = 0;
  if (std::sscanf(value + 1, "%6x%n", &rgb, &consumed) != 1 || consumed != 6) return false;
  for (int i = 1; i < 7; ++i) {
    const char c = value[i];
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
  }
  const uint16_t r = (rgb >> 19) & 0x1F;
  const uint16_t g = (rgb >> 10) & 0x3F;
  const uint16_t b = (rgb >> 3) & 0x1F;
  result = static_cast<uint16_t>((r << 11) | (g << 5) | b);
  return true;
}

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo&) override {
    connected = true;
    linkAuthenticated = false;
    profileClientReady = false;
    ownerLinkResetPending = true;
    staleIntervalSyncPending = true; // Re-send user preference on every CAPS handshake.
    viewportDisconnectPending = true; // New session; discard stale staging in pollBle().
  }

  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
    connected = false;
    linkAuthenticated = false;
    pairingPromptFinished = pairingPromptFinished || pairingPromptActive;
    pairingPromptActive = false;
    pairingPromptPending = false;
    pairingPromptCode = 0;
    profileClientReady = false;
    ownerLinkResetPending = true;
    viewportDisconnectPending = true;
    profileStage = ProfileStage{};
    teamStage = TeamStage{};
    if(teamQueue)xQueueReset(teamQueue);
    // G5A35: cancel message-only animations after phone loss, never the
    // last committed grid or OPERATOR profile. Prevent a stuck SCANNING bar.
    tickerStopRequested = true;
    checkingStopRequested = true;
    latestGps.valid = false; // A disconnected phone cannot provide live GPS.
    latestGps.bearingValid = false;
    // Batch reset happens in the main loop, outside the BLE callback.
    NimBLEDevice::startAdvertising();
  }

  uint32_t onPassKeyDisplay() override {
    // Fresh passkey per initial pairing, never a compiled-in PIN. Android's
    // system pairing UI requests this code, which appears on the watch.
    const uint32_t passkey = 100000U + (esp_random() % 900000U);
    pairingPromptCode = passkey;
    pairingPromptActive = true;
    pairingPromptPending = true;
    pairingPromptFinished = false;
    Serial.println("G5A44 pairing code ready on display"); // Never log the PIN.
    return passkey;
  }

  void onAuthenticationComplete(NimBLEConnInfo& info) override {
    const bool valid = bleSecureConnection(info.isEncrypted(), info.isAuthenticated(), info.isBonded());
    linkAuthenticated = valid;
    pairingPromptFinished = pairingPromptFinished || pairingPromptActive;
    pairingPromptActive = false;
    pairingPromptPending = false;
    pairingPromptCode = 0;
    Serial.printf("G5A44 BLE security: encrypted=%d authenticated=%d bonded=%d\n",
                  info.isEncrypted(), info.isAuthenticated(), info.isBonded());
    if (!valid) {
      // An unauthenticated or downgraded session must never process GPS,
      // account profile, cache, or watch-control messages.
      NimBLEDevice::getServer()->disconnect(info.getConnHandle());
    }
  }
};

class CommandCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* characteristic, NimBLEConnInfo& info) override {
    if (!linkAuthenticated || !bleSecureConnection(
            info.isEncrypted(), info.isAuthenticated(), info.isBonded())) return;
    const std::string command = characteristic->getValue();
    // Android writes only after enabling BLE notifications.
    profileClientReady = true;
    if (command == "CAPS?") {
      staleIntervalToSync = getStaleIntervalMinutes();
      staleIntervalSyncPending = true;
      // G4B capability discovery: never mutates the legacy snapshot or GPS state.
      response->setValue("CAPS,3,VIEW,ATOMIC,GANG,GANG96,GEO25,ACCOUNT1");
    } else if (command.compare(0, 6, "OWNER,") == 0) {
      if (command.size() != 38 || !ownerQueue) {
        response->setValue("OWNER:INVALID");
      } else {
        OwnerPacket packet{};
        memcpy(packet.value, command.c_str(), command.size() + 1);
        if (xQueueSend(ownerQueue, &packet, 0) == pdTRUE) return;
        response->setValue("OWNER:BUSY");
      }
    } else if (command.compare(0, 5, "TEAM,") == 0) {
      if(command.size()>=sizeof(TeamPacket::value) || !teamQueue) {
        response->setValue("TEAM:INVALID");
      } else {
        TeamPacket packet{};
        memcpy(packet.value,command.c_str(),command.size()+1);
        if(xQueueSend(teamQueue,&packet,0)==pdTRUE) return;
        response->setValue("TEAM:BUSY");
      }
    } else if (command.compare(0, 8, "PROFILE,") == 0) {
      // Profile data is parsed and committed on the main loop, not BLE callback.
      if (command.size() >= sizeof(ProfilePacket::value) || !profileQueue) {
        response->setValue("PROFILE:INVALID");
      } else {
        ProfilePacket packet{};
        std::memcpy(packet.value, command.c_str(), command.size() + 1);
        if (xQueueSend(profileQueue, &packet, 0) == pdTRUE) return;
        response->setValue("PROFILE:BUSY");
      }
    } else if (command.compare(0, 2, "K,") == 0 ||
               command.compare(0, 7, "CENTER,") == 0) {
      // Watch-first geographic cache/control. NVS, validation, display updates
      // happen on the main loop; never block a NimBLE callback.
      if (command.size() >= sizeof(GeoCommand::value) || !geoQueue) {
        response->setValue("K:INVALID");
      } else {
        GeoCommand item{};
        std::memcpy(item.value, command.c_str(), command.size() + 1);
        if (xQueueSend(geoQueue, &item, 0) == pdTRUE) return;
        response->setValue("K:BUSY");
      }
    } else if (command.compare(0, 2, "V,") == 0) {
      // Serialized, bounded command: V,BEGIN,seq,north,east;
      // V,TILE,seq,slot,north,east,P|U,total; V,END,seq.
      // All validation, ACKs and atomic commit execute in pollBle().
      if (command.size() >= sizeof(ViewportCommand::value) || !viewportQueue) {
        response->setValue("V:INVALID");
      } else {
        ViewportCommand update{};
        std::memcpy(update.value, command.c_str(), command.size() + 1);
        if (xQueueSend(viewportQueue, &update, 0) == pdTRUE) {
          return; // ACK arrives only after the main loop handles the command.
        }
        response->setValue("V:BUSY");
      }
    } else if (command == "HUD,CHECK,START") {
      checkingStartRequested = true;
      response->setValue("HUD:OK");
    } else if (command == "HUD,CHECK,STOP") {
      checkingStopRequested = true;
      response->setValue("HUD:OK");
    } else if (command.compare(0, 5, "TIME,") == 0) {
      // Keep RTC I2C writes OUT of the NimBLE callback thread.
      int yyyy = -1, mo = -1, dd = -1, hh = -1, mm = -1, ss = -1, wd = -1;
      int consumed = 0;
      const int parsed = std::sscanf(command.c_str(),
          "TIME,%d,%d,%d,%d,%d,%d,%d%n",
          &yyyy, &mo, &dd, &hh, &mm, &ss, &wd, &consumed);
      if (parsed == 7 && consumed == static_cast<int>(command.size()) &&
          yyyy >= 2020 && yyyy <= 2099 && mo >= 1 && mo <= 12 &&
          dd >= 1 && dd <= 31 && hh >= 0 && hh < 24 &&
          mm >= 0 && mm < 60 && ss >= 0 && ss < 60 &&
          wd >= 0 && wd <= 6 && clockQueue != nullptr) {
        const ClockSync stamp{static_cast<uint16_t>(yyyy),
                              static_cast<uint8_t>(mo),
                              static_cast<uint8_t>(dd),
                              static_cast<uint8_t>(hh),
                              static_cast<uint8_t>(mm),
                              static_cast<uint8_t>(ss),
                              static_cast<uint8_t>(wd)};
        response->setValue(xQueueOverwrite(clockQueue, &stamp) == pdTRUE
                           ? "TIME:OK" : "TIME:BUSY");
      } else {
        response->setValue("TIME:INVALID");
      }
    } else if (command == "PING") {
      response->setValue("PONG");
    } else if (command == "SCAN") {
      lastScanProgress = 0;
      // Early notification: API fetch has not started yet. No batch data touched.
      tickerStartRequested = true;
      response->setValue("SCAN:OK");
    } else if (command.compare(0, 5, "PROG,") == 0) {
      int value = -1, consumed = 0;
      if (std::sscanf(command.c_str(), "PROG,%d%n", &value, &consumed) == 1 &&
          consumed == static_cast<int>(command.size()) &&
          value >= 0 && value <= 100) {
        lastScanProgress = value;
        response->setValue("PROG:OK");
      } else {
        response->setValue("PROG:INVALID");
      }
    } else if (command == "SCANSTOP") {
      // Fetch failed; restore heading without disturbing an active batch.
      if (!batchActive) tickerStopRequested = true;
      response->setValue("SCANSTOP:OK");
    } else if (command == "GPSOFF") {
      latestGps.valid = false;
      latestGps.bearingValid = false;
      response->setValue("GPSOFF:OK");
    } else if (command.compare(0, 4, "GPS,") == 0) {
      float lat, lon, speed, bearing;
      int fixBearingValid = 0;
      int consumed = 0;
      const int parsed = std::sscanf(
          command.c_str(), "GPS,%f,%f,%f,%f,%d%n",
          &lat, &lon, &speed, &bearing, &fixBearingValid, &consumed);
      // E5.4 requires the fifth field: 1 = Android has a usable travel bearing.
      // A missing bearing is not silently interpreted as true north.
      if (parsed == 5 && consumed == static_cast<int>(command.size()) &&
          (fixBearingValid == 0 || fixBearingValid == 1) &&
          std::isfinite(lat) && std::isfinite(lon) &&
          std::isfinite(speed) && std::isfinite(bearing) &&
          lat >= -90.0f && lat <= 90.0f &&
          lon >= -180.0f && lon <= 180.0f &&
          speed >= 0.0f && bearing >= 0.0f && bearing < 360.0f) {
        latestGps.latitude = lat;
        latestGps.longitude = lon;
        latestGps.speedMph = speed;
        // A 0 flag means no NEW course, not a command to erase the last
        // reliable travel bearing. A valid 0-degree heading is still north.
        if (fixBearingValid == 1) {
          latestGps.bearingDegrees = bearing;
          latestGps.bearingSpeedMph = speed;
          latestGps.bearingValid = true;
          ++latestGps.bearingSerial;
          if (latestGps.bearingSerial == 0) ++latestGps.bearingSerial;
        }
        latestGps.valid = true;
        latestGps.updatedMs = millis();
        response->setValue("GPS:OK");
      } else {
        response->setValue("GPS:INVALID");
      }
    } else if (command.compare(0, 6, "BEGIN,") == 0) {
      int n = -1, rawN = -1, nameFlag = -1, consumed = 0;
      long centerN = 0, centerE = 0;
      const int geo = std::sscanf(command.c_str(), "BEGIN,%d,%d,%d,%ld,%ld%n",
                                  &n, &rawN, &nameFlag, &centerN, &centerE, &consumed);
      const bool geoExpected = geo == 5 && nameFlag == 1 &&
          consumed == static_cast<int>(command.size()) &&
          centerN >= INT32_MIN && centerN <= INT32_MAX &&
          centerE >= INT32_MIN && centerE <= INT32_MAX;
      if (!geoExpected) consumed = 0;
      const int extended = geoExpected ? 3 :
          std::sscanf(command.c_str(), "BEGIN,%d,%d,%d%n", &n, &rawN, &nameFlag, &consumed);
      const bool namesExpected = geoExpected || (extended == 3 && nameFlag == 1 &&
          consumed == static_cast<int>(command.size()));
      if (!namesExpected) {
        consumed = 0;
        std::sscanf(command.c_str(), "BEGIN,%d,%d%n", &n, &rawN, &consumed);
      }
      const bool legacy = !namesExpected && consumed == static_cast<int>(command.size());
      if ((namesExpected || legacy) &&
          n >= 0 && n <= 9 * MAX_HARDNESS_TIERS && rawN >= 0 && rawN <= 9 * 255 && !batchActive && !viewportTransaction.active()) {
        clearBatch();
        geoCacheAbort(); // A full watch snapshot takes precedence over a partial preload.
        expectedLevels = n;
        expectedRaw = rawN;
        batchRequiresNames = namesExpected;
        batchGeoTagged = geoExpected;
        if (geoExpected) { batchCenterNorth = static_cast<int32_t>(centerN);
                           batchCenterEast = static_cast<int32_t>(centerE); }
        batchActive = true;
        batchStartMs = millis();
        // G5A39: 0% is the beginning of the acknowledged AP data uplink.
        // Prior sector scans/prefetches never determine this progress.
        lastScanProgress = 0;
        tickerSyncRequested = true;
        response->setValue("BEGIN:OK");
      } else {
        response->setValue("BEGIN:INVALID");
      }
    } else if (command == "END") {
      if (batchActive && !endRequested) {
        endRequested = true;
        return; // ACK only after the main loop has validated the complete snapshot.
      } else {
        response->setValue("END:INVALID");
      }
    } else if (command.compare(0, 5, "CELL,") == 0) {
      int index = 0, count = 0, gang = 0, consumed = 0;
      // Accept both legacy CELL,index,count,gang and new CELL,index,count,gang,#RRGGBB.
      const int parsed = std::sscanf(command.c_str(), "CELL,%d,%d,%d%n", &index, &count, &gang, &consumed);
      bool valid = parsed == 3 && index >= 0 && index < 9 && count >= 0 && gang >= 0;
      bool hasOfficialColor = false;
      uint16_t officialColor = 0;
      CellUpdate update{};
      update.index = index;
      update.count = count;
      update.gang = gang;
      // Accept the legacy five-field CELL packet and a sixth ASCII-only gang
      // label from CAPS,3,VIEW,ATOMIC,GANG Android clients. No delimiter can
      // appear inside the label; the phone encodes it to <=20 ASCII bytes.
      if (valid && consumed < static_cast<int>(command.size())) {
        if (command[consumed] != ',') {
          valid = false;
        } else {
          const char* value = command.c_str() + consumed + 1;
          const char* nameSeparator = std::strchr(value, ',');
          const std::string officialColorText = nameSeparator
              ? std::string(value, static_cast<size_t>(nameSeparator - value))
              : std::string(value);
          if (officialColorText == "-") {
            // No official color: preserve previous gang-ID color fallback.
          } else {
            hasOfficialColor = parseOfficialColor(officialColorText.c_str(), officialColor);
            valid = hasOfficialColor;
          }
          if (nameSeparator) {
            const char* name = nameSeparator + 1;
            const size_t length = std::strlen(name);
            if (length == 0 || length > 20 || std::strchr(name, ',')) valid = false;
            for (size_t k = 0; valid && k < length; ++k)
              if (static_cast<unsigned char>(name[k]) < 32 ||
                  static_cast<unsigned char>(name[k]) > 126) valid = false;
            if (valid) std::memcpy(update.gangName, name, length + 1);
          }
        }
      }
      update.hasOfficialColor = hasOfficialColor;
      update.officialColor = officialColor;
      if (valid && batchActive && !endRequested && cellQueue) {
        response->setValue(xQueueSend(cellQueue, &update, 0) == pdTRUE ? "CELL:OK" : "CELL:BUSY");
      } else {
        response->setValue("CELL:INVALID");
      }
    } else if (command.compare(0, 5, "NAME,") == 0) {
      int index = -1, part = -1, parts = -1, dataOffset = 0;
      const int parsed = std::sscanf(command.c_str(), "NAME,%d,%d,%d,%n",
                                     &index, &part, &parts, &dataOffset);
      const char* data = command.c_str() + (dataOffset > 0 ? dataOffset : command.size());
      const size_t length = std::strlen(data);
      bool valid = parsed == 3 && dataOffset > 0 &&
          index >= 0 && index < 9 && parts >= 1 && parts <= 4 &&
          part >= 0 && part < parts && length >= 1 && length <= 24 &&
          (part == parts - 1 || length == 24);
      for (size_t i = 0; valid && i < length; ++i) {
        const unsigned char c = static_cast<unsigned char>(data[i]);
        if (c < 32 || c > 126 || c == ',') valid = false;
      }
      if (valid && batchActive && batchRequiresNames && !endRequested && nameQueue) {
        NameUpdate name{};
        name.index = static_cast<uint8_t>(index);
        name.part = static_cast<uint8_t>(part);
        name.parts = static_cast<uint8_t>(parts);
        std::memcpy(name.text, data, length);
        response->setValue(xQueueSend(nameQueue, &name, 0) == pdTRUE
                           ? "NAME:OK" : "NAME:BUSY");
      } else response->setValue("NAME:INVALID");
    } else if (command.compare(0, 4, "LVL,") == 0) {
      // LVL,index,slot,tier,count. Slot is tier-1; tier is 1..10.
      // Reuse command name for compatibility; Android and watch must match.
      int index = -1, slot = -1, level = 0, count = -1, consumed = 0;
      const int parsed = std::sscanf(command.c_str(), "LVL,%d,%d,%d,%d%n",
                                      &index, &slot, &level, &count, &consumed);
      if (parsed == 4 && consumed == static_cast<int>(command.size()) &&
          index >= 0 && index < 9 && slot >= 0 && slot < MAX_HARDNESS_TIERS &&
          level == slot + 1 && count > 0 && batchActive && !endRequested && levelQueue) {
        LevelUpdate update{index, slot, level, count};
        response->setValue(xQueueSend(levelQueue, &update, 0) == pdTRUE
                               ? "LVL:OK" : "LVL:BUSY");
      } else {
        response->setValue("LVL:INVALID");
      }
    } else if (command.compare(0, 4, "RAW,") == 0) {
      int index = -1, level = -1, count = -1, consumed = 0;
      const int parsed = std::sscanf(command.c_str(), "RAW,%d,%d,%d%n",
                                     &index, &level, &count, &consumed);
      if (parsed == 3 && consumed == static_cast<int>(command.size()) &&
          index >= 0 && index < 9 && level >= 1 && level <= 255 &&
          count > 0 && batchActive && !endRequested && rawQueue) {
        RawUpdate update{index, level, count};
        response->setValue(xQueueSend(rawQueue, &update, 0) == pdTRUE ? "RAW:OK" : "RAW:BUSY");
      } else response->setValue("RAW:INVALID");
    } else {
      response->setValue("ERR:UNKNOWN_COMMAND");
    }
    response->notify();
  }
};

ServerCallbacks serverCallbacks;
CommandCallbacks commandCallbacks;
}

void initializeBle() {
  cellQueue = xQueueCreate(12, sizeof(CellUpdate));
  levelQueue = xQueueCreate(24, sizeof(LevelUpdate));
  rawQueue = xQueueCreate(48, sizeof(RawUpdate));
  nameQueue = xQueueCreate(48, sizeof(NameUpdate));
  viewportQueue = xQueueCreate(16, sizeof(ViewportCommand));
  geoQueue = xQueueCreate(16, sizeof(GeoCommand));
  profileQueue = xQueueCreate(4, sizeof(ProfilePacket));
  ownerQueue = xQueueCreate(2, sizeof(OwnerPacket));
  teamQueue = xQueueCreate(8, sizeof(TeamPacket));
  clockQueue = xQueueCreate(1, sizeof(ClockSync));
  NimBLEDevice::init("WDG HUD");
  // DisplayOnly + Android passkey entry: authenticated LE Secure Connections
  // with persistent bonding. Keep the device name and UUIDs for migration.
  NimBLEDevice::setSecurityAuth(true, true, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(&serverCallbacks);
  NimBLEService* service = server->createService(SERVICE_UUID);
  NimBLECharacteristic* command = service->createCharacteristic(
      COMMAND_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_AUTHEN);
  command->setCallbacks(&commandCallbacks);
  response = service->createCharacteristic(
      RESPONSE_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_AUTHEN |
                     NIMBLE_PROPERTY::NOTIFY);
  response->setValue("READY");

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setName("WDG HUD");
  advertising->start();
}


// Called by showScreen() once per transition into OPERATOR.
void requestOperatorProfile() {
  profileRequestPending = true;
}
void requestTeamSummary() {teamSummaryRequestPending = true;}
void requestTeamRoster(uint16_t page) {teamPageRequestPending = page;}


void notifyStaleIntervalChanged(uint16_t minutes) {
  if (minutes < 1 || minutes > 240) return;
  staleIntervalToSync = minutes;
  staleIntervalSyncPending = true;
}

void pollBle() {
  // A new GATT owner must prove its account before sending personal packets.
  // Drain old-session staging without touching the geographic cache.
  if (ownerLinkResetPending || !connected) {
    ownerLinkResetPending = false;
    wdg::accountScopedStore.endSession();
    profileStage = ProfileStage{};
    teamStage = TeamStage{};
    if (profileQueue) xQueueReset(profileQueue);
    if (teamQueue) xQueueReset(teamQueue);
    if (ownerQueue) xQueueReset(ownerQueue);
  }
  OwnerPacket offerPacket{};
  if (connected && linkAuthenticated && ownerQueue &&
      xQueueReceive(ownerQueue, &offerPacket, 0) == pdTRUE) {
    wdg::AccountOwner offered{};
    const char* reply = "OWNER:INVALID";
    if (wdg::AccountOwner::fromHex(offerPacket.value + 6, offered)) {
      bool changed = false;
      if (wdg::accountScopedStore.offer(offered, changed)) {
        if (changed || !wdg::accountScopedStore.confirmed()) {
          resetAccountPersonalDisplay();
          profileStage = ProfileStage{};
          teamStage = TeamStage{};
          if (profileQueue) xQueueReset(profileQueue);
          if (teamQueue) xQueueReset(teamQueue);
          profileRequestPending = true;
        }
        reply = "OWNER:OK";
      } else reply = "OWNER:STORAGE";
    }
    if (response) { response->setValue(reply); response->notify(); }
    return;
  }
  // Publish only after a client has subscribed and completed the secure CAPS
  // handshake. One short notification; no AP packet and no GPS dependencies.
  // Re-sending after every CAPS recovers lost offline edits and reconnects.
  if (staleIntervalSyncPending && connected && linkAuthenticated &&
      profileClientReady && response && !batchActive &&
      !viewportTransaction.active() &&
      (!profileQueue || uxQueueMessagesWaiting(profileQueue) == 0) &&
      (!teamQueue || uxQueueMessagesWaiting(teamQueue) == 0)) {
    char packet[24];
    snprintf(packet, sizeof(packet), "CFG:STALE,%u",
             static_cast<unsigned>(staleIntervalToSync));
    staleIntervalSyncPending = false;
    response->setValue(packet);
    response->notify();
    return; // Do not mix with another notify on this loop iteration.
  }
  // Request only while a phone is connected. If opened offline, the request
  // remains pending until the next successful BLE connection.
  if (profileRequestPending && wdg::accountScopedStore.sessionReady() &&
      connected && linkAuthenticated && profileClientReady && response &&
      !batchActive && !viewportTransaction.active()) {
    profileRequestPending = false;
    profileStage = ProfileStage{};
    response->setValue("PROFILE:GET");
    response->notify();
  }
  // Summary requests are notifications, not fake AP sync packets.
  if (teamSummaryRequestPending && wdg::accountScopedStore.sessionReady() &&
      wdg::accountScopedStore.confirmed() && connected && linkAuthenticated && profileClientReady &&
      response && !batchActive && !viewportTransaction.active()) {
    teamSummaryRequestPending=false;
    teamStage=TeamStage{};
    response->setValue("TEAM:GET"); response->notify();
  } else if (teamPageRequestPending >= 0 && wdg::accountScopedStore.sessionReady() &&
             wdg::accountScopedStore.confirmed() && connected && linkAuthenticated &&
             profileClientReady && response && !batchActive && !viewportTransaction.active()) {
    const int page=teamPageRequestPending;
    teamPageRequestPending=-1;
    char msg[32];snprintf(msg,sizeof(msg),"TEAM:PAGE:%d",page);
    response->setValue(msg);response->notify();
  }
  // G5A22: atomic OPERATOR profile delivery; S -> N -> G chunks -> E.
  // Each short chunk has its own ACK; no larger BLE MTU is required.
  ProfilePacket profilePacket{};
  if (profileQueue && xQueueReceive(profileQueue, &profilePacket, 0) == pdTRUE) {
    if (!wdg::accountScopedStore.sessionReady()) {
      if (response) { response->setValue("PROFILE:OWNER_REQUIRED"); response->notify(); }
    } else {
    const char* message = profilePacket.value;
    char replyBuffer[32] = {};
    const char* reply = "PROFILE:INVALID";
    if (std::strcmp(message, "PROFILE,ERR") == 0) {
      profileStage = ProfileStage{};
      operatorProfileFetchFailed();
      reply = "PROFILE:ERR:OK";
    } else if (std::strncmp(message, "PROFILE,S,", 10) == 0) {
      profileStage = ProfileStage{};
      unsigned long wifi=0, ble=0, total=0, badges=0;
      int n=0;
      if (std::sscanf(message, "PROFILE,S,%lu,%lu,%lu,%lu%n",
                      &wifi, &ble, &total, &badges, &n) == 4 &&
          n == static_cast<int>(std::strlen(message)) &&
          wifi <= 999999999UL && ble <= 999999999UL &&
          total <= 999999999UL && badges <= 999999999UL) {
        profileStage.wifi = static_cast<uint32_t>(wifi);
        profileStage.ble = static_cast<uint32_t>(ble);
        profileStage.total = static_cast<uint32_t>(total);
        profileStage.badges = static_cast<uint32_t>(badges);
        profileStage.haveStats = true;
        reply = "PROFILE:S:OK";
      }
    } else if (std::strncmp(message, "PROFILE,N,", 10) == 0 &&
               profileStage.haveStats) {
      const char* name = message + 10;
      const size_t length = std::strlen(name);
      if (length >= 1 && length <= 20 && std::strchr(name, ',') == nullptr) {
        std::memcpy(profileStage.username, name, length + 1);
        profileStage.haveName = true;
        profileStage.gang[0] = '\0';
        profileStage.gangLength = 0;
        profileStage.nextGangChunk = 0;
        profileStage.expectedGangChunks = 0;
        reply = "PROFILE:N:OK";
      }
    } else if (std::strncmp(message, "PROFILE,G,", 10) == 0 &&
               profileStage.haveStats && profileStage.haveName) {
      int index = -1, count = -1, dataOffset = 0;
      if (std::sscanf(message, "PROFILE,G,%d,%d,%n",
                      &index, &count, &dataOffset) == 2 && dataOffset > 0) {
        const char* data = message + dataOffset;
        const size_t dataLength = std::strlen(data);
        bool clean = true;
        for (size_t i = 0; i < dataLength; ++i) {
          if (static_cast<unsigned char>(data[i]) < 32 ||
              static_cast<unsigned char>(data[i]) > 126 || data[i] == ',') {
            clean = false;
            break;
          }
        }
        if (count >= 1 && count <= 4 && index == profileStage.nextGangChunk &&
            (profileStage.expectedGangChunks == 0 ||
             count == profileStage.expectedGangChunks) &&
            index < count && dataLength >= 1 && dataLength <= 24 && clean &&
            profileStage.gangLength + dataLength < sizeof(profileStage.gang)) {
          std::memcpy(profileStage.gang + profileStage.gangLength, data, dataLength);
          profileStage.gangLength += dataLength;
          profileStage.gang[profileStage.gangLength] = '\0';
          profileStage.expectedGangChunks = count;
          ++profileStage.nextGangChunk;
          std::snprintf(replyBuffer, sizeof(replyBuffer), "PROFILE:G:%d:OK", index);
          reply = replyBuffer;
        }
      }
    } else if (std::strncmp(message,"PROFILE,A,",10)==0 &&
               profileStage.haveStats && profileStage.haveName) {
      char rank[16]={};int days=-1,n=0;
      if(sscanf(message,"PROFILE,A,%15[^,],%d%n",rank,&days,&n)==2 &&
         n==(int)strlen(message) && days>=-1 && days<=30) {
        snprintf(profileStage.activityRank,sizeof(profileStage.activityRank),"%s",rank);
        profileStage.activeDays=days;
        profileStage.haveActivity=true;
        reply="PROFILE:A:OK";
      }
    } else if (std::strcmp(message, "PROFILE,E") == 0 &&
               profileStage.haveStats && profileStage.haveName &&
               profileStage.expectedGangChunks > 0 &&
               profileStage.nextGangChunk == profileStage.expectedGangChunks &&
               profileStage.gangLength > 0) {
      wdg::ScopedOperator snapshot{};
      snprintf(snapshot.username, sizeof(snapshot.username), "%s", profileStage.username);
      snprintf(snapshot.gang, sizeof(snapshot.gang), "%s", profileStage.gang);
      snapshot.wifi = profileStage.wifi; snapshot.ble = profileStage.ble;
      snapshot.total = profileStage.total; snapshot.badges = profileStage.badges;
      if (wdg::accountScopedStore.commitOperator(snapshot)) {
        setOperatorProfile(snapshot.username, snapshot.gang,
                           snapshot.wifi, snapshot.ble, snapshot.total, snapshot.badges);
        if (profileStage.haveActivity)
          setOperatorActivity(profileStage.activityRank, profileStage.activeDays);
        restoreScopedGangAfterProfile();
        teamSummaryRequestPending = true;
        reply = "PROFILE:E:OK";
      } else reply = "PROFILE:E:STORAGE";
      profileStage = ProfileStage{};
    }
    if (response) { response->setValue(reply); response->notify(); }
    }
  }
  // Team snapshot/page is staged separately; bad/missing data never affects AP cache.
  TeamPacket teamPacket{};
  if(teamQueue && xQueueReceive(teamQueue,&teamPacket,0)==pdTRUE) {
    if (!wdg::accountScopedStore.sessionReady() || !wdg::accountScopedStore.confirmed()) {
      if (response) { response->setValue("TEAM:OWNER_REQUIRED"); response->notify(); }
    } else {
    const char* message=teamPacket.value;
    const char* reply="TEAM:INVALID";
    char out[32]={};
    if(strcmp(message,"TEAM,EMPTY")==0) {
      teamStage=TeamStage{};setTeamNoTeam();reply="TEAM:EMPTY:OK";
    } else if(strcmp(message,"TEAM,ERR")==0) {
      teamStage=TeamStage{};setTeamFetchFailed();reply="TEAM:ERR:OK";
    } else if(strncmp(message,"TEAM,S,",7)==0) {
      int rank=-2,count=-1,credits=-2,n=0;
      if(sscanf(message,"TEAM,S,%d,%d,%d%n",&rank,&count,&credits,&n)==3 &&
         n==(int)strlen(message) && rank>=-1 && count>=0 && count<=5000 && credits>=-1) {
        teamStage=TeamStage{};
        teamStage.rank=rank;teamStage.members=count;teamStage.credits=credits;
        teamStage.haveSummary=true;reply="TEAM:S:OK";
      }
    } else if(strncmp(message,"TEAM,T,",7)==0 && teamStage.haveSummary) {
      int wifi=-2,ble=-2,total=-2,n=0;
      if(sscanf(message,"TEAM,T,%d,%d,%d%n",&wifi,&ble,&total,&n)==3 &&
         n==(int)strlen(message) && wifi>=-1 && ble>=-1 && total>=-1) {
        teamStage.wifi=wifi;teamStage.ble=ble;teamStage.total=total;
        teamStage.haveTotals=true;reply="TEAM:T:OK";
      }
    } else if(strncmp(message,"TEAM,C,",7)==0 && teamStage.haveTotals) {
      uint16_t color=0x07FF;
      if(strcmp(message+7,"NONE")==0 || parseOfficialColor(message+7,color)) {
        teamStage.color=color;reply="TEAM:C:OK";
      }
    } else if(strncmp(message,"TEAM,N,",7)==0 && teamStage.haveTotals) {
      int index=-1,count=-1,at=0;
      if(sscanf(message,"TEAM,N,%d,%d,%n",&index,&count,&at)==2 && at>0) {
        const char* part=message+at;
        const int length=strlen(part);
        bool clean=length>=1 && length<=24;
        for(int i=0;i<length;++i) if((unsigned char)part[i]<32 ||
            (unsigned char)part[i]>126 || part[i]==',') clean=false;
        if(clean && count>=1 && count<=4 && index==teamStage.nextChunk && index<count &&
           (!teamStage.chunkCount || teamStage.chunkCount==count) &&
           teamStage.nameLength+length<sizeof(teamStage.name)) {
          memcpy(teamStage.name+teamStage.nameLength,part,length);
          teamStage.nameLength+=length;
          teamStage.name[teamStage.nameLength]=0;
          teamStage.chunkCount=count;++teamStage.nextChunk;
          snprintf(out,sizeof(out),"TEAM:N:%d:OK",index);reply=out;
        }
      }
    } else if(strcmp(message,"TEAM,E")==0 && teamStage.haveTotals &&
              teamStage.chunkCount>0 && teamStage.nextChunk==teamStage.chunkCount) {
      setTeamSummary(teamStage.name,teamStage.rank,teamStage.members,
                     teamStage.wifi,teamStage.ble,teamStage.total,teamStage.credits,
                     teamStage.color);
      teamStage=TeamStage{};reply="TEAM:E:OK";
    } else if(strncmp(message,"TEAM,P,",7)==0) {
      int page=-1,pages=-1,n=0;
      if(sscanf(message,"TEAM,P,%d,%d%n",&page,&pages,&n)==2 &&
         n==(int)strlen(message) && page>=0 && page<65535 && pages>=1 && pages<=65535 && page<pages) {
        teamStage.page=page;teamStage.pages=pages;teamStage.nextMember=0;
        for(auto &row:teamStage.rows)row=TeamMemberRow{};
        reply="TEAM:P:OK";
      }
    } else if(strncmp(message,"TEAM,R,",7)==0 && teamStage.page>=0) {
      int slot=-1,role=-1,total=-2,at=0;
      if(sscanf(message,"TEAM,R,%d,%d,%d,%n",&slot,&role,&total,&at)==3 && at>0) {
        const char* user=message+at;
        const size_t len=strlen(user);
        bool clean=len>=1 && len<=20;
        for(size_t i=0;i<len;++i)if((unsigned char)user[i]<32 ||
           (unsigned char)user[i]>126 || user[i]==',')clean=false;
        if(clean && slot==teamStage.nextMember && slot<4 && role>=0 && role<=2 && total>=-1) {
          TeamMemberRow &row=teamStage.rows[slot];
          snprintf(row.username,sizeof(row.username),"%s",user);
          row.role=role;row.total=total;
          ++teamStage.nextMember;
          snprintf(out,sizeof(out),"TEAM:R:%d:OK",slot);reply=out;
        }
      }
    } else if(strcmp(message,"TEAM,Q")==0 && teamStage.page>=0) {
      setTeamRosterPage((uint16_t)teamStage.page,(uint16_t)teamStage.pages,
                        teamStage.rows,teamStage.nextMember);
      teamStage.page=-1;reply="TEAM:Q:OK";
    }
    if(response){response->setValue(reply);response->notify();}
    }
  }
  ClockSync clock{};
  if (clockQueue && xQueueReceive(clockQueue, &clock, 0) == pdTRUE) {
    if (!syncOperatorClock(clock.year, clock.month, clock.day,
                           clock.hours, clock.minutes, clock.seconds,
                           clock.weekday))
      Serial.println("G5A15 PCF85063 RTC sync failed");
  }
  if (viewportDisconnectPending || !connected) {
    viewportDisconnectPending = false;
    viewportTransaction.resetSession();
    geoCacheAbort();
    if (geoQueue) xQueueReset(geoQueue);
    if (viewportQueue) xQueueReset(viewportQueue);
  }
  if (viewportTransaction.active() &&
      millis() - viewportStartedMs > VIEWPORT_TIMEOUT_MS) {
    viewportTransaction.abort();
    Serial.println("G4C2 viewport timeout; staged transaction discarded");
  }
  // Process a bounded number of G4C2 packets per tick; never block display/GPS.
  if (connected && viewportQueue) {
    ViewportCommand v{};
    for (int processed = 0; processed < 16 &&
                            xQueueReceive(viewportQueue, &v, 0) == pdTRUE; ++processed) {
      unsigned long seq = 0;
      int north = 0, east = 0, slot = -1, total = -1, consumed = 0;
      char state = 0;
      wdg::ViewportResult result = wdg::ViewportResult::InvalidData;
      const char* ack = "V:INVALID";
      const std::string message(v.value);
      if (std::sscanf(v.value, "V,BEGIN,%lu,%d,%d%n",
                      &seq, &north, &east, &consumed) == 3 &&
          consumed == static_cast<int>(message.size()) &&
          seq <= UINT32_MAX && !batchActive) {
        result = viewportTransaction.begin(static_cast<uint32_t>(seq), north, east);
        if (result == wdg::ViewportResult::Ok) viewportStartedMs = millis();
        ack = result == wdg::ViewportResult::Ok ? "VBEGIN:OK" : "VBEGIN:REJECT";
      } else if (std::sscanf(v.value, "V,TILE,%lu,%d,%d,%d,%c,%d%n",
                             &seq, &slot, &north, &east, &state, &total, &consumed) == 6 &&
                 consumed == static_cast<int>(message.size()) &&
                 seq <= UINT32_MAX && slot >= 0 && slot < 9 &&
                 (state == 'P' || state == 'U')) {
        result = viewportTransaction.put(static_cast<uint32_t>(seq),
                   static_cast<uint8_t>(slot), north, east,
                   state == 'P' ? wdg::TileState::Present : wdg::TileState::Unknown, total);
        ack = result == wdg::ViewportResult::Ok ? "VTILE:OK" : "VTILE:REJECT";
      } else if (std::sscanf(v.value, "V,END,%lu%n", &seq, &consumed) == 1 &&
                 consumed == static_cast<int>(message.size()) && seq <= UINT32_MAX) {
        result = viewportTransaction.commit(static_cast<uint32_t>(seq));
        if (result == wdg::ViewportResult::Ok) {
          // A geographic totals-only viewport is NEVER allowed to replace
          // the last complete detailed 3x3 grid (including one restored from flash).
          viewportDirty = !live3x3Data;
          viewportRenderActive = !live3x3Data;
          Serial.printf("G4C2 COMMIT seq=%lu north=%ld east=%ld (not rendered)\\n",
                        seq,
                        static_cast<long>(viewportTransaction.current().centerNorth),
                        static_cast<long>(viewportTransaction.current().centerEast));
        }
        ack = result == wdg::ViewportResult::Ok ? "VEND:OK" : "VEND:REJECT";
      }
      // Existing Android G4B has no viewport sender and is unaffected.
      if (response) { response->setValue(ack); response->notify(); }
    }
  }
  // Bounded geo-cache command processor. Every ACK is sent only after
  // validation/commit; separate from BEGIN/CELL/LVL/RAW/END.
  if (connected && geoQueue) {
    GeoCommand packet{};
    for (int handled = 0; handled < 8 &&
         xQueueReceive(geoQueue, &packet, 0) == pdTRUE; ++handled) {
      const char* cmd = packet.value;
      const int len = static_cast<int>(std::strlen(cmd));
      const char* ack = "K:INVALID";
      long n=0,e=0;int total=0,part=0,parts=0,level=0,count=0,used=0;
      unsigned color=0;
      if (std::sscanf(cmd,"CENTER,%ld,%ld%n",&n,&e,&used)==2 && used==len &&
          n>=INT32_MIN&&n<=INT32_MAX&&e>=INT32_MIN&&e<=INT32_MAX) {
        ack=geoCacheRecenter(static_cast<int32_t>(n),static_cast<int32_t>(e))
            ? "CENTER:OK" : "CENTER:MISS";
      } else if (std::sscanf(cmd,"K,B,%ld,%ld,%d,%x%n",
                             &n,&e,&total,&color,&used)==4 && used==len &&
                 n>=INT32_MIN&&n<=INT32_MAX&&e>=INT32_MIN&&e<=INT32_MAX&&
                 color<=0xFFFF && !batchActive) {
        ack=geoCacheBegin(static_cast<int32_t>(n),static_cast<int32_t>(e),
                           total,static_cast<uint16_t>(color)) ? "KB:OK":"KB:INVALID";
      } else if (std::sscanf(cmd,"K,N,%d,%d,%n",&part,&parts,&used)==2 &&
                 used>0 && used<len) {
        ack=geoCacheName(part,parts,cmd+used) ? "KN:OK":"KN:INVALID";
      } else if (std::sscanf(cmd,"K,R,%d,%d%n",&level,&count,&used)==2 && used==len) {
        ack=geoCacheRaw(level,count) ? "KR:OK":"KR:INVALID";
      } else if (std::strcmp(cmd,"K,E")==0) {
        ack=geoCacheCommit() ? "KE:OK":"KE:INVALID";
      } else if (std::strcmp(cmd,"K,A")==0) {
        geoCacheAbort();ack="KA:OK";
      }
      if (response) {response->setValue(ack);response->notify();}
    }
  }
  if (!cellQueue) return;
  if (batchActive && (!connected || millis() - batchStartMs > 120000UL)) {
    clearBatch();
    tickerStopRequested = true;
    return;
  }
  CellUpdate update;
  while (xQueueReceive(cellQueue, &update, 0) == pdTRUE) {
    if (!batchActive) continue;
    StagedSector& cell = staged[update.index];
    cell.total = update.count;
    cell.color = update.hasOfficialColor ? update.officialColor : gangColor(update.gang);
    std::memset(cell.gangName, 0, sizeof(cell.gangName));
    std::memcpy(cell.gangName, update.gangName, sizeof(update.gangName));
    cell.nameParts = 0;
    cell.nameNextPart = 0;
    stagedCellsMask |= static_cast<uint16_t>(1U << update.index);
  }
  // Names are drained after CELL packets; the NAME:OK acknowledgment means
  // queued, not committed. END verifies every slot has a complete name.
  if (nameQueue) {
    NameUpdate name{};
    while (xQueueReceive(nameQueue, &name, 0) == pdTRUE) {
      if (!batchActive) continue;
      StagedSector& cell = staged[name.index];
      const size_t length = std::strlen(name.text);
      const size_t offset = static_cast<size_t>(name.part) * 24;
      const bool ok = (stagedCellsMask & (1U << name.index)) &&
          name.part == cell.nameNextPart &&
          (name.part == 0 || cell.nameParts == name.parts) &&
          length > 0 && offset + length < sizeof(cell.gangName);
      if (!ok) { batchNamesInvalid = true; continue; }
      if (name.part == 0) {
        cell.nameParts = name.parts;
        std::memset(cell.gangName, 0, sizeof(cell.gangName));
      }
      std::memcpy(cell.gangName + offset, name.text, length);
      cell.gangName[offset + length] = 0;
      ++cell.nameNextPart;
    }
  }
  if (levelQueue) {
    LevelUpdate level;
    while (xQueueReceive(levelQueue, &level, 0) == pdTRUE) {
      if (!batchActive) continue;
      StagedSector& cell = staged[level.index];
      if (cell.count[level.level] == 0) ++stagedLevels;
      cell.count[level.level] = level.count;
    }
  }
  if (rawQueue) {
    RawUpdate raw;
    while (xQueueReceive(rawQueue, &raw, 0) == pdTRUE) {
      if (!batchActive) continue;
      if (staged[raw.index].raw[raw.level] == 0) ++stagedRaw;
      staged[raw.index].raw[raw.level] = raw.count;
    }
  }
  if (batchActive && endRequested) {
    // END is accepted only after Android got acknowledgements for every packet.
    // Acknowledge END promptly; validate and commit after both queues drain.
    bool valid = stagedCellsMask == 0x01FF && stagedLevels == expectedLevels &&
                 stagedRaw == expectedRaw && !batchNamesInvalid;
    if (valid && !batchRequiresNames) {
      // G5A36.1: CELL labels ending in '~' are explicitly truncated by the
      // older Android wire encoder. Never persist such a partial-name update.
      for (int i = 0; i < 9; ++i) {
        const size_t len = std::strlen(staged[i].gangName);
        if (len > 0 && staged[i].gangName[len - 1] == '~') {
          valid = false;
          Serial.println("G5A36.1: rejected legacy truncated gang name; GANG96 required");
          break;
        }
      }
    }
    if (valid && batchRequiresNames) {
      for (int i = 0; i < 9; ++i) {
        if (staged[i].nameParts < 1 ||
            staged[i].nameNextPart != staged[i].nameParts)
          valid = false;
      }
    }
    // Validate every sector against the API-reported AP total; otherwise a
    // partial tier list could silently hide rare high-hardness access points.
    if (valid) {
      for (int i = 0; i < 9; ++i) {
        int64_t summed = 0;
        for (int tier = 1; tier <= MAX_HARDNESS_TIERS; ++tier)
          summed += staged[i].count[tier];
        int64_t rawSum = 0;
        int64_t recomputed[MAX_HARDNESS_TIERS + 1] = {};
        for (int level = 1; level <= 255; ++level) {
          rawSum += staged[i].raw[level];
          const int tier = ((level - 1) / 10 + 1) > MAX_HARDNESS_TIERS
              ? MAX_HARDNESS_TIERS : (level - 1) / 10 + 1;
          recomputed[tier] += staged[i].raw[level];
        }
        if (rawSum != staged[i].total) valid = false;
        for (int tier = 1; tier <= MAX_HARDNESS_TIERS; ++tier)
          if (recomputed[tier] != staged[i].count[tier]) valid = false;
        if (summed != staged[i].total) {
          Serial.printf("E4 mismatch sector=%d levels=%lld total=%d\n",
                        i, static_cast<long long>(summed), staged[i].total);
          valid = false;
        }
      }
    }
    if (valid) {
      // G5A41: GPS may have changed CENTER while this older nine-sector
      // snapshot was uploading. Capture the LIVE geographic center before
      // committing the batch; an old END must never drag the watch backward.
      int32_t liveCenterNorth = 0, liveCenterEast = 0;
      const bool hadGpsCenter = geoCacheCurrentCenter(liveCenterNorth, liveCenterEast);
      const bool newerGpsCenter = hadGpsCenter && batchGeoTagged &&
          (liveCenterNorth != batchCenterNorth || liveCenterEast != batchCenterEast);
      for (int i = 0; i < 9; ++i) {
        sectors3x3[i].total = staged[i].total;
        for (int level = 1; level <= 255; ++level)
          liveRawLevels[i][level] = staged[i].raw[level];
        sectors3x3[i].territoryColor = staged[i].color;
        std::memcpy(sectors3x3[i].gangName, staged[i].gangName,
                    sizeof(sectors3x3[i].gangName));
        sectors3x3[i].gangName[sizeof(sectors3x3[i].gangName) - 1] = '\0';
        int dominant = 0;
        for (int tier = 1; tier <= MAX_HARDNESS_TIERS; ++tier) {
          liveTierCounts[i][tier] = staged[i].count[tier];
          if (staged[i].count[tier] > 0 &&
              (dominant == 0 || staged[i].count[tier] > staged[i].count[dominant]))
            dominant = tier;
        }
        // Display the three tiers with the largest AP counts.
        // Stable tie-break: prefer the lower hardness tier.
        int visible[3] = {0, 0, 0};
        for (int tier = 1; tier <= MAX_HARDNESS_TIERS; ++tier) {
          if (staged[i].count[tier] <= 0) continue;
          for (int slot = 0; slot < 3; ++slot) {
            if (visible[slot] == 0 || staged[i].count[tier] > staged[i].count[visible[slot]]) {
              for (int tail = 2; tail > slot; --tail) visible[tail] = visible[tail - 1];
              visible[slot] = tier;
              break;
            }
          }
        }
        for (int j = 0; j < 3; ++j) {
          liveLevelNumbers[i][j] = visible[j];
          liveLevelCounts[i][j] = visible[j] ? staged[i].count[visible[j]] : 0;
        }
      }
      live3x3Data = true;
      viewportRenderActive = false; // A fully validated detailed grid supersedes totals-only.
      viewportDirty = false;
      lastScanProgress = 100; // All validated work is complete; COMPLETE replaces the bar.
      sectorDirty = true; // Exactly one full-screen redraw on successful END.
      response->setValue("END:OK");
      response->notify();
      persistLastGoodGrid();  // Complete validated sample only; unchanged snapshots are not re-written.
      if (batchGeoTagged) {
        geoCacheFromValidatedGrid(batchCenterNorth,batchCenterEast);
        if (newerGpsCenter) {
          // Restore the new GPS center AFTER saving valid old-sector data.
          // This happens in the same poll tick, before the screen repaints.
          // Missing tiles remain UNKNOWN until their AP records arrive.
          geoCacheRecenter(liveCenterNorth, liveCenterEast);
          Serial.printf("G5A41: old AP END kept GPS center north=%ld east=%ld\n",
                        static_cast<long>(liveCenterNorth), static_cast<long>(liveCenterEast));
        }
      }
      Serial.printf("E4 COMMIT 9 sectors, %d tiers, redraw=1\n", stagedLevels);
    } else {
      response->setValue("END:INCOMPLETE");
      response->notify();
      Serial.printf("E4 REJECT cells=%03x tiers=%d/%d\n",
                    stagedCellsMask, stagedLevels, expectedLevels);
    }
    tickerStopRequested = true;
    clearBatch();
  }
}

bool consumeSectorUpdate() {
  const bool changed = sectorDirty;
  sectorDirty = false;
  return changed;
}

// Polled on the Arduino main loop, never draw from NimBLE callbacks.
bool consumeTickerStart() { bool v = tickerStartRequested; tickerStartRequested = false; return v; }
bool consumeTickerStop() { bool v = tickerStopRequested; tickerStopRequested = false; return v; }
bool consumeTickerSync() { bool v = tickerSyncRequested; tickerSyncRequested = false; return v; }
bool consumeCheckingStart() { bool v = checkingStartRequested; checkingStartRequested = false; return v; }
bool consumeCheckingStop() { bool v = checkingStopRequested; checkingStopRequested = false; return v; }
bool isUplinkInProgress() { return batchActive; }

// G5A42: manual discovery recovery for the Waveshare BOOT (GPIO0) button.
// Android remains the BLE central and performs the actual connection.
// Run on the Arduino main loop (not in NimBLE callbacks). No effect when linked.
void pollBleReconnectButton() {
  constexpr int kBootPin = 0;
  static bool downLast = false;
  static uint32_t pressedAt = 0;
  static uint32_t lastRestart = 0;
  const bool down = digitalRead(kBootPin) == LOW;
  const uint32_t nowMs = millis();
  if (down && !downLast) pressedAt = nowMs;
  if (!down && downLast) {
    const uint32_t held = nowMs - pressedAt;
    // Only a short deliberate release: no accidental long-press recovery.
    // Connected behavior is intentionally a no-op.
    if (held >= 60 && held <= 1200 && !connected &&
        (!lastRestart || nowMs - lastRestart >= 2000)) {
      if (auto* advertising = NimBLEDevice::getAdvertising()) {
        advertising->stop();
        const bool started = advertising->start();
        Serial.printf("G5A42 BOOT disconnected retry: advertising=%s\n",
                      started ? "started" : "failed");
        lastRestart = nowMs;
      }
    }
  }
  downLast = down;
}

// G5A44: pairing UI state is transferred on the main loop; no graphics in BLE
// callbacks. The watch can maintain its last-good sector cache during pairing.
bool takeBlePairingCode(uint32_t& code) {
  if (!pairingPromptPending || !pairingPromptActive) return false;
  code = pairingPromptCode;
  pairingPromptPending = false;
  return true;
}
bool consumeBlePairingFinished() {
  const bool finished = pairingPromptFinished;
  pairingPromptFinished = false;
  return finished;
}
bool bleIsConnected() { return connected && linkAuthenticated; }
uint32_t gpsFixAgeMs() { return latestGps.valid ? millis() - latestGps.updatedMs : UINT32_MAX; }
bool getLastGps(float& lat, float& lon, float& mph, float& bearing) {
  if (!latestGps.valid) return false;
  lat = latestGps.latitude; lon = latestGps.longitude;
  mph = latestGps.speedMph; bearing = latestGps.bearingDegrees;
  return true;
}

// Only trust Android's explicitly valid motion bearing, not a default 0-degree value.
bool getGpsHeading(float& bearing, float& speedMph) {
  if (!latestGps.valid || !latestGps.bearingValid) return false;
  bearing = latestGps.bearingDegrees;
  speedMph = latestGps.speedMph;
  return true;
}

// Stable event serial for UI: GPS packets with bearing flag 0 never count as
// a new direction, even if they refresh location or speed. Sample speed is
// captured from the same packet as the bearing, not a later stationary fix.
bool getGpsHeadingUpdate(float& bearing, float& speedMph, uint32_t& serial) {
  if (!latestGps.valid || !latestGps.bearingValid || !latestGps.bearingSerial)
    return false;
  bearing = latestGps.bearingDegrees;
  speedMph = latestGps.bearingSpeedMph;
  serial = latestGps.bearingSerial;
  return true;
}

// Read on Arduino's drawing loop. Writing this value never modifies a sector.
int getScanProgress() { return lastScanProgress; }

// G4C3: restored geographic cache updates and atomic redraws, independent of AP tiers.
bool consumeViewportUpdate() {
  const bool updated = viewportDirty;
  viewportDirty = false;
  return updated;
}
bool viewportDisplayActive() {
  return !live3x3Data && viewportRenderActive && viewportTransaction.current().valid;
}
const wdg::ViewportSnapshot& currentViewport() { return viewportTransaction.current(); }
