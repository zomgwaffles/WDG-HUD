#include "../src/ViewportTransaction.h"
#include <cassert>
#include <iostream>
using namespace wdg;

static void fill(ViewportTransaction& v, uint32_t sequence, bool unknownAtCenter = false) {
  const int dn[9] = {1,1,1,0,0,0,-1,-1,-1};
  const int de[9] = {-1,0,1,-1,0,1,-1,0,1};
  for (int i = 0; i < 9; ++i) {
    bool missing = (i == 4 && unknownAtCenter);
    assert(v.put(sequence, i, 1964 + dn[i], -3827 + de[i],
                 missing ? TileState::Unknown : TileState::Present,
                 missing ? 0 : 13000 + i) == ViewportResult::Ok);
  }
}
int main() {
  ViewportTransaction v;
  assert(!v.current().valid);
  assert(v.begin(1,1964,-3827)==ViewportResult::Ok);
  assert(v.commit(1)==ViewportResult::Incomplete);
  assert(!v.current().valid); // no partial visibility
  assert(v.put(1,0,1965,-3828,TileState::Present,13000)==ViewportResult::Ok);
  assert(v.put(1,0,1965,-3828,TileState::Present,13000)==ViewportResult::DuplicateTile);
  assert(v.put(1,1,1966,-3827,TileState::Present,10)==ViewportResult::WrongCoordinate);
  assert(v.put(1,1,1965,-3827,TileState::Present,-1)==ViewportResult::InvalidData);
  assert(v.put(1,1,1965,-3827,TileState::Unknown,100)==ViewportResult::InvalidData);
  v.abort();
  assert(v.begin(2,1964,-3827)==ViewportResult::Ok);
  fill(v,2,true);
  assert(v.commit(2)==ViewportResult::Ok);
  assert(v.current().valid && v.current().sequence==2);
  assert(v.current().tiles[4].state==TileState::Unknown);
  assert(v.current().tiles[4].total==0); // MUST check state before reading total
  assert(v.current().tiles[0].north==1965 && v.current().tiles[0].east==-3828);
  assert(v.begin(1,1964,-3827)==ViewportResult::Stale);
  assert(v.begin(3,1964,-3827)==ViewportResult::Ok);
  assert(v.put(2,0,1965,-3828,TileState::Present,1)==ViewportResult::Stale);
  assert(v.commit(3)==ViewportResult::Incomplete);
  assert(v.current().sequence==2); // incomplete transaction does not replace live
  assert(v.begin(4,1964,-3827)==ViewportResult::Ok); // replaces staging
  assert(v.put(3,0,1965,-3828,TileState::Present,1)==ViewportResult::Stale);
  v.resetSession();
  assert(!v.active());
  assert(v.begin(1,1964,-3827)==ViewportResult::Ok); // fresh BLE session
  fill(v,1);
  assert(v.commit(1)==ViewportResult::Ok);
  assert(v.current().tiles[4].state==TileState::Present);
  std::cout << "G4C1 viewport atomicity: PASS\n";
}
