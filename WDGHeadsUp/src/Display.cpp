#include <Arduino.h>
#include <Wire.h>
#include <Preferences.h>
#include <stddef.h>
#include <math.h>
#include "Arduino_GFX_Library.h"
#include "pin_config.h"
#include "Display.h"
#include "SectorModel.h"
#include "BleTransport.h"
#include "LastGoodStore.h"
#include "AccountScopedStore.h"
#include "GeoSectorCache.h"
#include "GeoSectorCache.h"
#include "IdleAudio.h"

extern int getScanProgress();
extern bool live3x3Data;
extern int liveLevelNumbers[9][3];
extern int liveLevelCounts[9][3];
extern int liveRawLevels[9][256];
// BLE interface for explicit OPERATOR-open refresh (defined in BleTransport.cpp).
void requestOperatorProfile();

namespace {
constexpr uint16_t BG = 0x0000, PANEL = 0x1082, WHITE = 0xFFFF;
constexpr uint16_t GREY = 0x8410, RED_AP = 0xF800, ORANGE_AP = 0xFD20;
constexpr uint16_t YELLOW_AP = 0xFFE0, CYAN_AP = 0x07FF;
// G5A12: exactly ten AP hardness tiers, ten AP levels per tier.
// T1 = L1-L10, T2 = L11-L20, ... T10 = L91-L100.
// Retain raw levels above 100; their counts are included in T10 rather
// than silently dropped. Sector Intel prints L91+ if any are present.
uint16_t tierColor(int tier) {
  switch (tier) {
    case 1: return 0x7CD6; // muted steel blue
    case 2: return 0x06FE; // cyan
    case 3: return 0x2F4E; // green
    case 4: return 0xFF05; // yellow
    case 5: return 0xFC85; // orange
    case 6: return 0xF9E8; // red
    case 7: return 0xFA36; // neon pink
    case 8: return 0xB35F; // purple
    case 9: return 0x7ABF; // ultraviolet
    case 10: return WHITE; // white is now the final/highest tier
    default: return GREY;
  }
}
uint16_t levelColor(int level) {
  if (level < 1) return GREY;
  const int tier = (level - 1) / 10 + 1;
  return tierColor(tier > 10 ? 10 : tier);
}
const char *levelNames[4] = {"L21", "L22", "L23", "L24"};
const Navigation* activeNavigation = nullptr;
struct OperatorProfileData {
  char username[21] = {};
  char gang[97] = {}; // G5A22: receive complete gang names up to 96 ASCII chars.
  uint32_t wifi = 0, ble = 0, total = 0, badges = 0;
  bool valid = false;
  bool refreshing = false;
  bool failed = false;
  uint32_t requestedAtMs = 0;
};
OperatorProfileData operatorProfile;
// G5A45: Optional personal rank never substitutes for a numeric leaderboard rank.
char activityRank[16] = "--";
int activityDays30 = -1;
struct TeamScreenData {
  char name[97] = {};
  int32_t rank = -1, wifi = -1, ble = -1, total = -1, credits = -1;
  uint32_t memberCount = 0;
  uint16_t teamColor = CYAN_AP;
  bool valid = false, noTeam = false, refreshing = false, failed = false;
  TeamMemberRow pageRows[8] = {};
  uint16_t page = 0, pageCount = 1;
  uint8_t pageSize = 0;
  bool pageValid = false;
} teamScreen;

// G5A46: Persist only complete, validated gang summaries. Statistics can change,
// but an API/BLE failure never erases the last valid record. One NVS blob is
// used rather than a collection of separately-written keys.
struct SavedGangSummary {
  uint32_t magic = 0x57444746UL; // WDGF
  uint16_t version = 1;
  uint16_t bytes = sizeof(SavedGangSummary);
  char name[97] = {};
  int32_t rank = -1, wifi = -1, ble = -1, total = -1, credits = -1;
  uint32_t members = 0;
  uint16_t color = CYAN_AP;
  uint8_t state = 0; // 1 = known gang, 2 = confirmed no gang
  uint8_t reserved = 0;
  uint32_t checksum = 0;
};
SavedGangSummary lastSavedGang;
bool haveSavedGang = false;

uint32_t gangChecksum(const SavedGangSummary& v) {
  const auto* bytes = reinterpret_cast<const uint8_t*>(&v);
  uint32_t hash = 2166136261UL;
  for (size_t i = 0; i < offsetof(SavedGangSummary, checksum); ++i)
    hash = (hash ^ bytes[i]) * 16777619UL;
  return hash;
}
void saveGangSummary(bool isNoGang = false) {
  SavedGangSummary current{};
  current.state = isNoGang ? 2 : 1;
  if (!isNoGang) {
    if (!teamScreen.valid || !teamScreen.name[0]) return;
    snprintf(current.name, sizeof(current.name), "%s", teamScreen.name);
    current.rank=teamScreen.rank;current.wifi=teamScreen.wifi;
    current.ble=teamScreen.ble;current.total=teamScreen.total;
    current.credits=teamScreen.credits;current.members=teamScreen.memberCount;
    current.color=teamScreen.teamColor;
  }
  current.checksum=gangChecksum(current);
  if (haveSavedGang && memcmp(&lastSavedGang,&current,sizeof(current))==0) return;
  bool saved = false;
  if (wdg::accountScopedStore.enabled()) {
    // Do not modify or fall back to the legacy unscoped gang key.
    saved = wdg::accountScopedStore.saveGang(&current, sizeof(current));
  } else {
    Preferences prefs;
    if (!prefs.begin("wdghud", false)) return;
    saved = prefs.putBytes("gangSummary", &current, sizeof(current)) == sizeof(current);
    prefs.end();
  }
  if (saved) {lastSavedGang=current;haveSavedGang=true;}
}
void loadGangSummary() {
  SavedGangSummary restored{};
  bool readOk = false;
  if (wdg::accountScopedStore.enabled()) {
    readOk = wdg::accountScopedStore.loadGang(&restored, sizeof(restored));
  } else {
    Preferences prefs;
    if (!prefs.begin("wdghud", true)) return;
    const bool lengthOk = prefs.getBytesLength("gangSummary") == sizeof(restored);
    readOk = lengthOk && prefs.getBytes("gangSummary", &restored, sizeof(restored)) == sizeof(restored);
    prefs.end();
  }
  if (!readOk || restored.magic!=0x57444746UL || restored.version!=1 ||
      restored.bytes!=sizeof(restored) || restored.checksum!=gangChecksum(restored) ||
      (restored.state!=1 && restored.state!=2) ||
      restored.members>5000 || restored.rank < -1 || restored.rank>999999 ||
      !memchr(restored.name,'\0',sizeof(restored.name)) ||
      (restored.state==1 && restored.name[0]=='\0')) return;
  lastSavedGang=restored;haveSavedGang=true;
  if (restored.state==2) {teamScreen.noTeam=true;return;}
  snprintf(teamScreen.name,sizeof(teamScreen.name),"%s",restored.name);
  teamScreen.rank=restored.rank;teamScreen.wifi=restored.wifi;
  teamScreen.ble=restored.ble;teamScreen.total=restored.total;
  teamScreen.credits=restored.credits;teamScreen.memberCount=restored.members;
  teamScreen.teamColor=restored.color;teamScreen.valid=true;
}

// The watch never reads account credentials or makes network requests itself.
void formatOperatorCount(uint32_t value, char (&out)[20]) {
  char digits[12];
  snprintf(digits, sizeof(digits), "%lu", static_cast<unsigned long>(value));
  const size_t length = strlen(digits);
  size_t j = 0;
  for (size_t i = 0; i < length && j + 1 < sizeof(out); ++i) {
    if (i && (length - i) % 3 == 0 && j + 1 < sizeof(out)) out[j++] = ' ';
    out[j++] = digits[i];
  }
  out[j] = 0;
}

Arduino_DataBus *bus = new Arduino_ESP32QSPI(
  LCD_CS, LCD_SCLK, LCD_SDIO0, LCD_SDIO1, LCD_SDIO2, LCD_SDIO3);
Arduino_GFX *gfx = new Arduino_CO5300(
  bus, LCD_RESET, 0, LCD_WIDTH, LCD_HEIGHT, 22, 0, 0, 0);

// G5A45.4: three explicit brightness steps, retaining the brightnessRaw NVS key.
// Old slider values are snapped to the nearest step on first boot.
constexpr uint8_t kBrightnessSteps[3] = {0x50, 0xA8, 0xFF};
constexpr uint8_t kOldBrightnessValues[5] = {0x50, 0x80, 0xA8, 0xD0, 0xFF};
uint8_t brightnessLevel = 1;  // LOW=0, MED=1, HIGH=2.
uint8_t brightnessRaw = kBrightnessSteps[1];
uint8_t savedBrightnessRaw = kBrightnessSteps[1];
constexpr uint16_t kStaleDefaultMinutes = 60;
uint16_t staleIntervalMinutes = kStaleDefaultMinutes;

// G5A36: colors are fixed RGB565 values, matching the limited HUD palette.
constexpr uint16_t kVisualizerColors[8] = {
  0x07FF, // cyan
  0x07F1, // neon green
  0x347F, // blue
  0xFFE9, // yellow
  0xFC86, // orange
  0xFA0B, // red
  0xFA5F, // magenta
  0xFFFF  // white
};
const char* const kVisualizerColorNames[8] = {
  "CYAN", "GREEN", "BLUE", "YELLOW",
  "ORANGE", "RED", "MAGENTA", "WHITE"
};
bool visualizerEnabled = true;
bool visualizerRainbow = true;
uint8_t visualizerColor = 0;

void loadVisualizerPreferences() {
  Preferences prefs;
  if (!prefs.begin("wdghud", true)) return;
  visualizerEnabled = prefs.getBool("vizEnabled", true);
  visualizerRainbow = prefs.getBool("vizRainbow", true);
  visualizerColor = prefs.getUChar("vizColor", 0);
  if (visualizerColor >= 8) visualizerColor = 0;
  prefs.end();
}
void persistVisualizerPreferences() {
  Preferences prefs;
  if (!prefs.begin("wdghud", false)) return;
  // Only called when the user taps a control, never on each audio frame.
  prefs.putBool("vizEnabled", visualizerEnabled);
  prefs.putBool("vizRainbow", visualizerRainbow);
  prefs.putUChar("vizColor", visualizerColor);
  prefs.end();
}


void applyBrightness() {
  static_cast<Arduino_CO5300*>(gfx)->setBrightness(brightnessRaw);
}

void persistBrightness() {
  if (brightnessRaw == savedBrightnessRaw) return;
  Preferences prefs;
  if (prefs.begin("wdghud", false)) {
    // Persist only on touch release or DEFAULT, never every drag frame.
    const size_t written = prefs.putUChar("brightnessRaw", brightnessRaw);
    prefs.end();
    if (written) savedBrightnessRaw = brightnessRaw;
  }
}

void loadBrightness() {
  Preferences prefs;
  if (prefs.begin("wdghud", true)) {
    uint8_t stored = prefs.getUChar("brightnessRaw", 0);
    if (stored < kBrightnessSteps[0]) {
      const uint8_t oldLevel = prefs.getUChar("brightness", 4);
      stored = oldLevel >= 1 && oldLevel <= 5
          ? kOldBrightnessValues[oldLevel - 1] : 0xD0;
    }
    // Preserve old preferences while migrating continuous slider -> LOW/MED/HIGH.
    unsigned bestDistance = 256;
    for (uint8_t i = 0; i < 3; ++i) {
      const unsigned distance = stored > kBrightnessSteps[i]
          ? stored - kBrightnessSteps[i] : kBrightnessSteps[i] - stored;
      if (distance < bestDistance) { bestDistance = distance; brightnessLevel = i; }
    }
    brightnessRaw = kBrightnessSteps[brightnessLevel];
    savedBrightnessRaw = stored;
    prefs.end();
  }
  applyBrightness();
  persistBrightness(); // One-time migration; subsequent boots need no rewrite.
}
void loadStaleInterval() {
  Preferences prefs;
  if (!prefs.begin("wdghud", true)) return;
  const uint16_t saved = prefs.getUShort("staleMinutes", kStaleDefaultMinutes);
  prefs.end();
  staleIntervalMinutes = (saved == 30 || saved == 60 || saved == 240)
      ? saved : kStaleDefaultMinutes;
}
}
namespace { void drawHudStatus(); }
bool isHudInitializing();
bool isHudAnnouncementActive();

void initializeDisplay() {
#ifdef GFX_EXTRA_PRE_INIT
  GFX_EXTRA_PRE_INIT();
#endif
  if (!gfx->begin()) {
    while (true) delay(1000);
  }
  gfx->fillScreen(BG);
  loadBrightness();
  loadStaleInterval();
  loadVisualizerPreferences();
  loadGangSummary();
}

void drawHeader(const char *subtitle) {
  gfx->fillRect(0, 0, 410, 72, BG);

  gfx->setTextSize(3);
  gfx->setTextColor(CYAN_AP);
  // Built-in 6px font, scaled 3x: center the title on the 410px display.
  constexpr char title[] = "WDG // HUD";
  constexpr int titleWidth = (sizeof(title) - 1) * 6 * 3;
  gfx->setCursor((410 - titleWidth) / 2, 7);
  gfx->print(title);

  // G5A38.4: no frame/brackets around the message strip.
}

void drawConnectionIcons() {
  // Lower both indicators 10 px to clear the curved upper bezel.
  // G reports last valid watch-received GPS fix age; B reports active BLE.
  const uint16_t bleColor = bleIsConnected() ? 0x07E0 : 0xF800;
  const uint32_t age = gpsFixAgeMs();
  // No GPS fix received (including GPSOFF): neutral grey, not an error.
  // A previously received fix that has become old is yellow, then red.
  const uint16_t gpsColor = age == UINT32_MAX ? 0x8410 :
                            age <= 30000UL ? 0x07E0 :
                            age <= 120000UL ? 0xFD20 : 0xF800;

  // Avoid the centered title (approximately x=115..295).
  gfx->fillRect(53, 6, 38, 43, BG);
  gfx->fillRect(314, 6, 65, 43, BG);
  // G5A38.5: replace text G/B with pixel-accurate icons derived from the
  // two approved reference silhouettes. Rows are doubled vertically to
  // avoid the CO5300's occasional 1-pixel horizontal raster issue.
  // Icon colors retain the original GPS age / BLE connection semantics.
  static constexpr uint32_t kGpsPin[16] = {
    0x00007FE0U, 0x0001FFF8U, 0x0007E07EU, 0x000F801FU,
    0x000F000FU, 0x000F000FU, 0x0007C03EU, 0x0003FFFCU,
    0x0001FFFCU, 0x0000FFF8U, 0x00007FF0U, 0x00003FE0U,
    0x00001FC0U, 0x00000F80U, 0x00000700U, 0x00000600U
  };
  static constexpr uint32_t kBluetooth[16] = {
    0x00000600U, 0x00001E00U, 0x0000FE00U, 0x0007EE00U,
    0x001F0E06U, 0x000FCE3FU, 0x0001FFF8U, 0x00003FC0U,
    0x00003FC0U, 0x0001FFF8U, 0x000FCE3FU, 0x001F0E06U,
    0x0007EE00U, 0x0000FE00U, 0x00001E00U, 0x00000600U
  };
  auto drawGlyph = [&](int x, int y, const uint32_t (&rows)[16],
                       int width, uint16_t color) {
    for (int row = 0; row < 16; ++row) {
      const uint32_t bits = rows[row];
      int col = 0;
      while (col < width) {
        while (col < width && !(bits & (1UL << col))) ++col;
        const int start = col;
        while (col < width && (bits & (1UL << col))) ++col;
        if (col > start) gfx->fillRect(x + start, y + row * 2, col - start, 2, color);
      }
    }
  };
  // Vendor rounded-corner reference: 111px provisional radius.
  // The entire glyph bounding boxes remain inside its conservative inset.
  drawGlyph(66, 12, kGpsPin, 20, gpsColor);
  drawGlyph(319, 12, kBluetooth, 22, bleColor);
  // G5A38.5.1: GPS/BLE icon silhouettes only; no adjacent status squares.
}

