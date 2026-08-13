#include "asset_model_loader.h"

#include <memory>

namespace halalify::android {

bool ReadAsset(
        AAssetManager* asset_manager,
        const char* asset_name,
        std::vector<uint8_t>* bytes,
        std::string* error) {
    if (asset_manager == nullptr || asset_name == nullptr || bytes == nullptr) {
        if (error) *error = "Android asset loader received an invalid argument.";
        return false;
    }
    using AssetPtr = std::unique_ptr<AAsset, decltype(&AAsset_close)>;
    AssetPtr asset(
            AAssetManager_open(asset_manager, asset_name, AASSET_MODE_BUFFER),
            &AAsset_close);
    if (!asset) {
        if (error) *error = std::string("Model asset was not found: ") + asset_name;
        return false;
    }
    const off64_t length = AAsset_getLength64(asset.get());
    if (length <= 0) {
        if (error) *error = "Model asset is empty.";
        return false;
    }
    bytes->resize(static_cast<size_t>(length));
    size_t offset = 0;
    while (offset < bytes->size()) {
        const int read = AAsset_read(
                asset.get(), bytes->data() + offset, bytes->size() - offset);
        if (read <= 0) {
            if (error) *error = "Could not read the complete model asset.";
            bytes->clear();
            return false;
        }
        offset += static_cast<size_t>(read);
    }
    return true;
}

}  // namespace halalify::android
