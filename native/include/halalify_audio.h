#ifndef HALALIFY_AUDIO_H_
#define HALALIFY_AUDIO_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ha_audio_engine ha_audio_engine;

typedef enum ha_status {
    HA_STATUS_OK = 0,
    HA_STATUS_INVALID_ARGUMENT = 1,
    HA_STATUS_MODEL_ERROR = 2,
    HA_STATUS_INFERENCE_ERROR = 3,
} ha_status;

typedef struct ha_audio_config {
    int32_t sample_rate;
    int32_t frame_samples;
    int32_t num_threads;
    float music_threshold;
} ha_audio_config;

typedef struct ha_audio_result {
    float music_score;
    uint8_t music_detected;
} ha_audio_result;

ha_audio_config ha_audio_default_config(void);

/**
 * Creates a waveform separator. The TFLite model must have one float32 input and one float32
 * output, each containing exactly frame_samples values. Input is normalized mono PCM in [-1, 1]
 * and output is the speech-only waveform in the same range.
 */
ha_status ha_audio_engine_create_from_buffer(
        const uint8_t* model_data,
        size_t model_size,
        const ha_audio_config* config,
        ha_audio_engine** out_engine);

ha_status ha_audio_engine_process(
        ha_audio_engine* engine,
        const int16_t* input_pcm,
        size_t input_samples,
        int16_t* speech_pcm,
        size_t speech_capacity,
        ha_audio_result* result);

const char* ha_audio_engine_last_error(const ha_audio_engine* engine);
void ha_audio_engine_destroy(ha_audio_engine* engine);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // HALALIFY_AUDIO_H_
