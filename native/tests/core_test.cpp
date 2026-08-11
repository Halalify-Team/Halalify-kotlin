#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

#include "core/nms.h"
#include "core/postprocess.h"
#include "core/preprocess.h"
#include "core/protection_policy.h"

namespace {

void TestPolicy() {
    assert(halalify::ShouldBlurClass(0, HB_BLUR_TARGET_FEMALE));
    assert(!halalify::ShouldBlurClass(1, HB_BLUR_TARGET_FEMALE));
    assert(halalify::ShouldBlurClass(1, HB_BLUR_TARGET_MALE));
    assert(!halalify::ShouldBlurClass(2, HB_BLUR_TARGET_MALE));
}

void TestPreprocessLetterboxAndRgb() {
    const std::vector<uint8_t> rgba = {255, 0, 0, 255, 0, 255, 0, 255};
    const hb_frame frame{rgba.data(), 2, 1, 8, 0, 0, HB_PIXEL_FORMAT_RGBA8888};
    std::vector<float> output;
    halalify::FrameTransform transform;
    std::string error;
    assert(halalify::PreprocessFrame(frame, &output, &transform, &error));
    assert(transform.resized_width == 416);
    assert(transform.resized_height == 208);
    const size_t padding_pixel = 0;
    assert(std::fabs(output[padding_pixel] - 114.0F / 255.0F) < 1e-5F);
    const size_t first_content_pixel = (104U * 416U) * 3U;
    assert(output[first_content_pixel] > output[first_content_pixel + 1]);
}

void TestDecodeAndNms() {
    std::vector<float> output(halalify::kOutputChannels * halalify::kOutputCandidates);
    auto set = [&output](int channel, int candidate, float value) {
        output[static_cast<size_t>(channel) * halalify::kOutputCandidates + candidate] = value;
    };
    set(0, 0, 208.0F); set(1, 0, 208.0F); set(2, 0, 200.0F); set(3, 0, 300.0F);
    set(4, 0, 0.9F);
    set(0, 1, 210.0F); set(1, 1, 208.0F); set(2, 1, 200.0F); set(3, 1, 300.0F);
    set(4, 1, 0.8F);
    halalify::FrameTransform transform{416, 416, 416, 416, 1.0F, 0.0F, 0.0F};
    hb_config config{};
    config.target = HB_BLUR_TARGET_FEMALE;
    config.female_confidence_threshold = 0.25F;
    config.male_confidence_threshold = 0.25F;
    config.ignored_confidence_threshold = 0.25F;
    config.iou_threshold = 0.5F;
    config.max_detections = 100;
    config.num_threads = 2;
    std::vector<hb_detection> detections;
    std::string error;
    assert(halalify::DecodeDetections(
            output.data(), output.size(), transform, config, &detections, &error));
    assert(detections.size() == 1);
    assert(detections.front().class_id == 0);
    assert(detections.front().should_blur == 1);
}

}  // namespace

int main() {
    TestPolicy();
    TestPreprocessLetterboxAndRgb();
    TestDecodeAndNms();
    return 0;
}
