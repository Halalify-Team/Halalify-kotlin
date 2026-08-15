#ifndef HALALIFY_BACKENDS_LITERT_NSFW_BACKEND_H_
#define HALALIFY_BACKENDS_LITERT_NSFW_BACKEND_H_

#include <vector>

#include "nsfw_backend.h"

struct TfLiteInterpreter;
struct TfLiteInterpreterOptions;
struct TfLiteModel;

namespace halalify {

class LiteRtNsfwBackend final : public NsfwBackend {
public:
    LiteRtNsfwBackend() = default;
    ~LiteRtNsfwBackend() override;
    LiteRtNsfwBackend(const LiteRtNsfwBackend&) = delete;
    LiteRtNsfwBackend& operator=(const LiteRtNsfwBackend&) = delete;

    bool Load(
            const uint8_t* model_data,
            size_t model_size,
            int num_threads,
            std::string* error) override;
    bool Invoke(
            const float* input,
            size_t input_count,
            std::vector<float>* output,
            std::string* error) override;

private:
    void Reset();

    std::vector<uint8_t> model_bytes_;
    TfLiteModel* model_ = nullptr;
    TfLiteInterpreterOptions* options_ = nullptr;
    TfLiteInterpreter* interpreter_ = nullptr;
};

}  // namespace halalify

#endif  // HALALIFY_BACKENDS_LITERT_NSFW_BACKEND_H_
