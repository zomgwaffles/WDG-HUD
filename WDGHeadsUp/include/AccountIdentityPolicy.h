#pragma once
// G5A49.4d.2a — WATCH ACCOUNT ISOLATION POLICY (host-testable only).
// This file does NOT handle BLE or NVS. Wire it in only after protocol/flash
// migration changes are implemented and tested together on the real watch.
// No API keys, secrets, or credentials belong in this class.

#include <stddef.h>
#include <stdint.h>
#include <string.h>

namespace wdg {

// 128-bit opaque identifier, derived by Android from a successful /api/me
// identity (prefer an immutable account ID). NOT from the API key.
struct AccountOwner {
    uint8_t bytes[16] = {};
    bool valid = false;

    static bool fromHex(const char* text, AccountOwner& out) {
        if (text == nullptr || strlen(text) != 32) return false;
        AccountOwner parsed{};
        for (size_t i = 0; i < 16; ++i) {
            const int hi = hexNibble(text[i * 2]);
            const int lo = hexNibble(text[i * 2 + 1]);
            if (hi < 0 || lo < 0) return false;
            parsed.bytes[i] = static_cast<uint8_t>((hi << 4) | lo);
        }
        if (text[32] != '\0') return false;
        parsed.valid = true;
        out = parsed;
        return true;
    }
    bool equals(const AccountOwner& other) const {
        if (!valid || !other.valid) return false;
        uint8_t delta = 0;
        for (size_t i = 0; i < 16; ++i) delta |= bytes[i] ^ other.bytes[i];
        return delta == 0;
    }
private:
    static int hexNibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
};

enum class OwnerOffer { Invalid, SameAccount, MustCommitNewProfile };
enum class ProfileCommit { Rejected, Refreshed, NewAccount };

// The policy separates *verified owner selection* from *full profile commit*.
// A new owner hides unrelated personal snapshots immediately, even if the
// subsequent PROFILE or TEAM transfer fails. Never change the AP/grid cache.
class WatchAccountPolicy {
public:
    explicit WatchAccountPolicy(const AccountOwner& persistedOwner = AccountOwner{})
        : boundOwner_(persistedOwner) {}

    OwnerOffer offerVerifiedOwner(const AccountOwner& offered) {
        if (!offered.valid) return OwnerOffer::Invalid;
        pendingOwner_ = offered;
        if (boundOwner_.equals(offered)) {
            pending_ = false;
            return OwnerOffer::SameAccount;
        }
        pending_ = true;
        return OwnerOffer::MustCommitNewProfile;
    }

    ProfileCommit acceptFullProfile(const AccountOwner& packetOwner) {
        if (!packetOwner.valid || !pendingOwner_.equals(packetOwner))
            return ProfileCommit::Rejected;
        if (pending_) {
            boundOwner_ = packetOwner;
            pending_ = false;
            return ProfileCommit::NewAccount;
        }
        return boundOwner_.equals(packetOwner) ? ProfileCommit::Refreshed
                                               : ProfileCommit::Rejected;
    }

    bool acceptsTeam(const AccountOwner& packetOwner) const {
        return !pending_ && boundOwner_.equals(packetOwner) &&
               pendingOwner_.equals(packetOwner);
    }

    // Legacy unscoped snapshots are shown only before the watch has ever
    // received a verified owner (e.g. first offline boot after upgrading).
    bool mayShowLegacySnapshot() const {
        return !boundOwner_.valid && !pendingOwner_.valid && !pending_;
    }

    // A saved OPERATOR/GANG may be shown only with its recorded owner token.
    bool mayShowScopedSnapshot(const AccountOwner& snapshotOwner) const {
        return !pending_ && boundOwner_.equals(snapshotOwner) &&
               (!pendingOwner_.valid || pendingOwner_.equals(snapshotOwner));
    }

    bool isSwitchPending() const { return pending_; }
    const AccountOwner& boundOwner() const { return boundOwner_; }
    const AccountOwner& offeredOwner() const { return pendingOwner_; }

    // Losing BLE/network does NOT change the state or erase data. In particular,
    // a pending switch stays hidden, rather than re-exposing the old account.
    void onDisconnect() {}

private:
    AccountOwner boundOwner_{};
    AccountOwner pendingOwner_{};
    bool pending_ = false;
};

} // namespace wdg
