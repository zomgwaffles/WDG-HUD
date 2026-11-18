#include "AccountScopedStore.h"
#include "AccountScopedRecord.h"
#ifdef WDG_ACCOUNT_STORE_HOST_TEST
#include "../test/test_account_store/MockPreferences.h"
#else
#include <Arduino.h>
#include <Preferences.h>
#endif
#include <cstring>

namespace wdg {
namespace {
constexpr const char* kNamespace = "wdgacct";
constexpr const char* kSelected = "selected";
constexpr const char* kConfirmed = "confirmed";
constexpr const char* kOperator = "operator";
constexpr const char* kGang = "gang";
constexpr const char* kEnabled = "scope_v1";

bool readSelection(const char* key, AccountOwner& out) {
  Preferences p;
  if (!p.begin(kNamespace, true)) return false;
  const size_t size = p.getBytesLength(key);
  uint8_t bytes[AccountScopedRecord::kMinimumLength]{};
  const bool ok = size == sizeof(bytes) && p.getBytes(key, bytes, sizeof(bytes)) == sizeof(bytes);
  p.end();
  if (!ok) return false;
  DecodedAccountRecord record{};
  if (!AccountScopedRecord::decode(bytes, sizeof(bytes), AccountRecordType::Selection, record)) return false;
  out = record.owner;
  return true;
}

bool putRecord(const char* key, AccountRecordType kind, const AccountOwner& owner,
               const void* payload, size_t length) {
  uint8_t bytes[AccountScopedRecord::kMinimumLength + AccountScopedRecord::kMaximumPayload]{};
  size_t count = 0;
  if (!AccountScopedRecord::encode(kind, owner,
        static_cast<const uint8_t*>(payload), length, bytes, sizeof(bytes), count)) return false;
  Preferences p;
  if (!p.begin(kNamespace, false)) return false;
  if (p.getBytesLength(key) == count) {
    uint8_t current[sizeof(bytes)]{};
    if (p.getBytes(key, current, count) == count && memcmp(current, bytes, count) == 0) {
      p.end(); return true;
    }
  }
  const bool saved = p.putBytes(key, bytes, count) == count;
  p.end();
  return saved;
}

bool readRecord(const char* key, AccountRecordType kind, const AccountOwner& owner,
                void* dest, size_t length) {
  if (!owner.valid || length > AccountScopedRecord::kMaximumPayload || !dest) return false;
  Preferences p;
  if (!p.begin(kNamespace, true)) return false;
  const size_t count = p.getBytesLength(key);
  uint8_t bytes[AccountScopedRecord::kMinimumLength + AccountScopedRecord::kMaximumPayload]{};
  const bool ok = count == AccountScopedRecord::kMinimumLength + length &&
                  p.getBytes(key, bytes, count) == count;
  p.end();
  if (!ok) return false;
  DecodedAccountRecord record{};
  if (!AccountScopedRecord::decode(bytes, count, kind, record) ||
      !AccountScopedRecord::visible(owner, record) || record.payloadLength != length) return false;
  memcpy(dest, record.payload, length);
  return true;
}

bool validString(const char* str, size_t cap, bool optional) {
  const size_t len = strnlen(str, cap);
  if (len == cap || (!optional && len == 0)) return false;
  for (size_t i = 0; i < len; ++i) {
    const unsigned char c = static_cast<unsigned char>(str[i]);
    if (c < 32 || c > 126 || c == ',') return false;
  }
  return true;
}
}
AccountScopedStore accountScopedStore;

bool AccountScopedStore::enabled() const {
  Preferences p;
  if (!p.begin(kNamespace, true)) return false;
  // Fail closed if the marker is damaged but any scoped selection remains.
  const bool state = p.getBool(kEnabled, false) ||
      p.getBytesLength(kSelected) > 0 || p.getBytesLength(kConfirmed) > 0;
  p.end(); return state;
}

bool AccountScopedStore::confirmed() const {
  if (!enabled()) return false;
  AccountOwner selected{}, done{};
  return readSelection(kSelected, selected) && readSelection(kConfirmed, done) && selected.equals(done);
}

bool AccountScopedStore::offer(const AccountOwner& owner, bool& changed) {
  changed = false;
  if (!owner.valid) return false;
  AccountOwner selected{};
  const bool existed = readSelection(kSelected, selected);
  // Enable scoped behavior BEFORE writing identity. A power cut at any stage
  // must never fall back to an unrelated legacy/unscoped personal snapshot.
  Preferences p;
  if (!p.begin(kNamespace, false)) return false;
  const bool marker = p.getBool(kEnabled, false) || p.putBool(kEnabled, true) == 1;
  p.end();
  if (!marker) return false;
  changed = !existed || !selected.equals(owner);
  if (changed) {
    // Invalidate confirmation BEFORE selecting another account. Otherwise
    // A -> B -> A can make the old A confirmation appear valid without a
    // newly committed OPERATOR profile. A single invalid byte is deliberate:
    // it cannot decode as any valid Selection record, including after reboot.
    Preferences guard;
    if (!guard.begin(kNamespace, false)) return false;
    const uint8_t invalid = 0;
    const bool hidden = guard.putBytes(kConfirmed, &invalid, 1) == 1;
    guard.end();
    if (!hidden) return false;
    if (!putRecord(kSelected, AccountRecordType::Selection, owner, nullptr, 0)) return false;
  }
  sessionOwner_ = owner;
  sessionClaimed_ = true;
  return true;
}

bool AccountScopedStore::matchesSession(const AccountOwner& owner) const {
  if (!sessionClaimed_ || !sessionOwner_.equals(owner)) return false;
  AccountOwner selected{};
  return readSelection(kSelected, selected) && selected.equals(owner);
}

bool AccountScopedStore::commitOperator(const ScopedOperator& snapshot) {
  if (!sessionClaimed_ || !matchesSession(sessionOwner_) ||
      !validString(snapshot.username, sizeof(snapshot.username), false) ||
      !validString(snapshot.gang, sizeof(snapshot.gang), true)) return false;
  if (!putRecord(kOperator, AccountRecordType::Operator, sessionOwner_,
                 &snapshot, sizeof(snapshot))) return false;
  // The confirmed selection is committed LAST. On power loss, restoring a
  // selected owner without matching confirmation shows no personal data.
  return putRecord(kConfirmed, AccountRecordType::Selection, sessionOwner_, nullptr, 0);
}

bool AccountScopedStore::loadOperator(ScopedOperator& snapshot) const {
  snapshot = ScopedOperator{};
  if (!confirmed()) return false;
  AccountOwner selected{};
  if (!readSelection(kSelected, selected) ||
      !readRecord(kOperator, AccountRecordType::Operator, selected, &snapshot, sizeof(snapshot)) ||
      !validString(snapshot.username, sizeof(snapshot.username), false) ||
      !validString(snapshot.gang, sizeof(snapshot.gang), true)) {
    snapshot = ScopedOperator{};
    return false;
  }
  return true;
}

bool AccountScopedStore::saveGang(const void* bytes, size_t length) {
  if (!sessionClaimed_ || !confirmed() || !matchesSession(sessionOwner_)) return false;
  return putRecord(kGang, AccountRecordType::Gang, sessionOwner_, bytes, length);
}

bool AccountScopedStore::loadGang(void* bytes, size_t length) const {
  if (!confirmed()) return false;
  AccountOwner selected{};
  return readSelection(kSelected, selected) &&
         readRecord(kGang, AccountRecordType::Gang, selected, bytes, length);
}

void AccountScopedStore::endSession() {
  sessionClaimed_ = false;
  sessionOwner_ = AccountOwner{};
}
} // namespace wdg
