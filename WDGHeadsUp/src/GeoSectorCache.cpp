#include <Arduino.h>
#include <Preferences.h>
#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include "GeoSectorCache.h"
#include "BleTransport.h"
#include "LastGoodStore.h"

extern bool live3x3Data;
extern int liveLevelNumbers[9][3];
extern int liveLevelCounts[9][3];

namespace {
constexpr int kCapacity = 25;
constexpr uint32_t kMagic = 0x38334757u; // 'WG38' little-endian
constexpr uint8_t kVersion = 1;
constexpr uint32_t kMaxBlobBytes = 24000;
constexpr uint32_t kMinWriteMs = 30000;

struct Record {
  int32_t north = 0;
  int32_t east = 0;
  SectorData sector = {};
  int32_t raw[256] = {};
  uint32_t used = 0;
  bool valid = false;
};
// Global static storage; never allocate 25 records on a small Arduino stack.
Record entries[kCapacity];
Record pending;
uint32_t useCounter = 0;
int32_t centerNorth = 0, centerEast = 0;
bool haveCenter = false;
bool haveSavedInspectionCenter = false;
int32_t requestedNorth=0,requestedEast=0;
bool haveRequested=false;
bool pendingActive = false;
uint32_t pendingUpdatedMs = 0;
uint8_t pendingNameNext = 0, pendingNameParts = 0;
bool pendingNameInvalid = false;
bool changed = false;
bool screenChanged = false;
// Bit i means that slot i in the CURRENT geographic 3x3 has validated data.
// Defaults to complete while a legacy last-good snapshot is on screen.
uint16_t visibleMask = 0x01FF;

uint32_t changedAt = 0, lastWriteAt = 0;
uint32_t lastSavedHash = 0;

uint32_t checksum(const uint8_t* data, size_t count) {
  uint32_t h = 2166136261u;
  for (size_t i = 0; i < count; ++i) { h ^= data[i]; h *= 16777619u; }
  return h;
}
void put32(std::vector<uint8_t>& out, uint32_t v) {
  for (int b = 0; b < 4; ++b) out.push_back(static_cast<uint8_t>(v >> (b * 8)));
}
void put16(std::vector<uint8_t>& out, uint16_t v) {
  out.push_back(static_cast<uint8_t>(v)); out.push_back(static_cast<uint8_t>(v >> 8));
}
void putVar(std::vector<uint8_t>& out, uint32_t n) {
  do { uint8_t b = static_cast<uint8_t>(n & 127); n >>= 7;
       out.push_back(n ? static_cast<uint8_t>(b | 128) : b); } while (n);
}
bool get32(const std::vector<uint8_t>& bytes, size_t& pos, size_t end, uint32_t& out) {
  if (pos > end || end - pos < 4) return false;
  out = 0; for (int i = 0; i < 4; ++i) out |= uint32_t(bytes[pos++]) << (8*i);
  return true;
}
bool get16(const std::vector<uint8_t>& bytes, size_t& pos, size_t end, uint16_t& out) {
  if (pos > end || end - pos < 2) return false;
  out = uint16_t(bytes[pos] | (bytes[pos+1] << 8)); pos += 2; return true;
}
bool getVar(const std::vector<uint8_t>& bytes, size_t& pos, size_t end, uint32_t& out) {
  out = 0;
  for (int i=0;i<5;++i) {
    if (pos >= end) return false;
    const uint8_t b = bytes[pos++];
    if (i == 4 && (b & 0xF0)) return false;
    out |= uint32_t(b & 127) << (i*7);
    if (!(b & 128)) return true;
  }
  return false;
}
bool validName(const char* name) {
  const size_t n = strnlen(name, 97);
  if (!n || n > 96) return false;
  for (size_t i = 0; i < n; ++i)
    if (name[i] < 32 || name[i] > 126 || name[i] == ',') return false;
  return true;
}
bool validRecord(const Record& r) {
  if (!r.valid || r.sector.total < 0 || !validName(r.sector.gangName)) return false;
  int64_t sum = 0;
  for (int l = 1; l < 256; ++l) {
    if (r.raw[l] < 0) return false;
    sum += r.raw[l];
    if (sum > r.sector.total) return false;
  }
  return r.raw[0] == 0 && sum == r.sector.total;
}
int findRecord(int32_t north, int32_t east) {
  for (int i = 0; i < kCapacity; ++i)
    if (entries[i].valid && entries[i].north == north && entries[i].east == east) return i;
  return -1;
}
int chooseSlot(int32_t n, int32_t e) {
  int at = findRecord(n,e);
  if (at >= 0) return at;
  for (int i=0;i<kCapacity;++i) if (!entries[i].valid) return i;
  // Avoid evicting currently visible sectors. Prefer entries furthest
  // from the active center, oldest among equally distant records.
  int best = -1; int64_t farthest = -1;
  for (int i=0;i<kCapacity;++i) {
    const auto& r=entries[i];
    const int64_t dn= int64_t(r.north)-centerNorth;
    const int64_t de= int64_t(r.east)-centerEast;
    if (haveCenter && std::abs(dn)<=1 && std::abs(de)<=1) continue;
    const int64_t distance = dn*dn+de*de;
    if (best<0 || distance>farthest ||
        (distance==farthest && r.used<entries[best].used)) {
      best=i; farthest=distance;
    }
  }
  return best; // All entries visible is impossible with 25 capacity.
}
bool sameRecord(const Record& a, const Record& b) {
  if (!a.valid || !b.valid || a.north!=b.north || a.east!=b.east ||
      a.sector.total!=b.sector.total || a.sector.territoryColor!=b.sector.territoryColor ||
      strcmp(a.sector.gangName,b.sector.gangName)) return false;
  return memcmp(a.raw,b.raw,sizeof(a.raw)) == 0;
}
void insertRecord(const Record& r) {
  if (!validRecord(r)) return;
  int idx=chooseSlot(r.north,r.east);
  if (idx<0) return;
  if (sameRecord(entries[idx],r)) { entries[idx].used = ++useCounter; return; }
  entries[idx]=r;
  entries[idx].used=++useCounter;
  changed=true; changedAt=millis();
}
std::vector<uint8_t> encode() {
  std::vector<uint8_t> b; b.reserve(10000);
  put32(b,kMagic); b.push_back(kVersion);
  b.push_back(haveCenter ? 1 : 0);
  put32(b,static_cast<uint32_t>(centerNorth));
  put32(b,static_cast<uint32_t>(centerEast));
  size_t countAt = b.size(); b.push_back(0);
  for (const auto& r: entries) {
    if (!validRecord(r)) continue;
    ++b[countAt];
    put32(b,static_cast<uint32_t>(r.north)); put32(b,static_cast<uint32_t>(r.east));
    put32(b,static_cast<uint32_t>(r.sector.total)); put16(b,r.sector.territoryColor);
    uint8_t size = static_cast<uint8_t>(strlen(r.sector.gangName));
    b.push_back(size);
    b.insert(b.end(),r.sector.gangName,r.sector.gangName+size);
    size_t nAt=b.size(); put16(b,0); uint16_t count=0;
    for (int lvl=1; lvl<256; ++lvl) if (r.raw[lvl]) {
      b.push_back(static_cast<uint8_t>(lvl));
      putVar(b,static_cast<uint32_t>(r.raw[lvl])); ++count;
    }
    b[nAt]=static_cast<uint8_t>(count); b[nAt+1]=static_cast<uint8_t>(count>>8);
    if (b.size()>kMaxBlobBytes-4) return {};
  }
  put32(b,checksum(b.data(),b.size()));
  return b;
}
bool decode(const std::vector<uint8_t>& b) {
  if (b.size()<19 || b.size()>kMaxBlobBytes) return false;
  const size_t end=b.size()-4; size_t p=0;
  uint32_t magic=0, n=0,e=0, checksumIn=0;
  if (!get32(b,p,end,magic) || magic!=kMagic || p>=end || b[p++]!=kVersion || p>=end) return false;
  uint8_t centered=b[p++]; if (centered>1) return false;
  if (!get32(b,p,end,n) || !get32(b,p,end,e) || p>=end) return false;
  uint8_t count=b[p++]; if(count>kCapacity) return false;
  static Record restore[kCapacity]; // No stack spike.
  for (auto& r:restore) r=Record{};
  for (uint8_t i=0;i<count;++i) {
    uint32_t rn=0,re=0,total=0; uint16_t color=0,rawCount=0;
    if(!get32(b,p,end,rn)||!get32(b,p,end,re)||!get32(b,p,end,total)||total>INT_MAX||
       !get16(b,p,end,color)||p>=end) return false;
    uint8_t nameSize=b[p++];
    if (!nameSize || nameSize>96 || end-p<nameSize) return false;
    Record& r=restore[i]; r.north=static_cast<int32_t>(rn);r.east=static_cast<int32_t>(re);
    r.sector.total=static_cast<int>(total);r.sector.territoryColor=color;
    memcpy(r.sector.gangName,b.data()+p,nameSize);r.sector.gangName[nameSize]=0; p+=nameSize;
    if (!get16(b,p,end,rawCount)||rawCount>255) return false;
    int previous=0;int64_t sum=0;
    for (int j=0;j<rawCount;++j) {
      if(p>=end) return false;
      int level=b[p++];uint32_t qty=0;
      if(level<=previous || !getVar(b,p,end,qty) || !qty || qty>INT_MAX) return false;
      r.raw[level]=static_cast<int32_t>(qty);sum+=qty;previous=level;
      if(sum>r.sector.total) return false;
    }
    r.valid=true;r.used=i+1;
    if(!validRecord(r)) return false;
    for(int j=0;j<i;++j)
      if(restore[j].north==r.north&&restore[j].east==r.east) return false;
  }
  if(p!=end || !get32(b,p,b.size(),checksumIn) || checksumIn!=checksum(b.data(),end)) return false;
  for(int i=0;i<kCapacity;++i) entries[i]=restore[i];
  centerNorth=static_cast<int32_t>(n);centerEast=static_cast<int32_t>(e);
  haveCenter=centered;useCounter=count;
  requestedNorth=centerNorth;requestedEast=centerEast;haveRequested=haveCenter;
  lastSavedHash=checksumIn;
  return true;
}
void assignVisible(const Record& r, int index) {
  sectors3x3[index]=r.sector;
  for (int l=0;l<256;++l) liveRawLevels[index][l]=r.raw[l];
  memset(liveTierCounts[index],0,sizeof(liveTierCounts[index]));
  for (int l=1;l<256;++l) {
    int tier=(l-1)/10+1; if(tier>10) tier=10;
    liveTierCounts[index][tier]+=r.raw[l];
  }
  int best[3]={};
  for(int tier=1;tier<=10;++tier) if(liveTierCounts[index][tier]) {
    for(int j=0;j<3;++j) if(!best[j] || liveTierCounts[index][tier]>liveTierCounts[index][best[j]]) {
      for(int k=2;k>j;--k) best[k]=best[k-1];
      best[j]=tier;
      break;
    }
  }
  for(int j=0;j<3;++j) {
    liveLevelNumbers[index][j]=best[j];
    liveLevelCounts[index][j]=best[j]?liveTierCounts[index][best[j]]:0;
  }
}
}

