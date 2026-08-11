#include <android/asset_manager_jni.h>
#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

#include "asset_model_loader.h"
#include "halalify_vision.h"

namespace {

constexpr int kDetectionFields = 7;

void Throw(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) env->ThrowNew(exception_class, message.c_str());
}

hb_engine* FromHandle(jlong handle) {
    return reinterpret_cast<hb_engine*>(static_cast<intptr_t>(handle));
}

jlong ToHandle(hb_engine* engine) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_halalify_kotlin_model_NativeVisionEngine_nativeCreate(
        JNIEnv* env,
        jobject /* instance */,
        jobject java_asset_manager,
        jstring java_asset_name,
        jint target) {
    if (java_asset_manager == nullptr || java_asset_name == nullptr) {
        Throw(env, "java/lang/IllegalArgumentException", "Asset manager and model name are required.");
        return 0;
    }
    AAssetManager* asset_manager = AAssetManager_fromJava(env, java_asset_manager);
    const char* asset_name = env->GetStringUTFChars(java_asset_name, nullptr);
    if (asset_name == nullptr) return 0;
    std::vector<uint8_t> model_bytes;
    std::string error;
    const bool loaded = halalify::android::ReadAsset(
            asset_manager, asset_name, &model_bytes, &error);
    env->ReleaseStringUTFChars(java_asset_name, asset_name);
    if (!loaded) {
        Throw(env, "java/lang/IllegalStateException", error);
        return 0;
    }

    hb_config config = hb_default_config();
    config.target = target == 1 ? HB_BLUR_TARGET_MALE : HB_BLUR_TARGET_FEMALE;
    hb_engine* engine = nullptr;
    const hb_status status = hb_engine_create_from_buffer(
            model_bytes.data(), model_bytes.size(), &config, &engine);
    if (status != HB_STATUS_OK || engine == nullptr) {
        const std::string model_error = engine == nullptr
                ? "LiteRT could not initialize the Halalify model."
                : hb_engine_last_error(engine);
        hb_engine_destroy(engine);
        Throw(env, "java/lang/IllegalStateException", model_error);
        return 0;
    }
    return ToHandle(engine);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_halalify_kotlin_model_NativeVisionEngine_nativeProcess(
        JNIEnv* env,
        jobject /* instance */,
        jlong handle,
        jobject rgba_buffer,
        jint width,
        jint height,
        jint row_stride,
        jint rotation_degrees,
        jlong timestamp_ns) {
    hb_engine* engine = FromHandle(handle);
    uint8_t* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgba_buffer));
    const jlong capacity = env->GetDirectBufferCapacity(rgba_buffer);
    const jlong required_bytes = static_cast<jlong>(row_stride) * height;
    if (engine == nullptr || pixels == nullptr || width <= 0 || height <= 0 ||
        row_stride < width * 4 || capacity < required_bytes) {
        Throw(env, "java/lang/IllegalArgumentException", "RGBA frame buffer is invalid.");
        return nullptr;
    }

    hb_frame frame{};
    frame.data = pixels;
    frame.width = width;
    frame.height = height;
    frame.row_stride = row_stride;
    frame.rotation_degrees = rotation_degrees;
    frame.timestamp_ns = timestamp_ns;
    frame.pixel_format = HB_PIXEL_FORMAT_RGBA8888;
    std::vector<hb_detection> detections(100);
    size_t detection_count = 0;
    const hb_status status = hb_engine_process(
            engine, &frame, detections.data(), detections.size(), &detection_count);
    if (status != HB_STATUS_OK) {
        Throw(env, "java/lang/IllegalStateException", hb_engine_last_error(engine));
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(
            static_cast<jsize>(detection_count * kDetectionFields));
    if (result == nullptr || detection_count == 0) return result;
    std::vector<float> flattened(detection_count * kDetectionFields);
    for (size_t index = 0; index < detection_count; ++index) {
        const hb_detection& detection = detections[index];
        const size_t base = index * kDetectionFields;
        flattened[base] = detection.x1;
        flattened[base + 1] = detection.y1;
        flattened[base + 2] = detection.x2;
        flattened[base + 3] = detection.y2;
        flattened[base + 4] = detection.confidence;
        flattened[base + 5] = static_cast<float>(detection.class_id);
        flattened[base + 6] = detection.should_blur ? 1.0F : 0.0F;
    }
    env->SetFloatArrayRegion(
            result, 0, static_cast<jsize>(flattened.size()), flattened.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_halalify_kotlin_model_NativeVisionEngine_nativeUpdateTarget(
        JNIEnv* env,
        jobject /* instance */,
        jlong handle,
        jint target) {
    hb_engine* engine = FromHandle(handle);
    if (engine == nullptr) {
        Throw(env, "java/lang/IllegalStateException", "Vision engine is closed.");
        return;
    }
    hb_config config = hb_default_config();
    config.target = target == 1 ? HB_BLUR_TARGET_MALE : HB_BLUR_TARGET_FEMALE;
    if (hb_engine_update_config(engine, &config) != HB_STATUS_OK) {
        Throw(env, "java/lang/IllegalArgumentException", hb_engine_last_error(engine));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_halalify_kotlin_model_NativeVisionEngine_nativeDestroy(
        JNIEnv* /* env */,
        jobject /* instance */,
        jlong handle) {
    hb_engine_destroy(FromHandle(handle));
}
