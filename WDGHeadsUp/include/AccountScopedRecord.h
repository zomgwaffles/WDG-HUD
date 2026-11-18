#pragma once
// G5A49.4d.2b — stable, allocation-free envelope for an account-scoped NVS key.
// This does NOT write NVS or send BLE. Integration must persist the Selection
// record before acknowledging an account change, and restore only records
// whose owner equals that selection. Do not use this to store API keys.
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include "AccountIdentityPolicy.h"

namespace wdg {

enum class AccountRecordType : uint8_t { Selection = 1, Operator = 2, Gang = 3 };

struct DecodedAccountRecord {
    AccountOwner owner{};
    AccountRecordType type = AccountRecordType::Selection;
    const uint8_t* payload = nullptr; // Points into the caller-owned input buffer.
    size_t payloadLength = 0;
};

class AccountScopedRecord {
public:
    static constexpr size_t kHeaderLength = 24;
    static constexpr size_t kCheckLength = 4;
    static constexpr size_t kMinimumLength = kHeaderLength + kCheckLength;
    static constexpr size_t kMaximumPayload = 1024;

    // Each complete NVS value is encoded and validated as one blob. The owner
    // and payload MUST NOT be written as separate NVS keys.
    static bool encode(AccountRecordType type, const AccountOwner& owner,
                       const uint8_t* payload, size_t payloadLength,
                       uint8_t* output, size_t outputCapacity, size_t& written) {
        written = 0;
        if (!owner.valid || !validType(type) ||
            payloadLength > kMaximumPayload ||
            (type == AccountRecordType::Selection && payloadLength != 0) ||
            (type != AccountRecordType::Selection && payloadLength == 0) ||
            (payloadLength != 0 && payload == nullptr) || output == nullptr ||
            outputCapacity < kMinimumLength + payloadLength) return false;
        output[0]='W'; output[1]='D'; output[2]='G'; output[3]='O';
        output[4]=1; // Explicit wire/storage version.
        output[5]=static_cast<uint8_t>(type);
        output[6]=static_cast<uint8_t>(payloadLength & 0xffu);
        output[7]=static_cast<uint8_t>((payloadLength >> 8) & 0xffu);
        memcpy(output+8, owner.bytes, 16);
        if (payloadLength) memcpy(output+kHeaderLength,payload,payloadLength);
        const uint32_t hash=checksum(output,kHeaderLength+payloadLength);
        for(size_t i=0;i<4;++i)
            output[kHeaderLength+payloadLength+i]=static_cast<uint8_t>(hash>>(8*i));
        written=kMinimumLength+payloadLength;
        return true;
    }

    static bool decode(const uint8_t* input, size_t length,
                       AccountRecordType requiredType, DecodedAccountRecord& output) {
        output=DecodedAccountRecord{};
        if (input==nullptr || length<kMinimumLength ||
            length>kMinimumLength+kMaximumPayload ||
            memcmp(input,"WDGO",4)!=0 || input[4]!=1 ||
            !validType(requiredType) || input[5]!=static_cast<uint8_t>(requiredType))
            return false;
        const size_t payloadLength=static_cast<size_t>(input[6]) |
                                  (static_cast<size_t>(input[7])<<8);
        if (payloadLength>kMaximumPayload || length != kMinimumLength+payloadLength ||
            (requiredType==AccountRecordType::Selection && payloadLength!=0) ||
            (requiredType!=AccountRecordType::Selection && payloadLength==0))
            return false;
        uint32_t stored=0;
        for(size_t i=0;i<4;++i)stored |= uint32_t(input[kHeaderLength+payloadLength+i])<<(8*i);
        if (checksum(input,kHeaderLength+payloadLength)!=stored) return false;
        memcpy(output.owner.bytes,input+8,16);
        output.owner.valid=true;
        output.type=requiredType;
        output.payload=input+kHeaderLength;
        output.payloadLength=payloadLength;
        return true;
    }

    // After selecting an account, NEVER display an unscoped legacy record.
    // An interrupted account change may leave the previous owner on flash;
    // the selection key is the authority for read-side visibility.
    static bool visible(const AccountOwner& selectedOwner,
                        const DecodedAccountRecord& record) {
        return selectedOwner.valid && record.owner.equals(selectedOwner) &&
               record.type!=AccountRecordType::Selection;
    }
private:
    static bool validType(AccountRecordType type) {
        return type==AccountRecordType::Selection ||
               type==AccountRecordType::Operator || type==AccountRecordType::Gang;
    }
    // FNV-1a is a corruption check, not an authenticity/signature mechanism.
    static uint32_t checksum(const uint8_t* bytes,size_t length) {
        uint32_t hash=2166136261u;
        for(size_t i=0;i<length;++i) hash=(hash^bytes[i])*16777619u;
        return hash;
    }
};

} // namespace wdg
