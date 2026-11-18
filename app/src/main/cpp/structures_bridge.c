// SPDX-License-Identifier: GPL-3.0-only
/* Borrowed CDiE DataStructs are detached synchronously. No format parser here. */
#include "records_json.h"
#include "app/cdie_file_info.h"
#include <limits.h>
#include <sys/stat.h>
#include <time.h>
#include <wchar.h>

#define MAX_ROWS 10000U
#define MAX_TEXT_UNITS (512U * 1024U)
#define MAX_INPUT_BYTES (256LL * 1024LL * 1024LL)
#define KNOWN_PROPERTIES 0xFFFU

typedef struct structures_context {
    json_report rows;
    JNIEnv *env;
    jobject stop;
    jmethodID poll;
    struct timespec started;
    const char *failure;
    size_t count, structures, fields, parent_index;
    xx_data_struct parent;
    int64_t file_size;
    char file_type[128];
    bool in_structure;
} structures_context;

static bool stopped(const xx_pd_struct *progress, void *user) {
    (void)progress;
    structures_context *context = user;
    if (context->failure) return true;
    if ((*context->env)->ExceptionCheck(context->env)) return true;
    if ((*context->env)->CallBooleanMethod(context->env, context->stop, context->poll)) {
        context->failure = "Structure request cancelled";
        return true;
    }
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0 || now.tv_sec - context->started.tv_sec >= 30) {
        context->failure = "Structure request exceeded its 30-second cooperative deadline";
        return true;
    }
    return context->rows.failed;
}

/* Emit JSON UTF-16 escapes directly, avoiding lossy wchar_t -> UTF-8 conversion.
 * The optional unitsHex representation retains even invalid 32-bit code points. */
static void wide_string(json_report *report, const wchar_t *value, size_t length) {
    literal(report, "\"");
    for (size_t i = 0; i < length && !report->failed; i++) {
        uint32_t code = (uint32_t)value[i];
        if (code <= 0xFFFFU) number_format(report, "\\u%04X", code);
        else if (code <= 0x10FFFFU) {
            code -= 0x10000U;
            number_format(report, "\\u%04X\\u%04X", 0xD800U + (code >> 10), 0xDC00U + (code & 1023U));
        } else literal(report, "\\uFFFD");
    }
    literal(report, "\"");
}

static size_t wide_length(json_report *report, const wchar_t *value) {
    if (!value) return 0;
    size_t length = wcsnlen(value, MAX_TEXT_UNITS + 1U);
    if (length > MAX_TEXT_UNITS) report->failed = true;
    return length;
}

static void wide_field(json_report *report, const char *key, const wchar_t *value) {
    number_format(report, ",\"%s\":", key);
    if (!value) { literal(report, "null"); return; }
    wide_string(report, value, wide_length(report, value));
}

static void hex_bytes(json_report *report, const uint8_t *bytes, size_t size) {
    if (size > MAX_TEXT_UNITS || (!bytes && size)) { report->failed = true; return; }
    literal(report, "\"");
    for (size_t i = 0; i < size && !report->failed; i++) number_format(report, "%02X", bytes[i]);
    literal(report, "\"");
}

static void wide_units(json_report *report, const wchar_t *value, size_t size) {
    if (size > MAX_TEXT_UNITS || (!value && size)) { report->failed = true; return; }
    literal(report, "\"");
    for (size_t i = 0; i < size && !report->failed; i++) number_format(report, "%08X", (uint32_t)value[i]);
    literal(report, "\"");
}

