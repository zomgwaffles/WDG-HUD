#pragma once
#include <stddef.h>
#include <stdint.h>
#include "AccountIdentityPolicy.h"

// Personal account snapshots only. Geographic cache and BLE bonding are never touched.
// All calls run on the Arduino main loop, not the NimBLE callback thread.
namespace wdg {
struct ScopedOperator {
  char username[21] = {};
  char gang[97] = {};
  uint32_t wifi = 0, ble = 0, total = 0, badges = 0;
};

class AccountScopedStore {
public:
  bool enabled() const;                // First migration marker persists across reboot.
  bool confirmed() const;              // Selected identity equals completed identity.
  bool offer(const AccountOwner& owner, bool& changed); // Durable selection before ACK.
  bool matchesSession(const AccountOwner& owner) const;
  bool sessionReady() const { return sessionClaimed_ && matchesSession(sessionOwner_); }
  bool commitOperator(const ScopedOperator& snapshot); // Durable profile before confirmation.
  bool loadOperator(ScopedOperator& snapshot) const;
  bool saveGang(const void* bytes, size_t length);
  bool loadGang(void* bytes, size_t length) const;
  void endSession();
private:
  AccountOwner sessionOwner_{};
  bool sessionClaimed_ = false;
};
extern AccountScopedStore accountScopedStore;
} // namespace wdg