void restoreGeoSectorCache() {
  Preferences prefs;
  if(!prefs.begin("wdggeo38",true)) return;
  const size_t len=prefs.getBytesLength("geo");
  if(len>=19 && len<=kMaxBlobBytes) {
    std::vector<uint8_t> b(len);
    if(prefs.getBytes("geo",b.data(),len)==len && decode(b))
      Serial.printf("G5A38: restored %u keyed sectors from NVS\n",static_cast<unsigned>(b[14]));
  }
  prefs.end();
}

void geoCacheDeferDisplayUntilCenter() {
  // Preserve the NVS center for 5x5 inspection only.
  haveSavedInspectionCenter = haveCenter;
  // A validated 3x3 may have been saved more recently than the 25-sector NVS
  // blob. Never replace that last-good view with an older cached center.
  haveCenter = false;
  haveRequested = false;
}

bool geoCacheCurrentCenter(int32_t& n,int32_t& e) {
  if(!haveCenter) return false;
  n=centerNorth;e=centerEast;return true;
}

bool geoCacheTileAvailable(int slot) {
  // Without a locally selected geographic center, the legacy restored snapshot
  // remains authoritative; it was already checked as one atomic nine-sector set.
  if (!haveCenter) return true;
  return slot >= 0 && slot < 9 && (visibleMask & (1U << slot)) != 0;
}

