// SPDX-License-Identifier: GPL-3.0-only
/* Read-only libmpv probe. No command, option, URI or path comes from the file. */
#define _POSIX_C_SOURCE 200809L
#include <jni.h>
#include <mpv/client.h>
#include <mpv/stream_cb.h>
#include <errno.h>
#include <inttypes.h>
#include <math.h>
#include <locale.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define INPUT_LIMIT (256LL * 1024 * 1024)
#define READ_LIMIT (32ULL * 1024 * 1024)
#define OUTPUT_LIMIT (32U * 1024 * 1024)
#define NODE_LIMIT 100000U
#define PROPERTY_LIMIT 4096
#define TEXT_LIMIT (8U * 1024 * 1024)
#define DEADLINE_MS 25000
#define HEADERS_SOURCE "0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6"
#define INPUT_URI "dieblob://selected"

typedef struct {
    char *data;
    size_t length, capacity;
    unsigned nodes;
    const char *error;
} Json;

static void append(Json *json, const char *text, size_t count) {
    if (json->error) return;
    if (count > OUTPUT_LIMIT - json->length) {
        json->error = "MPV report exceeds the 32 MiB output budget; no partial report returned";
        return;
    }
    size_t needed = json->length + count + 1;
    if (needed > json->capacity) {
        size_t capacity = needed < 4096 ? 4096 : needed * 2;
        if (capacity > OUTPUT_LIMIT + 1U) capacity = OUTPUT_LIMIT + 1U;
        char *next = realloc(json->data, capacity);
        if (!next) { json->error = "Unable to allocate the MPV report"; return; }
        json->data = next;
        json->capacity = capacity;
    }
    memcpy(json->data + json->length, text, count);
    json->length += count;
    json->data[json->length] = 0;
}
static void literal(Json *json, const char *text) { append(json, text, strlen(text)); }
static void number(Json *json, const char *format, ...) {
    char buffer[4096];
    va_list args;
    va_start(args, format);
    int count = vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    if (count < 0 || count >= (int)sizeof(buffer)) json->error = "MPV numeric rendering failed";
    else append(json, buffer, (size_t)count);
}
static size_t text_length(Json *json, const char *text) {
    if (!text) return 0;
    size_t count = strnlen(text, TEXT_LIMIT + 1U);
    if (count > TEXT_LIMIT) { json->error = "MPV string exceeds the 8 MiB value budget"; return 0; }
    return count;
}
/* JSON strings are ASCII escaped. Invalid UTF-8 is replaced visibly; every
 * engine-provided string/map key also retains its exact pre-decoding bytes. */
