#ifndef HALALIFY_CORE_FRAME_SAMPLING_H_
#define HALALIFY_CORE_FRAME_SAMPLING_H_

#include <string>
#include <vector>

#include "frame.h"

namespace halalify {

struct NormalizedRect {
    float x1 = 0.0F;
    float y1 = 0.0F;
    float x2 = 1.0F;
    float y2 = 1.0F;
};

// Produces the Open-NSFW Android adapter's input: resize the selected upright
// region to 256x256, center-crop 224x224, then emit BGR minus VGG means.
bool PreprocessNsfwRegion(
        const hb_frame& frame,
        const NormalizedRect& region,
        std::vector<float>* output,
        std::string* error);

}  // namespace halalify

#endif  // HALALIFY_CORE_FRAME_SAMPLING_H_
