// SPDX-License-Identifier: GPL-3.0-only
#ifndef DIEMOBILE_PHOTOREC_BLOB_INPUT_H
#define DIEMOBILE_PHOTOREC_BLOB_INPUT_H

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <functional>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace diemobile {
/** Owned descriptor and bounded pread windows; caller offsets never change.
 * Header pointers remain borrowed until their cache slot is replaced. */
class BlobInput {
public:
    static constexpr size_t HEADER_BYTES = 65536;
    static constexpr size_t SCAN_BYTES = 1024 * 1024;
    static constexpr size_t HEADER_WINDOWS = 2;
    static constexpr size_t WINDOW_BYTES = 2 * HEADER_BYTES;
    static constexpr size_t MAX_INPUT_READ_BUFFERS = SCAN_BYTES + HEADER_WINDOWS * WINDOW_BYTES;

private:
    struct Descriptor {
        int value;
        explicit Descriptor(int source) : value(fcntl(source, F_DUPFD_CLOEXEC, 0)) {
            if (value < 0) throw std::runtime_error("PhotoRec cannot duplicate the input descriptor");
        }
        ~Descriptor() { close(value); }
        Descriptor(const Descriptor &) = delete;
        Descriptor &operator=(const Descriptor &) = delete;
    } descriptor;
    struct HeaderWindow {
        uint64_t base = UINT64_MAX;
        std::vector<unsigned char> bytes;
    };
    std::array<HeaderWindow, HEADER_WINDOWS> headers;
    size_t next_header = 0;
    struct stat before{};
    std::function<void()> check;
    uint64_t length = 0, read_bytes = 0, read_operations = 0;

public:
    BlobInput(int source, uint64_t maximum, std::function<void()> budget_check)
        : descriptor(source), check(std::move(budget_check)) {
        check();
        int flags = fcntl(descriptor.value, F_GETFL);
        if (flags < 0 || (flags & O_ACCMODE) == O_WRONLY || fstat(descriptor.value, &before)
            || !S_ISREG(before.st_mode) || before.st_size < 0)
            throw std::runtime_error("PhotoRec requires a readable regular-file descriptor");
#ifdef O_PATH
        if (flags & O_PATH) throw std::runtime_error("PhotoRec requires a readable data descriptor, not O_PATH");
#endif
        length = uint64_t(before.st_size);
        if (length > maximum) throw std::runtime_error("PhotoRec input exceeds its 256 MiB whole-blob limit");
    }
    BlobInput(const BlobInput &) = delete;
    BlobInput &operator=(const BlobInput &) = delete;
    uint64_t size() const { return length; }
    uint64_t bytes_read() const { return read_bytes; }
    uint64_t operations() const { return read_operations; }

    void read_at(uint64_t offset, unsigned char *output, size_t count) {
        if (offset > length || uint64_t(count) > length - offset)
            throw std::runtime_error("PhotoRec input read exceeds captured source bounds");
        size_t done = 0;
        while (done < count) {
            check();
            ssize_t actual = pread(descriptor.value, output + done, count - done, off_t(offset + done));
            if (actual < 0 && errno == EINTR) continue;
            if (actual <= 0) throw std::runtime_error("PhotoRec could not read the complete descriptor interval");
            done += size_t(actual);
        }
        if (count) { read_bytes += count; ++read_operations; }
    }

    const unsigned char *header(uint64_t start) {
        if (start >= length) throw std::runtime_error("PhotoRec header starts outside the input");
        uint64_t base = (start / HEADER_BYTES) * HEADER_BYTES;
        for (auto &window : headers)
            if (window.base == base) return window.bytes.data() + size_t(start - base);
        auto &window = headers[next_header];
        next_header = (next_header + 1) % HEADER_WINDOWS;
        if (window.bytes.empty()) window.bytes.resize(WINDOW_BYTES);
        std::fill(window.bytes.begin(), window.bytes.end(), 0);
        size_t available = size_t(std::min<uint64_t>(WINDOW_BYTES, length - base));
        read_at(base, window.bytes.data(), available);
        window.base = base;
        return window.bytes.data() + size_t(start - base);
    }

    void verify_identity() {
        check();
        struct stat after{};
        if (fstat(descriptor.value, &after) || before.st_dev != after.st_dev || before.st_ino != after.st_ino
            || before.st_size != after.st_size || before.st_mtim.tv_sec != after.st_mtim.tv_sec
            || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec || before.st_ctim.tv_sec != after.st_ctim.tv_sec
            || before.st_ctim.tv_nsec != after.st_ctim.tv_nsec)
            throw std::runtime_error("PhotoRec input identity/timestamps changed during analysis");
    }
};
} // namespace diemobile
#endif
