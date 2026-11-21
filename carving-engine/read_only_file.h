// SPDX-License-Identifier: GPL-3.0-only
#pragma once

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include <utility>

#include "blob_input.h"

namespace diemobile {

/** Relative read/seek FILE view, with no path opens, writes or input-sized allocation. */
class ReadOnlyFile {
public:
    static constexpr size_t BUFFER_BYTES = 65536;

    /** Only an explicit work limit may preserve an already reported PDF footer boundary. */
    struct WorkLimit final : std::exception {
        const char *what() const noexcept override { return "aggregate-file-read-budget"; }
    };

    ReadOnlyFile(BlobInput &input, uint64_t start, uint64_t length, uint64_t maximum_work,
                 std::function<void(uint64_t)> charge)
        : input_(input), start_(start), length_(length), maximum_work_(maximum_work),
          charge_(std::move(charge)) {
        if (start > input.size() || length > input.size() - start)
            throw std::runtime_error("Read-only FILE scope exceeds captured source");
    }

    FILE *open() {
#ifdef __ANDROID__
        FILE *stream = funopen(this, &read_android, nullptr, &seek_android, nullptr);
#else
        cookie_io_functions_t callbacks{};
        callbacks.read = &read_cookie;
        callbacks.seek = &seek_cookie;
        FILE *stream = fopencookie(this, "r", callbacks);
#endif
        if (stream == nullptr) failure_ = "read-only-file-open-failed";
        else if (setvbuf(stream, buffer_.data(), _IOFBF, buffer_.size()) != 0) {
            fclose(stream);
            stream = nullptr;
            failure_ = "read-only-file-buffer-configuration-failed";
        }
        return stream;
    }

    uint64_t work() const { return work_; }
    const char *failure() const { return failure_; }
    bool read_limit_reached() const { return read_limited_; }

private:
    BlobInput &input_;
    std::array<char, BUFFER_BYTES> buffer_{};
    uint64_t start_, length_, maximum_work_, position_ = 0, work_ = 0;
    std::function<void(uint64_t)> charge_;
    const char *failure_ = nullptr;
    bool read_limited_ = false;

    ssize_t read(char *output, size_t requested) {
        if (failure_ != nullptr) { errno = EIO; return -1; }
        const size_t count = size_t(std::min<uint64_t>(requested, length_ - position_));
        if (count == 0) return 0;
        if (count > maximum_work_ - work_) {
            failure_ = "per-candidate-file-read-budget";
            read_limited_ = true;
            errno = EFBIG;
            return -1;
        }
        // A C stdio callback must never unwind a C++ exception through upstream C code.
        try {
            charge_(count);
            input_.read_at(start_ + position_, reinterpret_cast<unsigned char *>(output), count);
            position_ += count;
            work_ += count;
            return ssize_t(count);
        } catch (const WorkLimit &) {
            failure_ = "aggregate-file-read-budget";
            read_limited_ = true;
            errno = EFBIG;
            return -1;
        } catch (...) {
            failure_ = "source-read-or-deadline-failure";
            errno = EIO;
            return -1;
        }
    }

    int64_t seek(int64_t offset, int whence) {
        int64_t base;
        if (whence == SEEK_SET) base = 0;
        else if (whence == SEEK_CUR) base = int64_t(position_);
        else if (whence == SEEK_END) base = int64_t(length_);
        else { errno = EINVAL; return -1; }
        if ((offset < 0 && offset < -base)
                || (offset >= 0 && uint64_t(offset) > length_ - uint64_t(base))) {
            errno = EINVAL;
            return -1;
        }
        position_ = uint64_t(base + offset);
        return int64_t(position_);
    }

#ifdef __ANDROID__
    static int read_android(void *cookie, char *output, int count) {
        if (count < 0) { errno = EINVAL; return -1; }
        return int(static_cast<ReadOnlyFile *>(cookie)->read(output, size_t(count)));
    }
    static fpos_t seek_android(void *cookie, fpos_t offset, int whence) {
        return fpos_t(static_cast<ReadOnlyFile *>(cookie)->seek(int64_t(offset), whence));
    }
#else
    static ssize_t read_cookie(void *cookie, char *output, size_t count) {
        return static_cast<ReadOnlyFile *>(cookie)->read(output, count);
    }
    static int seek_cookie(void *cookie, off64_t *offset, int whence) {
        int64_t position = static_cast<ReadOnlyFile *>(cookie)->seek(*offset, whence);
        if (position < 0) return -1;
        *offset = position;
        return 0;
    }
#endif
};
} // namespace diemobile
