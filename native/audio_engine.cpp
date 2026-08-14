#include "audio_engine.h"

#include <new>
#include <utility>

#include "backends/audio_litert_backend.h"
#include "core/audio_signal.h"

namespace halalify {

AudioEngine::AudioEngine(std::unique_ptr<AudioInferenceBackend> backend)
    : backend_(std::move(backend)) {}

bool AudioEngine::ValidateConfig(const ha_audio_config& config, std::string* error) const {
    if (config.sample_rate < 8000 || config.sample_rate > 192000 ||
        config.frame_samples <= 0 || config.frame_samples > config.sample_rate * 30 ||
        config.num_threads <= 0 || config.num_threads > 32 ||
        config.music_threshold < 0.0F || config.music_threshold > 1.0F) {
        if (error) *error = "Audio sample rate, frame size, threshold, or thread count is invalid.";
        return false;
    }
    return true;
}

bool AudioEngine::Initialize(
        const uint8_t* model_data,
        size_t model_size,
        const ha_audio_config& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ValidateConfig(config, &last_error_)) return false;
    if (!backend_->Load(
            model_data,
            model_size,
            static_cast<size_t>(config.frame_samples),
            config.num_threads,
            &last_error_)) {
        return false;
    }
    config_ = config;
    initialized_ = true;
    last_error_.clear();
    return true;
}

ha_status AudioEngine::Process(
        const int16_t* input_pcm,
        size_t input_samples,
        int16_t* speech_pcm,
        size_t speech_capacity,
        ha_audio_result* result) {
    std::lock_guard<std::mutex> lock(mutex_);
    const size_t required = static_cast<size_t>(config_.frame_samples);
    if (!initialized_ || input_pcm == nullptr || speech_pcm == nullptr || result == nullptr ||
        input_samples != required || speech_capacity < required) {
        last_error_ = "Audio engine received an invalid PCM frame or output buffer.";
        return HA_STATUS_INVALID_ARGUMENT;
    }
    NormalizePcm16(input_pcm, input_samples, &input_);
    if (!backend_->Invoke(input_.data(), input_.size(), &output_, &last_error_)) {
        return HA_STATUS_INFERENCE_ERROR;
    }
    if (output_.size() != required) {
        last_error_ = "Audio inference output does not match the configured frame size.";
        return HA_STATUS_INFERENCE_ERROR;
    }
    FloatToPcm16(output_.data(), output_.size(), speech_pcm);
    result->music_score = ResidualEnergyRatio(input_.data(), output_.data(), input_.size());
    result->music_detected = result->music_score >= config_.music_threshold ? 1 : 0;
    last_error_.clear();
    return HA_STATUS_OK;
}

std::string AudioEngine::LastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_error_;
}

}  // namespace halalify

struct ha_audio_engine {
    std::unique_ptr<halalify::AudioEngine> impl;
    mutable std::string error_snapshot;
};

extern "C" {

ha_audio_config ha_audio_default_config(void) {
    ha_audio_config config{};
    config.sample_rate = 16000;
    config.frame_samples = 16000;
    // Prefer lower sustained CPU power over parallel throughput for the
    // continuous playback-monitoring path.
    config.num_threads = 1;
    config.music_threshold = 0.12F;
    return config;
}

ha_status ha_audio_engine_create_from_buffer(
        const uint8_t* model_data,
        size_t model_size,
        const ha_audio_config* config,
        ha_audio_engine** out_engine) {
    if (model_data == nullptr || model_size == 0 || config == nullptr || out_engine == nullptr) {
        return HA_STATUS_INVALID_ARGUMENT;
    }
    *out_engine = nullptr;
    std::unique_ptr<ha_audio_engine> engine(new (std::nothrow) ha_audio_engine{});
    if (!engine) return HA_STATUS_MODEL_ERROR;
    engine->impl = std::make_unique<halalify::AudioEngine>(
            std::make_unique<halalify::AudioLiteRtBackend>());
    if (!engine->impl->Initialize(model_data, model_size, *config)) {
        return HA_STATUS_MODEL_ERROR;
    }
    *out_engine = engine.release();
    return HA_STATUS_OK;
}

ha_status ha_audio_engine_process(
        ha_audio_engine* engine,
        const int16_t* input_pcm,
        size_t input_samples,
        int16_t* speech_pcm,
        size_t speech_capacity,
        ha_audio_result* result) {
    if (engine == nullptr) return HA_STATUS_INVALID_ARGUMENT;
    return engine->impl->Process(
            input_pcm,
            input_samples,
            speech_pcm,
            speech_capacity,
            result);
}

const char* ha_audio_engine_last_error(const ha_audio_engine* engine) {
    if (engine == nullptr) return "Audio engine is null.";
    engine->error_snapshot = engine->impl->LastError();
    return engine->error_snapshot.c_str();
}

void ha_audio_engine_destroy(ha_audio_engine* engine) {
    delete engine;
}

}  // extern "C"
