#include "protection_policy.h"

namespace halalify {

bool ShouldBlurClass(int class_id, hb_blur_target target) {
    if (class_id == 0) return target == HB_BLUR_TARGET_FEMALE;
    if (class_id == 1) return target == HB_BLUR_TARGET_MALE;
    return false;
}

}  // namespace halalify
