#include <unity.h>
#include "Navigation.h"
#include "SectorModel.h"

void test_tap_cycle() {
  Navigation n;
  TEST_ASSERT_EQUAL(SCREEN_3X3, n.screen);
  n.tap(); TEST_ASSERT_EQUAL(SCREEN_5X5, n.screen);
  n.tap(); TEST_ASSERT_EQUAL(SCREEN_DETAIL, n.screen);
  n.tap(); TEST_ASSERT_EQUAL(SCREEN_3X3, n.screen);
}
void test_commands_and_bounds() {
  Navigation n;
  TEST_ASSERT_TRUE(n.command('3'));
  TEST_ASSERT_EQUAL(SCREEN_DETAIL, n.screen);
  for (int i = 0; i < 20; ++i) n.command('+');
  TEST_ASSERT_EQUAL(8, n.selectedSector);
  for (int i = 0; i < 20; ++i) n.command('-');
  TEST_ASSERT_EQUAL(0, n.selectedSector);
  TEST_ASSERT_FALSE(n.command('x'));
}
void test_rank_and_dominant() {
  SectorData s = {42, {8, 18, 4, 12}, 0};
  int order[4]; rankLevels(s, order);
  TEST_ASSERT_EQUAL(1, order[0]);
  TEST_ASSERT_EQUAL(3, order[1]);
  TEST_ASSERT_EQUAL(0, order[2]);
  TEST_ASSERT_EQUAL(2, order[3]);
  TEST_ASSERT_EQUAL(1, dominantLevel(s));
}
void test_sample_data() {
  generate5x5SampleData();
  for (int i = 0; i < 25; ++i) {
    int sum = 0;
    for (int j = 0; j < 4; ++j) sum += sectors5x5[i].levels[j];
    TEST_ASSERT_EQUAL(sum, sectors5x5[i].total);
  }
}
int main() {
  UNITY_BEGIN();
  RUN_TEST(test_tap_cycle);
  RUN_TEST(test_commands_and_bounds);
  RUN_TEST(test_rank_and_dominant);
  RUN_TEST(test_sample_data);
  return UNITY_END();
}
