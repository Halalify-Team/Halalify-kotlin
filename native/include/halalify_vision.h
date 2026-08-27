#ifndef HALALIFY_VISION_H_
#define HALALIFY_VISION_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct hb_engine hb_engine;

typedef enum hb_status {
    HB_STATUS_OK = 0,
    HB_STATUS_INVALID_ARGUMENT = 1,
    HB_STATUS_MODEL_ERROR = 2,
    HB_STATUS_INFERENCE_ERROR = 3,
    HB_STATUS_BUFFER_TOO_SMALL = 4,
} hb_status;

typedef enum hb_pixel_format {
    HB_PIXEL_FORMAT_RGBA8888 = 0,
    HB_PIXEL_FORMAT_BGRA8888 = 1,
} hb_pixel_format;

typedef enum hb_blur_target {
    HB_BLUR_TARGET_FEMALE = 0,
    HB_BLUR_TARGET_MALE = 1,
} hb_blur_target;

typedef struct hb_frame {
    const uint8_t* data;
    int32_t width;
    int32_t height;
    int32_t row_stride;
    int32_t rotation_degrees;
    int64_t timestamp_ns;
    hb_pixel_format pixel_format;
} hb_frame;

typedef struct hb_detection {
    float x1;
    float y1;
    float x2;
    float y2;
    float confidence;
    int32_t class_id;
    int64_t track_id;
    uint8_t should_blur;
    uint8_t is_nsfw;
} hb_detection;

typedef struct hb_config {
    hb_blur_target target;
    float female_confidence_threshold;
    float male_confidence_threshold;
    float ignored_confidence_threshold;
    float iou_threshold;
    int32_t max_detections;
    int32_t num_threads;
    float nsfw_confidence_threshold;
    int32_t max_nsfw_regions;
} hb_config;

typedef struct hb_model_info {
    const char* model_id;
    const char* version;
    const char* sha256;
    int32_t input_width;
    int32_t input_height;
    int32_t output_channels;
    int32_t output_candidates;
} hb_model_info;

hb_config hb_default_config(void);

hb_status hb_engine_create_from_buffer(
        const uint8_t* model_data,
        size_t model_size,
        const hb_config* config,
        hb_engine** out_engine);

// Creates the unified native AI Engine. The gender detector and NSFW classifier
// are loaded together so platform adapters only pass model bytes and frames.
hb_status hb_engine_create_from_buffers(
        const uint8_t* gender_model_data,
        size_t gender_model_size,
        const uint8_t* nsfw_model_data,
        size_t nsfw_model_size,
        const hb_config* config,
        hb_engine** out_engine);

hb_status hb_engine_process(
        hb_engine* engine,
        const hb_frame* frame,
        hb_detection* detections,
        size_t detection_capacity,
        size_t* out_detection_count);

// Restarts portrait analysis at the full-screen pass. Detail tiles follow on
// subsequent calls.
hb_status hb_engine_restart_analysis_cycle(hb_engine* engine);
hb_status hb_engine_update_config(hb_engine* engine, const hb_config* config);
hb_model_info hb_engine_get_model_info(const hb_engine* engine);
const char* hb_engine_last_error(const hb_engine* engine);
void hb_engine_destroy(hb_engine* engine);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // HALALIFY_VISION_H_
