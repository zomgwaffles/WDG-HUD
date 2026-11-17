#include <unity.h>
#include "Navigation.h"
#include "SectorModel.h"

// G5A49 cleanup: the old Navigation::tap() method no longer exists.
// Touch handling is now in Touch.cpp; these test current USB debug commands.
void test_default_screen_and_supported_debug_commands() {
  Navigation n;
  TEST_ASSERT_EQUAL(SCREEN_3X3, n.screen);
  TEST_ASSERT_TRUE(n.command('5'));
  TEST_ASSERT_EQUAL(SCREEN_5X5, n.screen);
  TEST_ASSERT_TRUE(n.command('1'));
  TEST_ASSERT_EQUAL(SCREEN_3X3, n.screen);
  TEST_ASSERT_TRUE(n.command('2'));
  TEST_ASSERT_EQUAL(SCREEN_CARD, n.screen);
  TEST_ASSERT_TRUE(n.command('6'));
  TEST_ASSERT_EQUAL(SCREEN_TEAM, n.screen);
  TEST_ASSERT_TRUE(n.command('3'));
  TEST_ASSERT_EQUAL(SCREEN_DETAIL, n.screen);
  TEST_ASSERT_EQUAL(0, n.detailPage);
}

void test_rejected_members_debug_command_is_inert() {
  Navigation n;
  TEST_ASSERT_TRUE(n.command('6'));
  TEST_ASSERT_EQUAL(SCREEN_TEAM, n.screen);
  TEST_ASSERT_FALSE(n.command('7'));
  TEST_ASSERT_EQUAL(SCREEN_TEAM, n.screen);
  TEST_ASSERT_FALSE(n.command('x'));
}

void test_sector_selection_bounds() {
  Navigation n;
  TEST_ASSERT_TRUE(n.command('3'));
  for (int i = 0; i < 20; ++i) n.command('+');
  TEST_ASSERT_EQUAL(8, n.selectedSector);
  for (int i = 0; i < 20; ++i) n.command('-');
  TEST_ASSERT_EQUAL(0, n.selectedSector);
}

void test_rank_and_dominant() {
  SectorData sector = {42, {8, 18, 4, 12}, 0, {}};
  int order[4];
  rankLevels(sector, order);
  TEST_ASSERT_EQUAL(1, order[0]);
  TEST_ASSERT_EQUAL(3, order[1]);
  TEST_ASSERT_EQUAL(0, order[2]);
  TEST_ASSERT_EQUAL(2, order[3]);
  TEST_ASSERT_EQUAL(1, dominantLevel(sector));
}

void test_neutral_initialization() {
  generate5x5SampleData();
  for (int i = 0; i < 25; ++i) {
    TEST_ASSERT_EQUAL(0, sectors5x5[i].total);
    for (int j = 0; j < 4; ++j)
      TEST_ASSERT_EQUAL(0, sectors5x5[i].levels[j]);
  }
  for (int i = 0; i < 9; ++i)
    TEST_ASSERT_EQUAL(0, sectors3x3[i].total);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_default_screen_and_supported_debug_commands);
  RUN_TEST(test_rejected_members_debug_command_is_inert);
  RUN_TEST(test_sector_selection_bounds);
  RUN_TEST(test_rank_and_dominant);
  RUN_TEST(test_neutral_initialization);
  return UNITY_END();
}
