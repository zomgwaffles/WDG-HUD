#include <Arduino.h>
#include "Arduino_DriveBus_Library.h"
#include "pin_config.h"
#include "Touch.h"
#include "Display.h"
#include "BleTransport.h"
#include "GeoSectorCache.h"
#include "HWCDC.h"
extern HWCDC USBSerial;
std::shared_ptr<Arduino_IIC_DriveBus> IIC_Bus =
  std::make_shared<Arduino_HWIIC>(IIC_SDA, IIC_SCL, &Wire);

void Arduino_IIC_Touch_Interrupt(void);

std::unique_ptr<Arduino_IIC> FT3168(
  new Arduino_FT3x68(
    IIC_Bus,
    FT3168_DEVICE_ADDRESS,
    DRIVEBUS_DEFAULT_VALUE,
    TP_INT,
    Arduino_IIC_Touch_Interrupt
  )
);

void Arduino_IIC_Touch_Interrupt(void) {
  FT3168->IIC_Interrupt_Flag = true;
}


void initializeTouch() {
  Wire.begin(IIC_SDA, IIC_SCL);
  if (FT3168->begin()) {
    USBSerial.println("FT3168 touchscreen ready");
    // FT3168->IIC_Write_Device_State(
      // FT3168->Arduino_IIC_Touch::Device::TOUCH_POWER_MODE,
      // FT3168->Arduino_IIC_Touch::Device_Mode::TOUCH_POWER_MONITOR);
  } else {
    USBSerial.println("FT3168 touchscreen initialization FAILED");
  }
}
void pollTouch(Navigation& navigation) {
  // E4.1: track a touch until release. Act only once on release, with a
  // movement threshold. The old interrupt-only handler fired phantom taps.
  static bool down = false;
  static int32_t startX = 0, startY = 0, lastX = 0, lastY = 0;
  static uint32_t downAt = 0, lastActionAt = 0, lastPollAt = 0;
  const uint32_t now = millis();
  if (now - lastPollAt < 30) return;
  lastPollAt = now;
  const int32_t fingers = static_cast<int32_t>(FT3168->IIC_Read_Device_Value(
      FT3168->Arduino_IIC_Touch::Value_Information::TOUCH_FINGER_NUMBER));
  if (fingers == 1) {
    const int32_t x = static_cast<int32_t>(FT3168->IIC_Read_Device_Value(
        FT3168->Arduino_IIC_Touch::Value_Information::TOUCH_COORDINATE_X));
    const int32_t y = static_cast<int32_t>(FT3168->IIC_Read_Device_Value(
        FT3168->Arduino_IIC_Touch::Value_Information::TOUCH_COORDINATE_Y));
    if (x < 0 || x >= LCD_WIDTH || y < 0 || y >= LCD_HEIGHT) return;
    if (!down) {
      down = true; startX = x; startY = y; downAt = now;
    }
    lastX = x; lastY = y;
    return;
  }
  if (!down) return;
  down = false;
  if (now - lastActionAt < 350) return;
  const int32_t dx = lastX - startX;
  const int32_t dy = lastY - startY;
  const int32_t absDx = dx < 0 ? -dx : dx;
  const int32_t absDy = dy < 0 ? -dy : dy;
  const uint32_t duration = now - downAt;
  if (duration > 1200) return;
  bool changed = false;
  const bool tap = absDx < 25 && absDy < 25 && duration < 600;

  // Visualizer subpage uses the same accepted gear to return to SETTINGS.
  // G5A50.5e: touch geometry follows the approved OFF/SOLID/RAINBOW raster.
  if (navigation.screen == SCREEN_VISUALIZER_SETTINGS) {
    if ((absDy > 60 && absDy > absDx * 3 / 2 && dy > 0) ||
        (tap && startX >= 177 && startX < 235 &&
         startY >= 421 && startY < 476)) {
      navigation.screen = SCREEN_SETTINGS;
      changed = true;
    } else if (tap && startY >= 149 && startY < 200) {
      if (startX >= 43 && startX < 195) {
        // OFF button: tapping the already-active choice does nothing.
        if (isVisualizerEnabled()) toggleVisualizerEnabled();
      } else if (startX >= 215 && startX < 367) {
        if (!isVisualizerEnabled()) toggleVisualizerEnabled();
      } else return;
      lastActionAt = now;
      return;
    } else if (tap && isVisualizerEnabled() &&
               startY >= 263 && startY < 314) {
      if (startX >= 43 && startX < 195) {
        if (isVisualizerRainbowMode()) toggleVisualizerColorMode();
      } else if (startX >= 215 && startX < 367) {
        if (!isVisualizerRainbowMode()) toggleVisualizerColorMode();
      } else return;
      lastActionAt = now;
      return;
    } else if (tap && isVisualizerEnabled() && !isVisualizerRainbowMode() &&
               startY >= 367 && startY < 409) {
      for (int i = 0; i < 8; ++i) {
        const int x = 40 + i * 42;
        if (startX >= x - 4 && startX < x + 38) {
          selectVisualizerPaletteColor(static_cast<uint8_t>(i));
          lastActionAt = now;
          return;
        }
      }
      return;
    } else return;
  } else if (navigation.screen == SCREEN_TEAM) {
    // Single GANG information screen; no roster subpage or paging.
    if (tap || (absDy > 60 && absDy > absDx * 3 / 2 && dy > 0)) {
      navigation.screen = SCREEN_CARD;
      changed = true;
    } else return;
  } else if (navigation.screen == SCREEN_SETTINGS) {
    if ((absDy > 60 && absDy > absDx * 3 / 2 && dy > 0) ||
        (tap && startX >= 177 && startX < 235 &&
         startY >= 421 && startY < 476)) {
      navigation.screen = SCREEN_CARD;
      changed = true;
    } else if (tap && startX >= 42 && startX < 368 &&
               startY >= 348 && startY < 403) {
      navigation.screen = SCREEN_VISUALIZER_SETTINGS;
      changed = true;
    } else if (tap && startY >= 148 && startY < 196 &&
               startX >= 26 && startX < 384) {
      const int level = startX < 145 ? 0 : startX < 265 ? 1 : 2;
      setSettingsBrightnessLevel(static_cast<uint8_t>(level));
      lastActionAt = now;
      return;
    } else if (tap && startY >= 282 && startY < 330 &&
               startX >= 26 && startX < 384) {
      const uint16_t minutes = startX < 145 ? 30 : startX < 265 ? 60 : 240;
      selectStaleIntervalMinutes(minutes);
      lastActionAt = now;
      return;
    } else {
      return;
    }
  } else if (absDx > 60 && absDx > absDy * 3 / 2 &&
             navigation.screen == SCREEN_3X3 && dx < 0) {
    navigation.screen = SCREEN_5X5;
    changed = true;
  } else if (absDx > 60 && absDx > absDy * 3 / 2 &&
             navigation.screen == SCREEN_5X5 && dx > 0) {
    navigation.screen = SCREEN_3X3;
    changed = true;
  } else if (absDy > 60 && absDy > absDx * 3 / 2) {
    if (dy < 0) { navigation.screen = SCREEN_CARD; changed = true; }
    else {
      navigation.screen = navigation.screen == SCREEN_DETAIL && navigation.detailFrom5x5
          ? SCREEN_5X5 : SCREEN_3X3;
      if (navigation.screen != SCREEN_DETAIL) navigation.detailFrom5x5 = false;
      changed = true;
    }
  } else if (absDx > 60 && absDx > absDy * 3 / 2 && navigation.screen == SCREEN_DETAIL) {
    // Paginate the SAME SECTOR INTEL view only if >18 nonempty tiers.
    int visibleTiers = 0;
    for (int tier = 1; tier <= 10; ++tier) {
      if (navigation.detailFrom5x5) {
        int32_t counts[11] = {};
        if (geoCacheRead5x5(navigation.selectedSector5x5,nullptr,nullptr,counts)
            && counts[tier] > 0) ++visibleTiers;
      } else if (liveTierCounts[navigation.selectedSector][tier] > 0) {
        ++visibleTiers;
      }
    }
    const int pages = visibleTiers ? (visibleTiers + 17) / 18 : 1;
    navigation.detailPage = (navigation.detailPage + (dx < 0 ? 1 : pages - 1)) % pages;
    changed = true;
  } else if (absDx < 25 && absDy < 25 && duration < 600) {
    if (navigation.screen == SCREEN_3X3) {
      for (int r = 0; r < 3 && !changed; ++r) {
        for (int c = 0; c < 3; ++c) {
          const int tileX = 17 + c * 130;
          const int tileY = 84 + r * 118;
          if (startX >= tileX && startX < tileX + 120 &&
              startY >= tileY && startY < tileY + 105) {
            navigation.selectedSector = sectorIndexForGridPosition(r * 3 + c);
            navigation.detailPage = 0;
            navigation.screen = SCREEN_DETAIL;
            navigation.detailFrom5x5 = false;
            changed = true;
            break;
          }
        }
      }
    } else if (navigation.screen == SCREEN_5X5) {
      // Must match draw5x5Screen() x/y/pitch/width/height exactly.
      for (int r=0;r<5 && !changed;++r) for(int c=0;c<5;++c) {
        const int tx=22+c*74, ty=85+r*69;
        if(startX>=tx && startX<tx+68 && startY>=ty && startY<ty+61) {
          navigation.selectedSector5x5=r*5+c;
          navigation.detailFrom5x5=true;
          navigation.detailPage=0;
          navigation.screen=SCREEN_DETAIL;
          changed=true;
          break;
        }
      }
    } else if (navigation.screen == SCREEN_CARD &&
               // Match the original G5A44 gang-name position (one or two lines).
               startX >= 34 && startX < 376 &&
               startY >= 140 && startY < 205) {
      navigation.screen = SCREEN_TEAM;
      changed = true;
    } else if (navigation.screen == SCREEN_CARD &&
               startX >= 177 && startX < 235 &&
               startY >= 421 && startY < 476) {
      // Small, deliberate gear-only target. All other OPERATOR taps retain
      // their original behavior. The visible G5A27 gear is unchanged.
      navigation.screen = SCREEN_SETTINGS;
      changed = true;
    } else if (navigation.screen == SCREEN_DETAIL || navigation.screen == SCREEN_CARD) {
      // Sector Intel returns to the originating grid (3x3 or 5x5).
      navigation.screen = navigation.screen == SCREEN_DETAIL && navigation.detailFrom5x5
          ? SCREEN_5X5 : SCREEN_3X3;
      navigation.detailFrom5x5 = false;
      navigation.detailPage = 0;
      changed = true;
    }
  }
  if (changed) {
    lastActionAt = now;
    USBSerial.printf("TOUCH: %ld,%ld -> %ld,%ld screen=%d sector=%d\n",
                     (long)startX, (long)startY, (long)lastX, (long)lastY,
                     (int)navigation.screen, navigation.selectedSector);
    showScreen(navigation);
  }
}
