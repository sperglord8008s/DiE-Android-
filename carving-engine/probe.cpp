// SPDX-License-Identifier: GPL-3.0-only
// PhotoRec owns the signatures and format callbacks. This adapter owns only
// whole-blob matching, bounded read-only inputs, evidence and JNI transport.
#include "config.h"
#include <jni.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <mutex>
#include <queue>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

extern "C" {
#include "types.h"
#include "filegen.h"
#include "log.h"
extern file_enable_t array_file_enable[];
extern file_check_list_t file_check_list;
}

namespace {
constexpr uint64_t MAX_BLOB_BYTES = 64ULL * 1024 * 1024;
constexpr uint64_t MAX_WORK = 256ULL * 1024 * 1024;
constexpr uint64_t MAX_CANDIDATE_WORK = 8ULL * 1024 * 1024;
constexpr size_t MAX_ROWS = 250000;
constexpr size_t MAX_REPORT = 24 * 1024 * 1024;
constexpr unsigned BLOCK = 65536;
constexpr const char *REVISION = "281be432dd79121e08e0898887a9ee1f30fb3e96";
std::mutex engine_mutex; // Upstream format modules have process-wide state.

std::string quoted(const char *text) {
    std::string result = "\"";
    if (text != nullptr) {
        constexpr char hex[] = "0123456789abcdef";
        for (const unsigned char *p = reinterpret_cast<const unsigned char *>(text); *p; ++p) {
            if (*p == '"' || *p == '\\') { result += '\\'; result += char(*p); }
            else if (*p < 32) {
                result += "\\u00"; result += hex[*p >> 4]; result += hex[*p & 15];
            } else result += char(*p);
        }
    }
    return result + '"';
}

std::string hex_bytes(const void *data, size_t size) {
    const auto *bytes = static_cast<const unsigned char *>(data);
    constexpr char hex[] = "0123456789abcdef";
    std::string result;
    result.reserve(size * 2);
    for (size_t i = 0; i < size; ++i) {
        result += hex[bytes[i] >> 4]; result += hex[bytes[i] & 15];
    }
    return result;
}

struct Node {
    std::map<unsigned char, size_t> edges;
    size_t failure = 0;
    std::vector<size_t> outputs;
};

struct Registry {
    file_stat_t *stats = nullptr;
    std::vector<const file_check_t *> patterns;
    std::vector<Node> nodes{1};
    std::array<size_t, 256> root{};
    size_t format_count = 0;
    size_t searched_patterns = 0;
    std::string catalog;

