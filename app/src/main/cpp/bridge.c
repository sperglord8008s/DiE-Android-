// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <string.h>
#include <limits.h>
#include "die.h"
JNIEXPORT jbyteArray JNICALL Java_org_diemobile_Engine_scanNative(JNIEnv *env, jclass cls, jstring path, jstring db, jint flags) {
    (void)cls;
    const char *p = (*env)->GetStringUTFChars(env,path,0);
    if (!p) return NULL;
    const char *d = (*env)->GetStringUTFChars(env,db,0);
    if (!d) { (*env)->ReleaseStringUTFChars(env,path,p); return NULL; }
    char *r = DIE_ScanFileA((char*)p,(unsigned)flags | DIE_RESULTASJSON,(char*)d);
    (*env)->ReleaseStringUTFChars(env,db,d);
    (*env)->ReleaseStringUTFChars(env,path,p);
    if (!r) return NULL;
    size_t n = strlen(r);
    jbyteArray a = NULL;
    if(n <= INT_MAX) {
        a = (*env)->NewByteArray(env,(jsize)n);
        if(a) (*env)->SetByteArrayRegion(env,a,0,(jsize)n,(const jbyte*)r);
    }
    DIE_FreeMemoryA(r);
    return a;
}
