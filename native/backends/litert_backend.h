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
    bool input_quantized_ = false;
    bool split_quantized_output_ = false;
    bool single_quantized_output_ = false;
    float input_scale_ = 1.0F;
    int input_zero_point_ = 0;
    size_t output_element_count_ = 0;
    int output_candidate_count_ = 0;
};

}  // namespace halalify

#endif  // HALALIFY_BACKENDS_LITERT_BACKEND_H_
