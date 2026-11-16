#pragma once
#include <stdint.h>
struct SectorData { int total; int levels[4]; uint16_t territoryColor; char gangName[97]; };
extern SectorData sectors3x3[9];

extern SectorData sectors5x5[25];
void generate5x5SampleData();
void rankLevels(const SectorData& sector, int order[4]);
int dominantLevel(const SectorData& sector);

