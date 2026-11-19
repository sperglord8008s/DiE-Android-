// SPDX-License-Identifier: GPL-3.0-only
#ifndef DIE_ARCHIVE_JSON_H
#define DIE_ARCHIVE_JSON_H

#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace die_archive {
constexpr size_t MAX_REPORT = 24 * 1024 * 1024;
constexpr size_t MAX_TEXT = 1024 * 1024;

inline void append(std::string &out, const std::string &value) {
    if (value.size() > MAX_REPORT - out.size()) throw std::runtime_error("Archive report exceeds 24 MiB");
    out += value;
}
inline std::string hex(const void *data, size_t size) {
    if (size > 4 * 1024 * 1024) throw std::runtime_error("Archive metadata blob exceeds 4 MiB");
    const auto *bytes = static_cast<const unsigned char *>(data);
    const char *digits = "0123456789abcdef"; std::string out; out.reserve(size * 2);
    for (size_t i = 0; i < size; ++i) { out += digits[bytes[i] >> 4]; out += digits[bytes[i] & 15]; }
    return out;
}
inline void unit(std::string &out, uint32_t value) {
    const char *digits = "0123456789abcdef"; out += "\\u";
    for (int shift = 12; shift >= 0; shift -= 4) out += digits[(value >> shift) & 15];
}

/** ASCII-only JSON transport; valid UTF-8 becomes exact Unicode escapes, invalid bytes stay in hex. */
inline std::string quote(const char *text, bool *valid = nullptr) {
    if (!text) return "null";
    size_t size = strnlen(text, MAX_TEXT + 1);
    if (size > MAX_TEXT) throw std::runtime_error("Archive returned text exceeds 1 MiB");
    bool clean = true; std::string out = "\"";
    for (size_t i = 0; i < size;) {
        uint32_t code = static_cast<unsigned char>(text[i++]);
        if (code < 128) {
            if (code == '"' || code == '\\') { out += '\\'; out += char(code); }
            else if (code < 32) unit(out, code);
            else out += char(code);
            continue;
        }
        unsigned need = code >= 0xc2 && code <= 0xdf ? 1 : code >= 0xe0 && code <= 0xef ? 2 : code >= 0xf0 && code <= 0xf4 ? 3 : 0;
        uint32_t minimum = need == 1 ? 0x80 : need == 2 ? 0x800 : 0x10000;
        uint32_t decoded = code & (need == 1 ? 31 : need == 2 ? 15 : 7);
        bool ok = need && need <= size - i;
        for (unsigned n = 0; ok && n < need; ++n) {
            unsigned char next = static_cast<unsigned char>(text[i + n]);
            if ((next & 0xc0) != 0x80) ok = false;
            else decoded = decoded << 6 | (next & 63);
        }
        ok = ok && decoded >= minimum && decoded <= 0x10ffff && !(decoded >= 0xd800 && decoded <= 0xdfff);
        if (!ok) { clean = false; unit(out, 0xfffd); continue; }
        i += need;
        if (decoded <= 0xffff) unit(out, decoded);
        else { decoded -= 0x10000; unit(out, 0xd800 + (decoded >> 10)); unit(out, 0xdc00 + (decoded & 1023)); }
    }
    out += '"'; if (valid) *valid = clean; return out;
}

struct Object {
    std::string value = "{";
    void raw(const char *key, const std::string &encoded) {
        if (value.size() != 1) append(value, ",");
        append(value, quote(key)); append(value, ":"); append(value, encoded);
    }
    void text(const char *key, const char *text) { raw(key, quote(text)); }
    void number(const char *key, int64_t number) { raw(key, std::to_string(number)); }
    void flag(const char *key, bool flag) { raw(key, flag ? "true" : "false"); }
    std::string finish() const { return value + '}'; }
};
inline std::string array(const std::vector<std::string> &rows) {
    std::string out = "[";
    for (const auto &row : rows) { if (out.size() != 1) append(out, ","); append(out, row); }
    append(out, "]"); return out;
}
inline std::string returned_text(const char *text) {
    if (!text) return "null";
    bool valid = false; Object out; out.raw("text", quote(text, &valid));
    out.text("returnedBytesHex", hex(text, std::strlen(text)).c_str()); out.flag("utf8Valid", valid);
    out.text("meaning", "libarchive accessor C-string bytes; original stored encoding/physical location not implied");
    return out.finish();
}
} // namespace die_archive
#endif