static void native_value(json_report *report, const xx_var *value) {
    static const char *const types[] = {"none", "int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64",
        "float", "double", "bool", "string", "wstring", "bytes", "string-view", "wstring-view", "bytes-view", "pointer"};
    number_format(report, ",\"nativeValue\":{\"typeId\":%u,\"type\":", value->type);
    if (value->type >= sizeof(types) / sizeof(types[0])) { report->failed = true; return; }
    string(report, types[value->type]);
    literal(report, ",\"value\":");
    switch (value->type) {
        case XX_VAR_TYPE_NONE: literal(report, "null"); break;
        case XX_VAR_TYPE_INT8: number_format(report, "\"%lld\"", I64(value->val.i8)); break;
        case XX_VAR_TYPE_INT16: number_format(report, "\"%lld\"", I64(value->val.i16)); break;
        case XX_VAR_TYPE_INT32: number_format(report, "\"%lld\"", I64(value->val.i32)); break;
        case XX_VAR_TYPE_INT64: number_format(report, "\"%lld\"", I64(value->val.i64)); break;
        case XX_VAR_TYPE_UINT8: number_format(report, "\"%llu\"", U64(value->val.u8)); break;
        case XX_VAR_TYPE_UINT16: number_format(report, "\"%llu\"", U64(value->val.u16)); break;
        case XX_VAR_TYPE_UINT32: number_format(report, "\"%llu\"", U64(value->val.u32)); break;
        case XX_VAR_TYPE_UINT64: number_format(report, "\"%llu\"", U64(value->val.u64)); break;
        case XX_VAR_TYPE_FLOAT: {
            uint32_t bits; memcpy(&bits, &value->val.f, sizeof(bits));
            number_format(report, "\"%.9g\",\"ieeeBitsHex\":\"%08X\"", (double)value->val.f, bits); break;
        }
        case XX_VAR_TYPE_DOUBLE: {
            uint64_t bits; memcpy(&bits, &value->val.d, sizeof(bits));
            number_format(report, "\"%.17g\",\"ieeeBitsHex\":\"%016llX\"", value->val.d, U64(bits)); break;
        }
        case XX_VAR_TYPE_BOOL: literal(report, value->val.b ? "true" : "false"); break;
        case XX_VAR_TYPE_STRING: case XX_VAR_TYPE_STRING_VIEW:
            /* Length-delimited bytes preserve invalid UTF-8 and embedded NUL. */
            literal(report, "null,\"bytesHex\":"); hex_bytes(report, (const uint8_t *)value->val.str.ptr, value->val.str.len);
            number_format(report, ",\"lengthBytes\":%llu", U64(value->val.str.len)); break;
        case XX_VAR_TYPE_WSTRING: case XX_VAR_TYPE_WSTRING_VIEW:
            if (value->val.wstr.len > MAX_TEXT_UNITS || (!value->val.wstr.ptr && value->val.wstr.len)) { report->failed = true; break; }
            wide_string(report, value->val.wstr.ptr, value->val.wstr.len);
            literal(report, ",\"wcharUnitsHex\":"); wide_units(report, value->val.wstr.ptr, value->val.wstr.len);
            number_format(report, ",\"lengthNativeUnits\":%llu", U64(value->val.wstr.len)); break;
        case XX_VAR_TYPE_BYTES: case XX_VAR_TYPE_BYTES_VIEW:
            literal(report, "null,\"bytesHex\":"); hex_bytes(report, value->val.bytes.data, value->val.bytes.size);
            number_format(report, ",\"lengthBytes\":%llu", U64(value->val.bytes.size)); break;
        case XX_VAR_TYPE_PTR:
            literal(report, "null,\"opaquePointerPresent\":"); literal(report, value->val.ptr ? "true" : "false");
            string_field(report, "limitation", "Native process pointer is not input evidence and is never dereferenced or exported"); break;
    }
    literal(report, "}");
}

static int start_row(structures_context *context) {
    if (stopped(NULL, context)) return 0;
    if (context->count >= MAX_ROWS) { context->failure = "Structure report exceeds 10,000 combined structure/field rows; no partial snapshot returned"; return 0; }
    number_format(&context->rows, "%s{\"index\":%llu", context->count ? "," : "", U64(context->count));
    context->count++;
    return !context->rows.failed;
}

static void signed_field(json_report *report, const char *key, int64_t value) {
    number_format(report, ",\"%s\":\"%lld\"", key, I64(value));
}

static void known_size(json_report *report, int64_t size) {
    if (size < 0) literal(report, ",\"sizeBytes\":null"); else size_field(report, (uint64_t)size);
}

static int file_begin(const char *path, const char *type, int64_t size, void *user) {
    (void)path;
    structures_context *context = user;
    if (size < 0 || size > MAX_INPUT_BYTES) {
        context->failure = "Structure input changed or exceeds 256 MiB";
        return 0;
    }
    context->file_size = size;
    if (!type || strlen(type) >= sizeof(context->file_type)) return 0;
    memcpy(context->file_type, type, strlen(type) + 1);
    return !stopped(NULL, context);
}

static int structure_begin(const char *name, const xx_data_struct *item, void *user) {
    structures_context *context = user;
    if (context->in_structure || !start_row(context)) return 0;
    json_report *report = &context->rows;
    context->parent = *item; context->parent_index = context->count - 1; context->in_structure = true; context->structures++;
    string_field(report, "name", name); string_field(report, "rowType", "structure");
    literal(report, ",\"parentStructureIndex\":null");
    bool valid = item->offset >= 0 && item->total_size >= 0 && item->offset <= context->file_size && item->total_size <= context->file_size - item->offset;
    physical_field(report, "physicalOffset", valid ? item->offset : -1);
    address_field(report, "virtualAddress", (uint64_t)item->address); known_size(report, item->total_size);
    number_format(report, ",\"nativeStructureId\":%u,\"nativeStructureType\":%d,\"count\":\"%llu\",\"physicalRangeValid\":%s",
        item->id, item->type, U64(item->count), valid ? "true" : "false");
    signed_field(report, "nativeOffset", item->offset); signed_field(report, "nativeAddress", item->address);
    signed_field(report, "nativeEntrySize", item->entry_size); signed_field(report, "nativeTotalSize", item->total_size);
    literal(report, "}"); return !report->failed;
}