static void quoted(Json *json, const char *text, size_t count) {
    literal(json, "\"");
    for (size_t i = 0; i < count && !json->error;) {
        unsigned char byte = (unsigned char)text[i++];
        if (byte == '"' || byte == '\\') { char pair[2] = {'\\', (char)byte}; append(json, pair, 2); }
        else if (byte >= 0x20 && byte < 0x7f) append(json, (const char *)&byte, 1);
        else {
            uint32_t value = byte;
            if (byte >= 0x80) {
                unsigned extra = byte >= 0xc2 && byte <= 0xdf ? 1 : byte >= 0xe0 && byte <= 0xef ? 2 : byte >= 0xf0 && byte <= 0xf4 ? 3 : 0;
                value = extra ? byte & ((1U << (6 - extra)) - 1U) : 0xfffd;
                bool valid = extra && extra <= count - i;
                for (unsigned k = 0; valid && k < extra; k++) {
                    unsigned char next = (unsigned char)text[i + k];
                    if ((next & 0xc0) != 0x80) valid = false;
                    else value = (value << 6) | (next & 0x3f);
                }
                if (valid && (value < (extra == 1 ? 0x80U : extra == 2 ? 0x800U : 0x10000U) || value > 0x10ffffU || (value >= 0xd800 && value <= 0xdfff))) valid = false;
                if (valid) i += extra; else value = 0xfffd;
            }
            if (value <= 0xffff) number(json, "\\u%04" PRIx32, value);
            else { value -= 0x10000; number(json, "\\u%04" PRIx32 "\\u%04" PRIx32, 0xd800 + (value >> 10), 0xdc00 + (value & 0x3ff)); }
        }
    }
    literal(json, "\"");
}
static void string(Json *json, const char *text) {
    if (!text) literal(json, "null"); else quoted(json, text, text_length(json, text));
}
static void hex(Json *json, const void *bytes, size_t count) {
    const unsigned char *data = bytes;
    const char digits[] = "0123456789abcdef";
    literal(json, "\"");
    for (size_t i = 0; i < count && !json->error; i++) {
        char pair[2] = {digits[data[i] >> 4], digits[data[i] & 15]};
        append(json, pair, 2);
    }
    literal(json, "\"");
}
static void node(Json *json, const mpv_node *value, unsigned depth) {
    if (json->error) return;
    if (depth > 64 || ++json->nodes > NODE_LIMIT) { json->error = "MPV node tree exceeds depth 64 or 100,000 nodes"; return; }
    number(json, "{\"format\":%d,", value->format);
    switch (value->format) {
    case MPV_FORMAT_NONE: literal(json, "\"type\":\"none\",\"value\":null"); break;
    case MPV_FORMAT_STRING: {
        size_t count = text_length(json, value->u.string);
        literal(json, "\"type\":\"string\",\"value\":"); string(json, value->u.string);
        literal(json, ",\"utf8Hex\":"); hex(json, value->u.string, count);
        number(json, ",\"byteLength\":%zu", count); break;
    }
    case MPV_FORMAT_FLAG: literal(json, value->u.flag ? "\"type\":\"flag\",\"value\":true" : "\"type\":\"flag\",\"value\":false"); break;
    case MPV_FORMAT_INT64: number(json, "\"type\":\"int64\",\"value\":%" PRId64 ",\"decimal\":\"%" PRId64 "\"", value->u.int64, value->u.int64); break;
    case MPV_FORMAT_DOUBLE: {
        uint64_t bits; memcpy(&bits, &value->u.double_, sizeof(bits));
        literal(json, "\"type\":\"double\",\"value\":");
        if (isfinite(value->u.double_)) number(json, "%.17g", value->u.double_); else literal(json, "null");
        number(json, ",\"ieee754Bits\":\"%016" PRIx64 "\"", bits); break;
    }
    case MPV_FORMAT_NODE_ARRAY:
    case MPV_FORMAT_NODE_MAP: {
        mpv_node_list *list = value->u.list;
        if (!list || list->num < 0 || list->num > (int)NODE_LIMIT || (list->num && (!list->values || (value->format == MPV_FORMAT_NODE_MAP && !list->keys)))) { json->error = "MPV returned an invalid node list"; return; }
        literal(json, value->format == MPV_FORMAT_NODE_MAP ? "\"type\":\"map\",\"children\":[" : "\"type\":\"array\",\"children\":[");
        for (int i = 0; i < list->num && !json->error; i++) {
            if (i) literal(json, ",");
            if (value->format == MPV_FORMAT_NODE_MAP) {
                literal(json, "{\"key\":"); string(json, list->keys[i]);
                literal(json, ",\"keyUtf8Hex\":"); hex(json, list->keys[i], text_length(json, list->keys[i]));
                literal(json, ",\"node\":"); node(json, &list->values[i], depth + 1); literal(json, "}");
            } else node(json, &list->values[i], depth + 1);
        }
        literal(json, "]"); break;
    }
    case MPV_FORMAT_BYTE_ARRAY: {
        mpv_byte_array *array = value->u.ba;
        if (!array || array->size > TEXT_LIMIT || (array->size && !array->data)) { json->error = "MPV returned an invalid or oversized byte array"; return; }
        literal(json, "\"type\":\"bytes\",\"dataHex\":"); hex(json, array->data, array->size);
        number(json, ",\"byteLength\":%zu", array->size); break;
    }
    default: json->error = "MPV returned an unsupported node type; no partial report returned"; return;
    }
    literal(json, "}");
}