// G5A13: Read-only AXP2101 telemetry on the same I2C bus as FT3168.
// Only draw this on OPERATOR; never modify power, charger, or gauge registers.
namespace {
constexpr uint8_t AXP2101_I2C = 0x34;
uint32_t lastOperatorBatteryDrawMs = 0;

bool readAxp2101(uint8_t reg, uint8_t& value) {
  Wire.beginTransmission(AXP2101_I2C);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(AXP2101_I2C, static_cast<uint8_t>(1)) != 1)
    return false;
  value = static_cast<uint8_t>(Wire.read());
  return true;
}

// G5A15: PCF85063 time is written by the watch main loop after an Android
// TIME,YYYY,MM,DD,HH,MM,SS,weekday BLE message, and then read from the RTC here. This is *real*
// local phone time; an uninitialized RTC is displayed as --:--.
constexpr uint8_t PCF85063_I2C = 0x51;
constexpr uint8_t RTC_SYNC_MAGIC = 0xA7;
uint32_t lastOperatorClockDrawMs = 0;

bool rtcRead(uint8_t reg, uint8_t* values, uint8_t length) {
  Wire.beginTransmission(PCF85063_I2C);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(PCF85063_I2C, length) != length) return false;
  for (uint8_t i = 0; i < length; ++i)
    values[i] = static_cast<uint8_t>(Wire.read());
  return true;
}

bool rtcWrite(uint8_t reg, const uint8_t* values, uint8_t length) {
  Wire.beginTransmission(PCF85063_I2C);
  Wire.write(reg);
  for (uint8_t i = 0; i < length; ++i) Wire.write(values[i]);
  return Wire.endTransmission() == 0;
}

uint8_t toBcd(uint8_t value) {
  return static_cast<uint8_t>((value / 10) * 16 + (value % 10));
}

int fromBcd(uint8_t value) {
  if ((value & 0x0F) > 9 || ((value >> 4) & 0x0F) > 9) return -1;
  return (value >> 4) * 10 + (value & 0x0F);
}

bool readOperatorClock(uint8_t& hours, uint8_t& minutes) {
  uint8_t control = 0;
  uint8_t regs[4] = {};
  if (!rtcRead(0x00, &control, 1) || !rtcRead(0x03, regs, 4)) return false;
  // Control_1: bit 5 STOP and bit 1 12/24. Seconds bit 7 = oscillator stop.
  if ((control & 0x22) != 0 || regs[0] != RTC_SYNC_MAGIC ||
      (regs[1] & 0x80) != 0) return false;
  const int sec = fromBcd(regs[1] & 0x7F);
  const int min = fromBcd(regs[2] & 0x7F);
  const int hour = fromBcd(regs[3] & 0x3F);
  if (sec < 0 || sec > 59 || min < 0 || min > 59 || hour < 0 || hour > 23)
    return false;
  hours = static_cast<uint8_t>(hour);
  minutes = static_cast<uint8_t>(min);
  return true;
}

void drawOperatorClock() {
  lastOperatorClockDrawMs = millis();
  // G5A25: align the final clock digit just past the upper-left PCB pad.
  // Text is 5 * 6 * 2 = 60 px wide; at x=48 it ends at x=107.
  // Preserve Y, font, color, RTC source, and all other header geometry.
  constexpr int x = 48, y = 20;
  gfx->fillRect(38, 13, 90, 30, BG);
  uint8_t hours = 0, minutes = 0;
  const bool valid = readOperatorClock(hours, minutes);
  char digits[6];
  if (valid) snprintf(digits, sizeof(digits), "%02u:%02u",
                      static_cast<unsigned>(hours), static_cast<unsigned>(minutes));
  else snprintf(digits, sizeof(digits), "--:--");
  gfx->setTextWrap(false);
  gfx->setTextSize(2);
  gfx->setTextColor(valid ? CYAN_AP : GREY);
  gfx->setCursor(x, y);
  gfx->print(digits);
}

void drawOperatorBattery() {
  lastOperatorBatteryDrawMs = millis();

  // G5A17: approved pixel-art battery, rendered with explicit rectangles.
  // Keep G5A14's upper-right placement and the 0x07FF HUD cyan.
  constexpr int x = 310, y = 14;
  constexpr int bodyWidth = 35, terminalWidth = 4;
  static_assert(x + bodyWidth + terminalWidth - 1 <= 348,
                "Battery beyond conservative upper-right safe zone");

  // Clear only the battery/percentage slot; do not disturb the clock or HUD.
  gfx->fillRect(300, 7, 65, 42, BG);

  uint8_t presence = 0, percent = 0;
  const bool valid = readAxp2101(0x00, presence) &&
      (presence & 0x08) != 0 &&
      readAxp2101(0xA4, percent) && percent <= 100;
  // REG 0x00 bit 5 reports valid external USB input (VBUS good).
  // Show the bolt for USB present, even if charging has paused/full.
  // This indicator signifies external power, not confirmed charge current.
  const bool usbPowered = (presence & 0x20) != 0;
  // G5A30: only a confirmed empty battery on battery power turns red.
  // Missing gauge data stays grey; USB-powered indicator stays cyan.
  const uint16_t color = (valid && !usbPowered && percent == 0) ? RED_AP :
                         (valid || usbPowered) ? CYAN_AP : GREY;

  // Square-cut, 2px frame. The four corners are inset by two pixels.
  // The middle remains black; no rounded-rectangle/triangle overlap.
  gfx->fillRect(x + 2, y,      31, 2, color);  // top
  gfx->fillRect(x,     y + 2,   2,16, color);  // left
  gfx->fillRect(x + 33,y + 2,   2,16, color);  // right
  gfx->fillRect(x + 2, y + 18, 31, 2, color);  // bottom

  if (usbPowered) {
    // G5A29: CO5300 may drop single-pixel drawPixel() operations.
    // Render a bright cyan lightning bolt with solid >=2px-high rectangles.
    // No battery frame/position/terminal changes; black interior remains.
    gfx->fillRect(x + 20, y + 3,  4, 2, CYAN_AP);
    gfx->fillRect(x + 18, y + 5,  5, 2, CYAN_AP);
    gfx->fillRect(x + 16, y + 7,  6, 2, CYAN_AP);
    gfx->fillRect(x + 13, y + 9, 12, 2, CYAN_AP);
    gfx->fillRect(x + 18, y + 11, 5, 2, CYAN_AP);
    gfx->fillRect(x + 16, y + 13, 5, 2, CYAN_AP);
    gfx->fillRect(x + 14, y + 15, 5, 2, CYAN_AP);
  } else {
    // Exactly 3 independent 7x10 bars (1..33, 34..66, 67..100%).
    // 3 black pixels between bars; 2+ black pixels from the frame.
    const int lit = valid ? (static_cast<int>(percent) * 3 + 99) / 100 : 0;
    for (int part = 0; part < lit; ++part)
      gfx->fillRect(x + 4 + part * 10, y + 5, 7, 10, CYAN_AP);
  }

  // G5A22: icon only; no percentage number under the battery.
  // G5A18: accepted 4x6 square terminal, masked and drawn last.
  gfx->fillRect(x + 35, y + 5, 4, 10, BG);
  gfx->fillRect(x + 35, y + 7, 4, 6, color);
}

void drawOperatorGearPlaceholder() {
  // G5A27: exact approved 32x32 gear, drawn at native size.
  // No bitmap scaling, combined rows, circles, or outlines.
  // Ten fixed rectangles reproduce the preview's symmetric pixel silhouette.
  constexpr int x = 189, y = 432; // centered on the 410px panel
  constexpr uint16_t gearColor = 0x05FF;

  // Cyan outer shape: centered top/bottom teeth, shoulders and side teeth.
  gfx->fillRect(x + 12, y,      8,  4, gearColor);
  gfx->fillRect(x +  5, y +  4, 22, 8, gearColor);
  gfx->fillRect(x,      y + 12, 32, 8, gearColor);
  gfx->fillRect(x +  5, y + 20, 22, 8, gearColor);
  gfx->fillRect(x + 12, y + 28,  8, 4, gearColor);

  // Black stepped hole, drawn as native rectangles, centered at (205,448).
  gfx->fillRect(x + 13, y + 11,  6, 2, BG);
  gfx->fillRect(x + 11, y + 13, 10, 2, BG);
  gfx->fillRect(x + 10, y + 15, 12, 2, BG);
  gfx->fillRect(x + 11, y + 17, 10, 2, BG);
  gfx->fillRect(x + 13, y + 19,  6, 2, BG);
}
}  // namespace

// BLE callback queues a TIME message; pollBle calls this on the Arduino loop.
// Only the clock registers are written. This never alters PMIC power settings.
bool syncOperatorClock(uint16_t year, uint8_t month, uint8_t day,
                       uint8_t hours, uint8_t minutes, uint8_t seconds,
                       uint8_t weekday) {
  const bool leap = (year % 4 == 0) &&
                    ((year % 100 != 0) || (year % 400 == 0));
  static constexpr uint8_t daysPerMonth[12] =
      {31,28,31,30,31,30,31,31,30,31,30,31};
  if (year < 2020 || year > 2099 || month < 1 || month > 12 ||
      day < 1 || day > daysPerMonth[month - 1] + (month == 2 && leap) ||
      hours > 23 || minutes > 59 || seconds > 59 || weekday > 6)
    return false;
  uint8_t control = 0;
  if (!rtcRead(0x00, &control, 1)) return false;
  // Stop the RTC divider while updating the complete BCD date/time.
  const uint8_t halted = static_cast<uint8_t>((control | 0x20) & ~0x02);
  if (!rtcWrite(0x00, &halted, 1)) return false;
  const uint8_t values[7] = {toBcd(seconds), toBcd(minutes), toBcd(hours),
                              toBcd(day), weekday, toBcd(month),
                              toBcd(static_cast<uint8_t>(year % 100))};
  const bool updated = rtcWrite(0x04, values, 7);
  const uint8_t resumed = static_cast<uint8_t>(control & ~0x22);
  const bool running = rtcWrite(0x00, &resumed, 1);
  if (!updated || !running) return false;
  const uint8_t marker = RTC_SYNC_MAGIC;
  return rtcWrite(0x03, &marker, 1);
}

void tickConnectionIcons() {
  // The indicators are tiny: repaint them once a second regardless of whether
  // the entire grid has changed. This also repairs any header overwrite.
  // Never redraw the nine sectors for a status-only change.
  static uint32_t lastCheck = 0;
  if (millis() - lastCheck < 1000UL) return;
  lastCheck = millis();
  // Both geographical grid views share the same live GPS/BLE indicators.
  if (activeNavigation && (activeNavigation->screen == SCREEN_3X3 ||
                           activeNavigation->screen == SCREEN_5X5))
    drawConnectionIcons();
  else if (activeNavigation && (activeNavigation->screen == SCREEN_CARD ||
                                activeNavigation->screen == SCREEN_TEAM ||
                                activeNavigation->screen == SCREEN_MEMBERS)) {
    if (operatorProfile.refreshing &&
        millis() - operatorProfile.requestedAtMs > 40000UL) {
      operatorProfile.refreshing = false;
      operatorProfile.failed = true;
      // G5A23: keep the displayed, last-good profile and SYNCED label.
      // Only redraw an initial failure when there is no profile yet.
      if (!operatorProfile.valid) showScreen(*activeNavigation);
    }
    if (millis() - lastOperatorClockDrawMs >= 1000UL)
      drawOperatorClock();   // Only this small header area, not the entire card.
    if (millis() - lastOperatorBatteryDrawMs >= 2000UL)
      drawOperatorBattery();
  }
}

// High-contrast secondary-screen PCB frame: an OPEN title area, not a box.
// All traces live outside the content columns; the title at y=7..31 is untouched.
// Draw this on SECTOR INTEL and OPERATOR ONLY. No G/B on these screens.
// Some CO5300/Arduino_GFX paths fail to display single-pixel line primitives.
// Use filled rectangles for every PCB trace; these are known to render on the
// user's watch (status lights, square palette, and frame solder pads).
void pcbHorizontal(int x, int y, int w, uint16_t color, int thickness = 3) {
  if (w > 0) gfx->fillRect(x, y, w, thickness, color);
}
void pcbVertical(int x, int y, int h, uint16_t color, int thickness = 3) {
  if (h > 0) gfx->fillRect(x, y, thickness, h, color);
}
void pcbDiagonal(int x0, int y0, int x1, int y1, uint16_t color) {
  // Short stair-stepped 3x3 pixels: no drawLine or drawFast*Line calls.
  const int dx = abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
  const int dy = -abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
  int error = dx + dy;
  for (;;) {
    gfx->fillRect(x0, y0, 3, 3, color);
    if (x0 == x1 && y0 == y1) break;
    const int e2 = 2 * error;
    if (e2 >= dy) { error += dy; x0 += sx; }
    if (e2 <= dx) { error += dx; y0 += sy; }
  }
}

// On SECTOR INTEL and OPERATOR only. Leave title y=7..31 and bottom
// hardness legend untouched. Keep all strokes outside the central text area.
void drawSecondaryCircuitFrame() {
  constexpr uint16_t trace = 0x05FF;  // bright cyan, 3px thick
  constexpr uint16_t accent = CYAN_AP;
  constexpr uint16_t faint = 0x03F5;

  // Split shoulders, with a wide center opening around the screen title.
  pcbHorizontal(29, 53, 67, trace);
  pcbHorizontal(314, 53, 67, trace);
  pcbDiagonal(29, 53, 16, 66, accent);
  pcbDiagonal(381, 53, 392, 66, accent);

  // Left/right rails: intentionally segmented like PCB traces.
  pcbVertical(16, 66, 106, trace);
  pcbVertical(16, 193, 198, trace);
  pcbVertical(392, 66, 106, trace);
  pcbVertical(392, 193, 198, trace);
  pcbVertical(24, 90, 58, faint, 2);
  pcbVertical(384, 90, 58, faint, 2);
  pcbVertical(24, 329, 48, faint, 2);
  pcbVertical(384, 329, 48, faint, 2);

  // Angled midsection break and twin branches.
  pcbDiagonal(16, 171, 28, 184, accent);
  pcbDiagonal(392, 171, 380, 184, accent);
  pcbHorizontal(28, 184, 19, trace);
  pcbHorizontal(363, 184, 18, trace);
  gfx->fillRect(45, 182, 6, 6, accent);
  gfx->fillRect(359, 182, 6, 6, accent);

  pcbHorizontal(16, 111, 19, trace);
  pcbHorizontal(375, 111, 20, trace);
  gfx->fillRect(33, 109, 6, 6, accent);
  gfx->fillRect(371, 109, 6, 6, accent);
  pcbHorizontal(16, 347, 17, trace);
  pcbHorizontal(378, 347, 17, trace);
  gfx->fillRect(31, 345, 6, 6, accent);
  gfx->fillRect(374, 345, 6, 6, accent);

  // Footer elbows end at y=429, above the fixed legend (around y=455).
  pcbDiagonal(16, 391, 36, 426, accent);
  pcbDiagonal(392, 391, 372, 426, accent);
  pcbHorizontal(36, 426, 88, trace);
  pcbHorizontal(286, 426, 88, trace);
  gfx->fillRect(122, 424, 6, 6, accent);
  gfx->fillRect(282, 424, 6, 6, accent);
  gfx->fillRect(96, 51, 6, 6, accent);
  gfx->fillRect(308, 51, 6, 6, accent);
}

