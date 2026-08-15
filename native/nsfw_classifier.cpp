#include "nsfw_classifier.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace halalify {

NsfwClassifier::NsfwClassifier(std::unique_ptr<NsfwBackend> backend)
    : backend_(std::move(backend)) {}

bool NsfwClassifier::Initialize(
        const uint8_t* model_data,
        size_t model_size,
        int num_threads,
        std::string* error) {
    if (backend_ == nullptr) {
        if (error) *error = "NSFW backend is not available on this platform.";
        return false;
    }
    if (!backend_->Load(model_data, model_size, num_threads, error)) return false;
    initialized_ = true;
    input_.clear();
    output_.clear();
    return true;
}

bool NsfwClassifier::Score(
        const hb_frame& frame,
        const NormalizedRect& region,
        float* score,
        std::string* error) {
    if (!initialized_ || score == nullptr) {
        if (error) *error = "NSFW classifier is not initialized or score is null.";
        return false;
    }
    if (!PreprocessNsfwRegion(frame, region, &input_, error)) return false;
    if (!backend_->Invoke(input_.data(), input_.size(), &output_, error)) return false;
    if (output_.size() != 2 || !std::isfinite(output_[1])) {
        if (error) *error = "NSFW model output must contain two finite scores.";
        return false;
    }
    *score = std::clamp(output_[1], 0.0F, 1.0F);
    return true;
}

}  // namespace halalify
