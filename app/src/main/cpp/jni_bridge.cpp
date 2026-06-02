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
    reinterpret_cast<AudioEngine*>(handle)->play();
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    reinterpret_cast<AudioEngine*>(handle)->stop();
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetBpm(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jdouble bpm) {
    reinterpret_cast<AudioEngine*>(handle)->setBpm(static_cast<double>(bpm));
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

JNIEXPORT jboolean JNICALL
Java_com_example_tempoz_AudioEngine_nativeIsEnded(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return reinterpret_cast<AudioEngine*>(handle)->isEnded() ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT jlong JNICALL
Java_com_example_tempoz_AudioEngine_nativeGetAudiblePositionMs(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    return static_cast<jlong>(reinterpret_cast<AudioEngine*>(handle)->getAudiblePositionMs());
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

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetClickSound(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint id) {
    reinterpret_cast<AudioEngine*>(handle)->setClickSound(static_cast<int>(id));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetSubdivision(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint subdivision) {
    reinterpret_cast<AudioEngine*>(handle)->setSubdivision(static_cast<int>(subdivision));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetGhostVolume(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat volume) {
    reinterpret_cast<AudioEngine*>(handle)->setGhostVolume(static_cast<float>(volume));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetCountInBars(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint bars) {
    reinterpret_cast<AudioEngine*>(handle)->setCountInBars(static_cast<int>(bars));
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeStartWithCountIn(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    reinterpret_cast<AudioEngine*>(handle)->startWithCountIn();
}

JNIEXPORT void JNICALL
Java_com_example_tempoz_AudioEngine_nativeSetSpeed(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat speed) {
    reinterpret_cast<AudioEngine*>(handle)->setSpeed(static_cast<float>(speed));
}

JNIEXPORT jdoubleArray JNICALL
Java_com_example_tempoz_AudioEngine_nativeAnalyzeBpm(
        JNIEnv* env, jobject /*thiz*/,
        jlong /*handle*/, jint fd, jlong offset, jlong length) {
    BpmResult result = BpmAnalyzer{}.analyze(
            static_cast<int>(fd),
            static_cast<int64_t>(offset),
            static_cast<int64_t>(length));
    jdoubleArray arr = env->NewDoubleArray(3);
    if (arr) {
        jdouble values[3] = {static_cast<jdouble>(result.bpm),
                             static_cast<jdouble>(result.firstBeatFrames),
                             static_cast<jdouble>(result.beatsPerBar)};
        env->SetDoubleArrayRegion(arr, 0, 3, values);
    }
    return arr;
}

} // extern "C"
