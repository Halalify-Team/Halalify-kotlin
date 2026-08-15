#include "vision_engine.h"

#include <algorithm>
#include <cstring>
#include <new>
#include <utility>

#include "ai_engine.h"
#include "backends/litert_backend.h"
#include "backends/litert_nsfw_backend.h"
#include "core/postprocess.h"
#include "core/preprocess.h"

namespace halalify {

VisionEngine::VisionEngine(std::unique_ptr<InferenceBackend> backend)
    : backend_(std::move(backend)) {}

bool VisionEngine::ValidateConfig(const hb_config& config, std::string* error) const {
    if (config.target != HB_BLUR_TARGET_FEMALE && config.target != HB_BLUR_TARGET_MALE) {
        if (error) *error = "Blur target is invalid.";
        return false;
    }
    if (config.female_confidence_threshold < 0.0F ||
        config.female_confidence_threshold > 1.0F ||
        config.male_confidence_threshold < 0.0F ||
        config.male_confidence_threshold > 1.0F ||
        config.ignored_confidence_threshold < 0.0F ||
        config.ignored_confidence_threshold > 1.0F ||
        config.iou_threshold < 0.0F || config.iou_threshold > 1.0F ||
        config.max_detections <= 0 || config.max_detections > 1000 ||
        config.num_threads <= 0 || config.num_threads > 32) {
        if (error) *error = "Engine thresholds, limits, or thread count are invalid.";
        return false;
    }
    return true;
}

bool VisionEngine::Initialize(
        const uint8_t* model_data,
        size_t model_size,
        const hb_config& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ValidateConfig(config, &last_error_)) return false;
    if (!backend_->Load(model_data, model_size, config.num_threads, &last_error_)) return false;
    config_ = config;
    initialized_ = true;
    last_error_.clear();
    return true;
}

hb_status VisionEngine::Process(const hb_frame& frame, std::vector<hb_detection>* detections) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_ || detections == nullptr) {
        last_error_ = "Vision engine is not initialized or output is null.";
        return HB_STATUS_INVALID_ARGUMENT;
    }
    FrameTransform transform;
    if (!PreprocessFrame(frame, &input_, &transform, &last_error_)) {
        return HB_STATUS_INVALID_ARGUMENT;
    }
    if (!backend_->Invoke(input_.data(), input_.size(), &output_, &last_error_)) {
        return HB_STATUS_INFERENCE_ERROR;
    }
    if (!DecodeDetections(
                output_.data(), output_.size(), transform, config_, detections, &last_error_)) {
        return HB_STATUS_INFERENCE_ERROR;
    }
    last_error_.clear();
    return HB_STATUS_OK;
}

hb_status VisionEngine::UpdateConfig(const hb_config& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ValidateConfig(config, &last_error_)) return HB_STATUS_INVALID_ARGUMENT;
    config_ = config;
    last_error_.clear();
    return HB_STATUS_OK;
}

std::string VisionEngine::LastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_;
}

}  // namespace halalify

struct hb_engine {
    std::unique_ptr<halalify::AiEngine> impl;
    mutable std::string error_snapshot;
};

extern "C" {

hb_config hb_default_config(void) {
    hb_config config{};
    config.target = HB_BLUR_TARGET_FEMALE;
    config.female_confidence_threshold = 0.25F;
    config.male_confidence_threshold = 0.25F;
    config.ignored_confidence_threshold = 0.25F;
    config.iou_threshold = 0.5F;
    config.max_detections = 100;
    // One thread reduces sustained CPU wakeups and battery drain on phones.
    // The capture loop is intentionally rate-limited on the Kotlin side.
    config.num_threads = 1;
    config.nsfw_confidence_threshold = 0.70F;
    config.max_nsfw_regions = 8;
    return config;
}

hb_status hb_engine_create_from_buffer(
        const uint8_t* model_data,
        size_t model_size,
        const hb_config* config,
        hb_engine** out_engine) {
    if (model_data == nullptr || model_size == 0 || config == nullptr || out_engine == nullptr) {
        return HB_STATUS_INVALID_ARGUMENT;
    }
    *out_engine = nullptr;
    std::unique_ptr<hb_engine> engine(new (std::nothrow) hb_engine{});
    if (!engine) return HB_STATUS_MODEL_ERROR;
    engine->impl = std::make_unique<halalify::AiEngine>(
            std::make_unique<halalify::LiteRtBackend>(), nullptr);
    if (!engine->impl->Initialize(model_data, model_size, nullptr, 0, *config)) {
        return HB_STATUS_MODEL_ERROR;
    }
    *out_engine = engine.release();
    return HB_STATUS_OK;
}

hb_status hb_engine_create_from_buffers(
        const uint8_t* gender_model_data,
        size_t gender_model_size,
        const uint8_t* nsfw_model_data,
        size_t nsfw_model_size,
        const hb_config* config,
        hb_engine** out_engine) {
    if (gender_model_data == nullptr || gender_model_size == 0 ||
        nsfw_model_data == nullptr || nsfw_model_size == 0 || config == nullptr ||
        out_engine == nullptr) {
        return HB_STATUS_INVALID_ARGUMENT;
    }
    *out_engine = nullptr;
    std::unique_ptr<hb_engine> engine(new (std::nothrow) hb_engine{});
    if (!engine) return HB_STATUS_MODEL_ERROR;
    engine->impl = std::make_unique<halalify::AiEngine>(
            std::make_unique<halalify::LiteRtBackend>(),
            std::make_unique<halalify::LiteRtNsfwBackend>());
    if (!engine->impl->Initialize(
                gender_model_data,
                gender_model_size,
                nsfw_model_data,
                nsfw_model_size,
                *config)) {
        return HB_STATUS_MODEL_ERROR;
    }
    *out_engine = engine.release();
    return HB_STATUS_OK;
}

hb_status hb_engine_process(
        hb_engine* engine,
        const hb_frame* frame,
        hb_detection* detections,
        size_t detection_capacity,
        size_t* out_detection_count) {
    if (engine == nullptr || frame == nullptr || out_detection_count == nullptr ||
        (detection_capacity > 0 && detections == nullptr)) {
        return HB_STATUS_INVALID_ARGUMENT;
    }
    std::vector<hb_detection> decoded;
    const hb_status status = engine->impl->Process(*frame, &decoded);
    if (status != HB_STATUS_OK) return status;
    *out_detection_count = decoded.size();
    if (decoded.size() > detection_capacity) return HB_STATUS_BUFFER_TOO_SMALL;
    if (!decoded.empty()) {
        std::copy(decoded.begin(), decoded.end(), detections);
    }
    return HB_STATUS_OK;
}

hb_status hb_engine_update_config(hb_engine* engine, const hb_config* config) {
    if (engine == nullptr || config == nullptr) return HB_STATUS_INVALID_ARGUMENT;
    return engine->impl->UpdateConfig(*config);
}

hb_model_info hb_engine_get_model_info(const hb_engine* engine) {
    (void)engine;
    return {
            "halalify-gender-detector-v3-full-int8-split-output",
            "3.0.0",
            "65E2B0BE46BC7E865EEDD2E084E6FC8CCB4258C81213A3EBE1D8333218A503B0",
            halalify::kModelWidth,
            halalify::kModelHeight,
            halalify::kOutputChannels,
            halalify::kOutputCandidates,
    };
}

const char* hb_engine_last_error(const hb_engine* engine) {
    if (engine == nullptr) return "Vision engine is null.";
    engine->error_snapshot = engine->impl->LastError();
    return engine->error_snapshot.c_str();
}

void hb_engine_destroy(hb_engine* engine) {
    delete engine;
}

}  // extern "C"