static int field_visit(const xx_data_struct_record *field, void *user) {
    structures_context *context = user;
    if (!context->in_structure || !start_row(context)) return 0;
    json_report *report = &context->rows;
    context->fields++;
    literal(report, ",\"name\":"); wide_string(report, field->name, wide_length(report, field->name));
    string_field(report, "rowType", "field");
    number_format(report, ",\"parentStructureIndex\":%llu", U64(context->parent_index));
    wide_field(report, "nativeName", field->name);
    wide_field(report, "nativeType", field->type); wide_field(report, "rawValue", field->raw_value);
    wide_field(report, "displayValue", field->display_value); wide_field(report, "nativeComment", field->comment);
    signed_field(report, "relativeOffset", field->offset); signed_field(report, "nativeSize", field->size);
    bool parent_valid = context->parent.offset >= 0 && context->parent.total_size >= 0
        && context->parent.offset <= context->file_size && context->parent.total_size <= context->file_size - context->parent.offset;
    bool within = parent_valid && field->offset >= 0 && field->size >= 0 && context->parent.total_size >= 0
        && field->offset <= context->parent.total_size && field->size <= context->parent.total_size - field->offset;
    int64_t physical = within && context->parent.offset >= 0 && field->offset <= INT64_MAX - context->parent.offset
        ? context->parent.offset + field->offset : -1;
    bool valid = physical >= 0 && physical <= context->file_size && field->size <= context->file_size - physical;
    physical_field(report, "physicalOffset", valid ? physical : -1);
    uint64_t address = (uint64_t)context->parent.address;
    if (!within || address == UINT64_MAX || (uint64_t)field->offset > UINT64_MAX - address) address = UINT64_MAX;
    else address += (uint64_t)field->offset;
    address_field(report, "virtualAddress", address); known_size(report, field->size);
    number_format(report, ",\"physicalRangeValid\":%s,\"properties\":%u,\"unknownPropertyBits\":%u,\"propertyNames\":[",
        valid ? "true" : "false", field->property, field->property & ~KNOWN_PROPERTIES);
    static const char *const names[] = {"identifier", "virtual-address", "file-offset", "size", "count", "pointer", "flags", "timestamp", "string", "reserved", "signature", "checksum"};
    bool comma = false;
    for (unsigned i = 0; i < 12; i++) if (field->property & (1U << i)) {
        if (comma) literal(report, ","); string(report, names[i]); comma = true;
    }
    literal(report, "]"); native_value(report, &field->value);
    /* These exact raw wchar units additionally retain malformed native text. */
    literal(report, ",\"nativeTextUnits\":{");
    const wchar_t *texts[] = {field->name, field->type, field->raw_value, field->display_value, field->comment};
    const char *keys[] = {"nativeName", "nativeType", "rawValue", "displayValue", "nativeComment"};
    for (size_t i = 0; i < 5; i++) {
        if (i) literal(report, ","); string(report, keys[i]); literal(report, ":");
        if (!texts[i]) literal(report, "null"); else wide_units(report, texts[i], wide_length(report, texts[i]));
    }
    literal(report, "}}"); return !report->failed;
}

static int structure_end(void *user) {
    structures_context *context = user;
    if (!context->in_structure) return 0;
    context->in_structure = false;
    return !stopped(NULL, context);
}

static void report_failure(json_report *report, const char *message) {
    xx_buf_clear(&report->buffer); report->failed = false;
    literal(report, "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":false,\"kind\":\"structures\",\"error\":");
    string(report, message); literal(report, "}");
}

