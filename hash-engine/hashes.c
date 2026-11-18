// SPDX-License-Identifier: GPL-3.0-only
#define _POSIX_C_SOURCE 200809L
#define _FILE_OFFSET_BITS 64
#include "hashes.h"
#include "third_party/rhash/md4.h"
#include <xxfclib/algo/crc/xx_crc.h>
#include <xxfclib/algo/md5/xx_md5.h>
#include <xxfclib/algo/sha/xx_sha.h>
#include <xxfclib/algo/sha512/xx_sha512.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

const char *const die_hash_names[DIE_HASH_COUNT] = {
    "CRC32", "MD4", "MD5", "SHA1", "SHA224", "SHA256", "SHA384", "SHA512"
};

static int now_ms(uint64_t *value)
{
    struct timespec time;
    if (clock_gettime(CLOCK_MONOTONIC, &time)) return 0;
    *value = (uint64_t)time.tv_sec * 1000 + (uint64_t)time.tv_nsec / 1000000;
    return 1;
}

static void hex(char *text, const unsigned char *bytes, size_t size)
{
    static const char digits[] = "0123456789abcdef";
    size_t i;
    for (i = 0; i < size; i++) {
        text[2*i] = digits[bytes[i] >> 4];
        text[2*i+1] = digits[bytes[i] & 15];
    }
    text[2*size] = 0;
}

static int same_source(const struct stat *before, const struct stat *after)
{
    return before->st_dev == after->st_dev && before->st_ino == after->st_ino
        && before->st_size == after->st_size && before->st_mode == after->st_mode
        && before->st_mtim.tv_sec == after->st_mtim.tv_sec
        && before->st_mtim.tv_nsec == after->st_mtim.tv_nsec
        && before->st_ctim.tv_sec == after->st_ctim.tv_sec
        && before->st_ctim.tv_nsec == after->st_ctim.tv_nsec;
}

int die_hash_fd(int fd, uint64_t maximum_bytes, unsigned timeout_ms,
                die_hash_result *output, char error[DIE_HASH_ERROR_SIZE])
{
    const char *message = "Hash calculation did not complete";
    die_hash_result result;
    struct stat before, after;
    xx_crc_context crc;
    md4_ctx md4;
    xx_md5_context md5;
    xx_sha1_context sha1;
    xx_sha224_context sha224;
    xx_sha256_context sha256;
    xx_sha384_context sha384;
    xx_sha512_context sha512;
    unsigned char digest[64], *buffer = NULL;
    uint64_t started, current, done = 0;
    int owned = -1, ok = 0;
    if (!output || !error) return 0;
    memset(output, 0, sizeof(*output));
    memset(&result, 0, sizeof(result));
    error[0] = 0;
    if (maximum_bytes > DIE_HASH_INPUT_LIMIT || timeout_ms > DIE_HASH_TIMEOUT_MS) {
        message = "Requested hash budget exceeds the engine limit"; goto finish;
    }
    if (!now_ms(&started)) { message = "Cannot read the hash request clock"; goto finish; }
    owned = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (owned < 0) { message = "Cannot duplicate the selected input descriptor"; goto finish; }
    if (fstat(owned, &before) || !S_ISREG(before.st_mode) || before.st_size < 0) {
        message = "Hash input must be a regular file with a known length"; goto finish;
    }
    if ((uint64_t)before.st_size > maximum_bytes) {
        message = "Hash input exceeds the configured whole-file byte budget"; goto finish;
    }
    result.input_bytes = (uint64_t)before.st_size;
    buffer = malloc(65536);
    if (!buffer) { message = "Cannot allocate the 64 KiB hash read buffer"; goto finish; }
    rhash_md4_init(&md4);
    if (!xx_crc_context_init_type(&crc, XX_CRC_TYPE_CRC32) || !xx_md5_init(&md5)
        || !xx_sha1_init(&sha1) || !xx_sha224_init(&sha224) || !xx_sha256_init(&sha256)
        || !xx_sha384_init(&sha384) || !xx_sha512_init(&sha512)) {
        message = "Cannot initialize the pinned digest contexts"; goto finish;
    }
    for (;;) {
        size_t wanted;
        ssize_t count;
        if (!now_ms(&current) || current - started >= timeout_ms) {
            message = "Whole-file hashes exceeded the native request deadline"; goto finish;
        }
        if (done == result.input_bytes) break;
        wanted = result.input_bytes - done < 65536 ? (size_t)(result.input_bytes - done) : 65536;
        count = pread(owned, buffer, wanted, (off_t)done);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0 || (size_t)count > wanted) {
            message = "Selected input ended or failed before all declared bytes were hashed"; goto finish;
        }
        xx_crc_context_update(&crc, buffer, (size_t)count);
        rhash_md4_update(&md4, buffer, (size_t)count);
        xx_md5_update(&md5, buffer, (size_t)count);
        xx_sha1_update(&sha1, buffer, (size_t)count);
        xx_sha256_update(&sha256, buffer, (size_t)count);
        if (!xx_sha224_update(&sha224, buffer, (size_t)count)
            || !xx_sha384_update(&sha384, buffer, (size_t)count)
            || !xx_sha512_update(&sha512, buffer, (size_t)count)) {
            message = "Pinned SHA-2 context rejected an update"; goto finish;
        }
        done += (uint64_t)count;
    }
    if (fstat(owned, &after) || !same_source(&before, &after)) {
        message = "Selected input metadata changed during whole-file hashing"; goto finish;
    }
    snprintf(result.values[0], sizeof(result.values[0]), "%08x", (unsigned)xx_crc_context_final(&crc));
    rhash_md4_final(&md4, digest); hex(result.values[1], digest, md4_hash_size);
    if (!xx_md5_final(&md5, digest, sizeof(digest))) goto finish;
    hex(result.values[2], digest, XX_MD5_DIGEST_SIZE);
    if (!xx_sha1_final(&sha1, digest, sizeof(digest))) goto finish;
    hex(result.values[3], digest, XX_SHA1_DIGEST_SIZE);
    if (!xx_sha224_final(&sha224, digest, sizeof(digest))) goto finish;
    hex(result.values[4], digest, XX_SHA224_DIGEST_SIZE);
    if (!xx_sha256_final(&sha256, digest, sizeof(digest))) goto finish;
    hex(result.values[5], digest, XX_SHA256_DIGEST_SIZE);
    if (!xx_sha384_final(&sha384, digest, sizeof(digest))) goto finish;
    hex(result.values[6], digest, XX_SHA384_DIGEST_SIZE);
    if (!xx_sha512_final(&sha512, digest, sizeof(digest))) goto finish;
    hex(result.values[7], digest, XX_SHA512_DIGEST_SIZE);
    result.bytes_read = done;
    *output = result; ok = 1;
finish:
    free(buffer);
    if (owned >= 0) close(owned);
    if (!ok) snprintf(error, DIE_HASH_ERROR_SIZE, "%s", message);
    return ok;
}
