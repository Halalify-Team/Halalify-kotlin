#ifndef HALALIFY_CORE_PREPROCESS_H_
#define HALALIFY_CORE_PREPROCESS_H_

#include <string>
#include <vector>

#include "frame.h"

namespace halalify {

bool PreprocessFrame(
        const hb_frame& frame,
        std::vector<float>* output,
        FrameTransform* transform,
        std::string* error);

}  // namespace halalify

#endif  // HALALIFY_CORE_PREPROCESS_H_
