#ifndef HALALIFY_BACKENDS_LITERT_BACKEND_H_
#define HALALIFY_BACKENDS_LITERT_BACKEND_H_

#include <memory>
#include <vector>

#include "inference_backend.h"

struct TfLiteInterpreter;
struct TfLiteInterpreterOptions;
struct TfLiteModel;

namespace halalify {

class LiteRtBackend final : public InferenceBackend {
public:
    LiteRtBackend() = default;
    ~LiteRtBackend() override;
    LiteRtBackend(const LiteRtBackend&) = delete;
    LiteRtBackend& operator=(const LiteRtBackend&) = delete;

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

#endif  // HALALIFY_BACKENDS_LITERT_BACKEND_H_
