#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

#include "core/audio_signal.h"
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

void TestAudioSignalConversionAndResidualScore() {
    const std::vector<int16_t> pcm = {0, 16384, -32768};
    std::vector<float> normalized;
    halalify::NormalizePcm16(pcm.data(), pcm.size(), &normalized);
    assert(std::fabs(normalized[0]) < 1e-6F);
    assert(std::fabs(normalized[1] - 0.5F) < 1e-6F);
    assert(std::fabs(normalized[2] + 1.0F) < 1e-6F);

    const std::vector<float> mixture = {0.5F, -0.5F};
    const std::vector<float> speech = {0.25F, -0.25F};
    const float score = halalify::ResidualEnergyRatio(
            mixture.data(), speech.data(), mixture.size());
    assert(std::fabs(score - 0.5F) < 1e-6F);

    std::vector<int16_t> round_trip(normalized.size());
    halalify::FloatToPcm16(normalized.data(), normalized.size(), round_trip.data());
    assert(round_trip[0] == 0);
    assert(round_trip[1] == 16384);
    assert(round_trip[2] == -32768);
}

}  // namespace

int main() {
    TestPolicy();
    TestPreprocessLetterboxAndRgb();
    TestDecodeAndNms();
    TestAudioSignalConversionAndResidualScore();
    return 0;
}
