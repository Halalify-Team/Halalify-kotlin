#ifndef HALALIFY_CORE_POSTPROCESS_H_
#define HALALIFY_CORE_POSTPROCESS_H_

#include <string>
#include <vector>

#include "frame.h"

namespace halalify {

bool DecodeDetections(
        const float* output,
        size_t output_count,
        const FrameTransform& transform,
        const hb_config& config,
        std::vector<hb_detection>* detections,
        std::string* error);

}  // namespace halalify

#endif  // HALALIFY_CORE_POSTPROCESS_H_
