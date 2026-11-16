#include <Arduino.h>
#include "HWCDC.h"
#include "Display.h"
#include "Touch.h"
#include "Navigation.h"
#include "SectorModel.h"
#include "BleTransport.h"
#include "IdleAudio.h"
#include "LastGoodStore.h"
#include "GeoSectorCache.h"
#include "esp_heap_caps.h"

HWCDC USBSerial;
Navigation navigation;
// Only the pairing passkey overlay is temporary; stored HUD data is intact.
static bool pairingOverlayVisible = false;

void handleSerialNavigation() {
    while (USBSerial.available()) {
        char command = USBSerial.read();

        if (navigation.command(command)) {
            showScreen(navigation);
        }

        if (command == '1') USBSerial.println("3x3 view");
        if (command == '2') USBSerial.println("Card view");
        if (command == '3') USBSerial.println("Sector detail view");
        if (command == '5') USBSerial.println("5x5 cache inspection view");
        if (command == 'c') { startHudChecking(); USBSerial.println("CHECKING header demo"); }
        if (command == 'x') { stopHudChecking(); USBSerial.println("CHECKING stopped"); }
    }
}

void setup() {
    USBSerial.begin(115200);

    initializeDisplay();
    initializeTouch();
    initializeIdleAudio(); // Optional real ES7210 microphone; failure never blocks BLE.
    generate5x5SampleData();
    // Restore last validated samples before the first screen is drawn.
    // Phone, BLE, GPS and network are NOT prerequisites to displaying them.
    const bool recoveredSnapshot = restoreLastGoodSnapshots();
    if (recoveredSnapshot) stopHudTicker(false);
    restoreGeoSectorCache();
    int32_t savedNorth = 0, savedEast = 0;
    if (recoveredSnapshot) {
        // Last-good snapshot is written immediately at every successful commit;
        // the larger geographic cache is batched to reduce flash wear. It can
        // therefore be older. Keep the newer watch display, unchanged.
        geoCacheDeferDisplayUntilCenter();
    } else if (geoCacheCurrentCenter(savedNorth, savedEast)) {
        geoCacheRecenter(savedNorth, savedEast);
    }
    navigation.screen = SCREEN_3X3;

    showScreen(navigation);

    Serial.printf("Free heap before BLE: %u\n", ESP.getFreeHeap());
    Serial.printf(
        "Free internal heap: %u\n",
        heap_caps_get_free_size(MALLOC_CAP_INTERNAL)
    );
    Serial.printf(
        "Largest internal block: %u\n",
        heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL)
    );
    Serial.flush();

    delay(1000);
    initializeBle();
    pinMode(0, INPUT_PULLUP); // Waveshare BOOT: GPIO0, active low. Do not hold while resetting.
}

void loop() {
    if (!pairingOverlayVisible) {
        handleSerialNavigation();
        pollTouch(navigation);
    }

    // Process incoming CELL and LVL packets.
    pollBle();
    pollBleReconnectButton();
    uint32_t pairingPin = 0;
    if (takeBlePairingCode(pairingPin)) {
        pairingOverlayVisible = true;
        drawBlePairingCode(pairingPin);
    }
    if (consumeBlePairingFinished()) {
        pairingOverlayVisible = false;
        showScreen(navigation); // Restore unchanged 3x3/5x5 after pairing.
    }
    if (pairingOverlayVisible) {
        tickGeoSectorCache();
        delay(10);
        return;
    }

    if (consumeCheckingStop()) stopHudChecking();
    if (consumeCheckingStart()) startHudChecking();
    if (consumeTickerStart()) startHudTicker("SCANNING GRID");
    if (consumeTickerSync()) startHudSynchronizing();

    // The BLE receiver validates and commits one complete snapshot at END.
    const bool committed = consumeSectorUpdate();
    const bool cacheChanged = consumeGeoCacheScreenUpdate();
    const bool viewportCommitted = consumeViewportUpdate();
    const bool stopped = consumeTickerStop();
    if (committed || cacheChanged) {
        if (committed) stopHudTicker(false);
        // GPS navigation and incremental cached cells refresh BOTH watch grids
        // and Sector Intel, regardless of the active display mode.
        showScreen(navigation);
        if (committed && navigation.screen == SCREEN_3X3) startHudCompletion();
    } else if (viewportCommitted) {
        // Geographic totals are atomic and need one redraw, never fake scan progress.
        if (navigation.screen == SCREEN_3X3 || navigation.screen == SCREEN_5X5 ||
            navigation.screen == SCREEN_DETAIL)
            showScreen(navigation);
    } else if (stopped) {
        stopHudTicker(true); // Failed or interrupted uplink: no COMPLETE.
    }
    tickGeoSectorCache(); // Coalesced wear-conscious flash persistence.
    tickHudTicker(); // Only repaint the subtitle when its state changes.
    tickConnectionIcons();
    tickLiveHeading(); // GPS redraws the center-tile arrow, never the header.

    delay(10);
}