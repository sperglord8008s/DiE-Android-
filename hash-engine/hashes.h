// SPDX-License-Identifier: GPL-3.0-only
#ifndef DIE_HASHES_H
#define DIE_HASHES_H
#include <stdint.h>
#include <stddef.h>

#define DIE_HASH_COUNT 8
#define DIE_HASH_INPUT_LIMIT (UINT64_C(1024) * 1024 * 1024)
#define DIE_HASH_TIMEOUT_MS 40000
#define DIE_HASH_ERROR_SIZE 192

typedef struct die_hash_result {
    uint64_t input_bytes, bytes_read;
    char values[DIE_HASH_COUNT][129];
} die_hash_result;

extern const char *const die_hash_names[DIE_HASH_COUNT];
/* Borrow fd; duplicate it internally, use pread and leave its position unchanged.
 * No successful prefix: output is cleared on every failure. Linux/Android only.
 * Custom limits are exposed for deterministic boundary/deadline fixture checks. */
int die_hash_fd(int fd, uint64_t maximum_bytes, unsigned timeout_ms,
                die_hash_result *output, char error[DIE_HASH_ERROR_SIZE]);
#endif
