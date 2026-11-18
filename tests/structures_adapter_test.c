// SPDX-License-Identifier: GPL-3.0-only
/* Trusted adapter fixture, not an inspected executable. Directly exercise the
 * exact serializers used by JNI, including types absent from ordinary headers. */
#include "structures_bridge.c"
#include <stdlib.h>

static int emit(FILE *output, const char *name, const xx_var *value) {
    json_report report = {0}; xx_buf_init(&report.buffer);
    literal(&report, "{\"case\":"); string(&report, name); native_value(&report, value); literal(&report, "}\n");
    int ok = !report.failed && fwrite(report.buffer.data, 1, report.buffer.size, output) == report.buffer.size;
    xx_buf_free(&report.buffer); return ok;
}

int main(int argc, char **argv) {
    if (argc != 2 || sizeof(wchar_t) != 4) return 1;
    FILE *output = fopen(argv[1], "wb");
    if (!output) return 2;
    xx_var value = {0};
    value.type = XX_VAR_TYPE_UINT64; value.val.u64 = UINT64_MAX;
    if (!emit(output, "max-u64", &value)) return 3;
    value.type = XX_VAR_TYPE_INT64; value.val.i64 = INT64_MIN;
    if (!emit(output, "min-i64", &value)) return 4;
    value.type = XX_VAR_TYPE_FLOAT;
    uint32_t float_bits = UINT32_C(0x80000000); memcpy(&value.val.f, &float_bits, sizeof(float_bits));
    if (!emit(output, "negative-zero", &value)) return 5;
    value.type = XX_VAR_TYPE_DOUBLE;
    uint64_t double_bits = UINT64_C(0x7FF8123456789ABC); memcpy(&value.val.d, &double_bits, sizeof(double_bits));
    if (!emit(output, "nan-payload", &value)) return 6;
    char bytes[] = {'A', 0, (char)0xff, '"', '\n', '\\'};
    value.type = XX_VAR_TYPE_STRING_VIEW; value.val.str.ptr = bytes; value.val.str.len = sizeof(bytes);
    if (!emit(output, "raw-string", &value)) return 7;
    value.type = XX_VAR_TYPE_BYTES; value.val.bytes.data = (uint8_t *)bytes; value.val.bytes.size = sizeof(bytes);
    if (!emit(output, "raw-bytes", &value)) return 8;
    wchar_t *units = calloc(65539, sizeof(wchar_t));
    if (!units) return 9;
    for (uint32_t i = 0; i <= 65535; i++) units[i] = (wchar_t)i;
    units[65536] = 0x1F916; units[65537] = 0x110000; units[65538] = (wchar_t)UINT32_MAX;
    value.type = XX_VAR_TYPE_WSTRING_VIEW; value.val.wstr.ptr = units; value.val.wstr.len = 65539;
    if (!emit(output, "all-units", &value)) return 10;
    free(units);
    value.type = XX_VAR_TYPE_PTR; value.val.ptr = (void *)(uintptr_t)0x1234;
    if (!emit(output, "opaque-pointer", &value)) return 11;
    value.type = XX_VAR_TYPE_NONE;
    if (!emit(output, "absent", &value)) return 12;
    if (fclose(output)) return 13;
    structures_context context = {0};
    if (file_begin("fixture", "PE32", MAX_INPUT_BYTES + 1, &context) || !context.request.failure) return 14;
    structures_context report_limit = {0}; report_limit.rows.failed = true;
    if (!stopped(NULL, &report_limit) || !report_limit.request.failure || !strstr(report_limit.request.failure, "16 MiB")) return 16;
    json_report report = {0}; xx_buf_init(&report.buffer);
    value.type = 123456; native_value(&report, &value);
    if (!report.failed) return 14;
    xx_buf_free(&report.buffer); xx_buf_init(&report.buffer); report.failed = false;
    value.type = XX_VAR_TYPE_BYTES_VIEW; value.val.bytes.data = (uint8_t *)bytes; value.val.bytes.size = MAX_TEXT_UNITS + 1U;
    native_value(&report, &value);
    if (!report.failed) return 15;
    xx_buf_free(&report.buffer);
    puts("Native variant serializer boundaries passed; independent byte/Unicode reconstruction follows.");
    return 0;
}
