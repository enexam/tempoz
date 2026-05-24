#include <jni.h>

#include "AudioEngine.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_tempoz_AudioEngine_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(new AudioEngine());
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeLoadFile(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong handle, jint fd, jlong offset, jlong length) {
    reinterpret_cast<AudioEngine*>(handle)->loadFile(
            static_cast<int>(fd),
            static_cast<int64_t>(offset),
            static_cast<int64_t>(length));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    reinterpret_cast<AudioEngine*>(handle)->start();
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    reinterpret_cast<AudioEngine*>(handle)->stop();
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetBpm(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint bpm) {
    reinterpret_cast<AudioEngine*>(handle)->setBpm(static_cast<int>(bpm));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetBeatsPerBar(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint beatsPerBar) {
    reinterpret_cast<AudioEngine*>(handle)->setBeatsPerBar(static_cast<int>(beatsPerBar));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetTrackVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat volume) {
    reinterpret_cast<AudioEngine*>(handle)->setTrackVolume(static_cast<float>(volume));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetClickVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat volume) {
    reinterpret_cast<AudioEngine*>(handle)->setClickVolume(static_cast<float>(volume));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete reinterpret_cast<AudioEngine*>(handle);
}

} // extern "C"
