#pragma once
// Deterministic Preferences mock for native tests, not included in firmware.
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <vector>

class Preferences {
public:
    static inline std::map<std::string, std::vector<uint8_t>> disk{};
    static inline std::string failNextWriteKey{};
    static void reset() { disk.clear(); failNextWriteKey.clear(); }
    bool begin(const char* ns, bool readOnly = false) { prefix_ = std::string(ns) + ":"; readOnly_ = readOnly; return true; }
    void end() {}
    size_t putBytes(const char* key, const void* data, size_t len) {
        if (readOnly_) return 0;
        const auto id = prefix_ + key;
        if (failNextWriteKey == id) { failNextWriteKey.clear(); return 0; }
        const auto* p = static_cast<const uint8_t*>(data);
        disk[id] = std::vector<uint8_t>(p, p+len);
        return len;
    }
    size_t getBytesLength(const char* key) const {
        auto it = disk.find(prefix_ + key);
        return it == disk.end() ? 0 : it->second.size();
    }
    size_t getBytes(const char* key, void* data, size_t size) const {
        auto it = disk.find(prefix_ + key);
        if (it == disk.end() || it->second.size() > size) return 0;
        std::memcpy(data, it->second.data(), it->second.size());
        return it->second.size();
    }
    bool getBool(const char* key, bool def = false) const {
        auto it = disk.find(prefix_ + key);
        return it == disk.end() || it->second.size() != 1 ? def : it->second[0] == 1;
    }
    size_t putBool(const char* key, bool value) {
        uint8_t v = value ? 1 : 0;
        return putBytes(key, &v, 1);
    }
private:
    std::string prefix_;
    bool readOnly_ = false;
};
