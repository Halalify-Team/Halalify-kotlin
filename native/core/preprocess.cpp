#include "preprocess.h"

#include <algorithm>
#include <cmath>

namespace halalify {
namespace {

bool IsRotationValid(int rotation) {
    return rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270;
}

void UprightToSource(
        const hb_frame& frame,
        float upright_x,
        float upright_y,
        float* source_x,
        float* source_y) {
    switch (frame.rotation_degrees) {
        case 90:
            *source_x = upright_y;
            *source_y = static_cast<float>(frame.height - 1) - upright_x;
            break;
        case 180:
            *source_x = static_cast<float>(frame.width - 1) - upright_x;
            *source_y = static_cast<float>(frame.height - 1) - upright_y;
            break;
        case 270:
            *source_x = static_cast<float>(frame.width - 1) - upright_y;
            *source_y = upright_x;
            break;
        default:
            *source_x = upright_x;
            *source_y = upright_y;
            break;
    }
}

float ReadChannel(const hb_frame& frame, int x, int y, int rgb_channel) {
    x = std::clamp(x, 0, frame.width - 1);
    y = std::clamp(y, 0, frame.height - 1);
    const uint8_t* pixel = frame.data + static_cast<size_t>(y) * frame.row_stride + x * 4;
    int source_channel = rgb_channel;
    if (frame.pixel_format == HB_PIXEL_FORMAT_BGRA8888) {
        if (rgb_channel == 0) source_channel = 2;
        if (rgb_channel == 2) source_channel = 0;
    }
    return static_cast<float>(pixel[source_channel]);
}

float BilinearChannel(const hb_frame& frame, float source_x, float source_y, int channel) {
    source_x = std::clamp(source_x, 0.0F, static_cast<float>(frame.width - 1));
    source_y = std::clamp(source_y, 0.0F, static_cast<float>(frame.height - 1));
    const int x0 = static_cast<int>(std::floor(source_x));
    const int y0 = static_cast<int>(std::floor(source_y));
    const int x1 = std::min(x0 + 1, frame.width - 1);
    const int y1 = std::min(y0 + 1, frame.height - 1);
    const float fx = source_x - static_cast<float>(x0);
    const float fy = source_y - static_cast<float>(y0);
    const float top = ReadChannel(frame, x0, y0, channel) * (1.0F - fx) +
                      ReadChannel(frame, x1, y0, channel) * fx;
    const float bottom = ReadChannel(frame, x0, y1, channel) * (1.0F - fx) +
                         ReadChannel(frame, x1, y1, channel) * fx;
    return top * (1.0F - fy) + bottom * fy;
}

}  // namespace

bool PreprocessFrame(
        const hb_frame& frame,
        std::vector<float>* output,
        FrameTransform* transform,
        std::string* error) {
    if (output == nullptr || transform == nullptr) {
        if (error) *error = "Preprocess output arguments are null.";
        return false;
    }
    if (frame.data == nullptr || frame.width <= 0 || frame.height <= 0 ||
        frame.row_stride < frame.width * 4) {
        if (error) *error = "Frame data, dimensions, or row stride are invalid.";
        return false;
    }
    if (!IsRotationValid(frame.rotation_degrees)) {
        if (error) *error = "rotation_degrees must be 0, 90, 180, or 270.";
        return false;
    }
    if (frame.pixel_format != HB_PIXEL_FORMAT_RGBA8888 &&
        frame.pixel_format != HB_PIXEL_FORMAT_BGRA8888) {
        if (error) *error = "Only RGBA8888 and BGRA8888 are supported.";
        return false;
    }

    transform->upright_width =
            (frame.rotation_degrees == 90 || frame.rotation_degrees == 270)
                    ? frame.height
                    : frame.width;
    transform->upright_height =
            (frame.rotation_degrees == 90 || frame.rotation_degrees == 270)
                    ? frame.width
                    : frame.height;
    transform->scale = std::min(
            static_cast<float>(kModelWidth) / transform->upright_width,
            static_cast<float>(kModelHeight) / transform->upright_height);
    transform->resized_width = std::max(
            1, static_cast<int>(std::round(transform->upright_width * transform->scale)));
    transform->resized_height = std::max(
            1, static_cast<int>(std::round(transform->upright_height * transform->scale)));
    transform->pad_x = static_cast<float>(kModelWidth - transform->resized_width) * 0.5F;
    transform->pad_y = static_cast<float>(kModelHeight - transform->resized_height) * 0.5F;

    constexpr float kPadding = 114.0F / 255.0F;
    output->assign(kModelWidth * kModelHeight * kModelChannels, kPadding);
    for (int model_y = 0; model_y < kModelHeight; ++model_y) {
        const float resized_y = static_cast<float>(model_y) - transform->pad_y;
        if (resized_y < 0.0F || resized_y >= transform->resized_height) continue;
        for (int model_x = 0; model_x < kModelWidth; ++model_x) {
            const float resized_x = static_cast<float>(model_x) - transform->pad_x;
            if (resized_x < 0.0F || resized_x >= transform->resized_width) continue;

            const float upright_x = (resized_x + 0.5F) / transform->scale - 0.5F;
            const float upright_y = (resized_y + 0.5F) / transform->scale - 0.5F;
            float source_x = 0.0F;
            float source_y = 0.0F;
            UprightToSource(frame, upright_x, upright_y, &source_x, &source_y);
            const size_t index =
                    (static_cast<size_t>(model_y) * kModelWidth + model_x) * kModelChannels;
            for (int channel = 0; channel < kModelChannels; ++channel) {
                (*output)[index + channel] =
                        BilinearChannel(frame, source_x, source_y, channel) / 255.0F;
            }
        }
    }
    return true;
}

}  // namespace halalify
