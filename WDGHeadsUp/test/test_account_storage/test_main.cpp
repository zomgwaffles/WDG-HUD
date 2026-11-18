#include <unity.h>
#include <cstring>
#include <vector>
#include "AccountScopedRecord.h"
using namespace wdg;

static AccountOwner make(const char* hex) {
    AccountOwner owner{};
    TEST_ASSERT_TRUE(AccountOwner::fromHex(hex,owner));
    return owner;
}
static const AccountOwner a=make("0123456789abcdeffedcba9876543210");
static const AccountOwner b=make("fedcba98765432100123456789abcdef");
static std::vector<uint8_t> makeRecord(AccountRecordType type,const AccountOwner& owner,
                                       const std::vector<uint8_t>& payload={}) {
    uint8_t bytes[1100]{};
    size_t size=0;
    TEST_ASSERT_TRUE(AccountScopedRecord::encode(type,owner,payload.empty()?nullptr:payload.data(),
        payload.size(),bytes,sizeof(bytes),size));
    return std::vector<uint8_t>(bytes,bytes+size);
}
static DecodedAccountRecord decode(AccountRecordType type,const std::vector<uint8_t>& bytes) {
    DecodedAccountRecord result{};
    TEST_ASSERT_TRUE(AccountScopedRecord::decode(bytes.data(),bytes.size(),type,result));
    return result;
}
void test_selection_record_is_versioned_and_exactly_28_bytes() {
    const auto raw=makeRecord(AccountRecordType::Selection,a);
    TEST_ASSERT_EQUAL_UINT32(28,raw.size());
    auto v=decode(AccountRecordType::Selection,raw);
    TEST_ASSERT_TRUE(v.owner.equals(a));
    TEST_ASSERT_EQUAL_UINT32(0,v.payloadLength);
}
void test_operator_payload_roundtrips_as_single_owner_bound_blob() {
    const std::vector<uint8_t> payload={'O','P','E','R','A','T','O','R'};
    const auto raw=makeRecord(AccountRecordType::Operator,a,payload);
    const auto v=decode(AccountRecordType::Operator,raw);
    TEST_ASSERT_TRUE(v.owner.equals(a));
    TEST_ASSERT_EQUAL_UINT32(payload.size(),v.payloadLength);
    TEST_ASSERT_EQUAL_MEMORY(payload.data(),v.payload,payload.size());
    TEST_ASSERT_TRUE(AccountScopedRecord::visible(a,v));
}
void test_gang_payload_is_distinct_from_operator() {
    auto raw=makeRecord(AccountRecordType::Gang,a,{'G','A','N','G'});
    DecodedAccountRecord wrong{};
    TEST_ASSERT_FALSE(AccountScopedRecord::decode(raw.data(),raw.size(),AccountRecordType::Operator,wrong));
    auto v=decode(AccountRecordType::Gang,raw);
    TEST_ASSERT_TRUE(AccountScopedRecord::visible(a,v));
}
void test_verified_switch_hides_old_records_even_if_new_fetch_fails() {
    auto oldOp=makeRecord(AccountRecordType::Operator,a,{'A'});
    auto oldGang=makeRecord(AccountRecordType::Gang,a,{'G'});
    auto op=decode(AccountRecordType::Operator,oldOp);
    auto gang=decode(AccountRecordType::Gang,oldGang);
    TEST_ASSERT_TRUE(AccountScopedRecord::visible(a,op));
    TEST_ASSERT_TRUE(AccountScopedRecord::visible(a,gang));
    // After the account selection record B is persisted, A must stay hidden
    // even if the device loses power before B's profile is written.
    auto restoredSelection=decode(AccountRecordType::Selection,
                                  makeRecord(AccountRecordType::Selection,b));
    TEST_ASSERT_FALSE(AccountScopedRecord::visible(restoredSelection.owner,op));
    TEST_ASSERT_FALSE(AccountScopedRecord::visible(restoredSelection.owner,gang));
}
void test_reboot_restores_only_matching_new_records() {
    auto selected=decode(AccountRecordType::Selection,
                         makeRecord(AccountRecordType::Selection,b));
    auto oldOp=makeRecord(AccountRecordType::Operator,a,{'A'});
    auto newOp=makeRecord(AccountRecordType::Operator,b,{'B'});
    TEST_ASSERT_FALSE(AccountScopedRecord::visible(selected.owner,decode(AccountRecordType::Operator,oldOp)));
    TEST_ASSERT_TRUE(AccountScopedRecord::visible(selected.owner,decode(AccountRecordType::Operator,newOp)));
}
void test_corrupted_and_truncated_blobs_never_restore() {
    auto raw=makeRecord(AccountRecordType::Operator,a,{'P','R','O','F'});
    DecodedAccountRecord result{};
    raw[AccountScopedRecord::kHeaderLength] ^= 0x40;
    TEST_ASSERT_FALSE(AccountScopedRecord::decode(raw.data(),raw.size(),AccountRecordType::Operator,result));
    raw=makeRecord(AccountRecordType::Operator,a,{'P','R','O','F'});
    raw.pop_back();
    TEST_ASSERT_FALSE(AccountScopedRecord::decode(raw.data(),raw.size(),AccountRecordType::Operator,result));
    raw=makeRecord(AccountRecordType::Operator,a,{'P','R','O','F'});
    raw[4] = 2;
    TEST_ASSERT_FALSE(AccountScopedRecord::decode(raw.data(),raw.size(),AccountRecordType::Operator,result));
}
void test_reject_invalid_or_oversized_payload_before_write() {
    uint8_t bytes[1100]={};size_t written=900;
    const std::vector<uint8_t> giant(1025,0x31);
    TEST_ASSERT_FALSE(AccountScopedRecord::encode(AccountRecordType::Operator,a,
        giant.data(),giant.size(),bytes,sizeof(bytes),written));
    TEST_ASSERT_EQUAL_UINT32(0,written);
    TEST_ASSERT_FALSE(AccountScopedRecord::encode(AccountRecordType::Selection,a,
        giant.data(),1,bytes,sizeof(bytes),written));
    TEST_ASSERT_FALSE(AccountScopedRecord::encode(AccountRecordType::Gang,a,
        nullptr,0,bytes,sizeof(bytes),written));
}
void test_legacy_unscoped_storage_cannot_impersonate_scoped_record() {
    const uint8_t legacy[]={0x46,0x47,0x44,0x57,0x01,0x00};
    DecodedAccountRecord restored{};
    TEST_ASSERT_FALSE(AccountScopedRecord::decode(legacy,sizeof(legacy),AccountRecordType::Operator,restored));
}
int main(){UNITY_BEGIN();
    RUN_TEST(test_selection_record_is_versioned_and_exactly_28_bytes);
    RUN_TEST(test_operator_payload_roundtrips_as_single_owner_bound_blob);
    RUN_TEST(test_gang_payload_is_distinct_from_operator);
    RUN_TEST(test_verified_switch_hides_old_records_even_if_new_fetch_fails);
    RUN_TEST(test_reboot_restores_only_matching_new_records);
    RUN_TEST(test_corrupted_and_truncated_blobs_never_restore);
    RUN_TEST(test_reject_invalid_or_oversized_payload_before_write);
    RUN_TEST(test_legacy_unscoped_storage_cannot_impersonate_scoped_record);
    return UNITY_END();}
