#ifndef HALALIFY_NSFW_CLASSIFIER_H_
#define HALALIFY_NSFW_CLASSIFIER_H_

#include <memory>
#include <string>
#include <vector>

#include "backends/nsfw_backend.h"
#include "core/frame_sampling.h"

namespace halalify {

class NsfwClassifier {
public:
    explicit NsfwClassifier(std::unique_ptr<NsfwBackend> backend);

    bool Initialize(
            const uint8_t* model_data,
            size_t model_size,
            int num_threads,
            std::string* error);
    bool Score(
            const hb_frame& frame,
            const NormalizedRect& region,
            float* score,
            std::string* error);

private:
    std::unique_ptr<NsfwBackend> backend_;
    std::vector<float> input_;
    std::vector<float> output_;
    bool initialized_ = false;
};

}  // namespace halalify

#endif  // HALALIFY_NSFW_CLASSIFIER_H_
