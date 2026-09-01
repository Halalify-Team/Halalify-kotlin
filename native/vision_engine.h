#ifndef HALALIFY_VISION_ENGINE_INTERNAL_H_
#define HALALIFY_VISION_ENGINE_INTERNAL_H_

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "backends/inference_backend.h"
#include "core/frame.h"

namespace halalify {

class VisionEngine {
public:
    VisionEngine(
            std::unique_ptr<InferenceBackend> backend,
            std::unique_ptr<InferenceBackend> parallel_backend = nullptr);
    bool Initialize(
            const uint8_t* model_data,
            size_t model_size,
            const hb_config& config);
    hb_status Process(const hb_frame& frame, std::vector<hb_detection>* detections);
    void RestartAnalysisCycle();
    hb_status UpdateConfig(const hb_config& config);
    std::string LastError() const;

private:
    bool ValidateConfig(const hb_config& config, std::string* error) const;

    std::unique_ptr<InferenceBackend> backend_;
    std::unique_ptr<InferenceBackend> parallel_backend_;
    mutable std::mutex mutex_;
    hb_config config_{};
    std::vector<float> input_;
    std::vector<float> output_;
    std::vector<float> parallel_input_;
    std::vector<float> parallel_output_;
    std::string last_error_;
    bool initialized_ = false;
};

}  // namespace halalify

#endif  // HALALIFY_VISION_ENGINE_INTERNAL_H_
