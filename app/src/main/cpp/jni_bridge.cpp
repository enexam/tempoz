#include <jni.h>

#include "AudioEngine.h"
#include "BpmAnalyzer.h"

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
Java_com_example_tempoz_AudioEngine_nativePause(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    reinterpret_cast<AudioEngine*>(handle)->pause();
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeResume(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    reinterpret_cast<AudioEngine*>(handle)->resume();
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSeekToStart(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    reinterpret_cast<AudioEngine*>(handle)->seekToStart();
}

JNIEXPORT jboolean JNICALL
Java_com_example_tempoz_AudioEngine_nativeIsPlaying(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle)->isPlaying() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    delete reinterpret_cast<AudioEngine*>(handle);
}

JNIEXPORT jlong JNICALL
Java_com_example_tempoz_AudioEngine_nativeGetDurationMs(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return static_cast<jlong>(reinterpret_cast<AudioEngine*>(handle)->getDurationMs());
}

JNIEXPORT jlong JNICALL
Java_com_example_tempoz_AudioEngine_nativeGetPositionMs(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return static_cast<jlong>(reinterpret_cast<AudioEngine*>(handle)->getPositionMs());
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSeekTo(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong positionMs) {
    reinterpret_cast<AudioEngine*>(handle)->seekTo(static_cast<int64_t>(positionMs));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetFirstBeatOffset(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong frames) {
    reinterpret_cast<AudioEngine*>(handle)->setFirstBeatOffset(static_cast<int64_t>(frames));
}

JNIEXPORT jlongArray JNICALL
Java_com_example_tempoz_AudioEngine_nativeAnalyzeBpm(
        JNIEnv* env, jobject /*thiz*/,
        jlong /*handle*/, jint fd, jlong offset, jlong length) {
    BpmResult result = BpmAnalyzer{}.analyze(
            static_cast<int>(fd),
            static_cast<int64_t>(offset),
            static_cast<int64_t>(length));
    jlongArray arr = env->NewLongArray(2);
    if (arr) {
        jlong values[2] = {static_cast<jlong>(result.bpm),
                           static_cast<jlong>(result.firstBeatFrames)};
        env->SetLongArrayRegion(arr, 0, 2, values);
    }
    return arr;
}

} // extern "C"