bool geoCacheRecenter(int32_t n,int32_t e) {
  requestedNorth=n; requestedEast=e; haveRequested=true;
  // G5A39: navigation is NEVER blocked by a missing AP detail record.
  // Rebuild each position by its geographic key. Unknown sectors are explicitly
  // cleared, not reused from the previous window. No HTTP calls occur here.
  int idx[9]; int at=0; uint16_t newMask=0;
  for(int dy=1;dy>=-1;--dy) for(int dx=-1;dx<=1;++dx) {
    idx[at]=findRecord(n+dy,e+dx);
    if(idx[at]>=0) newMask |= static_cast<uint16_t>(1U << at);
    ++at;
  }
  const bool moved = !haveCenter || centerNorth!=n || centerEast!=e;
  // An unchanged CENTER ACK should not needlessly repaint the whole screen.
  if(!moved && visibleMask==newMask) return true;
  for(int i=0;i<9;++i) {
    if(idx[i]>=0) {
      assignVisible(entries[idx[i]],i);
      entries[idx[i]].used=++useCounter;
    } else {
      sectors3x3[i]=SectorData{};
      sectors3x3[i].territoryColor=0x8410; // neutral grey
      strncpy(sectors3x3[i].gangName,"UNKNOWN",sizeof(sectors3x3[i].gangName)-1);
      memset(liveRawLevels[i],0,sizeof(liveRawLevels[i]));
      memset(liveTierCounts[i],0,sizeof(liveTierCounts[i]));
      for(int j=0;j<3;++j) {liveLevelNumbers[i][j]=0;liveLevelCounts[i][j]=0;}
    }
  }
  visibleMask=newMask;
  centerNorth=n;centerEast=e;haveCenter=true;
  live3x3Data=true;
  // A partial viewport must never replace the separately saved full last-good
  // nine-cell snapshot. It is still a valid transient geographically keyed view.
  if(newMask==0x01FF) persistLastGoodGrid();
  screenChanged=true;
  changed=true;changedAt=millis();
  Serial.printf("G5A39: GPS recenter north=%ld east=%ld cached=%d/9\n",
                long(n),long(e),__builtin_popcount(unsigned(newMask)));
  return true;
}

