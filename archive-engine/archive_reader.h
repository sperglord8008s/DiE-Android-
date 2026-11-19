// SPDX-License-Identifier: GPL-3.0-only
#ifndef DIE_ARCHIVE_READER_H
#define DIE_ARCHIVE_READER_H
#include <cstdint>
#include <string>
namespace die_archive {
std::string inspect(int descriptor, int64_t offset, int64_t length);
std::string extract(int descriptor, int output, int64_t offset, int64_t length, int member);
}
#endif
