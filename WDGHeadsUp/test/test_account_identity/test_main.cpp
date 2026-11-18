#include <unity.h>
#include "AccountIdentityPolicy.h"
using namespace wdg;

static const char* kAlice = "0123456789abcdeffedcba9876543210";
static const char* kBob   = "fedcba98765432100123456789abcdef";

static AccountOwner token(const char* hex) {
    AccountOwner out{};
    TEST_ASSERT_TRUE(AccountOwner::fromHex(hex, out));
    return out;
}

void test_hex_identity_validates_exactly_128_bits() {
    AccountOwner owner{};
    TEST_ASSERT_FALSE(AccountOwner::fromHex(nullptr, owner));
    TEST_ASSERT_FALSE(AccountOwner::fromHex("short", owner));
    TEST_ASSERT_FALSE(AccountOwner::fromHex("0123456789abcdeffedcba9876543210extra", owner));
    TEST_ASSERT_FALSE(AccountOwner::fromHex("0123456789abcdeffedcba987654321x", owner));
    TEST_ASSERT_FALSE(owner.valid);
    TEST_ASSERT_TRUE(AccountOwner::fromHex(kAlice, owner));
    TEST_ASSERT_TRUE(owner.valid);
    TEST_ASSERT_TRUE(owner.equals(token("0123456789ABCDEFFEDCBA9876543210")));
}

void test_first_boot_legacy_offline_snapshot_is_preserved() {
    WatchAccountPolicy policy;
    TEST_ASSERT_TRUE(policy.mayShowLegacySnapshot());
    policy.onDisconnect();
    TEST_ASSERT_TRUE(policy.mayShowLegacySnapshot());
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(token(kAlice)));
}

void test_verified_claim_hides_unbound_data_until_complete_profile() {
    WatchAccountPolicy policy;
    auto a = token(kAlice);
    TEST_ASSERT_EQUAL(static_cast<int>(OwnerOffer::MustCommitNewProfile),
                      static_cast<int>(policy.offerVerifiedOwner(a)));
    TEST_ASSERT_FALSE(policy.mayShowLegacySnapshot());
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(a));
    TEST_ASSERT_FALSE(policy.acceptsTeam(a));
    TEST_ASSERT_EQUAL(static_cast<int>(ProfileCommit::NewAccount),
                      static_cast<int>(policy.acceptFullProfile(a)));
    TEST_ASSERT_TRUE(policy.mayShowScopedSnapshot(a));
    TEST_ASSERT_TRUE(policy.acceptsTeam(a));
}

void test_reconnecting_same_account_keeps_scoped_offline_data() {
    auto a = token(kAlice);
    WatchAccountPolicy policy(a);
    TEST_ASSERT_TRUE(policy.mayShowScopedSnapshot(a));
    TEST_ASSERT_EQUAL(static_cast<int>(OwnerOffer::SameAccount),
                      static_cast<int>(policy.offerVerifiedOwner(a)));
    policy.onDisconnect();
    TEST_ASSERT_FALSE(policy.isSwitchPending());
    TEST_ASSERT_TRUE(policy.mayShowScopedSnapshot(a));
}

void test_verified_switch_hides_a_and_blocks_late_a_packets() {
    auto a = token(kAlice), b = token(kBob);
    WatchAccountPolicy policy(a);
    policy.offerVerifiedOwner(a);
    TEST_ASSERT_TRUE(policy.acceptsTeam(a));
    policy.offerVerifiedOwner(b);
    TEST_ASSERT_TRUE(policy.isSwitchPending());
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(a));
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(b));
    TEST_ASSERT_FALSE(policy.acceptsTeam(a));
    TEST_ASSERT_FALSE(policy.acceptsTeam(b));
    TEST_ASSERT_EQUAL(static_cast<int>(ProfileCommit::Rejected),
                      static_cast<int>(policy.acceptFullProfile(a)));
    policy.onDisconnect();
    TEST_ASSERT_TRUE(policy.isSwitchPending());
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(a));
    TEST_ASSERT_EQUAL(static_cast<int>(ProfileCommit::NewAccount),
                      static_cast<int>(policy.acceptFullProfile(b)));
    TEST_ASSERT_TRUE(policy.mayShowScopedSnapshot(b));
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(a));
    TEST_ASSERT_FALSE(policy.acceptsTeam(a));
    TEST_ASSERT_TRUE(policy.acceptsTeam(b));
}

void test_invalid_claim_does_not_erase_previous_owner() {
    auto a = token(kAlice);
    WatchAccountPolicy policy(a);
    AccountOwner invalid{};
    TEST_ASSERT_EQUAL(static_cast<int>(OwnerOffer::Invalid),
                      static_cast<int>(policy.offerVerifiedOwner(invalid)));
    TEST_ASSERT_TRUE(policy.mayShowScopedSnapshot(a));
}

void test_return_to_old_account_requires_new_full_profile() {
    auto a = token(kAlice), b = token(kBob);
    WatchAccountPolicy policy(a);
    policy.offerVerifiedOwner(b);
    policy.acceptFullProfile(b);
    TEST_ASSERT_TRUE(policy.mayShowScopedSnapshot(b));
    policy.offerVerifiedOwner(a);
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(b));
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(a));
    TEST_ASSERT_EQUAL(static_cast<int>(ProfileCommit::NewAccount),
                      static_cast<int>(policy.acceptFullProfile(a)));
    TEST_ASSERT_TRUE(policy.mayShowScopedSnapshot(a));
    TEST_ASSERT_FALSE(policy.mayShowScopedSnapshot(b));
}

void test_reboot_restores_only_matching_scoped_snapshot() {
    auto a = token(kAlice), b = token(kBob);
    // Caller loads the owner atomically with a full validated profile record.
    WatchAccountPolicy rebooted(b);
    TEST_ASSERT_FALSE(rebooted.mayShowLegacySnapshot());
    TEST_ASSERT_FALSE(rebooted.mayShowScopedSnapshot(a));
    TEST_ASSERT_TRUE(rebooted.mayShowScopedSnapshot(b));
}

int main() {
    UNITY_BEGIN();
    RUN_TEST(test_hex_identity_validates_exactly_128_bits);
    RUN_TEST(test_first_boot_legacy_offline_snapshot_is_preserved);
    RUN_TEST(test_verified_claim_hides_unbound_data_until_complete_profile);
    RUN_TEST(test_reconnecting_same_account_keeps_scoped_offline_data);
    RUN_TEST(test_verified_switch_hides_a_and_blocks_late_a_packets);
    RUN_TEST(test_invalid_claim_does_not_erase_previous_owner);
    RUN_TEST(test_return_to_old_account_requires_new_full_profile);
    RUN_TEST(test_reboot_restores_only_matching_scoped_snapshot);
    return UNITY_END();
}