    Registry() {
        log_set_levels(0); // No native stdout/log file is part of an evidence report.
        for (file_enable_t *p = array_file_enable; p->file_hint; ++p) {
            // User-defined photorec.sig performs auxiliary filesystem acquisition.
            p->enable = std::strcmp(p->file_hint->extension, "custom") != 0;
            if (p->enable && p->file_hint->register_header_check) ++format_count;
        }
        stats = init_file_stats(array_file_enable);
        const td_list_head *group;
        td_list_for_each(group, &file_check_list.list) {
            const auto *bucket = td_list_entry_const(group, const file_check_list_t, list);
            for (unsigned b = 0; b < 256; ++b) {
                const td_list_head *entry;
                td_list_for_each(entry, &bucket->file_checks[b].list) {
                    const auto *p = td_list_entry_const(entry, const file_check_t, list);
                    if (!p->length || p->length > PHOTOREC_MAX_SIG_SIZE ||
                        p->offset > PHOTOREC_MAX_SIG_OFFSET || !p->file_stat ||
                        !p->file_stat->file_hint || !p->header_check)
                        throw std::runtime_error("Invalid upstream PhotoRec registration");
                    patterns.push_back(p);
                }
            }
        }
        if (patterns.size() > 10000) throw std::runtime_error("PhotoRec catalog exceeds 10000 patterns");
        // Stable original registration identity, independent of upstream search buckets.
        std::stable_sort(patterns.begin(), patterns.end(), [](const auto *a, const auto *b) {
            int order = std::strcmp(a->file_stat->file_hint->extension, b->file_stat->file_hint->extension);
            if (order != 0) return order < 0;
            if (a->offset != b->offset) return a->offset < b->offset;
            if (a->length != b->length) return a->length < b->length;
            return std::memcmp(a->value, b->value, a->length) < 0;
        });
        std::ostringstream out;
        out << '[';
        for (size_t i = 0; i < patterns.size(); ++i) {
            const auto *p = patterns[i];
            const auto *hint = p->file_stat->file_hint;
            const auto *bytes = static_cast<const unsigned char *>(p->value);
            bool all_zero = std::all_of(bytes, bytes + p->length, [](unsigned char byte) { return byte == 0; });
            bool searched = p->length >= 2 && !all_zero;
            if (i) out << ',';
            out << "{\"index\":" << i << ",\"name\":" << quoted(hint->extension)
                << ",\"description\":" << quoted(hint->description)
                << ",\"magicHex\":" << quoted(hex_bytes(p->value, p->length).c_str())
                << ",\"magicRelativeOffset\":" << p->offset
                << ",\"magicSizeBytes\":" << p->length
                << ",\"searched\":" << (searched ? "true" : "false")
                << ",\"searchExclusion\":" << (searched ? "null" : quoted(all_zero ? "all-zero signature requires anchored scope" : "one-byte signature requires anchored scope"))
                << ",\"upstreamRecover\":" << (hint->recover ? "true" : "false")
                << ",\"upstreamEnabledByDefault\":" << (hint->enable_by_default ? "true" : "false")
                << ",\"upstreamMaximumFileSizeBytes\":" << hint->max_filesize << '}';
            if (!searched) continue;
            ++searched_patterns;
            size_t node = 0;
            for (unsigned j = 0; j < p->length; ++j) {
                auto found = nodes[node].edges.find(bytes[j]);
                if (found == nodes[node].edges.end()) {
                    size_t next = nodes.size();
                    nodes[node].edges.emplace(bytes[j], next);
                    nodes.emplace_back();
                    node = next;
                } else node = found->second;
                if (nodes.size() > 262144) throw std::runtime_error("PhotoRec index exceeds state budget");
            }
            nodes[node].outputs.push_back(i);
        }
        out << ']'; catalog = out.str();
        std::queue<size_t> pending;
        for (const auto &edge : nodes[0].edges) { root[edge.first] = edge.second; pending.push(edge.second); }
        while (!pending.empty()) {
            size_t parent = pending.front(); pending.pop();
            for (const auto &edge : nodes[parent].edges) {
                size_t failure = nodes[parent].failure;
                while (failure && !nodes[failure].edges.count(edge.first)) failure = nodes[failure].failure;
                auto found = nodes[failure].edges.find(edge.first);
                if (found != nodes[failure].edges.end()) failure = found->second;
                nodes[edge.second].failure = failure;
                const auto &outputs = nodes[failure].outputs;
                nodes[edge.second].outputs.insert(nodes[edge.second].outputs.end(), outputs.begin(), outputs.end());
                pending.push(edge.second);
            }
        }
    }
    ~Registry() { free_header_check(); std::free(stats); }
};

struct Budget {
    uint64_t bytes = 0;
    size_t limited_candidates = 0;
    std::chrono::steady_clock::time_point deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    void check(uint64_t amount = 0) {
        if (amount > MAX_WORK - bytes) throw std::runtime_error("PhotoRec callback buffer work exceeds 256 MiB; no complete result");
        bytes += amount;
        if (std::chrono::steady_clock::now() > deadline) throw std::runtime_error("PhotoRec exceeded 30-second cooperative deadline; no complete result");
    }
};

std::string candidate(const Registry &registry, size_t pattern, uint64_t magic,
                      const std::vector<unsigned char> &input, uint64_t source_size, Budget &budget) {
    const auto *p = registry.patterns[pattern];
    const auto *hint = p->file_stat->file_hint;
    uint64_t start = magic - p->offset;
    uint64_t remaining = source_size - start;
    unsigned actual = unsigned(std::min<uint64_t>(remaining, BLOCK));
    budget.check();
    // fidentify uses a 64 KiB header window. Zero padding is explicitly exposed;
    // an accepted padded header never establishes a missing extent by itself.
    // Borrow the immutable acquired bytes and their single zero-padded suffix.
    // Rejected short signatures must not copy 64 KiB for every occurrence.
    const unsigned char *header = input.data() + start;
    file_recovery_t old{}, recovered{};
    reset_file_recovery(&old); reset_file_recovery(&recovered);
    old.blocksize = BLOCK; recovered.blocksize = BLOCK;
    header_ignored_cond_reset(0, 0);
    int accepted = p->header_check(header, BLOCK, 1, &old, &recovered);
    if (!accepted) {
        // Rejected headers dominate large blobs. Keep every occurrence and all
        // returned state in a dictionary-described tuple instead of repeating
        // hundreds of field-name bytes. The Java viewer expands it per row.
        std::ostringstream rejected;
        rejected << '['
            << quoted(std::to_string(recovered.calculated_file_size).c_str()) << ','
            << quoted(std::to_string(recovered.min_filesize).c_str()) << ','
            << (recovered.data_check ? "true" : "false") << ','
            << (recovered.file_check ? "true" : "false") << ',' << int64_t(recovered.time) << ',' << recovered.flags << ','
            << quoted(std::to_string(recovered.offset_ok).c_str()) << ','
            << quoted(std::to_string(recovered.offset_error).c_str()) << ','
            << quoted(std::to_string(recovered.extra).c_str()) << ','
            << quoted(std::to_string(recovered.checkpoint_offset).c_str()) << ','
            << recovered.checkpoint_status << ',' << recovered.data_check_tmp << ','
            << quoted(std::to_string(recovered.file_size).c_str()) << ','
            << (recovered.extension ? quoted(recovered.extension) : "null") << ']';
        return rejected.str();
    }
    uint64_t initial_calculated = recovered.calculated_file_size;
    uint64_t minimum = recovered.min_filesize;
    bool has_data = recovered.data_check != nullptr;
    bool has_file = recovered.file_check != nullptr;
    uint64_t extent = 0;
    uint64_t processed = 0;
    uint64_t padded = 0;
    int status = -1;
    bool limited = false;
    const char *limit_reason = nullptr;
    const char *data_mode = "not-run";
    const char *meaning = accepted ? "header-accepted/extent-unknown" : "header-rejected";
    if (accepted && has_data && recovered.calculated_file_size >= PHOTOREC_MAX_FILE_SIZE)
        meaning = "header-accepted/calculated-size-outside-upstream-data-contract";
    else if (accepted && has_data) {
        if (recovered.data_check == &data_check_size) {
            // Reviewed filegen.c: this callback ignores buffer contents and
            // compares only the declared size with the recovery counter.
            static const std::array<unsigned char, 2 * BLOCK> empty_window{};
            recovered.file_size = remaining > BLOCK ? remaining-BLOCK : 0;
            status = int(data_check_size(empty_window.data(), 2 * BLOCK, &recovered));
            data_mode = "upstream-declared-size-callback/no-payload-read";
        } else {
          data_mode = "upstream-streaming-data-callback";
          std::vector<unsigned char> window(2 * BLOCK, 0);
          for (uint64_t offset = 0; offset < remaining && recovered.data_check; offset += BLOCK) {
            unsigned count = unsigned(std::min<uint64_t>(remaining - offset, BLOCK));
            if (count > MAX_WORK-budget.bytes || count > MAX_CANDIDATE_WORK-processed) {
                limited = true;
                limit_reason = count > MAX_WORK-budget.bytes ? "aggregate-data-buffer-budget" : "per-candidate-data-buffer-budget";
                ++budget.limited_candidates;
                break;
            }
            budget.check(count);
            std::fill(window.begin() + BLOCK, window.end(), 0);
            std::memcpy(window.data() + BLOCK, input.data() + start + offset, count);
            recovered.file_size = offset;
            status = int(recovered.data_check(window.data(), 2 * BLOCK, &recovered));
            processed = offset + count;
            padded = BLOCK - count;
            if (status != DC_CONTINUE || recovered.data_check == nullptr ||
                recovered.calculated_file_size >= PHOTOREC_MAX_FILE_SIZE) break;
            std::memmove(window.data(), window.data() + BLOCK, BLOCK);
          }
        }
        if (limited) meaning = "header-accepted/data-budget-limited/extent-unknown";
        else if (status == DC_ERROR) meaning = "header-accepted/data-rejected";
        else if (status == DC_STOP) {
            meaning = "header-accepted/data-stop/out-of-source-or-invalid-extent";
            uint64_t size = recovered.calculated_file_size;
            if (size > 0 && size >= minimum && size <= remaining && size <= hint->max_filesize &&
                recovered.offset_error == 0) {
                extent = size;
                meaning = "header-accepted/upstream-data-stop-bounded-extent";
            }
        } else meaning = "header-accepted/data-incomplete/extent-unknown";
    }
    std::ostringstream out;
    out << "{\"name\":" << quoted(hint->extension) << ",\"description\":" << quoted(hint->description)
        << ",\"patternIndex\":" << pattern << ",\"physicalOffset\":" << start
        << ",\"candidateStartPhysicalOffset\":" << start
        << ",\"magicOffset\":" << magic << ",\"magicRelativeOffset\":" << p->offset
        << ",\"headerAccepted\":" << (accepted ? "true" : "false")
        << ",\"parserAccepted\":" << (accepted ? "true" : "false")
        << ",\"parserStatus\":" << quoted(meaning)
        << ",\"reportedSizeBytes\":" << (extent ? std::to_string(extent) : "null")
        << ",\"reportedExtension\":" << (recovered.extension ? quoted(recovered.extension) : "null")
        << ",\"headerWindowSourceBytes\":" << actual << ",\"headerWindowZeroPaddingBytes\":" << BLOCK - actual
        << ",\"headerCalculatedSizeBytes\":" << quoted(std::to_string(initial_calculated).c_str())
        << ",\"minimumFileSizeBytes\":" << quoted(std::to_string(minimum).c_str())
        << ",\"dataCheckAvailable\":" << (has_data ? "true" : "false")
        << ",\"dataCheckExecuted\":" << (status >= 0 ? "true" : "false")
        << ",\"dataCheckMode\":" << quoted(data_mode)
        << ",\"dataCheckLimited\":" << (limited ? "true" : "false")
        << ",\"dataCheckLimitReason\":" << (limit_reason ? quoted(limit_reason) : "null")
        << ",\"dataCheckStatus\":" << (status < 0 ? "null" : std::to_string(status))
        << ",\"dataCheckSourceBytesProcessed\":" << processed << ",\"lastBlockZeroPaddingBytes\":" << padded
        << ",\"finalCalculatedSizeBytes\":" << quoted(std::to_string(recovered.calculated_file_size).c_str())
        << ",\"upstreamOffsetOk\":" << quoted(std::to_string(recovered.offset_ok).c_str())
        << ",\"upstreamOffsetError\":" << quoted(std::to_string(recovered.offset_error).c_str())
        << ",\"dataCheckFileSizeCounterBytes\":" << quoted(std::to_string(recovered.file_size).c_str())
        << ",\"upstreamExtra\":" << quoted(std::to_string(recovered.extra).c_str())
        << ",\"upstreamCheckpointOffset\":" << quoted(std::to_string(recovered.checkpoint_offset).c_str())
        << ",\"upstreamCheckpointStatus\":" << recovered.checkpoint_status
        << ",\"upstreamDataCheckTemporary\":" << recovered.data_check_tmp
        << ",\"fileCheckAvailable\":" << (has_file ? "true" : "false")
        << ",\"fileCheckExecuted\":false,\"renameCheckExecuted\":false"
        << ",\"upstreamTime\":" << int64_t(recovered.time) << ",\"upstreamFlags\":" << recovered.flags << '}';
    return out.str();
}

std::string scan(int fd) {
    std::lock_guard<std::mutex> serial(engine_mutex);
    Budget budget;
    struct stat before{}, after{};
    if (fstat(fd, &before) != 0 || !S_ISREG(before.st_mode) || before.st_size < 0)
        throw std::runtime_error("PhotoRec requires a readable regular-file descriptor");
    if (uint64_t(before.st_size) > MAX_BLOB_BYTES) throw std::runtime_error("PhotoRec input exceeds 64 MiB");
    const size_t source_size = size_t(before.st_size);
    std::vector<unsigned char> input(source_size + BLOCK, 0);
    size_t read = 0;
    while (read < source_size) {
        budget.check();
        ssize_t count = pread(fd, input.data() + read, std::min<size_t>(65536, source_size - read), off_t(read));
        if (count <= 0) throw std::runtime_error("PhotoRec could not read the complete descriptor");
        read += size_t(count);
    }
    if (fstat(fd, &after) || before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size ||
        before.st_mtim.tv_sec != after.st_mtim.tv_sec || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec ||
        before.st_ctim.tv_sec != after.st_ctim.tv_sec || before.st_ctim.tv_nsec != after.st_ctim.tv_nsec)
        throw std::runtime_error("PhotoRec input changed during acquisition");
    static Registry registry;
    std::vector<std::string> rows;
    std::vector<std::string> rejected_states;
    std::map<std::string, size_t> state_indices;
    size_t compact_rejections = 0;
    std::vector<size_t> occurrences(registry.patterns.size(), 0);
    size_t report_bytes = registry.catalog.size();
    uint64_t underflow = 0;
    size_t node = 0;
    for (size_t offset = 0; offset < source_size; ++offset) {
        if ((offset & 4095) == 0) budget.check();
        unsigned char byte = input[offset];
        while (node && !registry.nodes[node].edges.count(byte)) node = registry.nodes[node].failure;
        if (!node) node = registry.root[byte];
        else node = registry.nodes[node].edges.at(byte);
        for (size_t pattern : registry.nodes[node].outputs) {
            const auto *p = registry.patterns[pattern];
            uint64_t magic = offset + 1 - p->length;
            if (rows.size() >= MAX_ROWS) {
                size_t common = size_t(std::max_element(occurrences.begin(), occurrences.end())-occurrences.begin());
                throw std::runtime_error("PhotoRec exceeds " + std::to_string(MAX_ROWS) + " candidate records at physical byte " + std::to_string(offset)
                    + "; dominant observed magic " + registry.patterns[common]->file_stat->file_hint->extension
                    + " (pattern " + std::to_string(common) + ", " + std::to_string(occurrences[common])
                    + " occurrences); no complete result");
            }
            ++occurrences[pattern];
            std::string row;
            if (magic < p->offset) {
                ++underflow;
                const auto *hint = p->file_stat->file_hint;
                std::ostringstream rejected;
                rejected << "{\"name\":" << quoted(hint->extension) << ",\"description\":" << quoted(hint->description)
                    << ",\"patternIndex\":" << pattern << ",\"physicalOffset\":" << magic
                    << ",\"magicOffset\":" << magic << ",\"magicRelativeOffset\":" << p->offset
                    << ",\"candidateStartPhysicalOffset\":null,\"derivedCandidateStart\":" << int64_t(magic) - p->offset
                    << ",\"physicalOffsetMeaning\":\"Observed magic only; derived candidate starts before input\""
                    << ",\"reportedSizeBytes\":null,\"headerAccepted\":false,\"parserAccepted\":false"
                    << ",\"fileCheckExecuted\":false,\"renameCheckExecuted\":false"
                    << ",\"parserStatus\":\"magic-matched/negative-candidate-start/header-not-called\"}";
                row = rejected.str();
            } else row = candidate(registry, pattern, magic, input, source_size, budget);
            if (!row.empty() && row.front() == '[') {
                auto found = state_indices.find(row);
                size_t state;
                if (found == state_indices.end()) {
                    state = rejected_states.size();
                    report_bytes += row.size();
                    rejected_states.push_back(row);
                    state_indices.emplace(std::move(row), state);
                } else state = found->second;
                row = '[' + std::to_string(pattern) + ',' + std::to_string(magic) + ',' + std::to_string(state) + ']';
                ++compact_rejections;
            }
            report_bytes += row.size();
            if (report_bytes > MAX_REPORT) throw std::runtime_error("PhotoRec report exceeds 24 MiB; no complete result");
            rows.push_back(std::move(row));
        }
    }
    std::ostringstream out;
    out << "{\"ok\":true,\"schema\":\"org.diemobile.photorec-evidence\",\"schemaVersion\":2"
        << ",\"engine\":\"PhotoRec\",\"engineVersion\":\"7.2\",\"sourceRevision\":" << quoted(REVISION)
        << ",\"sourceArchiveSha256\":\"0cfd82ca6890188a3a4fe01c9b970f654eb3af396580f408ed024789e2873616\""
        << ",\"adapter\":\"diemobile-photorec-2\",\"fileSizeBytes\":" << source_size
        << ",\"compactRejectedCandidateCount\":" << compact_rejections
        << ",\"rejectedRecoveryStateCount\":" << rejected_states.size()
        << ",\"compactRejectedEncoding\":\"[patternIndex,magicOffset,recoveryStateIndex]; original row ordinal is unchanged\""
        << ",\"maximumInputBytes\":" << MAX_BLOB_BYTES << ",\"maximumCandidateRecords\":" << MAX_ROWS
        << ",\"maximumReportBytes\":" << MAX_REPORT << ",\"cooperativeDeadlineMs\":30000"
        << ",\"headerWindowMode\":\"borrowed immutable source with zero-padded suffix\""
        << ",\"rejectedRecoveryStateFields\":[\"headerCalculatedSizeBytes\",\"minimumFileSizeBytes\",\"dataCheckAvailable\",\"fileCheckAvailable\",\"upstreamTime\",\"upstreamFlags\",\"upstreamOffsetOk\",\"upstreamOffsetError\",\"upstreamExtra\",\"upstreamCheckpointOffset\",\"upstreamCheckpointStatus\",\"upstreamDataCheckTemporary\",\"dataCheckFileSizeCounterBytes\",\"reportedExtension\"]"
        << ",\"fullBlobSearch\":true,\"nestedResultsPruned\":false,\"unknownExtentsInferred\":false"
        << ",\"externalExtractorsExecuted\":false,\"fileCheckCallbacksExecuted\":false"
        << ",\"renameCallbacksExecuted\":false,\"filesystemRecoveryExecuted\":false"
        << ",\"safeHeaderOnly\":true,\"auxiliarySignatureFiles\":false,\"optionalLibraries\":[]"
        << ",\"callbackBufferWorkBytes\":" << budget.bytes << ",\"maximumCallbackBufferWorkBytes\":" << MAX_WORK
        << ",\"maximumCandidateDataBufferBytes\":" << MAX_CANDIDATE_WORK
        << ",\"dataCallbacksLimitedCount\":" << budget.limited_candidates
        << ",\"negativeStartMagicMatches\":" << underflow
        << ",\"signatureCount\":" << registry.format_count << ",\"patternCount\":" << registry.patterns.size()
        << ",\"searchedPatternCount\":" << registry.searched_patterns
        << ",\"excludedPatternCount\":" << registry.patterns.size() - registry.searched_patterns
        << ",\"searchCoverage\":\"Every byte for catalog patterns marked searched; one-byte/all-zero signatures remain catalogued but require an anchored scope not implemented by this adapter\""
        << ",\"candidateCount\":" << rows.size() << ",\"patterns\":" << registry.catalog << ",\"rejectedRecoveryStates\":[";
    for (size_t i = 0; i < rejected_states.size(); ++i) { if (i) out << ','; out << rejected_states[i]; }
    out << "],\"rows\":[";
    for (size_t i = 0; i < rows.size(); ++i) { if (i) out << ','; out << rows[i]; }
    out << "]}";
    std::string report = out.str();
    if (report.size() > MAX_REPORT) throw std::runtime_error("PhotoRec complete report exceeds 24 MiB; no complete result");
    return report;
}

jstring result(JNIEnv *env, int descriptor) {
    std::string output;
    try { output = scan(descriptor); }
    catch (const std::exception &error) { output = "{\"ok\":false,\"error\":" + quoted(error.what()) + '}'; }
    jbyteArray bytes = env->NewByteArray(jsize(output.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, jsize(output.size()), reinterpret_cast<const jbyte *>(output.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring encoding = env->NewStringUTF("UTF-8");
    auto value = static_cast<jstring>(env->NewObject(string_class, constructor, bytes, encoding));
    env->DeleteLocalRef(encoding); env->DeleteLocalRef(bytes); env->DeleteLocalRef(string_class);
    return value;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_diemobile_PhotoRecEngine_scanFd(JNIEnv *env, jclass, jint descriptor) {
    return result(env, descriptor);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_diemobile_PhotoRecEngine_scanPath(JNIEnv *env, jclass, jstring path) {
    const char *name = env->GetStringUTFChars(path, nullptr);
    if (!name) return nullptr;
    int fd = open(name, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, name);
    jstring report = result(env, fd);
    if (fd >= 0) close(fd);
    return report;
}