void geoCacheFromValidatedGrid(int32_t n,int32_t e) {
  for(int i=0;i<9;++i) {
    Record r{};
    const int dy=1-i/3,dx=i%3-1;
    r.north=n+dy; r.east=e+dx;
    r.sector=sectors3x3[i];r.valid=true;
    for(int l=0;l<256;++l) r.raw[l]=liveRawLevels[i][l];
    insertRecord(r);
  }
  centerNorth=n;centerEast=e;haveCenter=true;
  visibleMask=0x01FF; // A newly validated atomic nine-sector snapshot.
  requestedNorth=n;requestedEast=e;haveRequested=true;
  changed=true;changedAt=millis();
}

bool geoCacheBegin(int32_t n,int32_t e,int32_t total,uint16_t color) {
  if(pendingActive && millis()-pendingUpdatedMs>30000UL) pendingActive=false;
  if(pendingActive || total<0) return false;
  pending=Record{};pending.north=n;pending.east=e;
  pending.sector.total=total; pending.sector.territoryColor=color;
  pending.valid=true;pendingActive=true;pendingUpdatedMs=millis();
  pendingNameNext=0;pendingNameParts=0;pendingNameInvalid=false;
  return true;
}
bool geoCacheName(int part,int parts,const char* text) {
  if(!pendingActive||pendingNameInvalid||!text||parts<1||parts>4||part<0||part>=parts||
     part!=pendingNameNext||(pendingNameParts && pendingNameParts!=parts)) return false;
  const size_t len=strlen(text);const size_t offset=size_t(part)*24;
  if(!len||len>24||(part<parts-1&&len!=24)||offset+len>96) return false;
  for(size_t i=0;i<len;++i) if(text[i]<32||text[i]>126||text[i]==',') return false;
  if(!part) {memset(pending.sector.gangName,0,sizeof(pending.sector.gangName));pendingNameParts=parts;}
  pendingUpdatedMs=millis();
  memcpy(pending.sector.gangName+offset,text,len);
  pending.sector.gangName[offset+len]=0; ++pendingNameNext;
  return true;
}
bool geoCacheRaw(int level,int count) {
  if(!pendingActive||level<1||level>255||count<=0||pending.raw[level])return false;
  pending.raw[level]=count;pendingUpdatedMs=millis();return true;
}
bool geoCacheCommit() {
  if(!pendingActive) return false;
  bool ok=pendingNameParts>0&&pendingNameNext==pendingNameParts&&!pendingNameInvalid&&validRecord(pending);
  if(ok) {
    const int former=findRecord(pending.north,pending.east);
    if(former>=0 && strcmp(pending.sector.gangName,"UNKNOWN")==0 &&
       strcmp(entries[former].sector.gangName,"UNKNOWN")!=0) {
      strncpy(pending.sector.gangName,entries[former].sector.gangName,96);
      pending.sector.gangName[96]=0;
      pending.sector.territoryColor=entries[former].sector.territoryColor;
    }
    // A received detail sector can upgrade the CURRENT window without waiting
    // for another nine-sector transfer. Only the single validated entry changes.
    const int existing = findRecord(pending.north,pending.east);
    const bool modified = existing<0 || !sameRecord(entries[existing],pending);
    insertRecord(pending);
    // The main 3x3 updates by its own key; 5x5 inspection ALSO needs a redraw
    // when a newly validated OUTER-RING sector arrives without a CENTER change.
    if(modified && haveCenter &&
       std::abs(int64_t(pending.north)-centerNorth)<=2 &&
       std::abs(int64_t(pending.east)-centerEast)<=2) screenChanged=true;
    if(modified && haveRequested &&
       (requestedNorth!=centerNorth || requestedEast!=centerEast || !haveCenter)) {
      geoCacheRecenter(requestedNorth,requestedEast);
    } else if(modified && haveCenter &&
       std::abs(int64_t(pending.north)-centerNorth)<=1 &&
       std::abs(int64_t(pending.east)-centerEast)<=1) {
      // One new validated sector upgrades the visible partial window immediately.
      // A CENTER is NOT needed and the other eight slots retain their keys.
      // Force the current-window rebuild even if the presence mask is unchanged:
      // its AP counts, tiers or owner colors may have changed.
      haveCenter=false;
      geoCacheRecenter(centerNorth,centerEast);
    }
  }
  pendingActive=false;
  return ok;
}
void geoCacheAbort(){pendingActive=false;}

