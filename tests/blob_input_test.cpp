// SPDX-License-Identifier: GPL-3.0-only
// Trusted test executable. Files below contain synthetic bytes and are never run.
#include "blob_input.h"
#include <cstdio>
#include <string>

static void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}
template <typename Work> static void failure(Work work) {
    bool failed = false;
    try { work(); } catch (const std::runtime_error &) { failed = true; }
    require(failed, "Invalid input operation succeeded");
}

int main(int argc, char **argv) {
    if (argc != 2) return 1;
    try {
        std::string path = std::string(argv[1]) + "/input-reader.bin";
        int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_CLOEXEC, 0600);
        require(fd >= 0, "Fixture open failed");
        std::vector<unsigned char> bytes(3 * diemobile::BlobInput::HEADER_BYTES + 17);
        for (size_t i = 0; i < bytes.size(); i++) bytes[i] = static_cast<unsigned char>(i % 251);
        require(write(fd, bytes.data(), bytes.size()) == ssize_t(bytes.size()), "Fixture write failed");
        require(lseek(fd, 23, SEEK_SET) == 23, "Fixture seek failed");
        {
            diemobile::BlobInput input(fd, 256ULL * 1024 * 1024, [] {});
            std::vector<unsigned char> copy(bytes.size());
            input.read_at(0, copy.data(), copy.size()); require(copy == bytes, "Complete read differs");
            size_t start = diemobile::BlobInput::HEADER_BYTES - 3;
            const unsigned char *header = input.header(start);
            for (size_t i = 0; i < diemobile::BlobInput::HEADER_BYTES; i++) require(header[i] == bytes[start+i], "Unaligned header seam differs");
            uint64_t acquired = input.bytes_read(); input.header(start+1);
            require(input.bytes_read() == acquired, "Repeated header copied another window");
            start = bytes.size() - 7; header = input.header(start);
            for (size_t i = 0; i < diemobile::BlobInput::HEADER_BYTES; i++) require(header[i] == (i < 7 ? bytes[start+i] : 0), "EOF header padding differs");
            failure([&] { unsigned char data[2]; input.read_at(input.size(), data, sizeof(data)); });
            failure([&] { input.header(input.size()); });
            input.verify_identity(); require(lseek(fd, 0, SEEK_CUR) == 23, "Caller descriptor position changed");
        }
        require(fcntl(fd, F_GETFD) >= 0, "Caller descriptor was closed");
        int write_only = open(path.c_str(), O_WRONLY | O_CLOEXEC);
        failure([&] { diemobile::BlobInput input(write_only, 256ULL*1024*1024, [] {}); }); close(write_only);
#ifdef O_PATH
        int path_only = open(path.c_str(), O_PATH | O_CLOEXEC);
        failure([&] { diemobile::BlobInput input(path_only, 256ULL*1024*1024, [] {}); }); close(path_only);
#endif
        failure([&] { diemobile::BlobInput input(fd, bytes.size()-1, [] {}); });
        {
            diemobile::BlobInput input(fd, 256ULL*1024*1024, [] {});
            require(ftruncate(fd, off_t(bytes.size()-1)) == 0, "Truncation fixture failed");
            failure([&] { input.verify_identity(); });
            failure([&] { unsigned char byte; input.read_at(input.size()-1, &byte, 1); });
        }
        require(ftruncate(fd, off_t(bytes.size())) == 0, "Restore fixture failed");
        {
            diemobile::BlobInput input(fd, 256ULL*1024*1024, [] {});
            struct stat status{}; require(fstat(fd, &status) == 0, "stat failed");
            struct timespec times[] = {status.st_atim, status.st_mtim}; ++times[1].tv_sec;
            require(futimens(fd, times) == 0, "Timestamp fixture failed"); failure([&] { input.verify_identity(); });
        }
        {
            diemobile::BlobInput input(fd, 256ULL*1024*1024, [] {});
            close(fd); fd = -1;
            unsigned char byte = 0; input.read_at(1, &byte, 1);
            require(byte == bytes[1], "Owned descriptor did not survive caller close"); input.verify_identity();
        }
        int again = open(path.c_str(), O_RDONLY | O_CLOEXEC); require(again >= 0, "Reopen failed");
        for (int i = 0; i < 2048; i++) { diemobile::BlobInput input(again, 256ULL*1024*1024, [] {}); }
        close(again); unlink(path.c_str());
        std::puts("Bounded source reader passed: seams/EOF padding/cache, exact intervals, caller offset/ownership, truncation/timestamps, unreadable descriptors and repeated lease cleanup.");
    } catch (const std::exception &error) { std::fprintf(stderr, "%s\n", error.what()); return 1; }
    return 0;
}
