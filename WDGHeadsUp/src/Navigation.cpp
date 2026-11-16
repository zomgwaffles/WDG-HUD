#include "Navigation.h"
bool Navigation::command(char c) {
  if (c == '1') { screen = SCREEN_3X3; return true; }
  if (c == '2') { screen = SCREEN_CARD; return true; }
  if (c == '5') { screen = SCREEN_5X5; return true; }
  if (c == '6') { screen = SCREEN_TEAM; return true; }
  if (c == '7') { screen = SCREEN_MEMBERS; membersPage = 0; return true; }
  if (c == '3') { screen = SCREEN_DETAIL; detailPage = 0; return true; }
  if (c == '+' && selectedSector < 8) { ++selectedSector; detailPage = 0; return screen == SCREEN_DETAIL; }
  if (c == '-' && selectedSector > 0) { --selectedSector; detailPage = 0; return screen == SCREEN_DETAIL; }
  return false;
}