void drawFooter(const char *message) {
  gfx->drawFastHLine(12, 464, 386, GREY);

  gfx->setTextSize(1);
  gfx->setTextColor(GREY);
  gfx->setCursor(16, 477);
  gfx->print(message);
}

void drawLevelLegend() {
  const int groupWidth = 62;
  const int groupSpacing = 28;
  const int totalWidth = 4 * groupWidth + 3 * groupSpacing;
  const int startX = (410 - totalWidth) / 2;

  gfx->setTextSize(1);

  for (int i = 0; i < 4; i++) {
    int groupX = startX + i * (groupWidth + groupSpacing);

    gfx->fillCircle(groupX + 5, 450, 5, levelColor(21 + i));

    gfx->setTextColor(WHITE);
    gfx->setCursor(groupX + 16, 446);
    gfx->print(levelNames[i]);
  }
}
// BLE sector ordering is already NW,N,NE / W,C,E / SW,S,SE.
// Keep the geographic grid NORTH-UP, regardless of course/bearing.
int sectorIndexForGridPosition(int slot) {
  return slot >= 0 && slot < 9 ? slot : 4;
}

// G4C3: a viewport contains only AP totals and geographic positions.
// Never reuse legacy tier / gang information with new coordinates.
void drawUniformTileFrame(int x, int y, uint16_t frameColor);

void drawViewportTile(int index, int x, int y) {
  const auto& snapshot = currentViewport();
  if (!snapshot.valid || index < 0 || index >= 9) return;
  const auto& tile = snapshot.tiles[index];
  // Original full-size 120x105 tile; the direction triangle fits in the
  // existing gutters, so shrinking the AP data tiles is unnecessary.
  constexpr int tileW = 120, tileH = 105;
  drawUniformTileFrame(x, y, GREY);
  gfx->setTextWrap(false);
  if (tile.state == wdg::TileState::Unknown) {
    gfx->setTextSize(3);
    gfx->setTextColor(YELLOW_AP);
    gfx->setCursor(x + 48, y + 23);
    gfx->print("?");
    gfx->setTextSize(1);
    gfx->setCursor(x + 35, y + 69);
    gfx->print("UNKNOWN");
    return;
  }
  char count[20];
  snprintf(count, sizeof(count), "%ld", static_cast<long>(tile.total));
  const int textSize = strlen(count) > 5 ? 2 : 3;
  gfx->setTextSize(textSize);
  gfx->setTextColor(WHITE);
  gfx->setCursor(x + (tileW - static_cast<int>(strlen(count)) * 6 * textSize) / 2, y + 21);
  gfx->print(count);
  gfx->setTextSize(1);
  gfx->setTextColor(CYAN_AP);
  gfx->setCursor(x + 39, y + 80);
  gfx->print("CACHED");
}

// G5A38.6 (approved): 118x103, closed uniform 3px rounded border,
// pixel-identical to the native 410x502 approved preview (corner radius 5).
// The CO5300 can lose single-pixel primitives, so the 4-row corner caps
// are sent as small complete RGB565 bitmaps, not 1px-high fillRect calls.
// This same geometry is used to restore the original background when the
// current-bearing arrow changes (some approved arrows cross border pixels).
static uint16_t tileFramePixel(int dx, int dy, uint16_t frameColor) {
  if (dx < 0 || dx >= 118 || dy < 0 || dy >= 103) return BG;
  // PIL rounded_rectangle[(0,0),(117,102)], radius=5, outline=3.
  // Upper and lower three rows have inset stepped arcs.
  const int distY = min(dy, 102 - dy);
  if (distY <= 2) {
    const int inset = 3 - distY;
    return (dx >= inset && dx <= 117 - inset) ? frameColor : BG;
  }
  // Row 3 and 99 have a four-pixel-thick vertical join.
  if (distY == 3)
    return (dx <= 3 || dx >= 114) ? frameColor : PANEL;
  return (dx <= 2 || dx >= 115) ? frameColor : PANEL;
}

void drawUniformTileFrame(int x, int y, uint16_t frameColor) {
  // G5A38.7: Render the entire 118x103 tile as short, complete RGB565
  // scanline blocks. The CO5300/QSPI path has shown incomplete strokes from
  // tall 3px fillRect rails on physical hardware. Short bitmap transfers
  // produce the SAME approved pixels and guarantee connected right edges.
  // Keep the panel fill and corner mask identical to tileFramePixel(), which
  // is also used when restoring tiles underneath the moving direction arrow.
  constexpr int kWidth = 118;
  constexpr int kHeight = 103;
  constexpr int kRowsPerTransfer = 4;  // proven safe for corner caps
  uint16_t pixels[kWidth * kRowsPerTransfer];

  for (int rowStart = 0; rowStart < kHeight;
       rowStart += kRowsPerTransfer) {
    const int rows = min(kRowsPerTransfer, kHeight - rowStart);
    for (int row = 0; row < rows; ++row) {
      for (int col = 0; col < kWidth; ++col) {
        pixels[row * kWidth + col] =
            tileFramePixel(col, rowStart + row, frameColor);
      }
    }
    gfx->draw16bitRGBBitmap(x + 1, y + 1 + rowStart,
                            pixels, kWidth, rows);
  }
}

void draw3x3Tile(int index, int x, int y) {
  SectorData &s = sectors3x3[index];
  constexpr int tileW = 120, tileH = 105;
  // G5A39: an uncached geographic slot is NOT an AP count of zero and must
  // never inherit a previous sector's territory color, count, or hardness.
  if (!geoCacheTileAvailable(index)) {
    drawUniformTileFrame(x, y, GREY);
    gfx->setTextWrap(false);
    gfx->setTextColor(YELLOW_AP);
    gfx->setTextSize(3);
    gfx->setCursor(x + 51, y + 23);
    gfx->print("?");
    gfx->setTextSize(1);
    gfx->setCursor(x + 36, y + 72);
    gfx->print("UNKNOWN");
    return;
  }
  // Uniform rails; leave the AP/tier numbers, markers and tile geometry intact.
  drawUniformTileFrame(x, y, s.territoryColor);
  // A missing geographically keyed AP record is UNKNOWN, never a fabricated
  // zero-AP sector. A VALID zero-AP record still prints 0 as before.
  if (!geoCacheTileAvailable(index)) {
    gfx->setTextSize(3);
    gfx->setTextColor(YELLOW_AP);
    gfx->setCursor(x + 48, y + 23); gfx->print("?");
    gfx->setTextSize(1);
    gfx->setCursor(x + 35, y + 69); gfx->print("UNKNOWN");
    return;
  }

  // Top-right signal: highest hardness tier actually present in this sector.
  // Uses the complete histogram, so even a single rare high-level AP is visible.
  int hardest = 0;
  for (int tier = 1; tier <= 10; ++tier)
    if (liveTierCounts[index][tier] > 0) hardest = tier;
  if (hardest > 0) {
    // A true tiny LED square (not a font glyph that looks like '=') .
    const int markerX = x + tileW - 18;
    const int markerY = y + 11;
    // Single solid high-tier marker; T10 uses pure white.
    gfx->fillRect(markerX, markerY, 8, 8, tierColor(hardest));
  }

  // The built-in font is 6 pixels wide per character at text size 1.
  // Center the AP total; use size 1 for large (5+ digit) totals.
  char totalText[20];
  snprintf(totalText, sizeof(totalText), "%d", s.total);
  const int digits = strlen(totalText);
  const int totalSize = digits > 5 ? 1 : 2;
  const int totalWidth = digits * 6 * totalSize;
  gfx->setTextWrap(false);
  gfx->setTextSize(totalSize);
  gfx->setTextColor(WHITE);
  gfx->setCursor(x + (tileW - totalWidth) / 2, y + 12);
  gfx->print(totalText);

  // Top three tiers by AP count from the complete validated BLE histogram.
  gfx->setTextWrap(false);
  gfx->setTextSize(2);
  for (int slot = 0; slot < 3; ++slot) {
    const int tier = liveLevelNumbers[index][slot];
    if (tier <= 0) continue;
    char line[24];
    snprintf(line, sizeof(line), "T%d:%d", tier, liveLevelCounts[index][slot]);
    const int textSize = strlen(line) <= 9 ? 2 : 1;
    gfx->setTextSize(textSize);
    const int width = strlen(line) * 6 * textSize;
    const int textX = x + (tileW - width) / 2;
    const int textY = y + 40 + slot * 17;
    gfx->setTextColor(tierColor(tier));
    gfx->setCursor(textX, textY);
    gfx->print(line);
  }

  // No per-tile bottom severity strip: this element was explicitly rejected.
  // Keep only the single hardest-tier square in the upper-right.
}

// The geographic grid stays NORTH-UP. Only a NEW, explicitly valid GPS
// bearing sample received WHILE MOVING may change the direction marker.
// Otherwise it keeps pointing north (startup) or holds its last direction.
namespace {
  int displayedHeading = 0;               // N before first accepted motion course
  uint32_t consumedBearingSerial = 0;     // never apply the same GPS bearing twice
  uint32_t lastHeadingCheckMs = 0;
}

// G5A38.13: The grid paints itself; this overlay paints CYAN ONLY.
// No attempt to recreate borders, panels, or territory colors from the arrow.
// On a bearing change we rebuild the entire HUD, then draw its arrow last.
static void drawCenterCourseTriangle(int octant) {
  struct Point { int x, y; };
  static constexpr Point kCenters[8] = {
    {207,195}, {272,195}, {272,254}, {272,313},
    {207,313}, {142,313}, {142,254}, {142,195}
  };
  // Bits are set at column x, left-to-right. Each mask is exactly 22x22.
  static constexpr uint32_t kArrow[8][22] = {
    // N
    {0x000C00UL, 0x001E00UL, 0x001E00UL, 0x003F00UL, 0x003F00UL, 0x003F00UL, 0x007F80UL, 0x007F80UL, 0x00FFC0UL, 0x00FFC0UL, 0x01FFE0UL,
     0x01FFE0UL, 0x03FFF0UL, 0x03FFF0UL, 0x07FFF8UL, 0x07FFF8UL, 0x0FFFFCUL, 0x0FFFFCUL, 0x1FFFFEUL, 0x1FFFFEUL, 0x3FFFFFUL, 0x3FFFFFUL},
    // NE
    {0x300000UL, 0x1E0000UL, 0x1F8000UL, 0x1FF800UL, 0x0FFF00UL, 0x0FFFC0UL, 0x07FFF8UL, 0x07FFFFUL, 0x07FFFEUL, 0x03FFF0UL, 0x03FF80UL,
     0x03FC00UL, 0x01FC00UL, 0x01F800UL, 0x01F800UL, 0x00F800UL, 0x00F000UL, 0x00F000UL, 0x007000UL, 0x006000UL, 0x002000UL, 0x002000UL},
    // E
    {0x000001UL, 0x000007UL, 0x00001EUL, 0x00007EUL, 0x0001FCUL, 0x000FFCUL, 0x003FF8UL, 0x00FFF8UL, 0x03FFF0UL, 0x0FFFF0UL, 0x3FFFE0UL,
     0x3FFFE0UL, 0x0FFFF0UL, 0x03FFF0UL, 0x00FFF8UL, 0x003FF8UL, 0x000FFCUL, 0x0001FCUL, 0x00007EUL, 0x00001EUL, 0x000007UL, 0x000001UL},
    // SE
    {0x002000UL, 0x002000UL, 0x006000UL, 0x007000UL, 0x00F000UL, 0x00F000UL, 0x00F800UL, 0x01F800UL, 0x01F800UL, 0x01FC00UL, 0x03FC00UL,
     0x03FF80UL, 0x03FFF0UL, 0x07FFFEUL, 0x07FFFFUL, 0x07FFF8UL, 0x0FFFC0UL, 0x0FFF00UL, 0x1FF800UL, 0x1F8000UL, 0x1E0000UL, 0x300000UL},
    // S
    {0x3FFFFFUL, 0x3FFFFFUL, 0x1FFFFEUL, 0x1FFFFEUL, 0x0FFFFCUL, 0x0FFFFCUL, 0x07FFF8UL, 0x07FFF8UL, 0x03FFF0UL, 0x03FFF0UL, 0x01FFE0UL,
     0x01FFE0UL, 0x00FFC0UL, 0x00FFC0UL, 0x007F80UL, 0x007F80UL, 0x003F00UL, 0x003F00UL, 0x003F00UL, 0x001E00UL, 0x001E00UL, 0x000C00UL},
    // SW
    {0x000100UL, 0x000100UL, 0x000180UL, 0x000380UL, 0x0003C0UL, 0x0003C0UL, 0x0007C0UL, 0x0007E0UL, 0x0007E0UL, 0x000FE0UL, 0x000FF0UL,
     0x007FF0UL, 0x03FFF0UL, 0x1FFFF8UL, 0x3FFFF8UL, 0x07FFF8UL, 0x00FFFCUL, 0x003FFCUL, 0x0007FEUL, 0x00007EUL, 0x00001EUL, 0x000003UL},
    // W
    {0x200000UL, 0x380000UL, 0x1E0000UL, 0x1F8000UL, 0x0FE000UL, 0x0FFC00UL, 0x07FF00UL, 0x07FFC0UL, 0x03FFF0UL, 0x03FFFCUL, 0x01FFFFUL,
     0x01FFFFUL, 0x03FFFCUL, 0x03FFF0UL, 0x07FFC0UL, 0x07FF00UL, 0x0FFC00UL, 0x0FE000UL, 0x1F8000UL, 0x1E0000UL, 0x380000UL, 0x200000UL},
    // NW
    {0x000003UL, 0x00001EUL, 0x00007EUL, 0x0007FEUL, 0x003FFCUL, 0x00FFFCUL, 0x07FFF8UL, 0x3FFFF8UL, 0x1FFFF8UL, 0x03FFF0UL, 0x007FF0UL,
     0x000FF0UL, 0x000FE0UL, 0x0007E0UL, 0x0007E0UL, 0x0007C0UL, 0x0003C0UL, 0x0003C0UL, 0x000380UL, 0x000180UL, 0x000100UL, 0x000100UL}
  };
  const int dir = (octant + 8) % 8;
  const int x0 = kCenters[dir].x - 11;
  const int y0 = kCenters[dir].y - 12;  // 1px upward correction from watch photo

  // Group adjacent scanlines into >=2px-high cyan rectangles. 1px-high
  // raster primitives were unreliable on the physical CO5300 display.
  // The union preserves the 22x22 outline without ever writing a background
  // pixel; crossing a green/purple/etc border cannot alter that border.
  for (int y = 0; y < 22; y += 2) {
    const uint32_t bits = kArrow[dir][y] | kArrow[dir][y + 1];
    int x = 0;
    while (x < 22) {
      while (x < 22 && !(bits & (1UL << x))) ++x;
      const int start = x;
      while (x < 22 && (bits & (1UL << x))) ++x;
      if (x > start)
        gfx->fillRect(x0 + start, y0 + y, x - start, 2, CYAN_AP);
    }
  }

}

