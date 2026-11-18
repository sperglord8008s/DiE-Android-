// SPDX-License-Identifier: GPL-3.0-only
#ifndef DIE_RECORDS_JSON_H
#define DIE_RECORDS_JSON_H
#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <xxfclib/buf/xx_buf.h>
#include <xxfclib/terminal/xx_terminal.h>
#define REPORT_LIMIT (16U * 1024U * 1024U)
#define U64(n) ((unsigned long long)(n))
#define I64(n) ((long long)(n))

typedef struct json_report {
    xx_buf_t buffer;
    bool failed;
} json_report;

static xxfc_status_t write_json(void *context, const char *data, size_t size) {
    json_report *report = context;
    if (report->failed || size > REPORT_LIMIT - report->buffer.size) {
        report->failed = true;
        return XXFC_ERR_OUT_OF_BOUNDS;
    }
    xx_buf_append(&report->buffer, data, size);
    if (!xx_buf_ok(&report->buffer)) {
        report->failed = true;
        return XXFC_ERR_OUT_OF_MEMORY;
    }
    return XXFC_OK;
}

static void literal(json_report *report, const char *text) {
    (void)write_json(report, text, strlen(text));
}

static void number_format(json_report *report, const char *format, ...) {
    char text[256];
    va_list arguments;
    va_start(arguments, format);
    int size = vsnprintf(text, sizeof(text), format, arguments);
    va_end(arguments);
    if (size < 0 || (size_t)size >= sizeof(text)) report->failed = true;
    else (void)write_json(report, text, (size_t)size);
}

static void string(json_report *report, const char *value) {
    xx_terminal_writer writer;
    xx_terminal_writer_init(&writer, write_json, report);
    if (xx_terminal_writer_json_string(&writer, value ? value : "") != XXFC_OK) report->failed = true;
}

static void string_field(json_report *report, const char *key, const char *value) {
    number_format(report, ",\"%s\":", key); string(report, value);
}

static void physical_field(json_report *report, const char *key, int64_t offset) {
    number_format(report, ",\"%s\":", key);
    if (offset < 0) literal(report, "null");
    else number_format(report, "%lld", I64(offset));
}

static void address_field(json_report *report, const char *key, uint64_t address) {
    number_format(report, ",\"%s\":", key);
    if (address == UINT64_MAX) literal(report, "null");
    else number_format(report, "\"0x%llX\"", U64(address));
}

static void size_field(json_report *report, uint64_t size) {
    number_format(report, ",\"sizeBytes\":\"%llu\"", U64(size));
}

#endif
