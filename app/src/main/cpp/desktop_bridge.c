// SPDX-License-Identifier: GPL-3.0-only
/* Adapter for the real CDiE desktop scanner backend, separate from the
 * legacy die_library-compatible scan. All non-cancellation calls are serialized
 * by Engine.java. The backend owns the loaded databases and options. */
#include <jni.h>
#include <stdint.h>
#include <stdatomic.h>
#include <string.h>
#include <xxfclib/buf/xx_buf.h>
#include <xxfclib/terminal/xx_terminal.h>
#include "gui/gui_backend.h"

#define MAX_REPORT (8U * 1024U * 1024U)
#define MAX_ROWS 20000U
#define STR(s) ((s) ? (s) : "")
#define U64(n) ((unsigned long long)(n))
#define I64(n) ((long long)(n))

static atomic_bool cancel_requested = false;

typedef struct desktop_output {
    xx_buf_t buf;
    size_t count;
    bool capped;
} desktop_output;

static xxfc_status_t json_sink(void *ctx, const char *data, size_t size) {
    xx_buf_append((xx_buf_t *)ctx, data, size);
    return XXFC_OK;
}

static void json_string(xx_buf_t *buf, const char *s) {
    xx_terminal_writer writer;
    xx_terminal_writer_init(&writer, json_sink, buf);
    (void)xx_terminal_writer_json_string(&writer, STR(s));
}

static void string_field(xx_buf_t *buf, const char *key, const char *value) {
    xx_buf_appendf(buf, ",\"%s\":", key);
    json_string(buf, value);
}

static bool observe(const xx_pd_struct *pd, void *user) {
    (void)pd; (void)user;
    return atomic_load_explicit(&cancel_requested, memory_order_relaxed);
}

JNIEXPORT void JNICALL Java_org_diemobile_Engine_cancelDesktopNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    atomic_store_explicit(&cancel_requested, true, memory_order_relaxed);
}

static int append_tree(size_t parent, const char *text, const char *category,
                       const gui_backend_detection *d, void *user) {
    desktop_output *out = user;
    if (out->count >= MAX_ROWS || out->buf.size >= MAX_REPORT) {
        out->capped = true;
        return 0;
    }
    if (out->count++) xx_buf_append_char(&out->buf, ',');
    xx_buf_appendf(&out->buf, "{\"parent\":%lld", parent == SIZE_MAX ? -1LL : I64(parent));
    string_field(&out->buf, "text", text);
    string_field(&out->buf, "category", category);
    if (d) {
        string_field(&out->buf, "name", d->name);
        string_field(&out->buf, "signature", d->signature);
    }
    xx_buf_append_char(&out->buf, '}');
    return xx_buf_ok(&out->buf) ? 1 : 0;
}

static void append_type(int id, const char *name, void *user) {
    desktop_output *out = user;
    if (out->count++) xx_buf_append_char(&out->buf, ',');
    xx_buf_appendf(&out->buf, "{\"id\":%d", id);
    string_field(&out->buf, "name", name);
    xx_buf_append_char(&out->buf, '}');
}

static void error_report(xx_buf_t *b, const char *message) {
    xx_buf_clear(b);
    xx_buf_append_str(b, "{\"ok\":false");
    string_field(b, "error", message);
    xx_buf_append_char(b, '}');
}

static int path_inside(const char *path, const char *directory) {
    size_t length = strlen(STR(directory));
    return length && path && !strncmp(path, directory, length) &&
        (path[length] == '/' || path[length] == '\\');
}

static int configure(const char *main_db, const char *custom_db,
                     unsigned flags, const char *inc, const char *exc) {
    /* init marks the backend initialized even when default desktop paths
       are absent on Android; set explicit private paths afterward. */
    (void)gui_backend_init();
    if (!gui_backend_db_paths_current(main_db, custom_db) ||
         gui_backend_signature_count() == 0) {
        if (!gui_backend_set_db_paths(main_db, custom_db)) return 0;
    }
    gui_backend_set_flags((flags & 1) != 0, (flags & 2) != 0, (flags & 16) != 0,
                          (flags & 32) != 0, (flags & 1024) != 0, 0);
    gui_backend_set_databases((flags & 2048) != 0);
    unsigned modes = ((flags & 4) ? GUI_SCAN_ALL_TYPES : 0) |
        ((flags & 64) ? GUI_SCAN_OVERLAYS : 0) |
        ((flags & 128) ? GUI_SCAN_RESOURCES : 0) |
        ((flags & 256) ? GUI_SCAN_ARCHIVES : 0);
    if (!gui_backend_set_scan_modes(modes)) return 0;
    gui_backend_scan_preferences pref = {
        (flags & 8) != 0, (flags & 512) != 0, (flags & 4096) != 0,
        (flags & 8192) != 0, "die"
    };
    if (!gui_backend_set_scan_preferences(&pref)) return 0;
    return gui_backend_set_tag_filters(inc, exc);
}

