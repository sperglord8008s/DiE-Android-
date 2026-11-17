// SPDX-License-Identifier: GPL-3.0-only
/* Detached typed snapshots over the pinned CDiE readers. No parser duplication.
 * Engine.java serializes these calls with every other native operation. */
#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <xxfclib/buf/xx_buf.h>
#include <xxfclib/terminal/xx_terminal.h>
#include "app/cdie_memory_map.h"
#include "app/cdie_imports.h"
#include "app/cdie_exports.h"
#include "app/cdie_symbols.h"
#include "app/cdie_resources.h"
#include "app/cdie_disasm.h"

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

static const char *kind_name(int tool) {
    static const char *const names[] = {"unknown", "memory-map", "imports", "exports", "symbols", "resources", "disassembly"};
    return tool >= 1 && tool <= 6 ? names[tool] : names[0];
}

static void begin(json_report *report, int tool, const char *file_type, uint64_t file_size, size_t count) {
    literal(report, "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":true,\"kind\":");
    string(report, kind_name(tool)); string_field(report, "fileType", file_type);
    string_field(report, "scope", tool == 6 ? "instruction-page" : "complete-snapshot");
    number_format(report, ",\"fileSizeBytes\":%llu,\"rowCount\":%llu,\"metadata\":{", U64(file_size), U64(count));
}

static void rows(json_report *report) { literal(report, "},\"rows\":["); }
static void row(json_report *report, size_t index) { number_format(report, "%s{\"index\":%llu", index ? "," : "", U64(index)); }
static void finish(json_report *report) { literal(report, "]}"); }

static void failure(json_report *report, int tool, const char *message) {
    xx_buf_clear(&report->buffer); report->failed = false;
    literal(report, "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":false,\"kind\":");
    string(report, kind_name(tool)); string_field(report, "error", message); literal(report, "}");
}

static void map_report(json_report *report, const char *path, int mode, xx_pd_struct *progress, char **error) {
    cdie_memory_map_data *data = NULL;
    if (mode < 0 || mode > 3) { failure(report, 1, "Unsupported memory-map mode"); return; }
    const char *map_mode = mode == 1 ? "Sections" : mode == 2 ? "Segments" : mode == 3 ? "Data" : NULL;
    if (!cdie_memory_map_read_mode(path, mode == 3 ? "Binary" : NULL, map_mode, progress, &data, error)) return;
    begin(report, 1, data->file_type, data->file_size, data->count);
    literal(report, "\"mode\":"); string(report, data->mode);
    string_field(report, "architecture", data->architecture); string_field(report, "bitness", data->bitness);
    string_field(report, "endianness", data->endianness);
    address_field(report, "moduleVirtualAddress", data->module_address);
    address_field(report, "entryVirtualAddress", data->entry_point_address);
    number_format(report, ",\"availableModes\":%u,\"maxRecords\":%u,\"nameMaxBytes\":%u", data->available_modes,
        CDIE_MEMORY_MAP_MAX_RECORDS, XX_MEMORY_RECORD_NAME_SIZE - 1);
    rows(report);
    for (size_t i = 0; i < data->count && !report->failed; i++) {
        const xx_memory_record *record = &data->records[i];
        row(report, i); string_field(report, "name", record->name);
        // The upstream map name is a bounded byte string. Preserve raw bytes
        // alongside JSON's replacement of invalid UTF-8, rather than losing them.
        literal(report, ",\"nameBytesHex\":\"");
        for (const unsigned char *p = (const unsigned char *)record->name; *p; p++) number_format(report, "%02X", *p);
        literal(report, "\"");
        physical_field(report, "physicalOffset", record->offset); address_field(report, "virtualAddress", record->address);
        size_field(report, (uint64_t)record->size);
        number_format(report, ",\"filePartFlags\":%u,\"filePartNumber\":%d,\"nativeIndex\":%d,\"isVirtual\":%s,\"isInvisible\":%s}",
            record->file_part, record->file_part_number, record->index,
            record->is_virtual ? "true" : "false", record->is_invisible ? "true" : "false");
    }
    finish(report); xx_rt_free(data);
}

static void imports_report(json_report *report, const char *path, xx_pd_struct *progress, char **error) {
    cdie_imports_data *data = NULL;
    if (!cdie_imports_read(path, progress, &data, error)) return;
    begin(report, 2, data->file_type, data->file_size, data->count);
    number_format(report, "\"maxRecords\":%u,\"maxNameBytes\":%u", CDIE_IMPORTS_MAX_RECORDS, CDIE_IMPORTS_MAX_NAME);
    rows(report);
    for (size_t i = 0; i < data->count && !report->failed; i++) {
        const xx_import_record *record = &data->records[i];
        row(report, i); string_field(report, "name", record->name); string_field(report, "library", record->library_name);
        physical_field(report, "physicalOffset", record->offset); address_field(report, "virtualAddress", record->address);
        number_format(report, ",\"sizeBytes\":null,\"ordinal\":\"%llu\",\"hint\":%u,\"byOrdinal\":%s,\"isDelay\":%s}",
            U64(record->ordinal), record->hint, record->by_ordinal ? "true" : "false", record->is_delay ? "true" : "false");
    }
    finish(report); cdie_imports_free(data);
}

