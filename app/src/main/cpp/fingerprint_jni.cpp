#include <jni.h>
#include <cstdint>
#include <string>
#include <chromaprint.h>

namespace {
ChromaprintContext *context_from(jlong handle) {
    return reinterpret_cast<ChromaprintContext *>(handle);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kashef_archive_data_ChromaprintEngine_nativeCreate(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kashef_archive_data_ChromaprintEngine_nativeStart(
    JNIEnv *, jobject, jlong handle, jint sample_rate, jint channels
) {
    auto *context = context_from(handle);
    return context != nullptr && chromaprint_start(context, sample_rate, channels) == 1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kashef_archive_data_ChromaprintEngine_nativeFeed(
    JNIEnv *env, jobject, jlong handle, jshortArray samples, jint sample_count
) {
    auto *context = context_from(handle);
    if (context == nullptr || samples == nullptr || sample_count <= 0) return JNI_FALSE;
    jshort *data = env->GetShortArrayElements(samples, nullptr);
    if (data == nullptr) return JNI_FALSE;
    const int result = chromaprint_feed(
        context,
        reinterpret_cast<const int16_t *>(data),
        sample_count
    );
    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    return result == 1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kashef_archive_data_ChromaprintEngine_nativeFinish(JNIEnv *env, jobject, jlong handle) {
    auto *context = context_from(handle);
    if (context == nullptr || chromaprint_finish(context) != 1) return nullptr;
    char *fingerprint = nullptr;
    if (chromaprint_get_fingerprint(context, &fingerprint) != 1 || fingerprint == nullptr) {
        return nullptr;
    }
    jstring result = env->NewStringUTF(fingerprint);
    chromaprint_dealloc(fingerprint);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kashef_archive_data_ChromaprintEngine_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    auto *context = context_from(handle);
    if (context != nullptr) chromaprint_free(context);
}
