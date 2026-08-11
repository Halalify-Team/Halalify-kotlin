#include "nms.h"

#include <algorithm>
#include <utility>

namespace halalify {

float IntersectionOverUnion(const hb_detection& left, const hb_detection& right) {
    const float intersection_width =
            std::max(0.0F, std::min(left.x2, right.x2) - std::max(left.x1, right.x1));
    const float intersection_height =
            std::max(0.0F, std::min(left.y2, right.y2) - std::max(left.y1, right.y1));
    const float intersection = intersection_width * intersection_height;
    const float left_area = std::max(0.0F, left.x2 - left.x1) *
                            std::max(0.0F, left.y2 - left.y1);
    const float right_area = std::max(0.0F, right.x2 - right.x1) *
                             std::max(0.0F, right.y2 - right.y1);
    const float union_area = left_area + right_area - intersection;
    return union_area > 0.0F ? intersection / union_area : 0.0F;
}

std::vector<hb_detection> ApplyClassAgnosticNms(
        std::vector<hb_detection> candidates,
        float iou_threshold,
        int max_detections) {
    std::stable_sort(
            candidates.begin(), candidates.end(),
            [](const hb_detection& left, const hb_detection& right) {
                return left.confidence > right.confidence;
            });
    std::vector<hb_detection> selected;
    selected.reserve(std::min(static_cast<int>(candidates.size()), max_detections));
    for (const hb_detection& candidate : candidates) {
        bool suppressed = false;
        for (const hb_detection& existing : selected) {
            if (IntersectionOverUnion(candidate, existing) > iou_threshold) {
                suppressed = true;
                break;
            }
        }
        if (!suppressed) {
            selected.push_back(candidate);
            if (static_cast<int>(selected.size()) >= max_detections) break;
        }
    }
    return selected;
}

}  // namespace halalify