static void exports_report(json_report *report, const char *path, xx_pd_struct *progress, char **error) {
    cdie_exports_data *data = NULL;
    if (!cdie_exports_read(path, progress, &data, error)) return;
    begin(report, 3, data->file_type, data->file_size, data->count);
    number_format(report, "\"maxRecords\":%u,\"maxNameBytes\":%u", CDIE_EXPORTS_MAX_RECORDS, CDIE_EXPORTS_MAX_NAME);
    rows(report);
    for (size_t i = 0; i < data->count && !report->failed; i++) {
        const xx_export_record *record = &data->records[i];
        row(report, i); string_field(report, "name", record->name); string_field(report, "forwarder", record->forwarder);
        physical_field(report, "physicalOffset", record->offset); address_field(report, "virtualAddress", record->address);
        literal(report, ",\"sizeBytes\":null,\"ordinal\":");
        if (record->ordinal == UINT64_MAX) literal(report, "null"); else number_format(report, "\"%llu\"", U64(record->ordinal));
        literal(report, "}");
    }
    finish(report); cdie_exports_free(data);
}

static void symbols_report(json_report *report, const char *path, xx_pd_struct *progress, char **error) {
    cdie_symbols_data *data = NULL;
    if (!cdie_symbols_read(path, progress, &data, error)) return;
    begin(report, 4, data->file_type, data->file_size, data->count);
    number_format(report, "\"maxRecords\":%u,\"maxNameBytes\":%u", CDIE_SYMBOLS_MAX_RECORDS, CDIE_SYMBOLS_MAX_NAME);
    rows(report);
    for (size_t i = 0; i < data->count && !report->failed; i++) {
        const xx_symbol_record *record = &data->records[i];
        row(report, i); string_field(report, "name", record->name); string_field(report, "fileName", record->file_name);
        physical_field(report, "physicalOffset", record->offset); address_field(report, "virtualAddress", record->address);
        size_field(report, record->size);
        // value is raw symbol metadata, not an address sentinel.
        number_format(report, ",\"value\":\"0x%llX\"", U64(record->value));
        physical_field(report, "recordOffset", record->record_offset);
        number_format(report, ",\"tableIndex\":");
        if (record->table_index == UINT64_MAX) literal(report, "null"); else number_format(report, "\"%llu\"", U64(record->table_index));
        number_format(report, ",\"kind\":%d,\"binding\":%d,\"sectionNumber\":%d,\"nativeType\":%u,\"storageClass\":%u,\"auxiliaryCount\":%u",
            record->kind, record->binding, record->section_number, record->native_type, record->native_storage_class, record->auxiliary_count);
        number_format(report, ",\"isUndefined\":%s,\"isAbsolute\":%s,\"isDebug\":%s}",
            record->is_undefined ? "true" : "false", record->is_absolute ? "true" : "false", record->is_debug ? "true" : "false");
    }
    finish(report); cdie_symbols_free(data);
}

static void resources_report(json_report *report, const char *path, xx_pd_struct *progress, char **error) {
    cdie_resources_data *data = NULL;
    if (!cdie_resources_read(path, progress, &data, error)) return;
    begin(report, 5, data->file_type, data->file_size, data->count);
    number_format(report, "\"maxRecords\":%u,\"maxNameBytes\":%u", CDIE_RESOURCES_MAX_RECORDS, CDIE_RESOURCES_MAX_NAME);
    rows(report);
    for (size_t i = 0; i < data->count && !report->failed; i++) {
        const xx_resource_record *record = &data->records[i];
        row(report, i); string_field(report, "name", record->name); string_field(report, "typeName", record->type_name);
        string_field(report, "languageName", record->language_name);
        physical_field(report, "physicalOffset", record->offset); literal(report, ",\"virtualAddress\":null");
        if (record->size < 0) literal(report, ",\"sizeBytes\":null"); else size_field(report, (uint64_t)record->size);
        number_format(report, ",\"typeId\":%u,\"nameId\":%u,\"languageId\":%u,\"codePage\":%u}",
            record->type_id, record->name_id, record->language_id, record->code_page);
    }
    finish(report); cdie_resources_free(data);
}

