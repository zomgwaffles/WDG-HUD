#pragma once
#include <stdint.h>
#include "ViewportTransaction.h"
void initializeBle();
void pollBle();
// G5A42: BOOT (GPIO0) short press restarts advertising ONLY if disconnected.
void pollBleReconnectButton();
bool consumeSectorUpdate();
bool consumeTickerStart();
bool consumeTickerStop();
bool consumeTickerSync();
bool consumeCheckingStart();
bool consumeCheckingStop();
bool isUplinkInProgress();
bool bleIsConnected();
// G5A44: callbacks only expose pairing state; Display paints on Arduino loop.
bool takeBlePairingCode(uint32_t& code);
bool consumeBlePairingFinished();
uint32_t gpsFixAgeMs();
bool getLastGps(float& latitude, float& longitude, float& speedMph, float& bearing);
extern int liveTierCounts[9][11];
extern int liveRawLevels[9][256];

// True only for an explicitly valid Android GPS travel bearing.
bool getGpsHeading(float& bearing, float& speedMph);
// Returns the latest explicitly reported GPS bearing and its serial number.
// The serial advances ONLY when Android sends GPS with bearing-valid flag=1.
bool getGpsHeadingUpdate(float& bearing, float& speedMph, uint32_t& serial);

// G4C3: atomic geographic viewport updates (no unverified tier or gang data).
bool consumeViewportUpdate();
bool viewportDisplayActive();
const wdg::ViewportSnapshot& currentViewport();

// G5A45.4: watch -> Android authenticated BLE settings sync.
void notifyStaleIntervalChanged(uint16_t minutes);
