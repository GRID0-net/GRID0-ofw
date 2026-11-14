#include <jni.h>
#include <string.h>

JNIEXPORT jstring JNICALL
Java_com_grid0_ofw_MainActivity_nativeVersion(JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "GRID0 3.0.0");
}