static void disasm_report(json_report *report, const char *path, int mode, int64_t offset, int syntax, xx_pd_struct *progress, char **error) {
    cdie_disasm_context *context = NULL;
    const cdie_disasm_page *page = NULL;
    cdie_memory_map_data *map_data = NULL;
    xx_memory_map map = {0};
    char *map_error = NULL;
    if (offset < -1 || mode < 0 || (size_t)mode >= cdie_disasm_mode_count()) { failure(report, 6, "Invalid disassembly offset or mode"); return; }
    if (!cdie_disasm_open(path, progress, &context, error)) return;
    (void)cdie_disasm_set_syntax(context, syntax);
    const cdie_disasm_info *info = cdie_disasm_get_info(context);
    uint64_t start = offset == -1 ? info->initial_offset : (uint64_t)offset;
    cdie_disasm_status status = cdie_disasm_load(context, start, (size_t)mode, &page);
    if (status != CDIE_DISASM_OK || !page) {
        failure(report, 6, status == CDIE_DISASM_UNSUPPORTED ? "No automatic architecture; select an instruction mode" : "Cannot decode this offset/mode");
        cdie_disasm_close(context); return;
    }
    if (cdie_memory_map_read(path, progress, &map_data, &map_error)) {
        map.records = map_data->records; map.record_count = map_data->count;
    }
    begin(report, 6, map_data ? map_data->file_type : "Unknown", info->file_size, page->count);
    number_format(report, "\"pageOffset\":%llu,\"nextOffset\":", U64(page->offset));
    if (page->next_offset > page->offset && page->next_offset < info->file_size) number_format(report, "%llu", U64(page->next_offset));
    else literal(report, "null");
    number_format(report, ",\"requestedMode\":%d,\"detectedMode\":%d,\"maxRecords\":%u,\"pageByteLimit\":%u",
        mode, info->detected_mode, CDIE_DISASM_MAX_ROWS, CDIE_DISASM_PAGE_BYTES);
    string_field(report, "mode", cdie_disasm_mode_name(mode ? (size_t)mode : info->detected_mode));
    string_field(report, "syntax", syntax ? "AT&T" : "Intel / native ARM");
    string_field(report, "description", info->description);
    string_field(report, "mappingError", map_error);
    rows(report);
    for (size_t i = 0; i < page->count && !report->failed; i++) {
        const cdie_disasm_row *record = &page->rows[i];
        uint64_t address = map_data ? xx_memory_map_offset_to_address(&map, (int64_t)record->offset) : UINT64_MAX;
        bool mapped = address != UINT64_MAX && address == record->address;
        row(report, i); string_field(report, "name", record->instruction);
        string_field(report, "bytes", record->bytes); string_field(report, "instruction", record->instruction); string_field(report, "comment", record->comment);
        physical_field(report, "physicalOffset", (int64_t)record->offset); size_field(report, record->size);
        address_field(report, "virtualAddress", mapped ? address : UINT64_MAX);
        // CDiE puts file offsets into address when unmapped. Export the actual
        // decoder operand address separately; never mislabel it as a VA.
        address_field(report, "decodeAddress", record->address);
        string_field(report, "addressDomain", mapped ? "virtual" : map_data && address == UINT64_MAX ? "file-offset" : "unknown");
        literal(report, "}");
    }
    finish(report); xx_rt_free(map_error); xx_rt_free(map_data); cdie_disasm_close(context);
}

JNIEXPORT jbyteArray JNICALL Java_org_diemobile_Engine_recordsNative(JNIEnv *env, jclass cls, jstring path, jint tool, jint mode, jlong offset, jint syntax) {
    (void)cls;
    json_report report = {0}; xx_buf_init(&report.buffer);
    xx_pd_struct progress = xx_pd_init();
    const char *target = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    char *error = NULL;
    if (!target) {
        failure(&report, tool, "Input path unavailable");
    } else if (tool != 1 && tool != 6 && mode != 0) {
        failure(&report, tool, "This table does not support a mode selector");
    } else switch (tool) {
        case 1: map_report(&report, target, mode, &progress, &error); break;
        case 2: imports_report(&report, target, &progress, &error); break;
        case 3: exports_report(&report, target, &progress, &error); break;
        case 4: symbols_report(&report, target, &progress, &error); break;
        case 5: resources_report(&report, target, &progress, &error); break;
        case 6: disasm_report(&report, target, mode, offset, syntax, &progress, &error); break;
        default: failure(&report, tool, "Unsupported structured table"); break;
    }
    if (target) (*env)->ReleaseStringUTFChars(env, path, target);
    if (report.failed) failure(&report, tool, "Structured report exceeds 16 MiB or allocation failed; no partial snapshot returned");
    else if (error || !report.buffer.size) failure(&report, tool, error ? error : "Native table unavailable for this input");
    xx_rt_free(error);
    jbyteArray output = NULL;
    if (!report.failed && xx_buf_ok(&report.buffer) && !(*env)->ExceptionCheck(env)) {
        output = (*env)->NewByteArray(env, (jsize)report.buffer.size);
        if (output) (*env)->SetByteArrayRegion(env, output, 0, (jsize)report.buffer.size, (const jbyte *)report.buffer.data);
    }
    xx_buf_free(&report.buffer); return output;
}
