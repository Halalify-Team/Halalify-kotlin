#ifndef HALALIFY_CORE_FRAME_H_
#define HALALIFY_CORE_FRAME_H_

#include <cstdint>

#include "halalify_vision.h"

namespace halalify {

constexpr int kModelWidth = 416;
constexpr int kModelHeight = 416;
constexpr int kModelChannels = 3;
constexpr int kOutputChannels = 7;
constexpr int kOutputCandidates = 3549;

struct FrameRegion {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;
};

struct FrameTransform {
    int upright_width = 0;
    int upright_height = 0;
    int resized_width = 0;
    int resized_height = 0;
    float scale = 1.0F;
    float pad_x = 0.0F;
    float pad_y = 0.0F;
    int crop_x = 0;
    int crop_y = 0;
    int crop_width = 0;
    int crop_height = 0;
};

}  // namespace halalify

#endif  // HALALIFY_CORE_FRAME_H_
