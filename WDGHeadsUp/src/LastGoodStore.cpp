#include <Arduino.h>
#include <Preferences.h>
#include <cstdint>
#include <cstring>
#include <vector>
#include <climits>
#include "LastGoodStore.h"
#include "AccountScopedStore.h"
#include "SectorModel.h"
#include "Display.h"
#include "BleTransport.h"

extern bool live3x3Data;
extern int liveLevelNumbers[9][3];
extern int liveLevelCounts[9][3];

namespace {
constexpr char kNamespace[] = "wdgsnap35";
constexpr char kGridKey[] = "grid";
constexpr char kOperatorKey[] = "operator";
constexpr uint8_t kVersion = 1;
constexpr size_t kGridMaxBytes = 16384;
constexpr size_t kOperatorMaxBytes = 256;
uint32_t savedGridChecksum = 0;
uint32_t savedOperatorChecksum = 0;
bool gridSaved = false;
bool operatorSaved = false;

// These static staging buffers avoid placing ~10KB on Arduino's small setup stack.
struct GridStage {
  SectorData sector[9] = {};
  int raw[9][256] = {};
  int tier[9][11] = {};
  int topLevel[9][3] = {};
  int topCount[9][3] = {};
};
GridStage restoredGrid;

void append32(std::vector<uint8_t>& bytes, uint32_t value) {
  for (int i = 0; i < 4; ++i) bytes.push_back(static_cast<uint8_t>(value >> (8 * i)));
}
void append16(std::vector<uint8_t>& bytes, uint16_t value) {
  bytes.push_back(static_cast<uint8_t>(value));
  bytes.push_back(static_cast<uint8_t>(value >> 8));
}
void appendVar(std::vector<uint8_t>& bytes, uint32_t value) {
  do {
    uint8_t digit = static_cast<uint8_t>(value & 0x7F);
    value >>= 7;
    bytes.push_back(value ? static_cast<uint8_t>(digit | 0x80) : digit);
  } while (value);
}
uint32_t fnv(const uint8_t* data, size_t length) {
  uint32_t checksum = 2166136261u;
  for (size_t i = 0; i < length; ++i) {
    checksum ^= data[i];
    checksum *= 16777619u;
  }
  return checksum;
}
bool next8(const std::vector<uint8_t>& bytes, size_t& at, uint8_t& value, size_t end) {
  if (at >= end) return false;
  value = bytes[at++];
  return true;
}
bool next16(const std::vector<uint8_t>& bytes, size_t& at, uint16_t& value, size_t end) {
  if (end - at < 2) return false;
  value = static_cast<uint16_t>(bytes[at] | (bytes[at + 1] << 8));
  at += 2;
  return true;
}
bool next32(const std::vector<uint8_t>& bytes, size_t& at, uint32_t& value, size_t end) {
  if (end - at < 4) return false;
  value = static_cast<uint32_t>(bytes[at]) |
          (static_cast<uint32_t>(bytes[at + 1]) << 8) |
          (static_cast<uint32_t>(bytes[at + 2]) << 16) |
          (static_cast<uint32_t>(bytes[at + 3]) << 24);
  at += 4;
  return true;
}
bool nextVar(const std::vector<uint8_t>& bytes, size_t& at, uint32_t& value, size_t end) {
  value = 0;
  for (int i = 0; i < 5; ++i) {
    uint8_t digit = 0;
    if (!next8(bytes, at, digit, end)) return false;
    if (i == 4 && (digit & 0xF0)) return false;
    value |= static_cast<uint32_t>(digit & 0x7F) << (7 * i);
    if (!(digit & 0x80)) return true;
  }
  return false;
}

bool validName(const char* value, size_t capacity, bool allowEmpty) {
  const size_t n = strnlen(value, capacity);
  if (n == capacity || (!allowEmpty && n == 0)) return false;
  for (size_t i = 0; i < n; ++i)
    if (static_cast<uint8_t>(value[i]) < 32 || static_cast<uint8_t>(value[i]) > 126)
      return false;
  return true;
}

bool checkEnvelope(const std::vector<uint8_t>& bytes, uint8_t type, uint32_t& checksum) {
  if (bytes.size() < 10 || std::memcmp(bytes.data(), "WDGH", 4) != 0 ||
      bytes[4] != kVersion || bytes[5] != type) return false;
  size_t pos = bytes.size() - 4;
  uint32_t stored = 0;
  if (!next32(bytes, pos, stored, bytes.size())) return false;
  checksum = fnv(bytes.data(), bytes.size() - 4);
  return stored == checksum;
}
void finishEnvelope(std::vector<uint8_t>& bytes, uint32_t& checksum) {
  checksum = fnv(bytes.data(), bytes.size());
  append32(bytes, checksum);
}
std::vector<uint8_t> makeEnvelope(uint8_t type) {
  std::vector<uint8_t> bytes;
  bytes.reserve(3072);
  bytes.insert(bytes.end(), {'W', 'D', 'G', 'H', kVersion, type});
  return bytes;
}

std::vector<uint8_t> encodeGrid(uint32_t& hash) {
  auto bytes = makeEnvelope(1);
  for (int i = 0; i < 9; ++i) {
    const SectorData& sector = sectors3x3[i];
    if (sector.total < 0 || !validName(sector.gangName, sizeof(sector.gangName), true)) return {};
    append32(bytes, static_cast<uint32_t>(sector.total));
    append16(bytes, sector.territoryColor);
    const size_t len = std::strlen(sector.gangName);
    bytes.push_back(static_cast<uint8_t>(len));
    bytes.insert(bytes.end(), sector.gangName, sector.gangName + len);
    size_t countAt = bytes.size();
    append16(bytes, 0);
    uint16_t nonzero = 0;
    uint64_t sum = 0;
    for (int lvl = 1; lvl <= 255; ++lvl) {
      const int count = liveRawLevels[i][lvl];
      if (count < 0) return {};
      if (count == 0) continue;
      ++nonzero;
      sum += static_cast<uint32_t>(count);
      bytes.push_back(static_cast<uint8_t>(lvl));
      appendVar(bytes, static_cast<uint32_t>(count));
    }
    if (sum != static_cast<uint32_t>(sector.total)) return {};
    bytes[countAt] = static_cast<uint8_t>(nonzero);
    bytes[countAt + 1] = static_cast<uint8_t>(nonzero >> 8);
    if (bytes.size() > kGridMaxBytes - 4) return {};
  }
  finishEnvelope(bytes, hash);
  return bytes;
}

bool decodeGrid(const std::vector<uint8_t>& bytes) {
  uint32_t checksum = 0;
  if (!checkEnvelope(bytes, 1, checksum)) return false;
  std::memset(&restoredGrid, 0, sizeof(restoredGrid));
  const size_t end = bytes.size() - 4;
  size_t pos = 6;
  for (int i = 0; i < 9; ++i) {
    uint32_t total = 0;
    uint16_t color = 0, entryCount = 0;
    uint8_t nameLen = 0;
    if (!next32(bytes, pos, total, end) || total > INT_MAX ||
        !next16(bytes, pos, color, end) || !next8(bytes, pos, nameLen, end) ||
        nameLen >= sizeof(restoredGrid.sector[i].gangName) ||
        end - pos < nameLen) return false;
    SectorData& sector = restoredGrid.sector[i];
    sector.total = static_cast<int>(total);
    sector.territoryColor = color;
    std::memcpy(sector.gangName, bytes.data() + pos, nameLen);
    sector.gangName[nameLen] = 0;
    pos += nameLen;
    if (!validName(sector.gangName, sizeof(sector.gangName), true) ||
        !next16(bytes, pos, entryCount, end) || entryCount > 255) return false;
    uint8_t previousLevel = 0;
    uint64_t sum = 0;
    for (int j = 0; j < entryCount; ++j) {
      uint8_t lvl = 0;
      uint32_t count = 0;
      if (!next8(bytes, pos, lvl, end) || lvl <= previousLevel ||
          !nextVar(bytes, pos, count, end) || count == 0 || count > INT_MAX) return false;
      previousLevel = lvl;
      restoredGrid.raw[i][lvl] = static_cast<int>(count);
      sum += count;
      if (sum > total) return false; // Reject corrupt counts before 32-bit tier addition.
      const int tier = (lvl - 1) / 10 + 1;
      restoredGrid.tier[i][tier > 10 ? 10 : tier] += static_cast<int>(count);
    }
    if (sum != total) return false;
    int top[3] = {0, 0, 0};
    for (int tier = 1; tier <= 10; ++tier) {
      if (restoredGrid.tier[i][tier] == 0) continue;
      for (int slot = 0; slot < 3; ++slot) {
        if (!top[slot] || restoredGrid.tier[i][tier] > restoredGrid.tier[i][top[slot]]) {
          for (int tail = 2; tail > slot; --tail) top[tail] = top[tail - 1];
          top[slot] = tier;
          break;
        }
      }
    }
    for (int j = 0; j < 3; ++j) {
      restoredGrid.topLevel[i][j] = top[j];
      restoredGrid.topCount[i][j] = top[j] ? restoredGrid.tier[i][top[j]] : 0;
    }
  }
  return pos == end;
}

void saveBlob(const char* key, const std::vector<uint8_t>& bytes,
              bool& hasSaved, uint32_t& oldChecksum, uint32_t nextChecksum) {
  if (bytes.empty() || (hasSaved && oldChecksum == nextChecksum)) return;
  Preferences prefs;
  if (!prefs.begin(kNamespace, false)) {
    Serial.printf("G5A35: NVS open failed for %s\n", key);
    return;
  }
  const size_t written = prefs.putBytes(key, bytes.data(), bytes.size());
  prefs.end();
  if (written == bytes.size()) {
    hasSaved = true;
    oldChecksum = nextChecksum;
    Serial.printf("G5A35: saved %s %u bytes\n", key, static_cast<unsigned>(written));
  } else Serial.printf("G5A35: NVS save failed for %s (%u/%u)\n", key,
                      static_cast<unsigned>(written), static_cast<unsigned>(bytes.size()));
}
std::vector<uint8_t> readBlob(Preferences& prefs, const char* key, size_t maxBytes) {
  const size_t len = prefs.getBytesLength(key);
  if (len < 10 || len > maxBytes) return {};
  std::vector<uint8_t> bytes(len);
  if (prefs.getBytes(key, bytes.data(), len) != len) return {};
  return bytes;
}
}