// G5A50.3f: dirty-tile tracking for the visible 3x3 / 5x5 grid.
// Fingerprints are a rendering optimization only: they never change BLE data,
// the NVS sector records, or account-bound snapshots.
namespace {
constexpr uint64_t kRenderHashBasis = 14695981039346656037ULL;
uint64_t signature3x3[9] = {};
uint64_t signature5x5[25] = {};
bool signature3x3Ready = false;
bool signature5x5Ready = false;
bool last3x3Live = false, last5x5Live = false;
bool last3x3Viewport = false, last5x5Viewport = false;

uint64_t renderMix(uint64_t hash, uint32_t value) {
  return (hash ^ static_cast<uint64_t>(value)) * 1099511628211ULL;
}
uint64_t fingerprint3x3(int slot) {
  const int index = sectorIndexForGridPosition(slot);
  uint64_t hash = renderMix(kRenderHashBasis, geoCacheTileAvailable(index) ? 1U : 0U);
  if (!geoCacheTileAvailable(index)) return hash;
  const SectorData& s = sectors3x3[index];
  hash = renderMix(hash, static_cast<uint32_t>(s.total));
  hash = renderMix(hash, static_cast<uint32_t>(s.territoryColor));
  for (int tier = 1; tier <= 10; ++tier)
    hash = renderMix(hash, static_cast<uint32_t>(liveTierCounts[index][tier]));
  for (int rank = 0; rank < 3; ++rank) {
    hash = renderMix(hash, static_cast<uint32_t>(liveLevelNumbers[index][rank]));
    hash = renderMix(hash, static_cast<uint32_t>(liveLevelCounts[index][rank]));
  }
  return hash;
}
uint64_t fingerprint5x5(int slot) {
  SectorData sector{};
  int32_t tiers[11] = {};
  const bool available = geoCacheRead5x5(slot, &sector, nullptr, tiers);
  uint64_t hash = renderMix(kRenderHashBasis, available ? 1U : 0U);
  if (!available) return hash;
  hash = renderMix(hash, static_cast<uint32_t>(sector.total));
  hash = renderMix(hash, static_cast<uint32_t>(sector.territoryColor));
  int dominant = 0;
  for (int tier = 1; tier <= 10; ++tier)
    if (tiers[tier] > 0 && (dominant == 0 || tiers[tier] > tiers[dominant]))
      dominant = tier;
  hash = renderMix(hash, static_cast<uint32_t>(dominant));
  hash = renderMix(hash, dominant ? static_cast<uint32_t>(tiers[dominant]) : 0U);
  return hash;
}
} // namespace

void draw3x3Screen();

void tickLiveHeading() {
  const uint32_t now = millis();
  if (now - lastHeadingCheckMs < 250UL) return;
  lastHeadingCheckMs = now;

  float rawBearing = 0.0f, bearingSpeedMph = 0.0f;
  uint32_t bearingSerial = 0;
  if (!getGpsHeadingUpdate(rawBearing, bearingSpeedMph, bearingSerial) ||
      gpsFixAgeMs() > 3000UL || bearingSerial == consumedBearingSerial) return;

  // Even a valid Android bearing can wobble with stationary GPS noise.
  // Consume it once, but do NOT update the arrow until actual movement.
  consumedBearingSerial = bearingSerial;
  if (bearingSpeedMph < 5.0f) return;

  // Eight compass octants. No interpolation or redraw without a NEW sample.
  const int octant = ((int)floorf((rawBearing + 22.5f) / 45.0f)) % 8;
  if (octant == displayedHeading) return;
  const int previousHeading = displayedHeading;
  displayedHeading = octant;
  if (activeNavigation && activeNavigation->screen == SCREEN_3X3 &&
      (live3x3Data || viewportDisplayActive())) {
    // Restore only the old arrow's actual 22x22 bounding box. Repaint any
    // territory tiles under it before drawing the new cyan marker LAST.
    static constexpr int centerX[8] = {207,272,272,272,207,142,142,142};
    static constexpr int centerY[8] = {195,195,254,313,313,313,254,195};
    const int oldX = centerX[previousHeading] - 11;
    const int oldY = centerY[previousHeading] - 12;
    gfx->fillRect(oldX, oldY, 22, 22, BG);
    for (int row = 0; row < 3; ++row) {
      for (int col = 0; col < 3; ++col) {
        const int tileX = 17 + col * 130;
        const int tileY = 84 + row * 118;
        // Tile raster is x+1..x+118, y+1..y+103.
        if (oldX >= tileX + 119 || oldX + 22 <= tileX + 1 ||
            oldY >= tileY + 104 || oldY + 22 <= tileY + 1) continue;
        const int index = sectorIndexForGridPosition(row * 3 + col);
        if (viewportDisplayActive()) drawViewportTile(index, tileX, tileY);
        else draw3x3Tile(index, tileX, tileY);
      }
    }
    drawCenterCourseTriangle(displayedHeading);
  }
}

void drawBottomPaletteGuide() {
  // Ten solid squares, one per 10-level tier. Pure white is T10 (last).
  constexpr int swatches = 10;
  constexpr int gap = 30;
  constexpr int squareSize = 9;
  const int x0 = (410 - (swatches - 1) * gap) / 2;
  for (int i = 0; i < swatches; ++i) {
    const int cx = x0 + i * gap;
    gfx->fillRect(cx - squareSize / 2, 451,
                  squareSize, squareSize, tierColor(i + 1));
  }
}

// STARTUP PIXEL MASCOT. One immutable pixel lattice is built first and
// each occupied cell is painted ONCE. This prevents the over-bright stripe
// caused by independent, overlapping strokes on the eyepatch.
namespace {
  constexpr int mascotCols = 80, mascotRows = 86;
  constexpr int mascotOriginX = 45, mascotOriginY = 75, mascotPitch = 4;
  uint8_t mascotMask[mascotRows][mascotCols];

  void mascotDot(float x, float y) {
    const int c = (int)lroundf((x - mascotOriginX) / mascotPitch);
    const int r = (int)lroundf((y - mascotOriginY) / mascotPitch);
    if (r >= 0 && r < mascotRows && c >= 0 && c < mascotCols)
      mascotMask[r][c] = 1;
  }
  void mascotLine(float x0, float y0, float x1, float y1) {
    const float dx = x1-x0, dy = y1-y0;
    const int n = max(1, (int)ceilf(sqrtf(dx*dx+dy*dy)/2.0f));
    for (int i=0; i<=n; ++i) {
      const float t=(float)i/n;
      mascotDot(x0+dx*t,y0+dy*t);
    }
  }
  void mascotArc(float cx,float cy,float rx,float ry,float a0,float a1) {
    const int n=max(2,(int)ceilf(fmaxf(rx,ry)*fabsf(a1-a0)/2.0f));
    for (int i=0;i<=n;++i) {
      const float a=a0+(a1-a0)*(float)i/n;
      mascotDot(cx+rx*cosf(a),cy+ry*sinf(a));
    }
  }
  void mascotBezier(float x0,float y0,float cx,float cy,float x1,float y1) {
    const float dx0=cx-x0,dy0=cy-y0,dx1=x1-cx,dy1=y1-cy;
    const int n=max(8,(int)ceilf((sqrtf(dx0*dx0+dy0*dy0)+
                                  sqrtf(dx1*dx1+dy1*dy1))/2.0f));
    for(int i=0;i<=n;++i){
      const float t=(float)i/n, q=1.0f-t;
      mascotDot(q*q*x0+2*q*t*cx+t*t*x1,
                q*q*y0+2*q*t*cy+t*t*y1);
    }
  }
  void mascotOval(float cx,float cy,float rx,float ry) {
    // Fill each pixel-grid position INSIDE the oval once, not as a
    // stack of overdrawn horizontal lines.
    for(int r=0;r<mascotRows;++r) {
      const float y=(mascotOriginY+r*mascotPitch-cy)/ry;
      if (fabsf(y)>1.0f) continue;
      for(int c=0;c<mascotCols;++c) {
        const float x=(mascotOriginX+c*mascotPitch-cx)/rx;
        if(x*x+y*y<=1.0f) mascotMask[r][c]=1;
      }
    }
  }
  void drawInitializingSmiley() {
    memset(mascotMask, 0, sizeof(mascotMask));
    constexpr float PI2=6.2831853f;
    // Single dotted shell: uniform brightness, no overlapping second rim.
    mascotArc(205,275,119,119,0,PI2);
    // Bomb neck + bent fuse + individual sparks.
    mascotLine(170,159,170,149);
    mascotLine(170,149,194,149);
    mascotLine(194,149,194,160);
    mascotBezier(181,149,184,121,216,121);
    mascotBezier(216,121,235,121,239,111);
    const int sparks[][2]={{244,103},{260,111},{252,93},{227,99},
                            {269,94},{256,127},{274,120},{240,86}};
    for(const auto &p:sparks) mascotDot((float)p[0],(float)p[1]);
    // Stop diagonal strap AT the patch, never draw across its interior.
    mascotLine(112,201,216,232);
    mascotOval(160,257,15,24);
    mascotOval(254,247,43,26);
    // Exactly one short straight connector to the right side of the shell.
    mascotLine(296,247,323,247);
    // Single-pixel-dot smile; no repeated overpainting.
    mascotBezier(135,300,149,355,205,357);
    mascotBezier(205,357,262,356,277,301);
    mascotLine(130,305,135,294);
    mascotLine(277,294,282,306);
    // Draw every logical LED pixel only once, with a consistent 3x3
    // cyan square and 1px black gap, matching the OLED dot-matrix feel.
    for (int r=0;r<mascotRows;++r)
      for(int c=0;c<mascotCols;++c)
        if(mascotMask[r][c])
          gfx->fillRect(mascotOriginX+c*mascotPitch-1,
                        mascotOriginY+r*mascotPitch-1,3,3,CYAN_AP);
  }
}

void draw3x3Screen() {
  gfx->fillScreen(BG);
  drawHeader(viewportDisplayActive() ? "CACHED // TOTALS ONLY" :
             live3x3Data ? "SECTOR UPLINK // LIVE" : "AWAITING SECTOR UPLINK");

  const int positionsX[3] = {17, 147, 277};
  const int positionsY[3] = {84, 202, 320};

  // Show mascot ONLY before the FIRST scan, with no validated sector snapshot.
  // Once SCANNING begins, show the existing empty neutral nine-tile grid.
  if (!viewportDisplayActive() && !live3x3Data && isHudInitializing()) {
    drawInitializingSmiley();
  } else {
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 3; col++) {
        int index = row * 3 + col;
        if (viewportDisplayActive()) drawViewportTile(index, positionsX[col], positionsY[row]);
        else draw3x3Tile(sectorIndexForGridPosition(index), positionsX[col], positionsY[row]);
      }
    }
  }

  // Fixed reference palette across the VERY bottom; no labels.
  drawBottomPaletteGuide();
  // Production HUD: no diagnostic marker or hardcoded levels.
  drawFooter(viewportDisplayActive() ? "GEOGRAPHIC CACHE // AP TOTALS" :
             live3x3Data ? "SECTOR MATRIX // LIVE" : "AWAITING SECTOR UPLINK");

  // Last draw on the 3x3 HUD: the arrow is always above ALL other UI.
  if (viewportDisplayActive() || live3x3Data || !isHudInitializing())
    drawCenterCourseTriangle(displayedHeading);
  for (int slot = 0; slot < 9; ++slot) signature3x3[slot] = fingerprint3x3(slot);
  signature3x3Ready = true;
  last3x3Live = live3x3Data;
  last3x3Viewport = viewportDisplayActive();
}

// G5A41: compact, north-up 5x5. This is purely a second PRESENTATION of the
// watch's existing 25 geographically keyed cached records. Opening it never
// triggers BLE, GPS navigation changes, or additional 7x7 lookahead.
void draw5x5Tile(int slot) {
  const int row = slot / 5, col = slot % 5;
  constexpr int left=22, top=85, pitchX=74, pitchY=69, width=68, height=61;
  gfx->setTextWrap(false);
    SectorData data{};
    int32_t tiers[11] = {};
    const bool available=geoCacheRead5x5(slot,&data,nullptr,tiers);
    const int x=left+col*pitchX, y=top+row*pitchY;
    const uint16_t edge=available?data.territoryColor:GREY;
    // Short transfers (2px/high) are reliable on the CO5300; the 3x3 raster
    // implementation remains unchanged. Never merge border and AP-tier colors.
    gfx->fillRect(x+2,y,width-4,2,edge);
    gfx->fillRect(x,y+2,2,height-4,edge);
    gfx->fillRect(x+width-2,y+2,2,height-4,edge);
    gfx->fillRect(x+2,y+height-2,width-4,2,edge);
    gfx->fillRect(x+2,y+2,width-4,height-4,PANEL);
    if (!available) {
      gfx->setTextSize(2);
      gfx->setTextColor(YELLOW_AP);
      gfx->setCursor(x+28,y+12); gfx->print("?");
      gfx->setTextSize(1);
      gfx->setCursor(x+11,y+38); gfx->print("UNKNOWN");
      return;
    }
    int dominant=0;
    for(int tier=1;tier<=10;++tier)
      if(tiers[tier]>0 && (dominant==0 || tiers[tier]>tiers[dominant])) dominant=tier;
    char total[18];
    snprintf(total,sizeof(total),"%d",data.total);
    const int len=static_cast<int>(strlen(total));
    const int size=len<=5?2:1;
    gfx->setTextSize(size); gfx->setTextColor(WHITE);
    gfx->setCursor(x+(width-len*6*size)/2,y+10);
    gfx->print(total);
    if(dominant) {
      char line[18];snprintf(line,sizeof(line),"T%d:%ld",dominant,static_cast<long>(tiers[dominant]));
      gfx->setTextSize(1);gfx->setTextColor(tierColor(dominant));
      const int chars=static_cast<int>(strlen(line));
      gfx->setCursor(x+(width-chars*6)/2,y+39);
      gfx->print(line);
    } else { // A validated zero-AP sector is NOT UNKNOWN.
      gfx->setTextSize(1);gfx->setTextColor(GREY);
      gfx->setCursor(x+20,y+39);gfx->print("EMPTY");
    }
    if(slot==12) { // Mark GPS-center with a small cyan notch, not a new border.
      gfx->fillRect(x+width/2-4,y+height-4,8,2,CYAN_AP);
    }
  }