static int64_t now_ms(void) {
    struct timespec time; clock_gettime(CLOCK_MONOTONIC, &time);
    return (int64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}
typedef struct {
    int fd;
    int64_t size, deadline;
    atomic_uint_fast64_t bytes, seeks, opens, denied;
    atomic_bool budget_hit;
} Input;
typedef struct { Input *input; int64_t position; atomic_bool cancelled; } Stream;
static int64_t stream_read(void *cookie, char *buffer, uint64_t requested) {
    Stream *stream = cookie; Input *input = stream->input;
    if (atomic_load(&stream->cancelled) || now_ms() > input->deadline) return -1;
    uint64_t available = stream->position < input->size ? (uint64_t)(input->size - stream->position) : 0;
    if (!available || !requested) return 0;
    if (requested > available) requested = available;
    if (requested > 1024 * 1024) requested = 1024 * 1024;
    uint_fast64_t used = atomic_load(&input->bytes);
    for (;;) {
        if (used >= READ_LIMIT) { atomic_store(&input->budget_hit, true); return -1; }
        if (requested > READ_LIMIT - used) requested = READ_LIMIT - used;
        if (atomic_compare_exchange_weak(&input->bytes, &used, used + requested)) break;
    }
    ssize_t count;
    do { count = pread(input->fd, buffer, (size_t)requested, stream->position); } while (count < 0 && errno == EINTR && now_ms() <= input->deadline);
    if (count > 0) stream->position += count;
    atomic_fetch_sub(&input->bytes, requested - (count > 0 ? (uint64_t)count : 0));
    return count;
}
static int64_t stream_seek(void *cookie, int64_t position) {
    Stream *stream = cookie;
    if (atomic_load(&stream->cancelled) || now_ms() > stream->input->deadline || position < 0 || position > stream->input->size) return MPV_ERROR_GENERIC;
    stream->position = position; atomic_fetch_add(&stream->input->seeks, 1); return position;
}
static int64_t stream_size(void *cookie) { return ((Stream *)cookie)->input->size; }
static void stream_close(void *cookie) { free(cookie); }
static void stream_cancel(void *cookie) { atomic_store(&((Stream *)cookie)->cancelled, true); }
static int stream_open(void *data, char *uri, mpv_stream_cb_info *info) {
    Input *input = data;
    if (strcmp(uri, INPUT_URI) != 0 || now_ms() > input->deadline) { atomic_fetch_add(&input->denied, 1); return MPV_ERROR_LOADING_FAILED; }
    Stream *stream = calloc(1, sizeof(*stream));
    if (!stream) return MPV_ERROR_NOMEM;
    stream->input = input; atomic_init(&stream->cancelled, false);
    info->cookie = stream; info->read_fn = stream_read; info->seek_fn = stream_seek;
    info->size_fn = stream_size; info->close_fn = stream_close; info->cancel_fn = stream_cancel;
    atomic_fetch_add(&input->opens, 1); return 0;
}

/* Setting every guard is mandatory. A removed/unsupported option fails closed. */
static const char *const options[][2] = {
    {"config","no"}, {"load-scripts","no"}, {"load-auto-profiles","no"},
    {"autoload-files","no"}, {"access-references","no"}, {"ytdl","no"},
    {"osc","no"}, {"input-default-bindings","no"}, {"input-vo-keyboard","no"},
    {"input-terminal","no"}, {"terminal","no"}, {"hwdec","no"},
    {"vo","null"}, {"ao","null"}, {"pause","yes"}, {"idle","yes"},
    {"keep-open","always"}, {"sid","no"}, {"cache","no"},
    {"demuxer","+lavf"}, {"demuxer-lavf-o","protocol_whitelist=dieblob"},
    {"demuxer-max-bytes","16MiB"}, {"demuxer-max-back-bytes","0"},
    {"demuxer-readahead-secs","0"}, {"demuxer-lavf-probesize","8388608"},
    {"demuxer-lavf-analyzeduration","10"}
};
/* Audited file/engine getters, rather than every getter in property-list.
 * Other getters can enumerate audio devices or access clipboard/system state.
 * The full name catalog is retained without invoking those unrelated getters.
 * Missing names are explicit errors, including version-dependent properties. */
static const char *const properties[] = {
    "mpv-version", "mpv-configuration", "ffmpeg-version", "libass-version",
    "decoder-list", "demuxer-lavf-list", "protocol-list",
    "file-format", "current-demuxer", "file-size", "stream-end",
    "seekable", "partially-seekable", "duration", "time-start",
    "demuxer-start-time", "metadata", "filtered-metadata", "chapter-metadata",
    "chapter-list", "chapters", "chapter", "edition-list", "editions", "edition",
    "track-list", "audio-codec", "audio-codec-name", "audio-codec-description",
    "audio-format", "audio-params", "audio-out-params", "audio-samplerate",
    "audio-channels", "video-format", "video-codec", "video-codec-name",
    "video-codec-description", "video-params", "video-dec-params", "video-out-params",
    "container-fps", "estimated-vf-fps", "width", "height", "fps",
    "packet-audio-bitrate", "packet-video-bitrate", "packet-sub-bitrate",
    "audio-bitrate", "video-bitrate", "demuxer-cache-state", "playlist"
};
static jbyteArray result_bytes(JNIEnv *env, Json *json, const char *error) {
    if (error || json->error) {
        const char *reason = error ? error : json->error;
        free(json->data); *json = (Json){0};
        literal(json, "{\"ok\":false,\"error\":"); string(json, reason); literal(json, "}");
    }
    if (json->error || !json->data) { free(json->data); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)json->length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)json->length, (const jbyte *)json->data);
    free(json->data); return result;
}
JNIEXPORT jbyteArray JNICALL Java_org_diemobile_MpvEngine_probeFd(JNIEnv *env, jclass type, jint fd) {
    (void)type;
    Json json = {0}, logs = {0};
    mpv_handle *mpv = NULL;
    mpv_node catalog = {0};
    struct stat before, after;
    const char *error = NULL;
    char failure[256] = {0};
    Input input = {.fd = -1, .deadline = now_ms() + DEADLINE_MS};
    atomic_init(&input.bytes, 0); atomic_init(&input.seeks, 0);
    atomic_init(&input.opens, 0); atomic_init(&input.denied, 0); atomic_init(&input.budget_hit, false);
    bool loaded = false; int load_error = 0, end_reason = -1;
    bool report_ready = false;
    unsigned log_count = 0;
    locale_t previous_locale = (locale_t)0;
    locale_t numeric_locale = newlocale(LC_NUMERIC_MASK, "C", (locale_t)0);
    if (!numeric_locale) { error = "Unable to establish an invariant MPV numeric locale"; goto done; }
    previous_locale = uselocale(numeric_locale);
#ifdef DIE_MPV_ANDROID_BUNDLE
    /* libmpv also checks the process-global C locale. Production executes only
     * inside the dedicated single-job isolated process, never the app UID. */
    if (!setlocale(LC_NUMERIC, "C")) { error = "Unable to establish libmpv's required C numeric locale"; goto done; }
#endif
    input.fd = dup(fd);
    if (input.fd < 0 || fstat(input.fd, &before) || !S_ISREG(before.st_mode)) { error = "MPV requires a granted seekable regular input descriptor"; goto done; }
    if (before.st_size < 0 || before.st_size > INPUT_LIMIT) { error = "MPV input exceeds the 256 MiB snapshot budget"; goto done; }
    input.size = before.st_size;
    unsigned long api = mpv_client_api_version();
    if ((api >> 16) != 2 || api < MPV_MAKE_VERSION(2, 0)) { error = "Unsupported libmpv API: this adapter requires compatible API 2.x"; goto done; }
    mpv = mpv_create();
    if (!mpv) { error = "Unable to create libmpv"; goto done; }
    for (size_t i = 0; i < sizeof(options) / sizeof(options[0]); i++) {
        int status = mpv_set_option_string(mpv, options[i][0], options[i][1]);
        if (status < 0) { snprintf(failure, sizeof(failure), "Mandatory MPV option %s failed: %s", options[i][0], mpv_error_string(status)); error = failure; goto done; }
    }
    int status = mpv_stream_cb_add_ro(mpv, "dieblob", &input, stream_open);
    if (status < 0) { error = "Unable to register the read-only MPV input stream"; goto done; }
    status = mpv_initialize(mpv);
    if (status < 0) { snprintf(failure, sizeof(failure), "MPV initialization failed: %s", mpv_error_string(status)); error = failure; goto done; }
    mpv_request_log_messages(mpv, "warn");
    const char *command[] = {"loadfile", INPUT_URI, NULL};
    status = mpv_command_async(mpv, 1, command);
    if (status < 0) { error = "MPV rejected the fixed input load request"; goto done; }
    literal(&logs, "[");
    while (now_ms() <= input.deadline) {
        mpv_event *event = mpv_wait_event(mpv, 0.1);
        if (event->event_id == MPV_EVENT_LOG_MESSAGE) {
            mpv_event_log_message *log = event->data;
            if (++log_count > 1000 || logs.length > 256 * 1024) { error = "MPV diagnostics exceed 1,000 events or 256 KiB; no silent truncation"; goto done; }
            if (log_count > 1) literal(&logs, ",");
            literal(&logs, "{\"prefix\":"); string(&logs, log->prefix);
            literal(&logs, ",\"level\":"); string(&logs, log->level);
            literal(&logs, ",\"text\":"); string(&logs, log->text);
            literal(&logs, ",\"textUtf8Hex\":"); hex(&logs, log->text, text_length(&logs, log->text)); literal(&logs, "}");
            if (logs.length > 256 * 1024) { error = "MPV diagnostics exceed 256 KiB; no silent truncation"; goto done; }
        } else if (event->event_id == MPV_EVENT_FILE_LOADED) { loaded = true; break; }
        else if (event->event_id == MPV_EVENT_END_FILE) {
            mpv_event_end_file *end = event->data; load_error = end->error; end_reason = end->reason; break;
        } else if (event->event_id == MPV_EVENT_COMMAND_REPLY && event->error < 0) { load_error = event->error; break; }
        else if (event->event_id == MPV_EVENT_SHUTDOWN) { error = "MPV shut down before producing a complete probe"; goto done; }
    }
    literal(&logs, "]");
    if (logs.error) { error = logs.error; goto done; }
    if (now_ms() > input.deadline) { error = "MPV exceeded the 25-second native probe deadline"; goto done; }
    if (atomic_load(&input.budget_hit)) { error = "MPV exhausted its 32 MiB cumulative input-read budget; no complete probe returned"; goto done; }
    if (atomic_load(&input.denied)) { error = "MPV requested an ungranted callback URI"; goto done; }
    status = mpv_get_property(mpv, "property-list", MPV_FORMAT_NODE, &catalog);
    if (status < 0 || catalog.format != MPV_FORMAT_NODE_ARRAY || !catalog.u.list || catalog.u.list->num < 1 || catalog.u.list->num > PROPERTY_LIMIT) { error = "MPV returned an invalid or over-budget property catalog"; goto done; }
    literal(&json, "{\"schema\":\"org.diemobile.mpv-evidence\",\"schemaVersion\":1,\"ok\":true,\"engine\":\"libmpv\",\"adapterVersion\":1,\"headersSourceRevision\":\"" HEADERS_SOURCE "\",");
#ifdef DIE_MPV_ANDROID_BUNDLE
    literal(&json, "\"engineBinaryOrigin\":\"mpv-android-2026-09-17-arm64\",\"engineSourceRevision\":\"" HEADERS_SOURCE "\",");
#else
    literal(&json, "\"engineBinaryOrigin\":\"external-host-runtime\",\"engineSourceRevision\":null,");
#endif
    number(&json, "\"runtimeApiVersion\":%lu,\"headersApiVersion\":%u,\"fileSizeBytes\":%" PRId64 ",\"fileProbeComplete\":%s,\"loadErrorCode\":%d,\"endReason\":%d,", api, MPV_CLIENT_API_VERSION, input.size, loaded ? "true" : "false", load_error, end_reason);
    literal(&json, "\"loadError\":"); string(&json, loaded ? NULL : load_error < 0 ? mpv_error_string(load_error) : "Input did not produce a playable/probeable media stream");
    literal(&json, ",\"diagnostics\":"); append(&json, logs.data, logs.length);
    number(&json, ",\"catalogCount\":%d,\"propertyCatalog\":", catalog.u.list->num);
    node(&json, &catalog, 0);
    number(&json, ",\"propertyCount\":%zu,\"properties\":[", sizeof(properties) / sizeof(properties[0]));
    for (size_t i = 0; i < sizeof(properties) / sizeof(properties[0]) && !json.error; i++) {
        if (now_ms() > input.deadline) { error = "MPV property capture exceeded its native deadline"; goto done; }
        if (i) literal(&json, ",");
        literal(&json, "{\"name\":"); string(&json, properties[i]);
        mpv_node value = {0};
        int result = mpv_get_property(mpv, properties[i], MPV_FORMAT_NODE, &value);
        if (result >= 0) { literal(&json, ",\"status\":\"available\",\"errorCode\":0,\"node\":"); node(&json, &value, 0); }
        else { number(&json, ",\"status\":\"unavailable\",\"errorCode\":%d,\"error\":", result); string(&json, mpv_error_string(result)); }
        mpv_free_node_contents(&value);
        literal(&json, "}");
    }
    literal(&json, "],\"options\":[");
    for (size_t i = 0; i < sizeof(options) / sizeof(options[0]); i++) {
        if (i) literal(&json, ",");
        literal(&json, "{\"name\":"); string(&json, options[i][0]);
        literal(&json, ",\"value\":"); string(&json, options[i][1]); literal(&json, "}");
    }
    literal(&json, "]");
    report_ready = true;
done:
    mpv_free_node_contents(&catalog);
    if (mpv) mpv_terminate_destroy(mpv); /* Must precede releasing stream input. */
    if (input.fd >= 0) {
        if (!error && (fstat(input.fd, &after) || after.st_size != before.st_size || after.st_mtim.tv_sec != before.st_mtim.tv_sec || after.st_mtim.tv_nsec != before.st_mtim.tv_nsec)) error = "Input changed during MPV probing";
        close(input.fd);
    }
    if (report_ready && !error && !json.error) {
        if (atomic_load(&input.budget_hit)) error = "MPV exhausted its 32 MiB cumulative input-read budget";
        else if (atomic_load(&input.denied)) error = "MPV requested an ungranted callback URI";
        else if (now_ms() > input.deadline) error = "MPV exceeded its native probe/teardown deadline";
        else number(&json, ",\"bytesRead\":%" PRIuFAST64 ",\"seekCalls\":%" PRIuFAST64 ",\"streamOpenCalls\":%" PRIuFAST64 ",\"ungrantedCallbackUris\":%" PRIuFAST64 ",\"maxInputBytes\":%lld,\"maxReadBytes\":%llu,\"nativeDeadlineMs\":%d,\"maxNodes\":%u,\"maxOutputBytes\":%u,\"nodeCount\":%u,\"referenceAccessEnabled\":false,\"configEnabled\":false,\"scriptsEnabled\":false,\"sidecarLoadingEnabled\":false,\"networkPermissionRequired\":false,\"playbackOutput\":\"null audio/video; paused software stream initialization may decode initial media packets\"}", atomic_load(&input.bytes), atomic_load(&input.seeks), atomic_load(&input.opens), atomic_load(&input.denied), INPUT_LIMIT, READ_LIMIT, DEADLINE_MS, NODE_LIMIT, OUTPUT_LIMIT, json.nodes);
    }
    free(logs.data);
    if (numeric_locale) { uselocale(previous_locale); freelocale(numeric_locale); }
    return result_bytes(env, &json, error);
}
