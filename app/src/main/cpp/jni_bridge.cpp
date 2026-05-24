#include <jni.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_tempoz_AudioEngine_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return 0L;
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeLoadFile(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong /*handle*/, jint /*fd*/, jlong /*offset*/, jlong /*length*/) {}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/) {}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/) {}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetBpm(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/, jint /*bpm*/) {}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetBeatsPerBar(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/, jint /*beatsPerBar*/) {}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetTrackVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/, jfloat /*volume*/) {}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetClickVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/, jfloat /*volume*/) {}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong /*handle*/) {}

} // extern "C"