void tickGeoSectorCache() {
  if(pendingActive && millis()-pendingUpdatedMs>30000UL) geoCacheAbort();
  if(!changed || millis()-changedAt<3000 || (lastWriteAt && millis()-lastWriteAt<kMinWriteMs)) return;
  auto bytes=encode();if(bytes.empty()) return;
  const uint32_t hash=checksum(bytes.data(),bytes.size()-4);
  if(hash==lastSavedHash) {changed=false;return;}
  Preferences prefs;
  if(!prefs.begin("wdggeo38",false))return;
  const size_t wrote=prefs.putBytes("geo",bytes.data(),bytes.size());prefs.end();
  if(wrote==bytes.size()){
    lastSavedHash=hash;lastWriteAt=millis();changed=false;
    Serial.printf("G5A38: cached %u sectors in flash (%u bytes)\n",
                  unsigned(bytes[14]),unsigned(bytes.size()));
  }else Serial.println("G5A38: cache flash save failed");
}

// A recentered or refreshed snapshot needs exactly one screen repaint.
bool consumeGeoCacheScreenUpdate() { bool result=screenChanged;screenChanged=false;return result; }

// G5A41: 5x5 inspection reads the existing immutable-key AP cache. The 5x5
// window is derived from the CURRENT GPS center; no network or scanning here.
bool geoCacheRead5x5(int slot, SectorData* out, int32_t* rawOut, int32_t* tiersOut) {
  if (slot < 0 || slot >= 25 || (!haveCenter && !haveSavedInspectionCenter)) return false;
  const int northDelta = 2 - slot / 5;
  const int eastDelta = slot % 5 - 2;
  const int found = findRecord(centerNorth + northDelta, centerEast + eastDelta);
  if (found < 0 || !validRecord(entries[found])) return false;
  const Record& r = entries[found];
  if (out) *out = r.sector;
  if (rawOut) for (int level=0; level<256; ++level) rawOut[level] = r.raw[level];
  if (tiersOut) {
    for (int t=0; t<=10; ++t) tiersOut[t]=0;
    for (int level=1; level<256; ++level) {
      int tier=(level-1)/10+1;
      if(tier>10) tier=10;
      tiersOut[tier]+=r.raw[level];
    }
  }
  return true;
}
