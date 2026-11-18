// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <xxfclib/buf/xx_buf.h>
#include "../../../../hash-engine/hashes.h"

JNIEXPORT jbyteArray JNICALL Java_org_diemobile_HashEngine_probeFd(JNIEnv *env, jclass type, jint fd)
{
    die_hash_result hashes;
    char error[DIE_HASH_ERROR_SIZE];
    xx_buf_t output;
    jbyteArray result;
    int i, ok = die_hash_fd(fd, DIE_HASH_INPUT_LIMIT, DIE_HASH_TIMEOUT_MS, &hashes, error);
    (void)type;
    xx_buf_init(&output);
    if (!ok) {
        /* All error messages are fixed ASCII literals, with no input-derived text. */
        xx_buf_appendf(&output, "{\"ok\":false,\"error\":\"%s\"}", error);
    } else {
        xx_buf_appendf(&output, "{\"schema\":\"org.diemobile.whole-file-hashes\",\"schemaVersion\":1,\"ok\":true,"
            "\"adapterVersion\":1,\"xxfclibSource\":\"0fc8b4fa12c2c9c97d79fa3a3806667fc1da9b9d\","
            "\"rhashSource\":\"6ea6791024499524cf41f9e0b6b08d456bf4a5e2\","
            "\"fileSizeBytes\":%llu,\"bytesRead\":%llu,\"wholeInputComplete\":true,\"passes\":1,"
            "\"maximumInputBytes\":%llu,\"nativeDeadlineMs\":%u,\"digests\":[",
            (unsigned long long)hashes.input_bytes, (unsigned long long)hashes.bytes_read,
            (unsigned long long)DIE_HASH_INPUT_LIMIT, DIE_HASH_TIMEOUT_MS);
        for (i = 0; i < DIE_HASH_COUNT; i++) {
            xx_buf_appendf(&output, "%s{\"algorithm\":\"%s\",\"hex\":\"%s\"}", i ? "," : "", die_hash_names[i], hashes.values[i]);
        }
        xx_buf_append_str(&output, "]}");
    }
    if (!xx_buf_ok(&output) || output.size > 16384) { xx_buf_free(&output); return NULL; }
    result = (*env)->NewByteArray(env, (jsize)output.size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)output.size, (const jbyte*)output.data);
    xx_buf_free(&output);
    return result;
}