JNIEXPORT jbyteArray JNICALL Java_org_diemobile_Engine_structuresNative(JNIEnv *env, jclass cls,
    jstring path, jstring selector, jint inventory, jint slice, jobject stop) {
    (void)cls;
    structures_context context = {0}; context.env = env; context.stop = stop;
    xx_buf_init(&context.rows.buffer);
    json_report output = {0}; xx_buf_init(&output.buffer);
    const char *target = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    const char *query = selector ? (*env)->GetStringUTFChars(env, selector, NULL) : NULL;
    char *error = NULL;
    struct stat status;
    jclass stop_class = stop ? (*env)->GetObjectClass(env, stop) : NULL;
    context.poll = stop_class ? (*env)->GetMethodID(env, stop_class, "getAsBoolean", "()Z") : NULL;
    if (stop_class) (*env)->DeleteLocalRef(env, stop_class);
    if (clock_gettime(CLOCK_MONOTONIC, &context.started) != 0) context.failure = "Cannot read monotonic deadline clock";
    else if (!target || !query || !context.poll) context.failure = "Structure request parameters unavailable";
    else if (inventory < 0 || inventory > 1 || slice < -1 || slice >= 4096 || strlen(query) > 4096) context.failure = "Invalid structure selector, inventory or slice";
    else if (stat(target, &status) || !S_ISREG(status.st_mode) || status.st_size < 0 || status.st_size > MAX_INPUT_BYTES) context.failure = "Structures require a regular input of at most 256 MiB";
    else {
        xx_pd_struct progress = xx_pd_init();
        xx_pd_observer previous = xx_pd_set_observer(&progress, stopped, &context);
        const cdie_file_info_visitor visitor = {file_begin, structure_begin, field_visit, structure_end};
        int ok = cdie_file_info_visit_slice(target, slice, *query ? query : "Info", inventory, &visitor, &context, &progress, &error);
        xx_pd_set_observer(previous.progress, previous.callback, previous.user_data);
        if (!ok && !context.failure) context.failure = error ? error : "Native structure reader failed";
    }
    if (context.rows.failed && !context.failure) context.failure = "Structure report exceeds 16 MiB, text/value limits or allocation failed; no partial snapshot returned";
    if (context.failure) report_failure(&output, context.failure);
    else {
        literal(&output, "{\"schema\":\"org.diemobile.native-records\",\"schemaVersion\":1,\"ok\":true,\"kind\":\"structures\",\"scope\":\"complete-snapshot\",\"fileType\":");
        string(&output, context.file_type);
        number_format(&output, ",\"fileSizeBytes\":%lld,\"rowCount\":%llu,\"metadata\":{\"adapterVersion\":1,\"referencePolicy\":\"reviewed-field-domains-v1\",\"selector\":", I64(context.file_size), U64(context.count));
        string(&output, *query ? query : "Info");
        number_format(&output, ",\"inventoryOnly\":%s,\"requestedMachOSliceIndex\":%d,\"structureCount\":%llu,\"fieldCount\":%llu,\"maxRows\":%u,\"maxReportBytes\":%u,\"maxInputBytes\":%lld,\"maxTextNativeUnits\":%u,\"nativeWcharBits\":%u,\"deadlineSeconds\":30",
            inventory ? "true" : "false", slice, U64(context.structures), U64(context.fields), MAX_ROWS, REPORT_LIMIT, I64(MAX_INPUT_BYTES), MAX_TEXT_UNITS, (unsigned)(sizeof(wchar_t) * CHAR_BIT));
        string_field(&output, "offsetDomain", "Parent structure offsets are absolute in the whole input; field relativeOffset is relative to that structure. physicalOffset requires both parent and whole-input bounds.");
        string_field(&output, "slicePolicy", "Explicit zero-based fat Mach-O slice, else native host architecture if present, else first slice. Other slices remain RAW_DATA; thin formats ignore the index.");
        string_field(&output, "valueSemantics", "Semantic flags describe declared values. File-offset/VA references do not prove validity, execution or verification. Pointer-only fields have no inferred address domain.");
        string_field(&output, "coverage", "Complete returned DataStructs for the captured selector and chosen slice, within explicit budgets; unsupported readers and exceeded budgets return failure without rows.");
        literal(&output, "},\"rows\":["); (void)write_json(&output, (const char *)context.rows.buffer.data, context.rows.buffer.size); literal(&output, "]}");
        if (output.failed) report_failure(&output, "Complete structure report exceeds 16 MiB or allocation failed; no partial snapshot returned");
    }
    if (query) (*env)->ReleaseStringUTFChars(env, selector, query);
    if (target) (*env)->ReleaseStringUTFChars(env, path, target);
    xx_rt_free(error);
    jbyteArray result = NULL;
    if (!output.failed && xx_buf_ok(&output.buffer) && !(*env)->ExceptionCheck(env)) {
        result = (*env)->NewByteArray(env, (jsize)output.buffer.size);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)output.buffer.size, (const jbyte *)output.buffer.data);
    }
    xx_buf_free(&context.rows.buffer); xx_buf_free(&output.buffer);
    return result;
}
