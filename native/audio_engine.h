#ifndef HALALIFY_AUDIO_ENGINE_INTERNAL_H_
#define HALALIFY_AUDIO_ENGINE_INTERNAL_H_

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "backends/audio_inference_backend.h"
#include "halalify_audio.h"

namespace halalify {

class AudioEngine {
public:
    explicit AudioEngine(std::unique_ptr<AudioInferenceBackend> backend);
    bool Initialize(
            const uint8_t* model_data,
            size_t model_size,
            const ha_audio_config& config);
    ha_status Process(
            const int16_t* input_pcm,
            size_t input_samples,
            int16_t* speech_pcm,
            size_t speech_capacity,
            ha_audio_result* result);
    std::string LastError() const;

private:
    bool ValidateConfig(const ha_audio_config& config, std::string* error) const;

    std::unique_ptr<AudioInferenceBackend> backend_;
    mutable std::mutex mutex_;
    ha_audio_config config_{};
    std::vector<float> input_;
    std::vector<float> output_;
    std::string last_error_;
    bool initialized_ = false;
};

}  // namespace halalify

#endif  // HALALIFY_AUDIO_ENGINE_INTERNAL_H_
