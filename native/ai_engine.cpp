#include "ai_engine.h"

#include <algorithm>
#include <utility>

#include "nsfw_classifier.h"
#include "vision_engine.h"

namespace halalify {

AiEngine::AiEngine(
        std::unique_ptr<InferenceBackend> gender_backend,
        std::unique_ptr<NsfwBackend> nsfw_backend,
        std::unique_ptr<InferenceBackend> parallel_gender_backend)
    : gender_engine_(std::make_unique<VisionEngine>(
              std::move(gender_backend),
              std::move(parallel_gender_backend))) {
    if (nsfw_backend != nullptr) {
        nsfw_classifier_ = std::make_unique<NsfwClassifier>(std::move(nsfw_backend));
    }
}

AiEngine::~AiEngine() = default;

bool AiEngine::ValidateConfig(const hb_config& config, std::string* error) const {
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
        config.num_threads <= 0 || config.num_threads > 32 ||
        config.nsfw_confidence_threshold < 0.0F ||
        config.nsfw_confidence_threshold > 1.0F ||
        config.max_nsfw_regions < 0 || config.max_nsfw_regions > 32) {
        if (error) *error = "AI Engine thresholds, limits, or thread count are invalid.";
        return false;
    }
    return true;
}

bool AiEngine::Initialize(
        const uint8_t* gender_model_data,
        size_t gender_model_size,
        const uint8_t* nsfw_model_data,
        size_t nsfw_model_size,
        const hb_config& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ValidateConfig(config, &last_error_)) return false;
    if (!gender_engine_->Initialize(gender_model_data, gender_model_size, config)) {
        last_error_ = gender_engine_->LastError();
        return false;
    }
    if (nsfw_classifier_ != nullptr &&
        !nsfw_classifier_->Initialize(
                nsfw_model_data, nsfw_model_size, config.num_threads, &last_error_)) {
        return false;
    }
    config_ = config;
    initialized_ = true;
    last_error_.clear();
    return true;
}

hb_status AiEngine::Process(const hb_frame& frame, std::vector<hb_detection>* detections) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_ || detections == nullptr) {
        last_error_ = "AI Engine is not initialized or output is null.";
        return HB_STATUS_INVALID_ARGUMENT;
    }
    hb_status status = gender_engine_->Process(frame, detections);
    if (status != HB_STATUS_OK) {
        last_error_ = gender_engine_->LastError();
        return status;
    }
    if (nsfw_classifier_ == nullptr) {
        last_error_.clear();
        return HB_STATUS_OK;
    }

    // NSFW is a secondary check for an already selected target region. It
    // must not turn a male/ignored detection into a blur when the user chose
    // female (or vice versa), and it must not protect an unrelated full
    // screen just because a classifier produced a false positive.
    std::vector<size_t> candidates;
    candidates.reserve(detections->size());
    for (size_t index = 0; index < detections->size(); ++index) {
        if ((*detections)[index].should_blur != 0) candidates.push_back(index);
    }
    std::stable_sort(candidates.begin(), candidates.end(), [&](size_t left, size_t right) {
        return (*detections)[left].confidence > (*detections)[right].confidence;
    });
    const size_t region_count = std::min(
            candidates.size(), static_cast<size_t>(config_.max_nsfw_regions));
    for (size_t position = 0; position < region_count; ++position) {
        hb_detection& detection = (*detections)[candidates[position]];
        float score = 0.0F;
        const NormalizedRect region{detection.x1, detection.y1, detection.x2, detection.y2};
        if (!nsfw_classifier_->Score(frame, region, &score, &last_error_)) {
            return HB_STATUS_INFERENCE_ERROR;
        }
        if (score >= config_.nsfw_confidence_threshold) {
            detection.is_nsfw = 1;
        }
    }
    last_error_.clear();
    return HB_STATUS_OK;
}

void AiEngine::RestartAnalysisCycle() {
    std::lock_guard<std::mutex> lock(mutex_);
    gender_engine_->RestartAnalysisCycle();
}

hb_status AiEngine::UpdateConfig(const hb_config& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ValidateConfig(config, &last_error_)) return HB_STATUS_INVALID_ARGUMENT;
    const hb_status status = gender_engine_->UpdateConfig(config);
    if (status != HB_STATUS_OK) {
        last_error_ = gender_engine_->LastError();
        return status;
    }
    config_ = config;
    last_error_.clear();
    return HB_STATUS_OK;
}

std::string AiEngine::LastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_;
}

}  // namespace halalify