void draw5x5Screen() {
  gfx->fillScreen(BG);
  // Same fixed header/footer and GPS/BLE icons as the 3x3 grid.
  drawHeader(viewportDisplayActive() ? "CACHED // TOTALS ONLY" :
             live3x3Data ? "SECTOR UPLINK // LIVE" : "AWAITING SECTOR UPLINK");
  for (int slot = 0; slot < 25; ++slot) draw5x5Tile(slot);
  drawBottomPaletteGuide();
  drawFooter(viewportDisplayActive() ? "GEOGRAPHIC CACHE // AP TOTALS" :
             live3x3Data ? "SECTOR MATRIX // LIVE" : "AWAITING SECTOR UPLINK");
  for (int slot = 0; slot < 25; ++slot) signature5x5[slot] = fingerprint5x5(slot);
  signature5x5Ready = true;
  last5x5Live = live3x3Data;
  last5x5Viewport = viewportDisplayActive();
}

// G5A50.3f: No whole-screen clear when a GEO25/K,E record arrives.
void refreshCachedGridTiles(const Navigation& navigation) {
  if (navigation.screen == SCREEN_3X3) {
    if (!signature3x3Ready || last3x3Live != live3x3Data ||
        last3x3Viewport != viewportDisplayActive()) {
      showScreen(navigation);  // An actual presentation/header transition.
      return;
    }
    if (viewportDisplayActive()) return; // Rendered by atomic viewport commits.
    bool changed = false;
    for (int row = 0; row < 3; ++row) for (int col = 0; col < 3; ++col) {
      const int slot = row * 3 + col;
      const uint64_t signature = fingerprint3x3(slot);
      if (signature == signature3x3[slot]) continue;
      draw3x3Tile(sectorIndexForGridPosition(slot), 17 + col * 130, 84 + row * 118);
      signature3x3[slot] = signature;
      changed = true;
    }
    if (changed) drawCenterCourseTriangle(displayedHeading);
  } else if (navigation.screen == SCREEN_5X5) {
    if (!signature5x5Ready || last5x5Live != live3x3Data ||
        last5x5Viewport != viewportDisplayActive()) {
      showScreen(navigation);
      return;
    }
    if (viewportDisplayActive()) return;
    for (int slot = 0; slot < 25; ++slot) {
      const uint64_t signature = fingerprint5x5(slot);
      if (signature == signature5x5[slot]) continue;
      draw5x5Tile(slot);
      signature5x5[slot] = signature;
    }
  } else if (navigation.screen == SCREEN_DETAIL) {
    // Preserve Sector Intel correctness when its selected sector is refreshed.
    showScreen(navigation);
  }
  // Other screens are unaffected by new AP cache records.
}

void drawDetailScreen() {
  gfx->fillScreen(BG);
  if (viewportDisplayActive() && !(activeNavigation && activeNavigation->detailFrom5x5)) {
    // A geographic cache packet carries totals ONLY. Never show stale owner
    // names, gang colors, or tier data from an earlier geographic center.
    drawHeader("CACHED GEOGRAPHIC VIEW");
    gfx->setTextColor(YELLOW_AP);
    gfx->setTextSize(2);
    gfx->setCursor(35, 180);
    gfx->print("TIER DATA NOT LOADED");
    drawFooter("SYNC FOR FULL SECTOR DETAIL");
    return;
  }
  const int index = activeNavigation ? activeNavigation->selectedSector : 4;
  const bool from5x5 = activeNavigation && activeNavigation->detailFrom5x5;
  static SectorData inspectSector;
  static int32_t inspectRaw[256];
  static int32_t inspectTiers[11];
  bool inspectAvailable = true;
  if (from5x5) {
    inspectSector = SectorData{};
    memset(inspectRaw,0,sizeof(inspectRaw));
    memset(inspectTiers,0,sizeof(inspectTiers));
    inspectAvailable = geoCacheRead5x5(activeNavigation->selectedSector5x5,
                                      &inspectSector,inspectRaw,inspectTiers);
    if (!inspectAvailable) {
      inspectSector.territoryColor = GREY;
      snprintf(inspectSector.gangName,sizeof(inspectSector.gangName),"UNKNOWN");
    }
  }
  const SectorData &sector = from5x5 ? inspectSector : sectors3x3[index];
  // Both detail origins display the identical full histogram/tier layout.
  auto rawLevelAt = [&](int level) -> int32_t {
    return from5x5 ? inspectRaw[level] : liveRawLevels[index][level];
  };
  auto tierCountAt = [&](int tier) -> int32_t {
    return from5x5 ? inspectTiers[tier] : liveTierCounts[index][tier];
  };
  gfx->setTextWrap(false);

  constexpr char heading[] = "SECTOR INTEL";
  gfx->setTextSize(3);
  gfx->setTextColor(CYAN_AP);
  gfx->setCursor((410 - (sizeof(heading) - 1) * 18) / 2, 7);
  gfx->print(heading);
  gfx->drawFastHLine(20, 72, 370, GREY);

  // Gang owner displayed before AP totals, in the verified official gang color.
  // Unknown/unclaimed labels are deliberately neutral; no owner is fabricated.
  const char* gang = sector.gangName[0] ? sector.gangName : "UNKNOWN";
  const size_t gangLength = strlen(gang);
  constexpr size_t maxCharsSize2 = 30; // 360 px, clear of side rails.
  constexpr size_t maxCharsSize1 = 60; // Same 360 px at size 1.
  int gangSize = 2;
  size_t split = 0;
  // Mirror the balanced, whole-word OPERATOR wrapping behavior.
  auto balancedWordSplit = [&](size_t limit) -> size_t {
    size_t best = 0, closest = gangLength + 1;
    for (size_t i = 1; i + 1 < gangLength; ++i) {
      if (gang[i] != ' ' || gang[i - 1] == ' ') continue;
      size_t start2 = i + 1;
      while (start2 < gangLength && gang[start2] == ' ') ++start2;
      if (start2 == gangLength || i > limit || gangLength - start2 > limit) continue;
      const size_t difference = i > gangLength - start2
          ? i - (gangLength - start2) : (gangLength - start2) - i;
      if (!best || difference < closest) { best = i; closest = difference; }
    }
    return best;
  };
  if (gangLength > 16) {
    split = balancedWordSplit(maxCharsSize2);
    if (!split) { gangSize = 1; split = balancedWordSplit(maxCharsSize1); }
  }
  if (!split && gangLength > maxCharsSize2) gangSize = 1;
  // Long unbroken names cannot be word-wrapped: split as a last resort.
  if (!split && gangLength > maxCharsSize1)
    split = gangLength / 2;
  char firstLine[97] = {};
  const char* line1 = gang;
  const char* line2 = nullptr;
  if (split) {
    memcpy(firstLine, gang, split);
    firstLine[split] = 0;
    line1 = firstLine;
    line2 = gang + split;
    while (*line2 == ' ') ++line2;
  }
  const bool knownGang = sector.gangName[0] &&
      strcmp(sector.gangName, "UNKNOWN") != 0 &&
      strcmp(sector.gangName, "UNCLAIMED") != 0;
  gfx->setTextColor(knownGang ? sector.territoryColor : GREY);
  gfx->setTextSize(gangSize);
  const int gangGlyphWidth = gangSize * 6;
  const int gangY = line2 ? (gangSize == 2 ? 84 : 91) : 94;
  gfx->setCursor((410 - static_cast<int>(strlen(line1)) * gangGlyphWidth) / 2, gangY);
  gfx->print(line1);
  if (line2) {
    gfx->setCursor((410 - static_cast<int>(strlen(line2)) * gangGlyphWidth) / 2,
                   gangY + (gangSize == 2 ? 21 : 13));
    gfx->print(line2);
  }

  char label[56];
  snprintf(label, sizeof(label), "TOTAL AP: %d", sector.total);
  gfx->setTextSize(2);
  gfx->setTextColor(WHITE);
  gfx->setCursor((410 - (int)strlen(label) * 12) / 2, 137);
  gfx->print(label);

  int maxLevel = 0;
  for (int level = 1; level <= 255; ++level)
    if (rawLevelAt(level) > 0) maxLevel = level;
  const int maxTier = maxLevel ? min(10, (maxLevel - 1) / 10 + 1) : 0;
  snprintf(label, sizeof(label), "MAX LEVEL: L%02d", maxLevel);
  const int labelX = (410 - (int)strlen(label) * 12) / 2;
  gfx->setTextSize(2);
  gfx->setTextColor(maxTier ? tierColor(maxTier) : GREY);
  gfx->setCursor(labelX, 165);
  gfx->print(label);
  gfx->drawFastHLine(20, 195, 370, GREY);

  // All populated hardness tiers, paginated before the fixed bottom legend.
  // Layout contract: one centered block; every tier label begins at the same X,
  // all colons share one X, and all AP counts are right-aligned at the same X.
  constexpr int perPage = 14;
  int tierList[10];
  int tierCount = 0;
  for (int tier = 1; tier <= 10; ++tier)
    if (tierCountAt(tier) > 0) tierList[tierCount++] = tier;
  const int pages = tierCount ? (tierCount + perPage - 1) / perPage : 1;
  const int page = activeNavigation ? activeNavigation->detailPage % pages : 0;
  const int from = page * perPage;
  const int rows = min(perPage, tierCount - from);

  // Compute column widths from the currently visible rows. Built-in font size
  // 2 = 12 horizontal pixels per character; no proportional-font assumptions.
  constexpr int textSize = 2;
  constexpr int glyphWidth = 12;
  int widestTierLabel = 0;
  int widestCount = 0;
  for (int row = 0; row < rows; ++row) {
    const int tier = tierList[from + row];
    const int first = (tier - 1) * 10 + 1;
    const int last = tier * 10;
    char tierText[36], countText[20];
    if (tier == 10 && maxLevel > 100)
      snprintf(tierText, sizeof(tierText), "T10 L91+");
    else
      snprintf(tierText, sizeof(tierText), "T%d L%02d-L%02d", tier, first, last);
    snprintf(countText, sizeof(countText), "%d", tierCountAt(tier));
    widestTierLabel = max(widestTierLabel, (int)strlen(tierText) * glyphWidth);
    widestCount = max(widestCount, (int)strlen(countText) * glyphWidth);
  }
  constexpr int gapToColon = 12;
  constexpr int gapToCount = 12;
  const int blockWidth = widestTierLabel + gapToColon + glyphWidth + gapToCount + widestCount;
  const int xLeft = (410 - blockWidth) / 2;
  const int xColon = xLeft + widestTierLabel + gapToColon;
  const int countRight = xColon + glyphWidth + gapToCount + widestCount;
  // Start close beneath MAX LEVEL rather than vertically centering a short
  // list in a mostly empty page. Squeeze 14 tiers into the safe grid area.
  const int listTop = 212;
  const int stride = rows <= 1 ? 26 : min(26, (423 - listTop) / (rows - 1));

  if (tierCount == 0) {
    gfx->setTextColor(GREY);
    gfx->setTextSize(2);
    constexpr char waiting[] = "AWAITING DATA";
    gfx->setCursor((410 - ((int)sizeof(waiting) - 1) * 12) / 2, 228);
    gfx->print(waiting);
  }
  for (int row = 0; row < rows; ++row) {
    const int tier = tierList[from + row];
    const int first = (tier - 1) * 10 + 1;
    const int last = tier * 10;
    char left[36], count[20];
    if (tier == 10 && maxLevel > 100)
      snprintf(left, sizeof(left), "T10 L91+");
    else
      snprintf(left, sizeof(left), "T%d L%02d-L%02d", tier, first, last);
    snprintf(count, sizeof(count), "%d", tierCountAt(tier));
    const int xCount = countRight - (int)strlen(count) * glyphWidth;
    const int y = listTop + row * stride;
    gfx->setTextSize(textSize);
    gfx->setTextColor(tierColor(tier));
    gfx->setCursor(xLeft, y); gfx->print(left);
    gfx->setCursor(xColon, y); gfx->print(":");
    gfx->setCursor(xCount, y); gfx->print(count);
  }
  if (pages > 1) {
    gfx->setTextColor(GREY); gfx->setTextSize(1);
    gfx->setCursor(160, 450);
    gfx->printf("%d / %d", page + 1, pages);
  }
  // Same permanent 10-color guide used on the 3x3 HUD.
  drawBottomPaletteGuide();
  drawSecondaryCircuitFrame();
}

