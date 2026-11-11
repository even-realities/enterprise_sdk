#include <jni.h>
#include <stdint.h>
#include "even_lc3.h"

/* Kotlin serializes this private handle; JNI never retains borrowed array memory. */
JNIEXPORT jlong JNICALL Java_com_evenrealities_devices_NativeLc3Decoder_nativeCreate(JNIEnv *env, jobject self) {
    return (jlong)(intptr_t)even_lc3_create();
}
JNIEXPORT jbyteArray JNICALL Java_com_evenrealities_devices_NativeLc3Decoder_nativeDecode(JNIEnv *env, jobject self, jlong handle, jbyteArray frame) {
    if (!frame || (*env)->GetArrayLength(env, frame) != 40) return NULL;
    uint8_t input[40], output[320];
    (*env)->GetByteArrayRegion(env, frame, 0, 40, (jbyte *)input);
    if ((*env)->ExceptionCheck(env)) return NULL;
    if (even_lc3_decode((void *)(intptr_t)handle, input, 40, output) < 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, 320);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, 320, (jbyte *)output);
    return result;
}
JNIEXPORT void JNICALL Java_com_evenrealities_devices_NativeLc3Decoder_nativeDestroy(JNIEnv *env, jobject self, jlong handle) {
    even_lc3_destroy((void *)(intptr_t)handle);
}
