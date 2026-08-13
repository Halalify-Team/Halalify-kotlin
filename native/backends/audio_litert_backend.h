#ifndef HALALIFY_BACKENDS_AUDIO_LITERT_BACKEND_H_
#define HALALIFY_BACKENDS_AUDIO_LITERT_BACKEND_H_

#include <vector>

#include "audio_inference_backend.h"

struct TfLiteInterpreter;
struct TfLiteInterpreterOptions;
struct TfLiteModel;

namespace halalify {

class AudioLiteRtBackend final : public AudioInferenceBackend {
public:
    AudioLiteRtBackend() = default;
    ~AudioLiteRtBackend() override;
    AudioLiteRtBackend(const AudioLiteRtBackend&) = delete;
    AudioLiteRtBackend& operator=(const AudioLiteRtBackend&) = delete;

    bool Load(
            const uint8_t* model_data,
            size_t model_size,
            size_t frame_samples,
            int num_threads,
            std::string* error) override;
    bool Invoke(
            const float* input,
            size_t input_count,
            std::vector<float>* output,
            std::string* error) override;

private:
    void Reset();
    size_t frame_samples_ = 0;
    std::vector<uint8_t> model_bytes_;
    TfLiteModel* model_ = nullptr;
    TfLiteInterpreterOptions* options_ = nullptr;
    TfLiteInterpreter* interpreter_ = nullptr;
};

}  // namespace halalify

#endif  // HALALIFY_BACKENDS_AUDIO_LITERT_BACKEND_H_
