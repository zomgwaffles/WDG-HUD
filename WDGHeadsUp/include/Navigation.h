#pragma once
#include <stdint.h>
enum ScreenMode { SCREEN_3X3, SCREEN_DETAIL, SCREEN_CARD, SCREEN_SETTINGS, SCREEN_VISUALIZER_SETTINGS, SCREEN_5X5, SCREEN_TEAM, SCREEN_MEMBERS };
struct Navigation {
  ScreenMode screen = SCREEN_3X3;
  int selectedSector = 4;
  int detailPage = 0;
  // 5x5 is an inspection view of the SAME 25-sector cache, not a new scan.
  int selectedSector5x5 = 12;
  bool detailFrom5x5 = false;
  uint16_t membersPage = 0;
  bool command(char c);
};