bool restoreLastGoodSnapshots() {
  Preferences prefs;
  if (!prefs.begin(kNamespace, true)) return false;
  const auto grid = readBlob(prefs, kGridKey, kGridMaxBytes);
  // Once account isolation is enabled, the unscoped legacy OPERATOR blob
  // must never be restored, even after a failed or interrupted account switch.
  const bool scoped = wdg::accountScopedStore.enabled();
  const auto op = scoped ? std::vector<uint8_t>{} :
      readBlob(prefs, kOperatorKey, kOperatorMaxBytes);
  prefs.end();
  bool restored = false;
  if (!grid.empty() && decodeGrid(grid)) {
    for (int i = 0; i < 9; ++i) {
      sectors3x3[i] = restoredGrid.sector[i];
      for (int lvl = 0; lvl < 256; ++lvl) liveRawLevels[i][lvl] = restoredGrid.raw[i][lvl];
      for (int tier = 0; tier < 11; ++tier) liveTierCounts[i][tier] = restoredGrid.tier[i][tier];
      for (int j = 0; j < 3; ++j) {
        liveLevelNumbers[i][j] = restoredGrid.topLevel[i][j];
        liveLevelCounts[i][j] = restoredGrid.topCount[i][j];
      }
    }
    uint32_t hash = 0;
    checkEnvelope(grid, 1, hash);
    savedGridChecksum = hash;
    gridSaved = true;
    live3x3Data = restored = true;
    Serial.println("G5A35: restored detailed 3x3 snapshot from flash");
  }
  uint32_t profileChecksum = 0;
  if (!op.empty() && checkEnvelope(op, 2, profileChecksum)) {
    size_t at = 6;
    const size_t end = op.size() - 4;
    uint8_t nameLen = 0, gangLen = 0;
    char username[21] = {}, gang[97] = {};
    uint32_t wifi = 0, ble = 0, total = 0, badges = 0;
    bool valid = next8(op, at, nameLen, end) && nameLen > 0 && nameLen < sizeof(username) &&
        end - at >= nameLen;
    if (valid) {
      std::memcpy(username, op.data() + at, nameLen);
      at += nameLen;
      valid = next8(op, at, gangLen, end) && gangLen < sizeof(gang) && end - at >= gangLen;
    }
    if (valid) {
      std::memcpy(gang, op.data() + at, gangLen);
      at += gangLen;
      valid = next32(op, at, wifi, end) && next32(op, at, ble, end) &&
          next32(op, at, total, end) && next32(op, at, badges, end) &&
          at == end && validName(username, sizeof(username), false) &&
          validName(gang, sizeof(gang), true);
    }
    if (valid) {
      // Set this before applying the profile so restoration won't re-write flash.
      savedOperatorChecksum = profileChecksum;
      operatorSaved = true;
      setOperatorProfile(username, gang, wifi, ble, total, badges);
      Serial.println("G5A35: restored OPERATOR snapshot from flash");
    }
  }
  if (scoped) {
    wdg::ScopedOperator personal{};
    if (wdg::accountScopedStore.loadOperator(personal)) {
      setOperatorProfile(personal.username, personal.gang,
                         personal.wifi, personal.ble, personal.total, personal.badges);
      Serial.println("G5A49.4d: restored account-scoped OPERATOR from flash");
    }
  }
  return restored;
}