JNIEXPORT jbyteArray JNICALL Java_org_diemobile_Engine_desktopNative(
    JNIEnv *env, jclass cls, jstring path, jstring db, jstring custom,
    jint verb, jint flags, jint type_id, jstring include, jstring exclude,
    jstring argument, jstring source) {
    (void)cls;
    jstring inputs[] = {path, db, custom, include, exclude, argument, source};
    const char *v[7] = {NULL};
    desktop_output out = {0};
    xx_buf_init(&out.buf);
    for (size_t i = 0; i < 7; i++) {
        if (inputs[i]) v[i] = (*env)->GetStringUTFChars(env, inputs[i], NULL);
        if (inputs[i] && !v[i]) goto cleanup;
    }
    if (!configure(STR(v[1]), STR(v[2]), (unsigned)flags, STR(v[3]), STR(v[4]))) {
        error_report(&out.buf, "Desktop backend database/configuration could not load");
        goto cleanup;
    }

    switch (verb) {
    case 0:
        xx_buf_append_str(&out.buf, "{\"ok\":true,\"types\":[");
        if (!gui_backend_file_types(STR(v[0]), append_type, &out)) {
            error_report(&out.buf, "Cannot determine file types");
            break;
        }
        xx_buf_append_str(&out.buf, "],\"signatureCount\":");
        xx_buf_appendf(&out.buf, "%d}", gui_backend_signature_count());
        break;
    case 1: {
        xx_pd_struct pd = xx_pd_init();
        gui_backend_scan_info info = {0};
        char *report = NULL, *type = NULL;
        atomic_store_explicit(&cancel_requested, false, memory_order_relaxed);
        xx_pd_observer previous = xx_pd_set_observer(&pd, observe, NULL);
        xx_buf_append_str(&out.buf, "{\"ok\":true,\"rows\":[");
        int ok = gui_backend_scan_tree(STR(v[0]), type_id, append_tree, &out,
                                       &report, &type, &info, &pd);
        (void)xx_pd_set_observer(previous.progress, previous.callback, previous.user_data);
        if (ok) {
            uint64_t hits = 0, misses = 0;
            gui_backend_cache_statistics(&hits, &misses);
            xx_buf_append_char(&out.buf, ']');
            string_field(&out.buf, "report", report);
            string_field(&out.buf, "type", type);
            string_field(&out.buf, "architecture", info.architecture);
            string_field(&out.buf, "endianness", info.endianness);
            string_field(&out.buf, "mode", info.mode);
            string_field(&out.buf, "format", info.type);
            string_field(&out.buf, "log", info.log);
            string_field(&out.buf, "engine", gui_backend_app_title());
            string_field(&out.buf, "includeTags", STR(v[3]));
            string_field(&out.buf, "excludeTags", STR(v[4]));
            xx_buf_appendf(&out.buf, ",\"signatureCount\":%d,\"flags\":%u,\"typeId\":%d,\"databaseGeneration\":%llu,\"cacheHits\":%llu,\"cacheMisses\":%llu}",
                gui_backend_signature_count(), (unsigned)flags, type_id,
                U64(gui_backend_database_generation()), U64(hits), U64(misses));
        } else {
            error_report(&out.buf, out.capped ? "Detection report capped" :
                atomic_load(&cancel_requested) ? "Scan cancelled" : "Desktop scan failed");
        }
        gui_backend_free(report); gui_backend_free(type); gui_backend_free(info.log);
        break;
    }
    case 2: {
        const char *display = NULL, *signature = NULL;
        int total = gui_backend_signature_count();
        xx_buf_append_str(&out.buf, "{\"ok\":true,\"signatures\":[");
        for (int n = 0; n < total && out.count < MAX_ROWS && out.buf.size < MAX_REPORT; n++) {
            if (!gui_backend_signature_entry(n, &display, &signature)) continue;
            if (out.count++) xx_buf_append_char(&out.buf, ',');
            xx_buf_append_str(&out.buf, "{\"display\":"); json_string(&out.buf, display);
            string_field(&out.buf, "path", signature);
            xx_buf_append_char(&out.buf, '}');
        }
        xx_buf_appendf(&out.buf, "],\"total\":%d,\"displayed\":%llu}", total, U64(out.count));
        break;
    }
    case 3: {
        char *script = gui_backend_signature_source(STR(v[5]));
        if (!script) error_report(&out.buf, "Signature source unavailable");
        else {
            xx_buf_append_str(&out.buf, "{\"ok\":true");
            string_field(&out.buf, "source", script);
            xx_buf_append_char(&out.buf, '}');
            gui_backend_free(script);
        }
        break;
    }
    case 4: {
        char *report = NULL;
        int ok = gui_backend_run_signature(STR(v[0]), type_id, STR(v[5]), STR(v[6]), &report);
        xx_buf_appendf(&out.buf, "{\"ok\":%s", ok ? "true" : "false");
        string_field(&out.buf, "report", report);
        xx_buf_append_char(&out.buf, '}');
        gui_backend_free(report);
        break;
    }
    case 5: {
        gui_backend_facts f = {0};
        if (!gui_backend_file_facts(STR(v[0]), type_id, &f)) {
            error_report(&out.buf, "No native format facts for this interpretation");
        } else {
            xx_buf_appendf(&out.buf, "{\"ok\":true,\"typeId\":%d,\"flags\":%u,\"fileSize\":%llu", f.type_id, f.flags, U64(f.file_size));
            string_field(&out.buf, "type", f.type_name);
            string_field(&out.buf, "page", f.main_label);
            string_field(&out.buf, "arch", f.architecture);
            string_field(&out.buf, "mode", f.mode);
            string_field(&out.buf, "endianness", f.endianness);
            string_field(&out.buf, "format", f.type);
            xx_buf_appendf(&out.buf, ",\"base\":\"0x%llX\",\"entry\":\"0x%llX\",\"entryOffset\":%lld", U64(f.base_address), U64(f.entry_point), I64(f.entry_point_offset));
            xx_buf_appendf(&out.buf, ",\"sections\":%u,\"programs\":%u,\"commands\":%u,\"segments\":%u,\"libraries\":%u,\"timeDateStamp\":%u,\"imageSize\":%u,\"overlayOffset\":%lld,\"overlaySize\":%lld,\"netId\":%u", f.sections,f.programs,f.commands,f.segments,f.libraries,f.time_date_stamp,f.size_of_image,I64(f.overlay_offset),I64(f.overlay_size),f.net_id);
            string_field(&out.buf, "timestampText", f.time_date_stamp_text);
            string_field(&out.buf, "headerSelector", f.header_selector);
            string_field(&out.buf, "sectionsSelector", f.sections_selector);
            string_field(&out.buf, "tlsSelector", f.tls_selector);
            string_field(&out.buf, "programsSelector", f.programs_selector);
            string_field(&out.buf, "commandsSelector", f.commands_selector);
            string_field(&out.buf, "segmentsSelector", f.segments_selector);
            string_field(&out.buf, "librariesSelector", f.libraries_selector);
            string_field(&out.buf, "manifest", f.manifest);
            string_field(&out.buf, "version", f.version);
            xx_buf_append_char(&out.buf, '}');
        }
        gui_backend_facts_free(&f);
        break;
    }
    case 6: {
        char *tags = gui_backend_used_tags();
        xx_buf_append_str(&out.buf, "{\"ok\":true");
        string_field(&out.buf, "tags", tags);
        xx_buf_append_char(&out.buf, '}');
        gui_backend_free(tags);
        break;
    }
    case 7: {
        int custom_count = 0;
        int total = gui_backend_signature_count();
        for (int i = 0; i < total; i++) {
            const char *path = NULL;
            if (gui_backend_signature_entry(i, NULL, &path) && path_inside(path, v[2])) custom_count++;
        }
        xx_buf_appendf(&out.buf, "{\"ok\":true,\"signatureCount\":%d,\"mainCount\":%d,\"customCount\":%d,\"databaseGeneration\":%llu}",
            total, total - custom_count, custom_count, U64(gui_backend_database_generation()));
        break;
    }
    default:
        error_report(&out.buf, "Unknown desktop operation");
    }

cleanup:
    for (size_t i = 0; i < 7; i++) if (v[i])
        (*env)->ReleaseStringUTFChars(env, inputs[i], v[i]);
    if (out.buf.size > MAX_REPORT) error_report(&out.buf, "Native report exceeds 8 MiB limit");
    jbyteArray result = NULL;
    if (xx_buf_ok(&out.buf) && out.buf.size) {
        result = (*env)->NewByteArray(env, (jsize)out.buf.size);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)out.buf.size, (jbyte *)out.buf.data);
    }
    xx_buf_free(&out.buf);
    return result;
}