// G5A45: OPERATOR retains the production CO5300 pixel font, circuit rails,
// RTC clock, battery and gear; the center content has the approved activity
// rank and tappable TEAM area. No user-specific values are hardcoded.
void drawCardScreen() {
  gfx->fillScreen(BG);
  gfx->setTextWrap(false);

  constexpr char heading[] = "OPERATOR";
  gfx->setTextSize(3);
  gfx->setTextColor(CYAN_AP);
  gfx->setCursor((410 - ((int)sizeof(heading) - 1) * 18) / 2, 7);
  gfx->print(heading);

  // G5A45.1: one muted activity-rank descriptor ABOVE the original callsign.
  // Do not display a placeholder when the card's rank could not be verified.
  if (strcmp(activityRank, "--") != 0 && activityRank[0] != '\0') {
    const int rankFontSize = 2; // 12 px; original callsign remains 3x/4x
    gfx->setTextSize(rankFontSize);
    gfx->setTextColor(0x7CD6); // muted steel blue; separate from cyan gang text
    gfx->setCursor((410 - static_cast<int>(strlen(activityRank)) * 6 * rankFontSize) / 2, 63);
    gfx->print(activityRank);
  }

  const char* callsign = operatorProfile.valid ? operatorProfile.username : "--";
  const int nameSize = strlen(callsign) > 12 ? 3 : 4;
  gfx->setTextSize(nameSize); gfx->setTextColor(WHITE);
  gfx->setCursor((410 - static_cast<int>(strlen(callsign)) * 6 * nameSize) / 2, 93);
  gfx->print(callsign);

  // G5A22: OPERATOR name -> full gang (1-2 centered, whole-word lines)
  // -> profile status -> unchanged Wi-Fi / BLE / TOTAL / BADGES sections.
  // Built-in Arduino font is the existing dotted/pixel font used by the HUD.
  const char* gang = operatorProfile.valid ? operatorProfile.gang : "--";
  const size_t gangLength = strlen(gang);
  size_t split = 0;
  int gangTextSize = 2;
  constexpr size_t maxCharsSize2 = 27; // 324 px; inside the side rails.
  constexpr size_t maxCharsSize1 = 54; // Same 324 px at the smaller size.

  auto balancedWordSplit = [&](size_t maxChars) -> size_t {
    size_t best = 0;
    size_t bestDifference = gangLength + 1;
    for (size_t i = 1; i + 1 < gangLength; ++i) {
      if (gang[i] != ' ' || gang[i - 1] == ' ') continue;
      size_t secondStart = i + 1;
      while (secondStart < gangLength && gang[secondStart] == ' ') ++secondStart;
      if (secondStart == gangLength) continue;
      const size_t secondLength = gangLength - secondStart;
      if (i > maxChars || secondLength > maxChars) continue;
      const size_t diff = i > secondLength ? i - secondLength : secondLength - i;
      if (!best || diff < bestDifference) {
        best = i;
        bestDifference = diff;
      }
    }
    return best;
  };

  if (gangLength > 16) {
    split = balancedWordSplit(maxCharsSize2);
    if (!split) {
      gangTextSize = 1;
      split = balancedWordSplit(maxCharsSize1);
    }
  }
  if (!split && gangLength > maxCharsSize2) gangTextSize = 1;

  char firstLine[97] = {};
  const char* line1 = gang;
  const char* line2 = nullptr;
  if (split) {
    memcpy(firstLine, gang, split);
    firstLine[split] = '\0';
    line1 = firstLine;
    const char* next = gang + split + 1;
    while (*next == ' ') ++next;
    line2 = next;
  }

  gfx->setTextWrap(false);
  gfx->setTextSize(gangTextSize);
  gfx->setTextColor(CYAN_AP);
  const int glyph = 6 * gangTextSize;
  // Two lines sit above status, while the unchanged stats begin at y=252.
  const int gangY = line2 ? (gangTextSize == 2 ? 145 : 155) : 164;
  gfx->setCursor((410 - static_cast<int>(strlen(line1)) * glyph) / 2, gangY);
  gfx->print(line1);
  if (line2) {
    const int secondY = gangY + (gangTextSize == 2 ? 24 : 14);
    gfx->setCursor((410 - static_cast<int>(strlen(line2)) * glyph) / 2, secondY);
    gfx->print(line2);
  }

  // G5A23: last-good snapshot stays visible while a new /api/me request
  // runs. A failed refresh never replaces the last-good SYNCED label.
  const char* state = operatorProfile.valid ? "WDGWARS // SYNCED" :
                      operatorProfile.refreshing ? "UPDATING PROFILE" :
                      operatorProfile.failed ? "UPDATE UNAVAILABLE" : "AWAITING PROFILE";
  gfx->setTextSize(2);
  gfx->setTextColor(operatorProfile.failed && !operatorProfile.valid ? YELLOW_AP : WHITE);
  gfx->setCursor((410 - static_cast<int>(strlen(state)) * 12) / 2, 213);
  gfx->print(state);

  constexpr int leftCenter = 110, rightCenter = 300;
  auto centered = [&](const char* str, int xCenter, int y, int size, uint16_t color) {
    gfx->setTextSize(size);
    gfx->setTextColor(color);
    gfx->setCursor(xCenter - (static_cast<int>(strlen(str)) * 6 * size) / 2, y);
    gfx->print(str);
  };
  centered("WI-FI", leftCenter, 252, 2, WHITE);
  centered("BLE", rightCenter, 252, 2, WHITE);
  char wifi[20], ble[20], total[20], badges[20];
  if (operatorProfile.valid) {
    formatOperatorCount(operatorProfile.wifi, wifi);
    formatOperatorCount(operatorProfile.ble, ble);
    formatOperatorCount(operatorProfile.total, total);
    formatOperatorCount(operatorProfile.badges, badges);
  } else {
    strcpy(wifi, "--"); strcpy(ble, "--");
    strcpy(total, "--"); strcpy(badges, "--");
  }
  centered(wifi, leftCenter, 284, strlen(wifi) > 10 ? 2 : 3, CYAN_AP);
  centered(ble, rightCenter, 284, strlen(ble) > 10 ? 2 : 3, 0xFA36);
  centered("TOTAL", leftCenter, 345, 2, WHITE);
  centered("BADGES", rightCenter, 345, 2, WHITE);
  centered(total, leftCenter, 377, strlen(total) > 10 ? 2 : 3, WHITE);
  centered(badges, rightCenter, 377, strlen(badges) > 10 ? 2 : 3, 0x07E0);

  drawSecondaryCircuitFrame();
  drawOperatorGearPlaceholder();
  drawOperatorClock();
  drawOperatorBattery();
}

static void teamNumber(int32_t value,char (&buffer)[20]) {
  if(value<0) snprintf(buffer,sizeof(buffer),"--");
  else snprintf(buffer,sizeof(buffer),"%ld",(long)value);
}
static void teamHeader(const char* title) {
  gfx->fillScreen(BG); gfx->setTextWrap(false);
  gfx->setTextSize(3);gfx->setTextColor(CYAN_AP);
  gfx->setCursor((410-(int)strlen(title)*18)/2,7);gfx->print(title);
}
static void teamNameAt(int y) {
  const char* name=teamScreen.valid?teamScreen.name:"--";
  int size=strlen(name)>26?1:2;
  const int chars=size==2?27:54;
  char first[55]={},second[55]={};
  const size_t n=strlen(name);
  if(n>(size_t)chars) {
    size_t split=chars;
    while(split>1 && name[split]!=' ') --split;
    if(split<=1) split=chars;
    memcpy(first,name,split);first[split]=0;
    const char* next=name+split;
    while(*next==' ')++next;
    snprintf(second,sizeof(second),"%s",next);
  } else snprintf(first,sizeof(first),"%s",name);
  gfx->setTextSize(size);gfx->setTextColor(teamScreen.teamColor);
  gfx->setCursor((410-(int)strlen(first)*size*6)/2,y);gfx->print(first);
  if(second[0]) {
    gfx->setCursor((410-(int)strlen(second)*size*6)/2,y+20);gfx->print(second);
  }
}
static void drawTeamScreen() {
  teamHeader("GANG");
  if(teamScreen.noTeam) {
    gfx->setTextColor(YELLOW_AP);gfx->setTextSize(2);
    gfx->setCursor(112,207);gfx->print("NO GANG FOUND");
  } else if(!teamScreen.valid) {
    gfx->setTextColor(GREY);gfx->setTextSize(2);
    const char* message=teamScreen.failed?"GANG UNAVAILABLE":"NO CACHED GANG";
    gfx->setCursor((410-(int)strlen(message)*12)/2,207);gfx->print(message);
  } else {
    teamNameAt(78);
    // Rank and member count sit directly below the gang name, including
    // names that wrap onto two lines. All lower content is statistics only.
    const size_t len=strlen(teamScreen.name);
    const int nameChars=len>26?54:27;
    const int rankY=len>(size_t)nameChars?124:105;
    const int membersY=rankY+27;
    const struct {const char* label;int32_t value;int y;} identity[] = {
      {"RANK:",teamScreen.rank,rankY},
      {"MEMBERS:",(int32_t)teamScreen.memberCount,membersY}
    };
    for(const auto &item:identity) {
      char number[20];teamNumber(item.value,number);
      gfx->setTextSize(2);gfx->setTextColor(WHITE);
      gfx->setCursor(100,item.y);gfx->print(item.label);
      gfx->setTextColor(item.label[0]=='R'?YELLOW_AP:CYAN_AP);
      gfx->setCursor(262-(int)strlen(number)*12,item.y);gfx->print(number);
    }
    gfx->fillRect(48,188,314,2,CYAN_AP);
    const struct {const char* label;int32_t value;int y;} stats[] = {
      {"WI-FI",teamScreen.wifi,213},{"BLE",teamScreen.ble,255},
      {"TOTAL",teamScreen.total,297},{"CREDITS",teamScreen.credits,339}
    };
    for(const auto &item:stats) {
      char number[20];teamNumber(item.value,number);
      gfx->setTextSize(2);gfx->setTextColor(WHITE);
      gfx->setCursor(100,item.y);gfx->print(item.label);
      gfx->setTextColor(CYAN_AP);
      gfx->setCursor(324-(int)strlen(number)*12,item.y);gfx->print(number);
    }
  }
  drawSecondaryCircuitFrame();drawOperatorClock();drawOperatorBattery();
}
static void drawMembersScreen() {
  teamHeader("MEMBERS");
  if(teamScreen.valid) {
    teamNameAt(65);
    char rankText[20];teamNumber(teamScreen.rank,rankText);
    gfx->setTextSize(2);gfx->setTextColor(YELLOW_AP);
    char line[34];snprintf(line,sizeof(line),"RANK: %s",rankText);
    gfx->setCursor((410-(int)strlen(line)*12)/2,119);gfx->print(line);
  }
  if(!teamScreen.pageValid || !activeNavigation ||
     activeNavigation->membersPage != teamScreen.page) {
    gfx->setTextSize(2);gfx->setTextColor(teamScreen.failed ? YELLOW_AP : GREY);
    const char* message=teamScreen.failed?"MEMBERS UNAVAILABLE":"LOADING MEMBERS";
    gfx->setCursor((410-(int)strlen(message)*12)/2,250);gfx->print(message);
  } else {
    const char* roleName[]={"MEMBER","OFFICER","OWNER"};
    // Eight readable rows, 34px pitch. Last AP line ends at y~406;
    // the bottom controls start at y=444, inside the rounded-display safe area.
    for(int i=0;i<teamScreen.pageSize && i<8;++i) {
      const TeamMemberRow& member=teamScreen.pageRows[i];
      const int y=145+i*34;
      gfx->setTextSize(2);gfx->setTextColor(WHITE);
      gfx->setCursor(54,y);gfx->print(member.username);
      gfx->setTextSize(1);gfx->setTextColor(YELLOW_AP);
      gfx->setCursor(54,y+19);gfx->print(roleName[member.role<=2?member.role:0]);
      char count[20];teamNumber(member.total,count);
      gfx->setTextColor(CYAN_AP);
      gfx->setCursor(270,y+19);gfx->print("AP ");gfx->print(count);
    }
  }
  gfx->setTextSize(2);gfx->setTextColor(CYAN_AP);
  gfx->setCursor(48,444);gfx->print("< TEAM");
  const uint16_t requestedPage = activeNavigation ?
      activeNavigation->membersPage : teamScreen.page;
  if (requestedPage > 0) {gfx->setCursor(175,444);gfx->print("<");}
  if (static_cast<uint32_t>(requestedPage) + 1 < static_cast<uint32_t>(teamRosterPageCount())) {
    gfx->setCursor(347,444);gfx->print(">");
  }
  char page[32];snprintf(page,sizeof(page),"PAGE %u/%u",
       static_cast<unsigned>(requestedPage+1),
       static_cast<unsigned>(teamRosterPageCount()));
  gfx->setTextSize(1);gfx->setTextColor(WHITE);
  gfx->setCursor(218,449);gfx->print(page);
  drawSecondaryCircuitFrame();drawOperatorClock();drawOperatorBattery();
}

void setOperatorActivity(const char* rank, int days) {
  static const char* allowed[]={"MACHINE","RELENTLESS","COMMITTED","REGULAR","CASUAL","DORMANT"};
  const char* selected="--";
  for(const auto* name:allowed) if(strcmp(name,rank)==0) {selected=name;break;}
  snprintf(activityRank,sizeof(activityRank),"%s",selected);
  activityDays30=days>=0 && days<=30?days:-1;
  if(activeNavigation && activeNavigation->screen==SCREEN_CARD)drawCardScreen();
}
void setTeamSummary(const char* name,int32_t rank,uint32_t count,
                    int32_t wifi,int32_t ble,int32_t total,int32_t credits,
                    uint16_t teamColor) {
  snprintf(teamScreen.name,sizeof(teamScreen.name),"%s",name);
  teamScreen.rank=rank;teamScreen.memberCount=count;
  teamScreen.wifi=wifi;teamScreen.ble=ble;teamScreen.total=total;teamScreen.credits=credits;
  teamScreen.teamColor=teamColor;
  teamScreen.valid=true;teamScreen.noTeam=false;teamScreen.failed=false;
  teamScreen.refreshing=false;teamScreen.pageValid=false;
  saveGangSummary(); // Only writes if the complete snapshot actually changed.
  if(activeNavigation && activeNavigation->screen==SCREEN_TEAM)drawTeamScreen();
}
void setTeamRosterPage(uint16_t page,uint16_t pages,const TeamMemberRow* rows,uint8_t count) {
  teamScreen.page=page;teamScreen.pageCount=pages?pages:1;
  teamScreen.pageSize=count>8?8:count;
  for(int i=0;i<8;++i)teamScreen.pageRows[i]= i<count?rows[i]:TeamMemberRow{};
  teamScreen.pageValid=true;
  if(activeNavigation && activeNavigation->screen==SCREEN_MEMBERS &&
     activeNavigation->membersPage==page)drawMembersScreen();
}
int teamRosterPageCount() {
  if (!teamScreen.valid) return 1;
  // 5,000 API member limit -> at most 625 pages with eight entries each.
  return max(1, static_cast<int>((teamScreen.memberCount + 7) / 8));
}
bool teamDataAvailable() {return teamScreen.valid;}
void setTeamNoTeam() {
  teamScreen=TeamScreenData{};teamScreen.noTeam=true;
  saveGangSummary(true); // Only a confirmed 404 clears a previously known gang.
  if(activeNavigation && activeNavigation->screen==SCREEN_TEAM)drawTeamScreen();
}
void setTeamFetchFailed() {
  teamScreen.refreshing=false;teamScreen.failed=true;
  if(activeNavigation && activeNavigation->screen==SCREEN_TEAM)drawTeamScreen();
}

// G5A28: separate SETTINGS screen. OPERATOR screen code and its exact
// approved G5A27 clock, gear, and statistics remain unchanged.
// Settings reuses the EXACT accepted G5A27 gear for returning to OPERATOR.
// Only the changed brightness/stale controls repaint; no full-screen flash.
// G5A45.4: compact SETTINGS; no DEFAULT button, no continuous slider.
// G5A50.5d: one shared preset renderer for the approved Settings buttons.
// Native raster geometry is used on the 410x502 CO5300 display.
static void drawSettingsPreset(int x, int y, const char* text, bool selected) {
  constexpr int kWidth = 118, kHeight = 48, kRadius = 7;
  // Solid filled selection; neon-cyan 3px outline on unselected options.
  gfx->fillRoundRect(x, y, kWidth, kHeight, kRadius, CYAN_AP);
  if (!selected) {
    gfx->fillRoundRect(x + 3, y + 3, kWidth - 6, kHeight - 6,
                       kRadius - 2, BG);
  } else {
    gfx->drawRoundRect(x + 1, y + 1, kWidth - 2, kHeight - 2,
                       kRadius - 1, WHITE);
  }
  gfx->setTextWrap(false);
  gfx->setTextSize(2);
  gfx->setTextColor(selected ? BG : WHITE);
  gfx->setCursor(x + (kWidth - static_cast<int>(strlen(text)) * 12) / 2,
                 y + 16);
  gfx->print(text);
}

void drawBrightnessChoices() {
  constexpr int bx[3] = {26, 146, 266};
  constexpr const char* names[3] = {"LOW", "MED", "HIGH"};
  for (uint8_t i = 0; i < 3; ++i) {
    drawSettingsPreset(bx[i], 148, names[i], brightnessLevel == i);
  }
}

