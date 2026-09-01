#ifndef HALALIFY_AI_ENGINE_H_
#define HALALIFY_AI_ENGINE_H_

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "backends/inference_backend.h"
#include "backends/nsfw_backend.h"
#include "halalify_vision.h"

namespace halalify {

class NsfwClassifier;
class VisionEngine;

// Shared orchestration layer for Android and future iOS adapters. It owns the
// gender detector and NSFW classifier, while platform code only supplies model
// bytes and an implementation of each inference backend.
class AiEngine {
public:
    AiEngine(
            std::unique_ptr<InferenceBackend> gender_backend,
            std::unique_ptr<NsfwBackend> nsfw_backend,
            std::unique_ptr<InferenceBackend> parallel_gender_backend = nullptr);
    ~AiEngine();

    bool Initialize(
            const uint8_t* gender_model_data,
            size_t gender_model_size,
            const uint8_t* nsfw_model_data,
            size_t nsfw_model_size,
            const hb_config& config);
    hb_status Process(const hb_frame& frame, std::vector<hb_detection>* detections);
    void RestartAnalysisCycle();
    hb_status UpdateConfig(const hb_config& config);
    std::string LastError() const;

private:
    bool ValidateConfig(const hb_config& config, std::string* error) const;

    std::unique_ptr<VisionEngine> gender_engine_;
    std::unique_ptr<NsfwClassifier> nsfw_classifier_;
    mutable std::mutex mutex_;
    hb_config config_{};
    std::string last_error_;
    bool initialized_ = false;
};

}  // namespace halalify

#endif  // HALALIFY_AI_ENGINE_H_
