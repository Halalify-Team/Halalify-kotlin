#ifndef HALALIFY_ANDROID_ASSET_MODEL_LOADER_H_
#define HALALIFY_ANDROID_ASSET_MODEL_LOADER_H_

#include <android/asset_manager.h>

#include <cstdint>
#include <string>
#include <vector>

namespace halalify::android {

bool ReadAsset(
        AAssetManager* asset_manager,
        const char* asset_name,
        std::vector<uint8_t>* bytes,
        std::string* error);

}  // namespace halalify::android

#endif  // HALALIFY_ANDROID_ASSET_MODEL_LOADER_H_
