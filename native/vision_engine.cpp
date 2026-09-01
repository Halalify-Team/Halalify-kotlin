#include "vision_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>
#include <thread>
#include <utility>

#include <core/nms.h>
#include "ai_engine.h"
#include "backends/litert_backend.h"
#include "backends/litert_nsfw_backend.h"
#include "core/postprocess.h"
#include "core/preprocess.h"

namespace halalify {

VisionEngine::VisionEngine(
        std::unique_ptr<InferenceBackend> backend,
        std::unique_ptr<InferenceBackend> parallel_backend)
    : backend_(std::move(backend)),
      parallel_backend_(std::move(parallel_backend)) {}

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
    const int threads_per_backend =
            parallel_backend_ != nullptr
            ? std::max(1, config.num_threads / 2)
            : config.num_threads;
    if (!backend_->Load(
                model_data, model_size, threads_per_backend, &last_error_)) {
        return false;
    }
    if (parallel_backend_ != nullptr &&
        !parallel_backend_->Load(
                model_data, model_size, threads_per_backend, &last_error_)) {
        return false;
    }
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
    // A tall portrait screenshot loses too much detail when letterboxed into
    // one square. Analyze both overlapping portrait tiles from this exact
    // captured frame, merge them, and publish only the complete result. This
    // prevents protected regions from appearing one tile at a time.
    const bool swaps_axes = frame.rotation_degrees == 90 || frame.rotation_degrees == 270;
    const int upright_width = swaps_axes ? frame.height : frame.width;
    const int upright_height = swaps_axes ? frame.width : frame.height;
    constexpr float kPortraitRatio = 1.4F;
    constexpr float kDetailTileHeightInWidths = 1.25F;
    constexpr int kDetailTileCount = 2;
    std::vector<FrameRegion> regions;
    if (upright_height >= static_cast<int>(std::round(upright_width * kPortraitRatio))) {
        const int tile_height = std::min(
                upright_height,
                std::max(
                        upright_width,
                        static_cast<int>(
                                std::round(upright_width * kDetailTileHeightInWidths))));
        const int maximum_y = upright_height - tile_height;
        for (int tile_index = 0; tile_index < kDetailTileCount; ++tile_index) {
            const int tile_y = maximum_y * tile_index / (kDetailTileCount - 1);
            regions.push_back({0, tile_y, upright_width, tile_height});
        }
    } else {
        regions.push_back({});
    }

    std::vector<hb_detection> combined;
    if (regions.size() == 2 && parallel_backend_ != nullptr) {
        FrameTransform first_transform;
        FrameTransform second_transform;
        if (!PreprocessFrameRegion(
                    frame, regions[0], &input_, &first_transform, &last_error_)) {
            return HB_STATUS_INVALID_ARGUMENT;
        }
        if (!PreprocessFrameRegion(
                    frame, regions[1], &parallel_input_, &second_transform,
                    &last_error_)) {
            return HB_STATUS_INVALID_ARGUMENT;
        }

        bool parallel_ok = false;
        std::string parallel_error;
        std::thread parallel_inference([&]() {
            parallel_ok = parallel_backend_->Invoke(
                    parallel_input_.data(),
                    parallel_input_.size(),
                    &parallel_output_,
                    &parallel_error);
        });
        std::string primary_error;
        const bool primary_ok = backend_->Invoke(
                input_.data(), input_.size(), &output_, &primary_error);
        parallel_inference.join();
        if (!primary_ok || !parallel_ok) {
            last_error_ = primary_ok ? parallel_error : primary_error;
            return HB_STATUS_INFERENCE_ERROR;
        }

        std::vector<hb_detection> first_detections;
        if (!DecodeDetections(
                    output_.data(), output_.size(), first_transform, config_,
                    &first_detections, &last_error_)) {
            return HB_STATUS_INFERENCE_ERROR;
        }
        std::vector<hb_detection> second_detections;
        if (!DecodeDetections(
                    parallel_output_.data(), parallel_output_.size(),
                    second_transform, config_, &second_detections,
                    &last_error_)) {
            return HB_STATUS_INFERENCE_ERROR;
        }
        combined.insert(
                combined.end(), first_detections.begin(), first_detections.end());
        combined.insert(
                combined.end(), second_detections.begin(), second_detections.end());
    } else {
        for (const FrameRegion& region : regions) {
            FrameTransform transform;
            if (!PreprocessFrameRegion(frame, region, &input_, &transform, &last_error_)) {
                return HB_STATUS_INVALID_ARGUMENT;
            }
            if (!backend_->Invoke(input_.data(), input_.size(), &output_, &last_error_)) {
                return HB_STATUS_INFERENCE_ERROR;
            }
            std::vector<hb_detection> region_detections;
            if (!DecodeDetections(
                        output_.data(), output_.size(), transform, config_,
                        &region_detections, &last_error_)) {
                return HB_STATUS_INFERENCE_ERROR;
            }
            combined.insert(
                    combined.end(), region_detections.begin(), region_detections.end());
        }
    }
    *detections = ApplyClassAgnosticNms(
            std::move(combined), config_.iou_threshold, config_.max_detections);
    last_error_.clear();
    return HB_STATUS_OK;
}

void VisionEngine::RestartAnalysisCycle() {
    std::lock_guard<std::mutex> lock(mutex_);
    // Portrait processing is now an atomic two-tile batch, so there is no
    // rotating pass index to reset. Keep the API for adapter compatibility.
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
    // Keep the detector threshold aligned with the model's calibrated
    // benchmark threshold. The female/anime scores commonly fall between
    // 0.25 and 0.50; raising this to 0.50 makes valid subjects disappear.
    // False-positive control belongs to NMS/region validation, not by
    // discarding the lower-confidence female detections outright.
    config.female_confidence_threshold = 0.25F;
    config.male_confidence_threshold = 0.25F;
    config.ignored_confidence_threshold = 0.25F;
    config.iou_threshold = 0.5F;
    config.max_detections = 100;
    // Two threads are the total real-time detector budget. Atomic portrait
    // processing splits them across its two parallel interpreters.
    config.num_threads = 2;
    config.nsfw_confidence_threshold = 0.70F;
    // Female/male detections already make the localized protection decision.
    // The secondary NSFW score is informational and does not change
    // should_blur, so disable its per-region inference in the real-time screen
    // path. This removes up to two extra 224x224 model invocations per pass.
    config.max_nsfw_regions = 0;
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
            std::make_unique<halalify::LiteRtBackend>(),
            nullptr,
            std::make_unique<halalify::LiteRtBackend>());
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
            std::make_unique<halalify::LiteRtNsfwBackend>(),
            std::make_unique<halalify::LiteRtBackend>());
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

hb_status hb_engine_restart_analysis_cycle(hb_engine* engine) {
    if (engine == nullptr) return HB_STATUS_INVALID_ARGUMENT;
    engine->impl->RestartAnalysisCycle();
    return HB_STATUS_OK;
}

hb_status hb_engine_update_config(hb_engine* engine, const hb_config* config) {
    if (engine == nullptr || config == nullptr) return HB_STATUS_INVALID_ARGUMENT;
    return engine->impl->UpdateConfig(*config);
}

hb_model_info hb_engine_get_model_info(const hb_engine* engine) {
    (void)engine;
    return {
            "halalify_v2",
            "2.0.0",
            "44F0CEE3A5AABE2074042DC1D8AA50A02C703D9B0EB97D32082C78DC6A2C8945",
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
