#include "postprocess.h"

#include <algorithm>
#include <array>
#include <cmath>

#include "nms.h"
#include "protection_policy.h"

namespace halalify {
namespace {

float ClassThreshold(const hb_config& config, int class_id) {
    if (class_id == 0) return config.female_confidence_threshold;
    if (class_id == 1) return config.male_confidence_threshold;
    return config.ignored_confidence_threshold;
}

float ChannelValue(const float* output, int channel, int candidate) {
    return output[static_cast<size_t>(channel) * kOutputCandidates + candidate];
}

}  // namespace

bool DecodeDetections(
        const float* output,
        size_t output_count,
        const FrameTransform& transform,
        const hb_config& config,
        std::vector<hb_detection>* detections,
        std::string* error) {
    if (output == nullptr || detections == nullptr ||
        output_count != static_cast<size_t>(kOutputChannels * kOutputCandidates)) {
        if (error) *error = "Model output does not match [1, 7, 3549].";
        return false;
    }
    if (transform.scale <= 0.0F || transform.upright_width <= 0 ||
        transform.upright_height <= 0) {
        if (error) *error = "Frame transform is invalid.";
        return false;
    }

    std::vector<hb_detection> candidates;
    candidates.reserve(256);
    for (int candidate = 0; candidate < kOutputCandidates; ++candidate) {
        const std::array<float, 3> scores = {
                ChannelValue(output, 4, candidate),
                ChannelValue(output, 5, candidate),
                ChannelValue(output, 6, candidate),
        };
        const int class_id = static_cast<int>(
                std::distance(scores.begin(), std::max_element(scores.begin(), scores.end())));
        const float confidence = scores[class_id];
        if (!std::isfinite(confidence) || confidence < ClassThreshold(config, class_id)) continue;

        float center_x = ChannelValue(output, 0, candidate);
        float center_y = ChannelValue(output, 1, candidate);
        float width = ChannelValue(output, 2, candidate);
        float height = ChannelValue(output, 3, candidate);
        if (!std::isfinite(center_x) || !std::isfinite(center_y) || !std::isfinite(width) ||
            !std::isfinite(height) || width <= 0.0F || height <= 0.0F) {
            continue;
        }

        const float largest_coordinate = std::max(
                {std::fabs(center_x), std::fabs(center_y), std::fabs(width), std::fabs(height)});
        if (largest_coordinate <= 1.5F) {
            center_x *= kModelWidth;
            center_y *= kModelHeight;
            width *= kModelWidth;
            height *= kModelHeight;
        }

        const float x1_pixels = (center_x - width * 0.5F - transform.pad_x) / transform.scale;
        const float y1_pixels = (center_y - height * 0.5F - transform.pad_y) / transform.scale;
        const float x2_pixels = (center_x + width * 0.5F - transform.pad_x) / transform.scale;
        const float y2_pixels = (center_y + height * 0.5F - transform.pad_y) / transform.scale;
        hb_detection decoded{};
        decoded.x1 = std::clamp(x1_pixels / transform.upright_width, 0.0F, 1.0F);
        decoded.y1 = std::clamp(y1_pixels / transform.upright_height, 0.0F, 1.0F);
        decoded.x2 = std::clamp(x2_pixels / transform.upright_width, 0.0F, 1.0F);
        decoded.y2 = std::clamp(y2_pixels / transform.upright_height, 0.0F, 1.0F);
        if (decoded.x2 <= decoded.x1 || decoded.y2 <= decoded.y1) continue;
        decoded.confidence = confidence;
        decoded.class_id = class_id;
        decoded.track_id = -1;
        decoded.should_blur = ShouldBlurClass(class_id, config.target) ? 1 : 0;
        candidates.push_back(decoded);
    }

    *detections = ApplyClassAgnosticNms(
            std::move(candidates), config.iou_threshold, config.max_detections);
    return true;
}

}  // namespace halalify
