// SPDX-License-Identifier: GPL-3.0-only
/* Exact histogram/region/block evidence from the pinned streaming reader. */
#include "records_json.h"
#include "native_request.h"
#include "app/cdie_entropy.h"
#include <math.h>
#include <sys/stat.h>

#define MAX_ENTROPY_INPUT (256ULL * 1024ULL * 1024ULL)

static void entropy_failure(json_report *report, const char *message) {
    xx_buf_clear(&report->buffer); report->failed = false;
    literal(report, "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":false,\"kind\":\"entropy\",\"error\":");
    string(report, message); literal(report, "}");
}

static void real_field(json_report *report, const char *name, double value) {
    char text[64];
    if (!isfinite(value)) { report->failed = true; return; }
    xx_rt_dtoa_shortest(value, text, sizeof(text));
    number_format(report, ",\"%s\":", name); literal(report, text);
}

static void measurement(json_report *report, size_t index, const char *type, const char *name,
    uint64_t offset, uint64_t size, double entropy) {
    number_format(report, "%s{\"index\":%llu", index ? "," : "", U64(index));
    string_field(report, "name", name); string_field(report, "rowType", type);
    physical_field(report, "physicalOffset", (int64_t)offset);
    literal(report, ",\"virtualAddress\":null"); size_field(report, size);
    real_field(report, "entropyBitsPerByte", entropy);
    uint64_t bits; memcpy(&bits, &entropy, sizeof(bits));
    number_format(report, ",\"entropyIeeeBitsHex\":\"%016llX\"", U64(bits));
    string_field(report, "upstreamDisplayLabel", entropy >= 6.5 ? "packed" : "not packed");
    literal(report, "}");
}

static int validate(const cdie_entropy_data *data) {
    if (!data || data->size > MAX_ENTROPY_INPUT || !data->region_count
        || data->region_count > CDIE_ENTROPY_MAX_REGIONS || data->sample_count > CDIE_ENTROPY_MAX_SAMPLES) return 0;
    uint64_t total = 0, end = 0;
    for (size_t i = 0; i < 256; i++) {
        if (data->counts[i] > data->size - total) return 0;
        total += data->counts[i];
    }
    if (total != data->size) return 0;
    for (size_t i = 0; i < data->sample_count; i++) {
        const cdie_entropy_sample *sample = data->samples + i;
        if (sample->offset != end || !sample->size || sample->size > data->size - end) return 0;
        end += sample->size;
    }
    if (end != data->size) return 0;
    for (size_t i = 0; i < data->region_count; i++) {
        const cdie_entropy_region *region = data->regions + i;
        if (region->offset > data->size || region->size > data->size - region->offset) return 0;
    }
    return 1;
}