void drawStaleChoices() {
  constexpr int bx[3] = {26, 146, 266};
  constexpr uint16_t values[3] = {30, 60, 240};
  constexpr const char* names[3] = {"30M", "60M", "240M"};
  for (uint8_t i = 0; i < 3; ++i) {
    drawSettingsPreset(bx[i], 282, names[i], staleIntervalMinutes == values[i]);
  }
}
void drawSettingsScreen() {
  gfx->fillScreen(BG);
  gfx->setTextWrap(false);
  constexpr char title[] = "SETTINGS";
  gfx->setTextColor(CYAN_AP);
  gfx->setTextSize(3);
  gfx->setCursor((410 - (static_cast<int>(sizeof(title)) - 1) * 18) / 2, 18);
  gfx->print(title);
  gfx->fillRect(38, 78, 334, 3, CYAN_AP);

  // Approved neon-pink section headings.
  constexpr uint16_t kSectionMagenta = 0xFA5F;
  gfx->setTextSize(2);
  gfx->setTextColor(kSectionMagenta);
  gfx->setCursor(46, 112);
  gfx->print("BRIGHTNESS");
  drawBrightnessChoices();

  gfx->setTextColor(kSectionMagenta);
  gfx->setCursor(46, 240);
  gfx->print("SECTOR SYNC");
  drawStaleChoices();

  // Large, visibly clickable cyan frame, raised above the existing gear.
  // Fill the dark interior separately for a 4-pixel, rounded outline.
  gfx->fillRoundRect(42, 348, 326, 55, 9, CYAN_AP);
  gfx->fillRoundRect(46, 352, 318, 47, 7, PANEL);
  gfx->fillRect(58, 354, 294, 2, 0x054C); // subtle inset highlight
  gfx->setTextSize(3);
  gfx->setTextColor(WHITE);
  gfx->setCursor(115, 364);
  gfx->print("VISUALIZER");

  // Same previously approved 32x32 Settings gear; no geometry changes.
  drawOperatorGearPlaceholder();
}
// G5A36 visualizer controls: no changes to the approved OPERATOR/brightness layout.
// Touch regions: enabled y=107..148, mode y=163..208, 4x2 palette y=250/318.
// G5A50.5e: approved VISUALIZER layout, retaining the native 410x502 look.
// Selected buttons: black text on cyan. All other labels: fixed HUD colors.
// Colour is represented by the palette and rainbow graph, not recolored text.
static void drawVisualizerOption(int x, int y, int width, const char* label,
                                 bool selected) {
  constexpr int kHeight = 51;
  gfx->fillRoundRect(x, y, width, kHeight, 8, CYAN_AP);
  if (!selected) {
    gfx->fillRoundRect(x + 3, y + 3, width - 6, kHeight - 6, 6, BG);
  } else {
    gfx->drawRoundRect(x + 1, y + 1, width - 2, kHeight - 2, 7, WHITE);
  }
  gfx->setTextWrap(false);
  gfx->setTextSize(2);
  gfx->setTextColor(selected ? BG : WHITE);
  gfx->setCursor(x + (width - static_cast<int>(strlen(label)) * 12) / 2,
                 y + 17);
  gfx->print(label);
}

static void drawVisualizerEqualizer(bool rainbow) {
  constexpr int kBars[15] = {3, 5, 8, 11, 14, 9, 7, 17, 13, 9, 7, 11, 8, 5, 3};
  constexpr uint16_t kRainbow[7] = {
      0xF81F, 0xF9A0, 0xFD20, 0xFFE0, 0x07E0, 0x07FF, 0x681F};
  constexpr int x0 = 96;
  constexpr int bottom = 382;
  for (int i = 0; i < 15; ++i) {
    const uint16_t ink = rainbow ? kRainbow[(i * 7) / 15] : 0x3186;
    for (int bar = 0; bar < kBars[i]; ++bar) {
      gfx->fillRect(x0 + i * 15, bottom - bar * 3, 10, 2, ink);
    }
  }
}

void drawVisualizerSettingsScreen() {
  gfx->fillScreen(BG);
  gfx->setTextWrap(false);
  constexpr uint16_t kSectionMagenta = 0xFA5F;

  // Retained dark, clean WDG-HUD styling and dot-matrix typography.
  constexpr char title[] = "VISUALIZER";
  gfx->setTextSize(3);
  gfx->setTextColor(CYAN_AP);
  gfx->setCursor((410 - (static_cast<int>(sizeof(title)) - 1) * 18) / 2, 18);
  gfx->print(title);
  // Small equalizer marks are static icons, not an audio-preview animation.
  for (int i = 0; i < 5; ++i) {
    const int height = 4 + (i % 3) * 3;
    gfx->fillRect(40 + i * 10, 48 - height, 6, height, CYAN_AP);
    gfx->fillRect(324 + i * 10, 48 - height, 6, height, CYAN_AP);
  }
  gfx->fillRect(38, 78, 334, 3, CYAN_AP);

  // One quiet content frame; no extra decorative panels or glow effects.
  gfx->drawRoundRect(21, 91, 368, 324, 14, CYAN_AP);

  gfx->setTextSize(2);
  gfx->setTextColor(kSectionMagenta);
  gfx->setCursor(43, 111);
  gfx->print("STATUS");
  gfx->fillRect(130, 122, 239, 2, kSectionMagenta);

  drawVisualizerOption(43, 149, 152, "OFF", !visualizerEnabled);
  drawVisualizerOption(215, 149, 152, "ON", visualizerEnabled);

  if (!visualizerEnabled) {
    // OFF: no MODE, no COLOR controls, and no invisible hit targets.
    drawVisualizerEqualizer(false);
    gfx->setTextSize(2);
    gfx->setTextColor(CYAN_AP);
    constexpr char state[] = "DISABLED";
    gfx->setCursor((410 - (static_cast<int>(sizeof(state)) - 1) * 12) / 2,
                   389);
    gfx->print(state);
  } else {
    gfx->setTextSize(2);
    gfx->setTextColor(kSectionMagenta);
    gfx->setCursor(43, 224);
    gfx->print("MODE");
    gfx->fillRect(115, 235, 254, 2, kSectionMagenta);

    drawVisualizerOption(43, 263, 152, "SOLID", !visualizerRainbow);
    drawVisualizerOption(215, 263, 152, "RAINBOW", visualizerRainbow);

    if (visualizerRainbow) {
      // Rainbow: static spectrum graphic; text stays cyan/white/black.
      // No palette is shown or touchable while rainbow mode is active.
      drawVisualizerEqualizer(true);
      gfx->setTextSize(1);
      gfx->setTextColor(CYAN_AP);
      constexpr char legend[] = "RAINBOW (CYCLING)";
      gfx->setCursor((410 - (static_cast<int>(sizeof(legend)) - 1) * 6) / 2,
                     396);
      gfx->print(legend);
    } else {
      gfx->setTextSize(2);
      gfx->setTextColor(kSectionMagenta);
      gfx->setCursor(43, 335);
      gfx->print("COLOR");
      gfx->fillRect(114, 346, 255, 2, kSectionMagenta);

      // Draw every solid-color swatch first.
      for (uint8_t i = 0; i < 8; ++i) {
        const int x = 40 + static_cast<int>(i) * 42;
        gfx->fillRoundRect(x, 371, 34, 34, 5, kVisualizerColors[i]);
      }

      // Then draw the active selection as a conspicuous two-tone frame.
      // White outer rim, cyan band, black separator: visible even for CYAN.
      if (visualizerColor < 8) {
        const int x = 40 + static_cast<int>(visualizerColor) * 42;
        gfx->fillRoundRect(x - 6, 365, 46, 46, 8, WHITE);
        gfx->fillRoundRect(x - 4, 367, 42, 42, 7, CYAN_AP);
        gfx->fillRoundRect(x - 2, 369, 38, 38, 6, BG);
        gfx->fillRoundRect(x, 371, 34, 34, 5,
                           kVisualizerColors[visualizerColor]);
      }
    }
  }

  // Original G5A27 gear, same geometry and touch target as before.
  drawOperatorGearPlaceholder();
}

bool isVisualizerEnabled() { return visualizerEnabled; }
bool isVisualizerRainbowMode() { return visualizerRainbow; }

void toggleVisualizerEnabled() {
  visualizerEnabled = !visualizerEnabled;
  persistVisualizerPreferences();
  if (activeNavigation && activeNavigation->screen == SCREEN_VISUALIZER_SETTINGS)
    drawVisualizerSettingsScreen();
}
void toggleVisualizerColorMode() {
  visualizerRainbow = !visualizerRainbow;
  persistVisualizerPreferences();
  if (activeNavigation && activeNavigation->screen == SCREEN_VISUALIZER_SETTINGS)
    drawVisualizerSettingsScreen();
}
void selectVisualizerPaletteColor(uint8_t index) {
  if (index >= 8) return;
  visualizerColor = index;
  visualizerRainbow = false;
  persistVisualizerPreferences();
  if (activeNavigation && activeNavigation->screen == SCREEN_VISUALIZER_SETTINGS)
    drawVisualizerSettingsScreen();
}

void setSettingsBrightnessLevel(uint8_t level) {
  if (level >= 3 || brightnessLevel == level) return;
  brightnessLevel = level;
  brightnessRaw = kBrightnessSteps[level];
  applyBrightness();
  persistBrightness();
  if (activeNavigation && activeNavigation->screen == SCREEN_SETTINGS)
    drawBrightnessChoices();
}

uint16_t getStaleIntervalMinutes() { return staleIntervalMinutes; }
void selectStaleIntervalMinutes(uint16_t minutes) {
  if ((minutes != 30 && minutes != 60 && minutes != 240) ||
      minutes == staleIntervalMinutes) return;
  staleIntervalMinutes = minutes;
  Preferences prefs;
  if (prefs.begin("wdghud", false)) {
    prefs.putUShort("staleMinutes", staleIntervalMinutes);
    prefs.end();
  }
  // AP detail freshness only. Ownership/profile use separate hourly TTLs.
  // Send through the already authenticated BLE settings channel.
  notifyStaleIntervalChanged(staleIntervalMinutes);
  if (activeNavigation && activeNavigation->screen == SCREEN_SETTINGS)
    drawStaleChoices();
}
void setOperatorProfile(const char* username, const char* gang,
                        uint32_t wifi, uint32_t ble, uint32_t total, uint32_t badges) {
  if(operatorProfile.valid && strcmp(operatorProfile.username,username)!=0) {
    snprintf(activityRank,sizeof(activityRank),"--");activityDays30=-1;
  }
  snprintf(operatorProfile.username, sizeof(operatorProfile.username), "%s", username);
  snprintf(operatorProfile.gang, sizeof(operatorProfile.gang), "%s", gang);
  operatorProfile.wifi = wifi;
  operatorProfile.ble = ble;
  operatorProfile.total = total;
  operatorProfile.badges = badges;
  operatorProfile.valid = true;
  operatorProfile.refreshing = false;
  operatorProfile.failed = false;
  persistLastGoodOperator(operatorProfile.username, operatorProfile.gang,
                          operatorProfile.wifi, operatorProfile.ble,
                          operatorProfile.total, operatorProfile.badges);
  if (activeNavigation && activeNavigation->screen == SCREEN_CARD) drawCardScreen();
}

void resetAccountPersonalDisplay() {
  // Only a verified owner change hides personal snapshots. Never touch the
  // geographic cache, 3x3/5x5 grid, AP counts, or BLE bonding.
  operatorProfile = OperatorProfileData{};
  teamScreen = TeamScreenData{};
  lastSavedGang = SavedGangSummary{};
  haveSavedGang = false;
  snprintf(activityRank, sizeof(activityRank), "--");
  activityDays30 = -1;
  if (activeNavigation && activeNavigation->screen == SCREEN_CARD) drawCardScreen();
  if (activeNavigation && activeNavigation->screen == SCREEN_TEAM) drawTeamScreen();
  if (activeNavigation && activeNavigation->screen == SCREEN_MEMBERS) drawMembersScreen();
}

void restoreScopedGangAfterProfile() {
  // A completed owner-matched OPERATOR record unlocks only its matching GANG.
  teamScreen = TeamScreenData{};
  loadGangSummary();
  if (activeNavigation && activeNavigation->screen == SCREEN_TEAM) drawTeamScreen();
}

void operatorProfileFetchFailed() {
  operatorProfile.refreshing = false;
  operatorProfile.failed = true;
  // G5A23: retained profile is still trustworthy as last-known-good data.
  // Do not clear the screen or replace its status on a failed refresh.
  if (!operatorProfile.valid && activeNavigation &&
      activeNavigation->screen == SCREEN_CARD) drawCardScreen();
}

// G5A44: pairing UI deliberately interrupts only the visual display. Incoming
// GPS and validated watch cache packets continue to be processed normally.
void drawBlePairingCode(uint32_t code) {
  gfx->fillScreen(BG);
  gfx->setTextWrap(false);
  gfx->setTextColor(CYAN_AP);
  gfx->setTextSize(3);
  gfx->setCursor(106, 112);
  gfx->print("BLE PAIRING");
  char digits[7];
  snprintf(digits, sizeof(digits), "%06lu", static_cast<unsigned long>(code));
  gfx->setTextSize(5);
  gfx->setCursor(115, 214);
  gfx->print(digits);
  gfx->setTextColor(WHITE);
  gfx->setTextSize(2);
  gfx->setCursor(37, 304);
  gfx->print("ENTER ON YOUR PHONE");
  gfx->setTextColor(GREY);
  gfx->setTextSize(1);
  gfx->setCursor(55, 360);
  gfx->print("Only pair when your phone asks");
}

// ==========================================
// SCREEN MANAGER
// ==========================================

void showScreen(const Navigation& navigation) {
  // Only a real transition INTO OPERATOR requests a new account snapshot.
  // Repainting for a clock tick or a BLE result does not request again.
  static ScreenMode previousScreen = SCREEN_3X3;
  const bool justOpenedTeam = navigation.screen==SCREEN_TEAM && previousScreen!=SCREEN_TEAM;
  const bool justOpenedOperator = navigation.screen == SCREEN_CARD && previousScreen != SCREEN_CARD;
  previousScreen = navigation.screen;
  activeNavigation = &navigation;
  if (justOpenedTeam) { teamScreen.refreshing = true; requestTeamSummary(); }
  if (justOpenedOperator) {
    operatorProfile.refreshing = true;
    operatorProfile.failed = false;
    operatorProfile.requestedAtMs = millis();
    requestOperatorProfile();
  }
  switch (activeNavigation->screen) {
    case SCREEN_3X3:
      draw3x3Screen();
      drawConnectionIcons();
      drawHudStatus();
      break;

    case SCREEN_5X5:
      draw5x5Screen();
      drawConnectionIcons();
      drawHudStatus();
      break;

    case SCREEN_CARD:
      drawCardScreen();
      break;
    case SCREEN_TEAM:
      drawTeamScreen();
      break;

    case SCREEN_DETAIL:
      drawDetailScreen();
      break;

    case SCREEN_SETTINGS:
      drawSettingsScreen();
      break;
    case SCREEN_VISUALIZER_SETTINGS:
      drawVisualizerSettingsScreen();
      break;
  }
}


