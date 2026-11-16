#include "SectorModel.h"
namespace { constexpr uint16_t BLUE = 0x001F, GREY = 0x8410, RED_AP = 0xF800; }
// Neutral until a live CELL packet is received.
SectorData sectors3x3[9] = {};
SectorData sectors5x5[25] = {};

void generate5x5SampleData() {
  for (int i = 0; i < 9; ++i) {
    sectors3x3[i].total = 0;
    sectors3x3[i].territoryColor = GREY;
    for (int j = 0; j < 4; ++j) sectors3x3[i].levels[j] = 0;
  }
  for (int i = 0; i < 25; ++i) {
    sectors5x5[i].total = 0;
    sectors5x5[i].territoryColor = GREY;
    for (int j = 0; j < 4; ++j) sectors5x5[i].levels[j] = 0;
  }
}

void rankLevels(const SectorData& s, int order[4]) {
  for (int i = 0; i < 4; ++i) order[i] = i;
  for (int a = 0; a < 3; ++a)
    for (int b = a + 1; b < 4; ++b)
      if (s.levels[order[b]] > s.levels[order[a]]) {
        int temp = order[a]; order[a] = order[b]; order[b] = temp;
      }
}
int dominantLevel(const SectorData& s) {
  int dominant = 0;
  for (int i = 1; i < 4; ++i)
    if (s.levels[i] > s.levels[dominant]) dominant = i;
  return dominant;
}
