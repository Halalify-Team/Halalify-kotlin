#ifndef HALALIFY_BACKENDS_AUDIO_INFERENCE_BACKEND_H_
#define HALALIFY_BACKENDS_AUDIO_INFERENCE_BACKEND_H_

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace halalify {

class AudioInferenceBackend {
public:
    virtual ~AudioInferenceBackend() = default;
    virtual bool Load(
            const uint8_t* model_data,
            size_t model_size,
            size_t frame_samples,
            int num_threads,
            std::string* error) = 0;
    virtual bool Invoke(
            const float* input,
            size_t input_count,
            std::vector<float>* output,
            std::string* error) = 0;
};

}  // namespace halalify

#endif  // HALALIFY_BACKENDS_AUDIO_INFERENCE_BACKEND_H_
