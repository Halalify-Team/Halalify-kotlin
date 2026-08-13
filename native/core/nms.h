#ifndef HALALIFY_CORE_NMS_H_
#define HALALIFY_CORE_NMS_H_

#include <vector>

#include "halalify_vision.h"

namespace halalify {

float IntersectionOverUnion(const hb_detection& left, const hb_detection& right);
std::vector<hb_detection> ApplyClassAgnosticNms(
        std::vector<hb_detection> candidates,
        float iou_threshold,
        int max_detections);

}  // namespace halalify

#endif  // HALALIFY_CORE_NMS_H_
