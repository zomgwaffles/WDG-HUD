#pragma once
#include "Navigation.h"
void initializeDisplay();
void showScreen(const Navigation& navigation);
// Partial tile repaint for new GPS-centered cache records; no full-screen clear.
void refreshCachedGridTiles(const Navigation& navigation);
// Pairing PIN shown only on the physical watch during initial BLE bonding.
void drawBlePairingCode(uint32_t code);

// Called on the main Arduino loop after an authenticated BLE profile transfer.
void setOperatorProfile(const char* username, const char* gang,
                        uint32_t wifi, uint32_t ble, uint32_t total, uint32_t badges);
void operatorProfileFetchFailed();
// Called on the Arduino main loop after a durable verified OWNER offer.
void resetAccountPersonalDisplay();
void restoreScopedGangAfterProfile();
// G5A45: optional account activity rank and team/roster, independent of sector sync.
void setOperatorActivity(const char* rank, int activeDays30);
struct TeamMemberRow {
  char username[21] = {};
  uint8_t role = 0; // 0 member, 1 officer, 2 owner
  int32_t total = -1;
};
void setTeamSummary(const char* name, int32_t rank, uint32_t memberCount,
                    int32_t wifi, int32_t ble, int32_t total, int32_t credits,
                    uint16_t teamColor);
void setTeamRosterPage(uint16_t page, uint16_t pages, const TeamMemberRow* members, uint8_t count);
void setTeamNoTeam();
void setTeamFetchFailed();
void requestTeamSummary();
void requestTeamRoster(uint16_t page);
int teamRosterPageCount();
bool teamDataAvailable();


void startHudTicker(const char* message);
void startHudSynchronizing();
void startHudChecking();
void stopHudChecking();
void tickHudTicker();
void startHudCompletion();
void stopHudTicker(bool restoreTitle);

void tickConnectionIcons();

// RTC sync is invoked on the watch main loop after a queued BLE TIME packet.
bool syncOperatorClock(uint16_t year, uint8_t month, uint8_t day,
                       uint8_t hours, uint8_t minutes, uint8_t seconds,
                       uint8_t weekday);

// Check GPS heading, redraw only the shared heading strip.
void tickLiveHeading();

// Convert a visible 3x3 tile position into its fixed geographic data index.
int sectorIndexForGridPosition(int slot);

// G5A45.4: three brightness steps; configurable AP stale age (minutes).
void setSettingsBrightnessLevel(uint8_t level);
uint16_t getStaleIntervalMinutes();
void adjustStaleIntervalMinutes(int deltaMinutes);

// G5A36: persistent microphone visualizer preferences on a dedicated Settings page.
void toggleVisualizerEnabled();
void toggleVisualizerColorMode();
void selectVisualizerPaletteColor(uint8_t index);
