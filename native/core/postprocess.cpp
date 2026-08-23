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

float ChannelValue(const float* output, int channel, int candidate, int candidate_count) {
    return output[static_cast<size_t>(channel) * candidate_count + candidate];
}

bool DecodeRawDetections(
        const float* output,
        int candidate_count,
        const FrameTransform& transform,
        const hb_config& config,
        std::vector<hb_detection>* candidates) {
    candidates->reserve(std::min(candidate_count, 512));
    for (int candidate = 0; candidate < candidate_count; ++candidate) {
        const std::array<float, 3> scores = {
                ChannelValue(output, 4, candidate, candidate_count),
                ChannelValue(output, 5, candidate, candidate_count),
                ChannelValue(output, 6, candidate, candidate_count),
        };
        const int class_id = static_cast<int>(
                std::distance(scores.begin(), std::max_element(scores.begin(), scores.end())));
        const float confidence = scores[class_id];
        if (!std::isfinite(confidence) || confidence < ClassThreshold(config, class_id)) continue;

        float center_x = ChannelValue(output, 0, candidate, candidate_count);
        float center_y = ChannelValue(output, 1, candidate, candidate_count);
        float width = ChannelValue(output, 2, candidate, candidate_count);
        float height = ChannelValue(output, 3, candidate, candidate_count);
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
        const float x1 = center_x - width * 0.5F;
        const float y1 = center_y - height * 0.5F;
        const float x2 = center_x + width * 0.5F;
        const float y2 = center_y + height * 0.5F;
        hb_detection decoded{};
        decoded.x1 = std::clamp(
                (static_cast<float>(transform.crop_x) + (x1 - transform.pad_x) / transform.scale) /
                        transform.upright_width,
                0.0F,
                1.0F);
        decoded.y1 = std::clamp(
                (static_cast<float>(transform.crop_y) + (y1 - transform.pad_y) / transform.scale) /
                        transform.upright_height,
                0.0F,
                1.0F);
        decoded.x2 = std::clamp(
                (static_cast<float>(transform.crop_x) + (x2 - transform.pad_x) / transform.scale) /
                        transform.upright_width,
                0.0F,
                1.0F);
        decoded.y2 = std::clamp(
                (static_cast<float>(transform.crop_y) + (y2 - transform.pad_y) / transform.scale) /
                        transform.upright_height,
                0.0F,
                1.0F);
        if (decoded.x2 <= decoded.x1 || decoded.y2 <= decoded.y1) continue;
        decoded.confidence = confidence;
        decoded.class_id = class_id;
        decoded.track_id = -1;
        decoded.should_blur = ShouldBlurClass(class_id, config.target) ? 1 : 0;
        candidates->push_back(decoded);
    }
    return true;
}

bool DecodeEndToEndDetections(
        const float* output,
        int candidate_count,
        const FrameTransform& transform,
        const hb_config& config,
        std::vector<hb_detection>* candidates) {
    constexpr int kDetectionFields = 6;
    candidates->reserve(candidate_count);
    for (int candidate = 0; candidate < candidate_count; ++candidate) {
        const float* row = output + static_cast<size_t>(candidate) * kDetectionFields;
        float x1 = row[0];
        float y1 = row[1];
        float x2 = row[2];
        float y2 = row[3];
        const float confidence = row[4];
        if (!std::isfinite(row[5])) continue;
        const int class_id = static_cast<int>(std::lrint(row[5]));
        if (class_id < 0 || class_id > 2 || !std::isfinite(confidence) ||
            confidence < ClassThreshold(config, class_id) ||
            !std::isfinite(x1) || !std::isfinite(y1) ||
            !std::isfinite(x2) || !std::isfinite(y2) || x2 <= x1 || y2 <= y1) {
            continue;
        }
        const float largest_coordinate =
                std::max({std::fabs(x1), std::fabs(y1), std::fabs(x2), std::fabs(y2)});
        if (largest_coordinate <= 1.5F) {
            x1 *= kModelWidth;
            x2 *= kModelWidth;
            y1 *= kModelHeight;
            y2 *= kModelHeight;
        }
        hb_detection decoded{};
        decoded.x1 = std::clamp(
                (static_cast<float>(transform.crop_x) + (x1 - transform.pad_x) / transform.scale) /
                        transform.upright_width,
                0.0F,
                1.0F);
        decoded.y1 = std::clamp(
                (static_cast<float>(transform.crop_y) + (y1 - transform.pad_y) / transform.scale) /
                        transform.upright_height,
                0.0F,
                1.0F);
        decoded.x2 = std::clamp(
                (static_cast<float>(transform.crop_x) + (x2 - transform.pad_x) / transform.scale) /
                        transform.upright_width,
                0.0F,
                1.0F);
        decoded.y2 = std::clamp(
                (static_cast<float>(transform.crop_y) + (y2 - transform.pad_y) / transform.scale) /
                        transform.upright_height,
                0.0F,
                1.0F);
        if (decoded.x2 <= decoded.x1 || decoded.y2 <= decoded.y1) continue;
        decoded.confidence = confidence;
        decoded.class_id = class_id;
        decoded.track_id = -1;
        decoded.should_blur = ShouldBlurClass(class_id, config.target) ? 1 : 0;
        candidates->push_back(decoded);
    }
    return true;
}

}  // namespace

bool DecodeDetections(
        const float* output,
        size_t output_count,
        const FrameTransform& transform,
        const hb_config& config,
        std::vector<hb_detection>* detections,
        std::string* error) {
    if (output == nullptr || detections == nullptr || output_count == 0) {
        if (error) *error = "Model output is empty.";
        return false;
    }
    if (transform.scale <= 0.0F || transform.upright_width <= 0 ||
        transform.upright_height <= 0) {
        if (error) *error = "Frame transform is invalid.";
        return false;
    }

    std::vector<hb_detection> candidates;
    if (output_count % kOutputChannels == 0) {
        const int candidate_count = static_cast<int>(output_count / kOutputChannels);
        DecodeRawDetections(output, candidate_count, transform, config, &candidates);
    } else if (output_count % 6 == 0) {
        const int candidate_count = static_cast<int>(output_count / 6);
        DecodeEndToEndDetections(output, candidate_count, transform, config, &candidates);
    } else {
        if (error) {
            *error = "Model output must be raw [1, 7, N] or end-to-end [1, N, 6].";
        }
        return false;
    }

    *detections = ApplyClassAgnosticNms(
            std::move(candidates), config.iou_threshold, config.max_detections);
    return true;
}

}  // namespace halalify
