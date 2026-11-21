// SPDX-License-Identifier: GPL-3.0-only
// Trusted test program. Synthetic source bytes are data and are never executed.
#include "read_only_file.h"
#include <climits>
#include <vector>

static void require(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

int main(int argc, char **argv) {
    if (argc != 2) return 1;
    try {
        std::string path = std::string(argv[1]) + "/relative-file.bin";
        int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR | O_CLOEXEC, 0600);
        require(fd >= 0, "Source open failed");
        std::vector<unsigned char> original(131145);
        for (size_t i = 0; i < original.size(); i++) original[i] = static_cast<unsigned char>(i % 251);
        require(write(fd, original.data(), original.size()) == ssize_t(original.size()), "Source write failed");
        require(lseek(fd, 23, SEEK_SET) == 23, "Source seek failed");
        {
            diemobile::BlobInput input(fd, original.size(), [] {});
            uint64_t charged = 0;
            diemobile::ReadOnlyFile view(input, 37, 70000, 200000,
                    [&charged](uint64_t count) { charged += count; });
            FILE *stream = view.open(); require(stream != nullptr, "Relative stream open failed");
            std::vector<unsigned char> found(70017);
            size_t count = fread(found.data(), 1, found.size(), stream);
            require(count == 70000 && feof(stream), "Relative EOF escaped its scope");
            for (size_t i = 0; i < count; i++) require(found[i] == original[37+i], "Relative source bytes differ");
            require(ftello(stream) == 70000, "Relative tell differs");
            require(fseeko(stream, -3, SEEK_END) == 0, "Relative end seek failed");
            require(fread(found.data(), 1, 3, stream) == 3 && found[0] == original[37+69997], "Relative end bytes differ");
            require(fseeko(stream, -1, SEEK_SET) < 0, "Negative seek accepted");
            require(fseeko(stream, 1, SEEK_END) < 0, "Seek beyond virtual EOF accepted");
            require(fseeko(stream, LLONG_MIN, SEEK_CUR) < 0, "Seek underflow accepted");
            require(fseeko(stream, LLONG_MAX, SEEK_CUR) < 0, "Seek overflow accepted");
            require(fseeko(stream, 0, SEEK_SET) == 0, "Stream did not recover after invalid seek");
            require(fwrite("untrusted callback write", 1, 24, stream) == 0 && ferror(stream), "Read-only stream accepted a write");
            require(view.failure() == nullptr && charged == view.work(), "Read accounting differs");
            fclose(stream);
            require(lseek(fd, 0, SEEK_CUR) == 23, "Caller descriptor position changed");
            require(fcntl(fd, F_GETFD) >= 0, "Caller descriptor was closed");
            diemobile::ReadOnlyFile budget(input, 0, 70000, 3, [](uint64_t) {});
            stream = budget.open(); require(stream != nullptr, "Budget stream open failed");
            require(fread(found.data(), 1, 16, stream) == 0 && ferror(stream), "Read cap was ignored");
            require(budget.work() <= 3 && budget.failure() != nullptr && budget.read_limit_reached(), "Read cap failure was hidden"); fclose(stream);
            diemobile::ReadOnlyFile throwing(input, 0, 70000, 80000,
                    [](uint64_t) { throw std::runtime_error("deliberate cooperative refusal"); });
            stream = throwing.open(); require(stream != nullptr, "Throwing stream open failed");
            require(fread(found.data(), 1, 16, stream) == 0 && ferror(stream)
                    && throwing.failure() != nullptr && !throwing.read_limit_reached(), "C++ exception escaped the C stdio boundary"); fclose(stream);
            diemobile::ReadOnlyFile aggregate(input, 0, 70000, 80000,
                    [](uint64_t) { throw diemobile::ReadOnlyFile::WorkLimit(); });
            stream = aggregate.open(); require(stream != nullptr, "Aggregate budget stream open failed");
            require(fread(found.data(), 1, 16, stream) == 0 && ferror(stream) && aggregate.read_limit_reached(),
                    "Explicit aggregate work limit did not retain its distinct failure class"); fclose(stream);
            bool refused = false;
            try { diemobile::ReadOnlyFile invalid(input, 131140, 100, 100, [](uint64_t) {}); }
            catch (const std::runtime_error &) { refused = true; }
            require(refused, "Foreign source scope accepted");
            input.verify_identity();
        }
        std::vector<unsigned char> after(original.size());
        require(pread(fd, after.data(), after.size(), 0) == ssize_t(after.size()) && after == original,
                "Source changed through read-only view");
        close(fd); unlink(path.c_str());
        std::puts("Read-only relative stdio passed: exact bounded bytes/EOF, seek overflow/underflow, denied writes, IO work caps, exception containment and caller descriptor ownership.");
    } catch (const std::exception &error) { std::fprintf(stderr, "%s\n", error.what()); return 1; }
    return 0;
}
