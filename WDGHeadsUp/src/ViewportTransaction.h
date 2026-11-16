#pragma once
#include <cstdint>

// G4C1 is deliberately independent of BLE and the current SectorModel.
// A future BLE transport must explicitly advertise VIEW support only after integration.
namespace wdg {
enum class TileState : uint8_t { Unknown = 0, Present = 1 };
struct ViewportTile {
  int32_t north = 0;
  int32_t east = 0;
  TileState state = TileState::Unknown;
  // Meaningful only when state == Present; unknown is never a zero-AP record.
  int32_t total = 0;
};
struct ViewportSnapshot {
  int32_t centerNorth = 0;
  int32_t centerEast = 0;
  uint32_t sequence = 0;
  ViewportTile tiles[9] = {};
  bool valid = false;
};

enum class ViewportResult : uint8_t {
  Ok, NoTransaction, Stale, InvalidSlot, WrongCoordinate,
  DuplicateTile, InvalidData, Incomplete
};

class ViewportTransaction {
 public:
  // Sequence numbers are strictly increasing within a connected BLE session.
  ViewportResult begin(uint32_t sequence, int32_t centerNorth, int32_t centerEast);
  ViewportResult put(uint32_t sequence, uint8_t slot, int32_t north, int32_t east,
                     TileState state, int32_t total);
  ViewportResult commit(uint32_t sequence);
  void abort();
  // Call on BLE reconnect; guarantees no stale in-flight transaction survives.
  void resetSession();
  const ViewportSnapshot& current() const { return live_; }
  bool active() const { return active_; }
 private:
  ViewportSnapshot live_ = {};
  ViewportSnapshot staged_ = {};
  uint16_t seen_ = 0;
  uint32_t lastCommittedSequence_ = 0;
  bool hasCommittedSequence_ = false;
  bool active_ = false;
};
}
