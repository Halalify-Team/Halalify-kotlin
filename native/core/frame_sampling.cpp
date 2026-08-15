#include "frame_sampling.h"

#include <algorithm>
#include <cmath>

namespace halalify {
namespace {

constexpr int kInputSize = 224;
constexpr int kSourceSize = 256;
constexpr int kChannels = 3;
constexpr float kCropPaddingRatio = 0.05F;
constexpr float kVggMean[kChannels] = {103.939F, 116.779F, 123.68F};

bool IsRotationValid(int rotation) {
    return rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270;
}

bool ValidateFrame(const hb_frame& frame, std::string* error) {
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
    return true;
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

float ReadRgbChannel(const hb_frame& frame, int x, int y, int rgb_channel) {
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

float BilinearRgbChannel(const hb_frame& frame, float source_x, float source_y, int channel) {
    source_x = std::clamp(source_x, 0.0F, static_cast<float>(frame.width - 1));
    source_y = std::clamp(source_y, 0.0F, static_cast<float>(frame.height - 1));
    const int x0 = static_cast<int>(std::floor(source_x));
    const int y0 = static_cast<int>(std::floor(source_y));
    const int x1 = std::min(x0 + 1, frame.width - 1);
    const int y1 = std::min(y0 + 1, frame.height - 1);
    const float fx = source_x - static_cast<float>(x0);
    const float fy = source_y - static_cast<float>(y0);
    const float top = ReadRgbChannel(frame, x0, y0, channel) * (1.0F - fx) +
                      ReadRgbChannel(frame, x1, y0, channel) * fx;
    const float bottom = ReadRgbChannel(frame, x0, y1, channel) * (1.0F - fx) +
                         ReadRgbChannel(frame, x1, y1, channel) * fx;
    return top * (1.0F - fy) + bottom * fy;
}

}  // namespace

bool PreprocessNsfwRegion(
        const hb_frame& frame,
        const NormalizedRect& region,
        std::vector<float>* output,
        std::string* error) {
    if (output == nullptr) {
        if (error) *error = "NSFW preprocess output is null.";
        return false;
    }
    if (!ValidateFrame(frame, error)) return false;

    const int upright_width =
            (frame.rotation_degrees == 90 || frame.rotation_degrees == 270)
                    ? frame.height
                    : frame.width;
    const int upright_height =
            (frame.rotation_degrees == 90 || frame.rotation_degrees == 270)
                    ? frame.width
                    : frame.height;
    const float width = static_cast<float>(upright_width);
    const float height = static_cast<float>(upright_height);
    const float raw_x1 = std::clamp(region.x1, 0.0F, 1.0F) * width;
    const float raw_y1 = std::clamp(region.y1, 0.0F, 1.0F) * height;
    const float raw_x2 = std::clamp(region.x2, 0.0F, 1.0F) * width;
    const float raw_y2 = std::clamp(region.y2, 0.0F, 1.0F) * height;
    const float padding_x = (raw_x2 - raw_x1) * kCropPaddingRatio;
    const float padding_y = (raw_y2 - raw_y1) * kCropPaddingRatio;
    const float crop_x1 = std::clamp(raw_x1 - padding_x, 0.0F, width - 1.0F);
    const float crop_y1 = std::clamp(raw_y1 - padding_y, 0.0F, height - 1.0F);
    const float crop_x2 = std::clamp(raw_x2 + padding_x, crop_x1 + 1.0F, width);
    const float crop_y2 = std::clamp(raw_y2 + padding_y, crop_y1 + 1.0F, height);
    const float crop_width = crop_x2 - crop_x1;
    const float crop_height = crop_y2 - crop_y1;

    output->resize(static_cast<size_t>(kInputSize) * kInputSize * kChannels);
    constexpr int kCenterCropOffset = (kSourceSize - kInputSize) / 2;
    for (int model_y = 0; model_y < kInputSize; ++model_y) {
        const float resized_y = static_cast<float>(model_y + kCenterCropOffset) + 0.5F;
        const float upright_y = crop_y1 + resized_y / kSourceSize * crop_height - 0.5F;
        for (int model_x = 0; model_x < kInputSize; ++model_x) {
            const float resized_x = static_cast<float>(model_x + kCenterCropOffset) + 0.5F;
            const float upright_x = crop_x1 + resized_x / kSourceSize * crop_width - 0.5F;
            float source_x = 0.0F;
            float source_y = 0.0F;
            UprightToSource(frame, upright_x, upright_y, &source_x, &source_y);
            const size_t index =
                    (static_cast<size_t>(model_y) * kInputSize + model_x) * kChannels;
            // Open-NSFW's reference adapter feeds BGR, while ReadRgbChannel uses RGB ids.
            for (int channel = 0; channel < kChannels; ++channel) {
                const int rgb_channel = kChannels - channel - 1;
                (*output)[index + channel] =
                        BilinearRgbChannel(frame, source_x, source_y, rgb_channel) -
                        kVggMean[channel];
            }
        }
    }
    return true;
}

}  // namespace halalify
