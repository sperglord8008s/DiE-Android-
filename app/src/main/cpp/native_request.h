// SPDX-License-Identifier: GPL-3.0-only
#ifndef DIE_NATIVE_REQUEST_H
#define DIE_NATIVE_REQUEST_H
#include <jni.h>
#include <stdbool.h>
#include <time.h>
#include <xxfclib/data/xx_pd.h>

/* Synchronous worker-thread observer. No global stop flag or cross-thread JNI. */
typedef struct native_request {
    JNIEnv *env;
    jobject stop;
    jmethodID poll;
    struct timespec started;
    const char *failure;
} native_request;

static void native_request_init(native_request *request, JNIEnv *env, jobject stop) {
    request->env = env; request->stop = stop;
    jclass type = stop ? (*env)->GetObjectClass(env, stop) : NULL;
    request->poll = type ? (*env)->GetMethodID(env, type, "getAsBoolean", "()Z") : NULL;
    if (type) (*env)->DeleteLocalRef(env, type);
    if (!request->poll) request->failure = "Native request cancellation parameters unavailable";
    else if (clock_gettime(CLOCK_MONOTONIC, &request->started) != 0) request->failure = "Cannot read monotonic deadline clock";
}

static bool native_request_stopped(const xx_pd_struct *progress, void *user) {
    (void)progress;
    native_request *request = user;
    if (request->failure || (*request->env)->ExceptionCheck(request->env)) return true;
    if ((*request->env)->CallBooleanMethod(request->env, request->stop, request->poll)) {
        request->failure = "Native request cancelled";
        return true;
    }
    if ((*request->env)->ExceptionCheck(request->env)) return true;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0 || now.tv_sec - request->started.tv_sec > 30
        || (now.tv_sec - request->started.tv_sec == 30 && now.tv_nsec >= request->started.tv_nsec)) {
        request->failure = "Native request exceeded its 30-second cooperative deadline";
        return true;
    }
    return false;
}
#endif
