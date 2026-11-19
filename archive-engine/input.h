// SPDX-License-Identifier: GPL-3.0-only
#ifndef DIE_ARCHIVE_INPUT_H
#define DIE_ARCHIVE_INPUT_H

#include <archive.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace die_archive {
constexpr uint64_t kMaximumInputBytes = 1024ULL * 1024 * 1024;
constexpr uint64_t kMaximumIoWorkBytes = 4ULL * 1024 * 1024 * 1024;
constexpr uint64_t kMaximumMemberBytes = 256ULL * 1024 * 1024;

struct Descriptor {
    int value;
    explicit Descriptor(int input) : value(fcntl(input, F_DUPFD_CLOEXEC, 0)) {
        if (value < 0) throw std::runtime_error("Cannot own archive descriptor");
    }
    ~Descriptor() { close(value); }
    Descriptor(const Descriptor &) = delete;
    Descriptor &operator=(const Descriptor &) = delete;
};

/** The supplied tail is a parsing scope, never an inferred recovered-file extent. */
struct Input {
    Descriptor fd;
    struct stat before{};
    uint64_t base, length, position = 0, bytes_read = 0, operations = 0;
    std::array<unsigned char, 65536> buffer{};
    std::chrono::steady_clock::time_point deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    std::string failure;

    Input(int source, int64_t offset, int64_t count) : fd(source), base(0), length(0) {
        int flags = fcntl(fd.value, F_GETFL);
        if (flags < 0 || (flags & O_ACCMODE) == O_WRONLY || fstat(fd.value, &before)
                || !S_ISREG(before.st_mode) || before.st_size < 0)
            throw std::runtime_error("Archive requires a readable regular-file descriptor");
#ifdef O_PATH
        if (flags & O_PATH) throw std::runtime_error("O_PATH is not an archive data descriptor");
#endif
        if (uint64_t(before.st_size) > kMaximumInputBytes) throw std::runtime_error("Archive input exceeds 1 GiB");
        if (offset < 0 || count < 0 || offset > before.st_size || count > before.st_size - offset)
            throw std::runtime_error("Archive parsing scope exceeds its exact input");
        base = uint64_t(offset); length = uint64_t(count);
    }

    void check() const {
        if (std::chrono::steady_clock::now() > deadline) throw std::runtime_error("Archive exceeded its cooperative 30-second budget");
        if (bytes_read > kMaximumIoWorkBytes) throw std::runtime_error("Archive exceeded 4 GiB source-read work");
    }
    void verify() const {
        check(); struct stat after{};
        if (fstat(fd.value, &after) || before.st_dev != after.st_dev || before.st_ino != after.st_ino
                || before.st_size != after.st_size || before.st_mtim.tv_sec != after.st_mtim.tv_sec
                || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec || before.st_ctim.tv_sec != after.st_ctim.tv_sec
                || before.st_ctim.tv_nsec != after.st_ctim.tv_nsec)
            throw std::runtime_error("Archive source identity/timestamps changed");
    }
    static la_ssize_t read(struct archive *archive, void *opaque, const void **out) noexcept {
        auto &input = *static_cast<Input *>(opaque);
        try {
            input.check(); size_t count = size_t(std::min<uint64_t>(input.buffer.size(), input.length - input.position));
            size_t done = 0;
            while (done < count) {
                input.check();
                ssize_t result = pread(input.fd.value, input.buffer.data() + done, count - done, off_t(input.base + input.position + done));
                if (result < 0 && errno == EINTR) continue;
                if (result <= 0) throw std::runtime_error("Cannot read complete archive source interval");
                done += size_t(result);
            }
            input.position += count; input.bytes_read += count; if (count) ++input.operations;
            input.check(); *out = input.buffer.data(); return la_ssize_t(count);
        } catch (const std::exception &error) {
            input.failure = error.what(); archive_set_error(archive, EIO, "%s", error.what()); return -1;
        }
    }
    static la_int64_t skip(struct archive *archive, void *opaque, la_int64_t count) noexcept {
        auto &input = *static_cast<Input *>(opaque);
        try {
            input.check(); if (count <= 0) return 0;
            uint64_t skipped = std::min<uint64_t>(uint64_t(count), input.length - input.position);
            input.position += skipped; return la_int64_t(skipped);
        } catch (const std::exception &error) {
            input.failure = error.what(); archive_set_error(archive, EIO, "%s", error.what()); return -1;
        }
    }
    static la_int64_t seek(struct archive *archive, void *opaque, la_int64_t offset, int origin) noexcept {
        auto &input = *static_cast<Input *>(opaque);
        try {
            input.check(); int64_t anchor = origin == SEEK_SET ? 0 : origin == SEEK_CUR ? int64_t(input.position)
                    : origin == SEEK_END ? int64_t(input.length) : -1;
            if (anchor < 0 || offset < -anchor || offset > int64_t(input.length) - anchor)
                throw std::runtime_error("Archive seek exceeds its parsing scope");
            input.position = uint64_t(anchor + offset); return la_int64_t(input.position);
        } catch (const std::exception &error) {
            input.failure = error.what(); archive_set_error(archive, EIO, "%s", error.what()); return ARCHIVE_FATAL;
        }
    }
};
} // namespace die_archive
#endif