void persistLastGoodGrid() {
  if (!live3x3Data) return;
  uint32_t hash = 0;
  auto bytes = encodeGrid(hash);
  saveBlob(kGridKey, bytes, gridSaved, savedGridChecksum, hash);
}

void persistLastGoodOperator(const char* username, const char* gang,
                             uint32_t wifi, uint32_t ble, uint32_t total, uint32_t badges) {
  // Scoped OPERATOR is written transactionally by AccountScopedStore on PROFILE,E.
  if (wdg::accountScopedStore.enabled()) return;
  if (!username || !gang || !validName(username, 21, false) ||
      !validName(gang, 97, true)) return;
  auto bytes = makeEnvelope(2);
  const size_t userLen = std::strlen(username);
  const size_t gangLen = std::strlen(gang);
  bytes.push_back(static_cast<uint8_t>(userLen));
  bytes.insert(bytes.end(), username, username + userLen);
  bytes.push_back(static_cast<uint8_t>(gangLen));
  bytes.insert(bytes.end(), gang, gang + gangLen);
  append32(bytes, wifi);
  append32(bytes, ble);
  append32(bytes, total);
  append32(bytes, badges);
  uint32_t hash = 0;
  finishEnvelope(bytes, hash);
  saveBlob(kOperatorKey, bytes, operatorSaved, savedOperatorChecksum, hash);
}
