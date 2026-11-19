// SPDX-License-Identifier: GPL-3.0-only
#include "archive_reader.h"
#include "json.h"
#include <jni.h>
#include <exception>

namespace {
template<class Task> jstring result(JNIEnv *env, Task task) {
    std::string output;
    try { output = task(); }
    catch (const std::exception &error) {
        die_archive::Object failed; failed.flag("ok", false); failed.flag("complete", false);
        failed.text("error", error.what()); output = failed.finish();
    }
    // The serializer emits ASCII JSON, including exact Unicode escapes, rather than modified UTF-8.
    return env->NewStringUTF(output.c_str());
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_diemobile_ArchiveEngine_inspectFd(JNIEnv *env, jclass, jint descriptor, jlong offset, jlong length) {
    return result(env, [&] { return die_archive::inspect(descriptor, offset, length); });
}
extern "C" JNIEXPORT jstring JNICALL
Java_org_diemobile_ArchiveEngine_extractFd(JNIEnv *env, jclass, jint descriptor, jint output,
        jlong offset, jlong length, jint member) {
    return result(env, [&] { return die_archive::extract(descriptor, output, offset, length, member); });
}