// G5A5: all activity effects are confined to the inset MESSAGE HEADER
// (x=40..370, y=51..81), below G/B icon updates (which clear through y=48).
// The title, connection icons and nine data tiles are never painted by this ticker.
namespace {
enum class HudStatus : uint8_t { Initializing, Idle, Checking, Fetching, Synchronizing, Complete };
HudStatus hudStatus = HudStatus::Initializing;
bool statusVisible = true;
uint32_t statusLastToggle = 0;
uint32_t statusStarted = 0;
uint32_t statusLastFrame = 0;
char statusMessage[40] = "";
int lastRenderedScanProgress = -1;
int lastScanX = -1;

// G5A38.4: SECTOR SCAN alternates three text blinks and ONE complete
// left-to-right-to-left beam sweep. 500ms ON, 500ms OFF, three times (3s),
// followed by the existing 2s-out / 2s-back sweep (4s): 7s total.
// A real SCAN event starts the sequence. Its first cycle finishes even if
// the fetch/transfer completes quickly; this affects the header ONLY.
constexpr uint32_t kScanBlinkHalfMs = 500UL;
constexpr uint32_t kScanTextMs = 6UL * kScanBlinkHalfMs;
constexpr int kScanSweepSegment = 6;
constexpr uint32_t kScanSweepMs = 4000UL;
constexpr uint32_t kScanCycleMs = kScanTextMs + kScanSweepMs;
bool scanAnnouncementActive = false;
uint32_t scanAnnouncementStarted = 0;
int scanRenderedSegment = -1;  // 0..5 for blinks, 6 for the sweep

bool scanAnnouncementVisible(uint32_t now) {
  // Once BEGIN is accepted, show SYNCHRONIZING continuously until END,
  // even if the original seven-second scan animation has time remaining.
  if (hudStatus == HudStatus::Synchronizing) return false;
  return scanAnnouncementActive &&
      (hudStatus == HudStatus::Fetching ||
       (now - scanAnnouncementStarted) < kScanCycleMs);
}

// G5A38.4: status strip has no decorative brackets or frame.
// SCAN text, progress line, and cyan sweep remain in their original positions.
void drawScanProgressBar(int percent) {
  if (!activeNavigation || (activeNavigation->screen != SCREEN_3X3 &&
                             activeNavigation->screen != SCREEN_5X5)) return;
  // Real progress stays below message text and above the first tile (y=84).
  constexpr int x = 105, y = 78, width = 200, height = 2;
  gfx->fillRect(x, y, width, height, BG);
  if (hudStatus != HudStatus::Synchronizing) return;
  gfx->fillRect(x, y, width, height, 0x18C3);
  const int fill = width * constrain(percent, 0, 100) / 100;
  if (fill > 0) gfx->fillRect(x, y, fill, height, CYAN_AP);
}

void drawCheckingBand(uint32_t now, uint32_t sweepStartedAt) {
  // G5A2: a HORIZONTAL cyan beam travels LEFT <-> RIGHT across the message
  // header (not a vertical band). Two seconds per leg, four seconds per cycle.
  // The short luminous trail follows the beam tip and reverses at each edge.
  // Beam remains inside the inset frame and clear of the 3x3 (y=84).
  constexpr int left = 48, right = 362, travel = right - left;
  constexpr uint32_t legMs = 2000UL;
  const uint32_t phase = (now - sweepStartedAt) % (2UL * legMs);
  const bool movingRight = phase < legMs;
  const uint32_t along = movingRight ? phase : 2UL * legMs - phase;
  const int tip = left + static_cast<int>((along * travel) / legMs);
  const int sign = movingRight ? 1 : -1;

  gfx->fillRect(44, 53, 322, 26, BG);
  constexpr int beamY = 66;
  // Low-energy line helps the viewer track the direction without hiding text.
  gfx->fillRect(left, beamY, travel + 1, 1, 0x00A4);

  // All segments are clipped to the usable horizontal header span.
  auto trail = [&](int nearTip, int farTip, int y, int h, uint16_t color) {
    int a = tip - sign * farTip;
    int b = tip - sign * nearTip;
    if (a > b) { const int temp = a; a = b; b = temp; }
    if (a < left) a = left;
    if (b > right + 1) b = right + 1;
    if (b > a) gfx->fillRect(a, y, b - a, h, color);
  };
  trail(80, 130, beamY, 1, 0x0185);  // faded far end
  trail(42, 80, beamY - 1, 3, 0x03AB);
  trail(12, 42, beamY - 1, 3, 0x05D7);
  trail(0, 12, beamY - 1, 3, CYAN_AP);
  // Thin neon core + very small leading-edge glow, entirely within header.
  const int glowX = constrain(tip - 3, left, right - 6);
  gfx->fillRect(glowX, beamY - 3, 7, 7, 0x03CB);
  gfx->fillRect(constrain(tip - 1, left, right - 2), beamY - 1, 3, 3, 0xFFFF);
  lastScanX = tip;
}

// Show a complete SECTOR SCAN message/sweep cycle without touching data tiles.
// This sequence is purely visual: the phone, BLE and snapshot commits run
// independently, even while the first complete animation is still playing.
void drawSectorScanSequence(uint32_t now) {
  const uint32_t phase = (now - scanAnnouncementStarted) % kScanCycleMs;
  lastRenderedScanProgress = -1; // Sync progress is painted when this ends.
  if (phase < kScanTextMs) {
    const int segment = static_cast<int>(phase / kScanBlinkHalfMs);
    scanRenderedSegment = segment;
    const bool showText = (segment % 2) == 0;
    statusVisible = showText;
    gfx->fillRect(44, 53, 322, 26, BG);
    if (showText) {
      gfx->setTextWrap(false);
      gfx->setTextSize(3);
      gfx->setTextColor(CYAN_AP);
      constexpr char message[] = "SECTOR SCAN";
      const int px = (static_cast<int>(sizeof(message)) - 1) * 18;
      gfx->setCursor((410 - px) / 2, 53);
      gfx->print(message);
    }
  } else {
    statusVisible = false;
    scanRenderedSegment = kScanSweepSegment;
    // Give this sweep its own anchor, so each leg runs for the full 2s.
    const uint32_t sweepStartedAt = now - (phase - kScanTextMs);
    drawCheckingBand(now, sweepStartedAt);
  }
}

// G5A33: update the idle visualizer WITHOUT blanking the entire header on
// every frame. CO5300 writes are visible while they happen; repeatedly
// clearing and printing a static label causes the reported flashing.
// Cached bar heights also avoid rewriting unchanged mic levels.
bool idleVisualNeedsReset = true;
bool idleVisualHadBars = false;
uint8_t idleVisualLastHeights[20] = {};

void drawIdleAudioBars() {
  uint8_t values[20] = {};
  const IdleAudioState state = getIdleAudioState();
  const bool haveLevels = visualizerEnabled && getIdleAudioLevels(values);
  bool hasMeasuredBars = false;
  if (visualizerEnabled && state == IdleAudioState::Active && haveLevels) {
    for (const uint8_t value : values) {
      if (value > 0) { hasMeasuredBars = true; break; }
    }
  }
  // G5A35: no IDLE labels, including microphone starting/offline/error.
  // Those conditions are reported only in USB serial diagnostics.
  // Silent and faulty inputs share the same blank visual state; no flicker.
  const bool switchingMode = hasMeasuredBars != idleVisualHadBars;
  const bool redrawAll = idleVisualNeedsReset || switchingMode;
  if (redrawAll) {
    gfx->fillRect(44, 53, 322, 26, BG);
    memset(idleVisualLastHeights, 0, sizeof(idleVisualLastHeights));
  }
  idleVisualNeedsReset = false;
  idleVisualHadBars = hasMeasuredBars;
  if (!hasMeasuredBars) return;

  constexpr int bars = 20, barWidth = 8, pitch = 14;
  constexpr int xStart = (410 - ((bars - 1) * pitch + barWidth)) / 2;
  constexpr int baseY = 77;
  for (int i = 0; i < bars; ++i) {
    const uint8_t height = static_cast<uint8_t>(min(20, (static_cast<int>(values[i]) / 2) * 2));
    if (!redrawAll && height == idleVisualLastHeights[i]) continue;
    const int x = xStart + i * pitch;
    gfx->fillRect(x, baseY - 20, barWidth, 20, BG);
    if (height >= 2)
      gfx->fillRect(x, baseY - height, barWidth, height,
                    visualizerRainbow
                        ? tierColor(1 + (i * 9) / (bars - 1))
                        : kVisualizerColors[visualizerColor]);
    idleVisualLastHeights[i] = height;
  }
}

void drawHudStatus() {
  if (!activeNavigation || (activeNavigation->screen != SCREEN_3X3 &&
                             activeNavigation->screen != SCREEN_5X5)) return;
  const uint32_t now = millis();
  if (scanAnnouncementVisible(now)) {
    drawSectorScanSequence(now);
    return;
  }
  if (hudStatus == HudStatus::Checking) {
    drawCheckingBand(now, statusStarted);
    drawScanProgressBar(0);
    return;
  }
  gfx->fillRect(44, 53, 322, 26, BG);
  if (hudStatus == HudStatus::Idle) {
    // The full header was cleared above (such as after showScreen()).
    // Reset the idle draw cache so labels/bars are repainted exactly once.
    idleVisualNeedsReset = true;
    drawIdleAudioBars();
  } else if (statusVisible) {
    gfx->setTextWrap(false);
    gfx->setTextSize(3);
    gfx->setTextColor(hudStatus == HudStatus::Complete ? 0x4E4E : CYAN_AP);
    const char *message = hudStatus == HudStatus::Initializing ? "INITIALIZING" : statusMessage;
    const int px = static_cast<int>(strlen(message)) * 18;
    gfx->setCursor((410 - px) / 2, 53);
    gfx->print(message);
  }
  drawScanProgressBar(hudStatus == HudStatus::Synchronizing ? getScanProgress() : 0);
  lastRenderedScanProgress = hudStatus == HudStatus::Synchronizing ? getScanProgress() : -1;
}

void setStatus(HudStatus next, const char* label) {
  hudStatus = next;
  snprintf(statusMessage, sizeof(statusMessage), "%s", label ? label : "");
  statusVisible = true;
  statusStarted = statusLastToggle = millis();
  statusLastFrame = 0;
  lastRenderedScanProgress = -1;
  lastScanX = -1;
  drawHudStatus();
}
}  // namespace

bool isHudInitializing() { return hudStatus == HudStatus::Initializing; }
bool isHudAnnouncementActive() { return hudStatus != HudStatus::Idle; }

void startHudTicker(const char*) {
  // A real SCAN command starts the three-blinks / full-sweep header cycle.
  // Visual timing never blocks receiving, validating or committing AP data.
  const bool replaceBootMascot = isHudInitializing() && !live3x3Data &&
      activeNavigation && activeNavigation->screen == SCREEN_3X3;
  scanAnnouncementActive = true;
  scanAnnouncementStarted = millis();
  scanRenderedSegment = -1;
  setStatus(HudStatus::Fetching, "SECTOR SCAN");
  if (replaceBootMascot) showScreen(*activeNavigation);
}
void startHudSynchronizing() {
  // G5A40.1: AP uplink has visual priority over the older SECTOR SCAN
  // animation. The scan may have started <7s ago, but it must not hide
  // SYNCHRONIZING or its real BLE AP-level progress bar.
  scanAnnouncementActive = false;
  scanRenderedSegment = -1;
  const bool replaceBootMascot = isHudInitializing() && !live3x3Data &&
      activeNavigation && activeNavigation->screen == SCREEN_3X3;
  setStatus(HudStatus::Synchronizing, "SYNCHRONIZING");
  if (replaceBootMascot) showScreen(*activeNavigation);
}
void startHudChecking() {
  if (hudStatus == HudStatus::Fetching || hudStatus == HudStatus::Synchronizing ||
      scanAnnouncementVisible(millis())) return;
  setStatus(HudStatus::Checking, "");
}
void stopHudChecking() {
  if (hudStatus == HudStatus::Checking) stopHudTicker(true);
}
void startHudCompletion() { setStatus(HudStatus::Complete, "COMPLETE"); }

void tickHudTicker() {
  const uint32_t now = millis();
  const bool scanVisible = scanAnnouncementVisible(now);
  const bool idleOnGrid = activeNavigation &&
      (activeNavigation->screen == SCREEN_3X3 ||
       activeNavigation->screen == SCREEN_5X5) &&
      hudStatus == HudStatus::Idle && !scanVisible;
  setIdleAudioActive(idleOnGrid && visualizerEnabled);
  if (!activeNavigation || (activeNavigation->screen != SCREEN_3X3 &&
                             activeNavigation->screen != SCREEN_5X5)) return;

  // A quick fetch can finish before the first 7s scan animation finishes.
  // Finish that first cycle without delaying BLE transfer or flash writes,
  // then show the current real status (SYNC, COMPLETE, or idle).
  if (scanAnnouncementActive && !scanVisible) {
    scanAnnouncementActive = false;
    scanRenderedSegment = -1;
    // COMPLETE is visible for its usual 500ms AFTER the full cycle.
    if (hudStatus == HudStatus::Complete) statusStarted = now;
    drawHudStatus();
  }
  if (scanVisible) {
    const uint32_t phase = (now - scanAnnouncementStarted) % kScanCycleMs;
    const int segment = phase < kScanTextMs
        ? static_cast<int>(phase / kScanBlinkHalfMs) : kScanSweepSegment;
    if (segment != scanRenderedSegment ||
        (segment == kScanSweepSegment && now - statusLastFrame >= 33UL)) {
      statusLastFrame = now;
      drawSectorScanSequence(now);
    }
    return;
  }
  if (idleOnGrid) {
    if (now - statusLastFrame >= 100UL) {
      statusLastFrame = now;
      drawIdleAudioBars();
      // G5A35: quiet/fault states remain blank; changed bars only.
      // Only changed audio bars are repainted.
    }
    return;
  }
  if (hudStatus == HudStatus::Checking) {
    // Connection/API failures cannot strand the watch in CHECKING indefinitely.
    // Actual SCAN or BEGIN status supersedes this immediately.
    if (now - statusStarted >= 8000UL) {
      stopHudChecking();
      return;
    }
    if (now - statusLastFrame >= 33UL) {
      statusLastFrame = now;
      drawCheckingBand(now, statusStarted);
    }
    return;
  }
  if (hudStatus == HudStatus::Synchronizing) {
    // Blink only the message, NOT the AP uplink bar at y=78..79.
    // Render at 500 ms ON / 500 ms OFF independently of packet progress.
    if (now - statusLastToggle >= 500UL) {
      statusLastToggle = now;
      statusVisible = !statusVisible;
      gfx->fillRect(44, 53, 322, 24, BG);
      if (statusVisible) {
        gfx->setTextWrap(false);
        gfx->setTextSize(3);
        gfx->setTextColor(CYAN_AP);
        const int px = static_cast<int>(strlen(statusMessage)) * 18;
        gfx->setCursor((410 - px) / 2, 53);
        gfx->print(statusMessage);
      }
    }
    const int progress = getScanProgress();
    if (progress != lastRenderedScanProgress) {
      drawScanProgressBar(progress);
      lastRenderedScanProgress = progress;
    }
  }
  if (hudStatus == HudStatus::Complete && now - statusStarted >= 500UL) {
    stopHudTicker(true);
    return;
  }
  if (hudStatus != HudStatus::Initializing) return;
  if (now - statusLastToggle < 500UL) return;
  statusLastToggle = now;
  statusVisible = !statusVisible;
  drawHudStatus();
}
void stopHudTicker(bool restoreTitle) {
  // restoreTitle=true means failure/cancel/disconnect: stop the animation.
  // The successful END path uses false, immediately followed by COMPLETE;
  // retain the minimum first cycle in that case.
  if (restoreTitle) {
    scanAnnouncementActive = false;
    scanRenderedSegment = -1;
  }
  hudStatus = HudStatus::Idle;
  statusVisible = false;
  lastScanX = -1;
  if (restoreTitle && activeNavigation &&
      (activeNavigation->screen == SCREEN_3X3 || activeNavigation->screen == SCREEN_5X5))
    drawHudStatus();
}
