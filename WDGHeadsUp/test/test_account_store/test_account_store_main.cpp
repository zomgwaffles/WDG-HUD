#include <unity.h>
#include "AccountScopedRecord.h"
#define WDG_ACCOUNT_STORE_HOST_TEST 1
#include "../../src/AccountScopedStore.cpp"
#include <cstring>

namespace {
wdg::AccountOwner owner(const char* hex) {
    wdg::AccountOwner o{};
    TEST_ASSERT_TRUE(wdg::AccountOwner::fromHex(hex, o));
    return o;
}
wdg::ScopedOperator sample(const char* username) {
    wdg::ScopedOperator s{};
    std::strncpy(s.username, username, sizeof(s.username)-1);
    std::strncpy(s.gang, "SCOPED TEST", sizeof(s.gang)-1);
    s.wifi = 11; s.ble = 22; s.total = 33; s.badges = 44;
    return s;
}
void reset() { Preferences::reset(); wdg::accountScopedStore.endSession(); }
const char* const A = "0123456789abcdef0123456789abcdef";
const char* const B = "fedcba9876543210fedcba9876543210";
}

void test_first_upgrade_has_no_scoped_selection() {
    reset();
    TEST_ASSERT_FALSE(wdg::accountScopedStore.enabled());
    TEST_ASSERT_FALSE(wdg::accountScopedStore.confirmed());
}
void test_owner_offer_is_durable_but_unconfirmed() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(changed);
    TEST_ASSERT_TRUE(wdg::accountScopedStore.enabled());
    TEST_ASSERT_FALSE(wdg::accountScopedStore.confirmed());
    wdg::ScopedOperator op{};
    TEST_ASSERT_FALSE(wdg::accountScopedStore.loadOperator(op));
}
void test_complete_profile_confirms_and_restores_after_reboot() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.confirmed());
    wdg::accountScopedStore.endSession();
    wdg::ScopedOperator restored{};
    TEST_ASSERT_TRUE(wdg::accountScopedStore.loadOperator(restored));
    TEST_ASSERT_EQUAL_STRING("ALPHA", restored.username);
    TEST_ASSERT_EQUAL_UINT32(33, restored.total);
}
void test_same_account_reconnect_retains_data() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    wdg::accountScopedStore.endSession();
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_FALSE(changed);
    TEST_ASSERT_TRUE(wdg::accountScopedStore.confirmed());
}
void test_verified_switch_hides_previous_data_on_reboot() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(B), changed));
    TEST_ASSERT_TRUE(changed);
    TEST_ASSERT_FALSE(wdg::accountScopedStore.confirmed());
    wdg::accountScopedStore.endSession();
    wdg::ScopedOperator restored{};
    TEST_ASSERT_FALSE(wdg::accountScopedStore.loadOperator(restored));
}
void test_operator_write_failure_cannot_confirm_switch() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    Preferences::failNextWriteKey = "wdgacct:operator";
    TEST_ASSERT_FALSE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    TEST_ASSERT_FALSE(wdg::accountScopedStore.confirmed());
}
void test_confirmation_write_failure_hides_old_profile() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(B), changed));
    Preferences::failNextWriteKey = "wdgacct:confirmed";
    TEST_ASSERT_FALSE(wdg::accountScopedStore.commitOperator(sample("BRAVO")));
    TEST_ASSERT_FALSE(wdg::accountScopedStore.confirmed());
    wdg::ScopedOperator restored{};
    TEST_ASSERT_FALSE(wdg::accountScopedStore.loadOperator(restored));
}
void test_gang_is_scoped_to_selected_confirmed_owner() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    const char gang[]="GANG ALPHA";
    TEST_ASSERT_TRUE(wdg::accountScopedStore.saveGang(gang, sizeof(gang)));
    char restored[sizeof(gang)]{};
    TEST_ASSERT_TRUE(wdg::accountScopedStore.loadGang(restored, sizeof(restored)));
    TEST_ASSERT_EQUAL_STRING(gang, restored);
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(B), changed));
    TEST_ASSERT_FALSE(wdg::accountScopedStore.loadGang(restored, sizeof(restored)));
}
void test_return_to_previous_owner_stays_unconfirmed_until_new_profile() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(B), changed));
    TEST_ASSERT_FALSE(wdg::accountScopedStore.confirmed());
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(changed);
    // Previous ALPHA profile still exists in NVS, but cannot be exposed.
    TEST_ASSERT_FALSE(wdg::accountScopedStore.confirmed());
    wdg::accountScopedStore.endSession();
    wdg::ScopedOperator restored{};
    TEST_ASSERT_FALSE(wdg::accountScopedStore.loadOperator(restored));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_FALSE(changed);
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA NEW")));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.loadOperator(restored));
    TEST_ASSERT_EQUAL_STRING("ALPHA NEW", restored.username);
}
void test_failed_confirmation_invalidation_rejects_change() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    Preferences::failNextWriteKey = "wdgacct:confirmed";
    TEST_ASSERT_FALSE(wdg::accountScopedStore.offer(owner(B), changed));
    // Failed selection write cannot move the confirmed account to B.
    TEST_ASSERT_TRUE(wdg::accountScopedStore.confirmed());
    TEST_ASSERT_FALSE(wdg::accountScopedStore.matchesSession(owner(B)));
}

void test_corrupted_operator_blob_is_never_restored() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    Preferences::disk["wdgacct:operator"][29] ^= 0x01;
    wdg::ScopedOperator restored{};
    TEST_ASSERT_FALSE(wdg::accountScopedStore.loadOperator(restored));
}
void test_corrupted_enable_marker_never_reenables_legacy_fallback() {
    reset(); bool changed = false;
    TEST_ASSERT_TRUE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.commitOperator(sample("ALPHA")));
    Preferences::disk["wdgacct:scope_v1"] = {0};
    TEST_ASSERT_TRUE(wdg::accountScopedStore.enabled());
}

void test_broken_selection_marker_prevents_legacy_fallback() {
    reset();
    Preferences::failNextWriteKey = "wdgacct:selected";
    bool changed = false;
    TEST_ASSERT_FALSE(wdg::accountScopedStore.offer(owner(A), changed));
    TEST_ASSERT_TRUE(wdg::accountScopedStore.enabled());
    TEST_ASSERT_FALSE(wdg::accountScopedStore.confirmed());
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_first_upgrade_has_no_scoped_selection);
    RUN_TEST(test_owner_offer_is_durable_but_unconfirmed);
    RUN_TEST(test_complete_profile_confirms_and_restores_after_reboot);
    RUN_TEST(test_same_account_reconnect_retains_data);
    RUN_TEST(test_verified_switch_hides_previous_data_on_reboot);
    RUN_TEST(test_operator_write_failure_cannot_confirm_switch);
    RUN_TEST(test_confirmation_write_failure_hides_old_profile);
    RUN_TEST(test_gang_is_scoped_to_selected_confirmed_owner);
    RUN_TEST(test_return_to_previous_owner_stays_unconfirmed_until_new_profile);
    RUN_TEST(test_failed_confirmation_invalidation_rejects_change);
    RUN_TEST(test_corrupted_operator_blob_is_never_restored);
    RUN_TEST(test_corrupted_enable_marker_never_reenables_legacy_fallback);
    RUN_TEST(test_broken_selection_marker_prevents_legacy_fallback);
    return UNITY_END();
}