JNIEXPORT jbyteArray JNICALL Java_org_diemobile_Engine_entropyNative(JNIEnv *env, jclass cls, jstring path, jobject stop) {
    (void)cls;
    json_report report = {0}; xx_buf_init(&report.buffer);
    native_request request = {0}; native_request_init(&request, env, stop);
    const char *target = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    cdie_entropy_data *data = NULL;
    char *error = NULL;
    struct stat status;
    if (!request.failure && (!target || stat(target, &status) || !S_ISREG(status.st_mode) || status.st_size < 0 || (uint64_t)status.st_size > MAX_ENTROPY_INPUT))
        request.failure = "Entropy requires a regular input of at most 256 MiB";
    if (!request.failure) {
        xx_pd_struct progress = xx_pd_init();
        xx_pd_observer previous = xx_pd_set_observer(&progress, native_request_stopped, &request);
        int ok = cdie_entropy_read(target, &progress, &data, &error);
        xx_pd_set_observer(previous.progress, previous.callback, previous.user_data);
        if (!ok && !request.failure) request.failure = error ? error : "Native entropy reader failed";
        if (ok && !validate(data)) request.failure = "Native entropy size/count/range contract failed; no partial snapshot returned";
    }
    if (!request.failure && !native_request_stopped(NULL, &request)) {
        size_t count = 1 + data->region_count + data->sample_count + 256;
        literal(&report, "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":true,\"kind\":\"entropy\",\"scope\":\"complete-snapshot\",\"fileType\":");
        string(&report, data->file_type);
        number_format(&report, ",\"fileSizeBytes\":%llu,\"rowCount\":%llu,\"metadata\":{\"adapterVersion\":1,\"requestContractVersion\":1,\"regionCount\":%llu,\"sampleCount\":%llu,\"histogramBinCount\":256",
            U64(data->size), U64(count), U64(data->region_count), U64(data->sample_count));
        number_format(&report, ",\"maxRegions\":%u,\"maxSamples\":%u,\"maxInputBytes\":%llu,\"maxReportBytes\":%u,\"deadlineSeconds\":30",
            CDIE_ENTROPY_MAX_REGIONS, CDIE_ENTROPY_MAX_SAMPLES, U64(MAX_ENTROPY_INPUT), REPORT_LIMIT);
        string_field(&report, "algorithm", "Shannon byte entropy: -sum(p * log2(p)), over every byte in each reported physical interval; zero for empty intervals");
        string_field(&report, "samplePolicy", "Complete contiguous partition, not sparse sampling: min(100, floor(size/512)) blocks, at least one for nonempty input; final block includes remainder");
        string_field(&report, "regionPolicy", "Pinned native default map excludes virtual/invisible/nonpositive/outside regions and clips overlong extents. Ranges may overlap; they are not a partition. Without eligible regions, Data covers the entire input.");
        string_field(&report, "histogramPolicy", "All 256 whole-input byte counts, including zero counts. Probability is derived count/fileSize, or zero for an empty file. Histogram bins have no individual physical storage offset or payload range.");
        string_field(&report, "legacyHeuristic", "Pinned CDiE display labels entropy >= 6.5 packed, otherwise not packed. This threshold does not establish packing, compression, encryption or maliciousness.");
        literal(&report, "},\"rows\":[");
        measurement(&report, 0, "whole-file", "Whole input", 0, data->size, data->total);
        size_t row = 1;
        for (size_t i = 0; i < data->region_count && !report.failed && !native_request_stopped(NULL, &request); i++, row++) {
            const cdie_entropy_region *region = data->regions + i;
            measurement(&report, row, "region", region->name, region->offset, region->size, region->entropy);
        }
        for (size_t i = 0; i < data->sample_count && !report.failed && !native_request_stopped(NULL, &request); i++, row++) {
            const cdie_entropy_sample *sample = data->samples + i;
            char name[64]; snprintf(name, sizeof(name), "Block %llu", U64(i));
            measurement(&report, row, "block", name, sample->offset, sample->size, sample->entropy);
        }
        for (unsigned i = 0; i < 256 && !report.failed && !native_request_stopped(NULL, &request); i++, row++) {
            char name[32]; snprintf(name, sizeof(name), "Byte 0x%02X", i);
            number_format(&report, ",{\"index\":%llu", U64(row)); string_field(&report, "name", name);
            string_field(&report, "rowType", "byte-frequency");
            number_format(&report, ",\"physicalOffset\":null,\"virtualAddress\":null,\"sizeBytes\":null,\"byteValue\":%u,\"count\":\"%llu\"", i, U64(data->counts[i]));
            real_field(&report, "probability", data->size ? (double)data->counts[i] / (double)data->size : 0);
            literal(&report, "}");
        }
        literal(&report, "]}");
    }
    if (request.failure) entropy_failure(&report, request.failure);
    else if (report.failed) entropy_failure(&report, "Entropy report failed: 16 MiB limit, nonfinite value, formatting or allocation error; no partial snapshot returned");
    if (target) (*env)->ReleaseStringUTFChars(env, path, target);
    xx_rt_free(data); xx_rt_free(error);
    jbyteArray result = NULL;
    if (!report.failed && xx_buf_ok(&report.buffer) && !(*env)->ExceptionCheck(env)) {
        result = (*env)->NewByteArray(env, (jsize)report.buffer.size);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)report.buffer.size, (const jbyte *)report.buffer.data);
    }
    xx_buf_free(&report.buffer); return result;
}
