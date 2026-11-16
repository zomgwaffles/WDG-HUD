#include "ViewportTransaction.h"
#include <limits>

namespace wdg {
namespace {
// North-up, row-major slot order: NW,N,NE,W,C,E,SW,S,SE.
constexpr int8_t NORTH_OFFSET[9] = { 1, 1, 1, 0, 0, 0, -1, -1, -1 };
constexpr int8_t EAST_OFFSET[9]  = {-1, 0, 1,-1, 0, 1, -1,  0,  1 };
}

ViewportResult ViewportTransaction::begin(uint32_t sequence, int32_t north, int32_t east) {
  if (hasCommittedSequence_ && sequence <= lastCommittedSequence_)
    return ViewportResult::Stale;
  if (active_ && sequence <= staged_.sequence) return ViewportResult::Stale;
  // Prevent overflow while deriving the 9 adjacent canonical sector coordinates.
  if (north == std::numeric_limits<int32_t>::min() ||
      north == std::numeric_limits<int32_t>::max() ||
      east == std::numeric_limits<int32_t>::min() ||
      east == std::numeric_limits<int32_t>::max()) return ViewportResult::InvalidData;
  staged_ = ViewportSnapshot{};
  staged_.sequence = sequence;
  staged_.centerNorth = north;
  staged_.centerEast = east;
  seen_ = 0;
  active_ = true;
  return ViewportResult::Ok;
}

ViewportResult ViewportTransaction::put(uint32_t sequence, uint8_t slot,
                                        int32_t north, int32_t east,
                                        TileState state, int32_t total) {
  if (!active_) return ViewportResult::NoTransaction;
  if (staged_.sequence != sequence) return ViewportResult::Stale;
  if (slot >= 9) return ViewportResult::InvalidSlot;
  const uint16_t bit = static_cast<uint16_t>(1u << slot);
  if ((seen_ & bit) != 0) return ViewportResult::DuplicateTile;
  if (north != staged_.centerNorth + NORTH_OFFSET[slot] ||
      east != staged_.centerEast + EAST_OFFSET[slot]) return ViewportResult::WrongCoordinate;
  if (state != TileState::Unknown && state != TileState::Present)
    return ViewportResult::InvalidData;
  if (state == TileState::Present && total < 0) return ViewportResult::InvalidData;
  if (state == TileState::Unknown && total != 0) return ViewportResult::InvalidData;
  staged_.tiles[slot].north = north;
  staged_.tiles[slot].east = east;
  staged_.tiles[slot].state = state;
  staged_.tiles[slot].total = total;
  seen_ |= bit;
  return ViewportResult::Ok;
}

ViewportResult ViewportTransaction::commit(uint32_t sequence) {
  if (!active_) return ViewportResult::NoTransaction;
  if (staged_.sequence != sequence) return ViewportResult::Stale;
  if (seen_ != 0x01ffu) return ViewportResult::Incomplete;
  staged_.valid = true;
  live_ = staged_; // Atomic at transaction/model level; BLE must call on main loop.
  lastCommittedSequence_ = sequence;
  hasCommittedSequence_ = true;
  active_ = false;
  seen_ = 0;
  return ViewportResult::Ok;
}

void ViewportTransaction::abort() {
  active_ = false;
  seen_ = 0;
  staged_ = ViewportSnapshot{};
}

void ViewportTransaction::resetSession() {
  abort();
  hasCommittedSequence_ = false;
  lastCommittedSequence_ = 0;
  // Preserve last screen for continuity; source can mark it stale when disconnected.
}
}
