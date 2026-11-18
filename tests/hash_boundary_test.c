// SPDX-License-Identifier: GPL-3.0-only
#define _POSIX_C_SOURCE 200809L
#include "hashes.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static void cleared(const die_hash_result *output)
{
    const unsigned char *bytes = (const unsigned char*)output;
    size_t i;
    for (i = 0; i < sizeof(*output); i++) assert(bytes[i] == 0);
}

int main(void)
{
    die_hash_result output;
    char error[DIE_HASH_ERROR_SIZE];
    FILE *file = tmpfile();
    int fd, pipes[2];
    assert(file);
    assert(fwrite("abc", 1, 3, file) == 3 && !fflush(file));
    fd = fileno(file);
    assert(lseek(fd, 2, SEEK_SET) == 2);
    assert(die_hash_fd(fd, 3, DIE_HASH_TIMEOUT_MS, &output, error));
    assert(!strcmp(output.values[1], "a448017aaf21d8525fc10ae87aa6729d"));
    assert(output.input_bytes == 3 && output.bytes_read == 3 && !error[0]);
    assert(lseek(fd, 0, SEEK_CUR) == 2); /* Native reads must not move the caller cursor. */
    memset(&output, 255, sizeof(output));
    assert(!die_hash_fd(fd, 2, DIE_HASH_TIMEOUT_MS, &output, error));
    assert(strstr(error, "byte budget")); cleared(&output);
    memset(&output, 255, sizeof(output));
    assert(!die_hash_fd(fd, 3, 0, &output, error));
    assert(strstr(error, "deadline")); cleared(&output);
    assert(!die_hash_fd(fd, DIE_HASH_INPUT_LIMIT + 1, DIE_HASH_TIMEOUT_MS, &output, error));
    assert(strstr(error, "budget exceeds")); cleared(&output);
    assert(!die_hash_fd(-1, 3, DIE_HASH_TIMEOUT_MS, &output, error));
    assert(strstr(error, "duplicate")); cleared(&output);
    assert(!pipe(pipes));
    assert(!die_hash_fd(pipes[0], 3, DIE_HASH_TIMEOUT_MS, &output, error));
    assert(strstr(error, "regular file")); cleared(&output);
    close(pipes[0]); close(pipes[1]);
    assert(lseek(fd, 0, SEEK_CUR) == 2);
    assert(fclose(file) == 0);
    puts("Native hash boundary fixtures passed: actual MD4, descriptor borrowing/position, regular-file/size/timeout limits and no successful prefix.");
    return 0;
}
