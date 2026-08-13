#ifndef HALALIFY_CORE_PROTECTION_POLICY_H_
#define HALALIFY_CORE_PROTECTION_POLICY_H_

#include "halalify_vision.h"

namespace halalify {

bool ShouldBlurClass(int class_id, hb_blur_target target);

}  // namespace halalify

#endif  // HALALIFY_CORE_PROTECTION_POLICY_H_
