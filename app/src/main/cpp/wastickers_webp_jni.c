#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "webp/encode.h"
#include "webp/mux.h"

#define LOG_TAG "wastickers-webp-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Minimal JNI wrapper over libwebp's WebPAnimEncoder. The Kotlin converter uses
 * APNG4Android's muxer for normal animation encoding; this native library keeps
 * the documented libwebp animation encoder available for alpha-quality paths.
 */
JNIEXPORT jbyteArray JNICALL
Java_io_github_midihl_wastickersimporter_convert_AnimatedWebpEncoder_nativeEncode(
        JNIEnv *env, jclass clazz, jint width, jint height, jint quality,
        jintArray jpixels, jintArray jdurations, jint frameCount) {
    (void) clazz;
    if (frameCount <= 0 || jpixels == NULL || jdurations == NULL) return NULL;
    jint *pixels = (*env)->GetIntArrayElements(env, jpixels, NULL);
    jint *durations = (*env)->GetIntArrayElements(env, jdurations, NULL);
    if (pixels == NULL || durations == NULL) return NULL;

    WebPAnimEncoderOptions options;
    if (!WebPAnimEncoderOptionsInit(&options)) {
        (*env)->ReleaseIntArrayElements(env, jpixels, pixels, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, jdurations, durations, JNI_ABORT);
        return NULL;
    }
    options.minimize_size = 1;
    options.allow_mixed = 1;
    WebPAnimEncoder *encoder = WebPAnimEncoderNew(width, height, &options);
    if (encoder == NULL) {
        (*env)->ReleaseIntArrayElements(env, jpixels, pixels, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, jdurations, durations, JNI_ABORT);
        return NULL;
    }

    WebPConfig config;
    if (!WebPConfigPreset(&config, WEBP_PRESET_DEFAULT, (float) quality) || !WebPConfigLosslessPreset(&config, 6)) {
        WebPAnimEncoderDelete(encoder);
        (*env)->ReleaseIntArrayElements(env, jpixels, pixels, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, jdurations, durations, JNI_ABORT);
        return NULL;
    }
    config.alpha_quality = 100;
    config.exact = 0;
    config.method = 4;

    WebPPicture picture;
    if (!WebPPictureInit(&picture)) {
        WebPAnimEncoderDelete(encoder);
        (*env)->ReleaseIntArrayElements(env, jpixels, pixels, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, jdurations, durations, JNI_ABORT);
        return NULL;
    }
    picture.width = width;
    picture.height = height;
    picture.use_argb = 1;

    int timestamp = 0;
    int ok = 1;
    size_t frameSize = (size_t) width * (size_t) height;
    for (int frame = 0; frame < frameCount && ok; frame++) {
        if (!WebPPictureAlloc(&picture)) { ok = 0; break; }
        memcpy(picture.argb, pixels + frame * frameSize, frameSize * sizeof(uint32_t));
        ok = WebPAnimEncoderAdd(encoder, &picture, timestamp, &config);
        WebPPictureFree(&picture);
        timestamp += durations[frame];
    }
    if (ok) ok = WebPAnimEncoderAdd(encoder, NULL, timestamp, NULL);
    WebPData output;
    WebPDataInit(&output);
    if (ok) ok = WebPAnimEncoderAssemble(encoder, &output);
    WebPAnimEncoderDelete(encoder);
    (*env)->ReleaseIntArrayElements(env, jpixels, pixels, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, jdurations, durations, JNI_ABORT);
    if (!ok) {
        WebPDataClear(&output);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize) output.size);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) output.size, (const jbyte *) output.bytes);
    }
    WebPDataClear(&output);
    return result;
}
