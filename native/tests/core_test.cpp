#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

#include "core/audio_signal.h"
#include "core/frame_sampling.h"
#include "core/nms.h"
#include "core/postprocess.h"
#include "core/preprocess.h"
#include "core/protection_policy.h"
#include "core/site_filter.h"

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

void TestPreprocessDetailRegion() {
    const std::vector<uint8_t> rgba = {
            255, 0, 0, 255, 255, 0, 0, 255,
            255, 0, 0, 255, 255, 0, 0, 255,
            0, 0, 255, 255, 0, 0, 255, 255,
            0, 0, 255, 255, 0, 0, 255, 255,
    };
    const hb_frame frame{rgba.data(), 2, 4, 8, 0, 0, HB_PIXEL_FORMAT_RGBA8888};
    std::vector<float> output;
    halalify::FrameTransform transform;
    std::string error;
    assert(halalify::PreprocessFrameRegion(
            frame, halalify::FrameRegion{0, 2, 2, 2}, &output, &transform, &error));
    assert(transform.crop_y == 2);
    assert(transform.crop_height == 2);
    const size_t center = (208U * 416U + 208U) * 3U;
    assert(output[center + 2] > output[center]);
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

void TestDecodeEndToEndOutput() {
    const std::vector<float> output = {100.0F, 80.0F, 220.0F, 300.0F, 0.91F, 0.0F};
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
    assert(std::fabs(detections.front().x1 - 100.0F / 416.0F) < 1e-5F);
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

void TestNsfwPreprocessUsesBgrVggMeans() {
    const std::vector<uint8_t> rgba = {
            255, 0, 0, 255,
            255, 0, 0, 255,
            255, 0, 0, 255,
            255, 0, 0, 255,
    };
    const hb_frame frame{rgba.data(), 2, 2, 8, 0, 0, HB_PIXEL_FORMAT_RGBA8888};
    std::vector<float> output;
    std::string error;
    assert(halalify::PreprocessNsfwRegion(
            frame, halalify::NormalizedRect{}, &output, &error));
    assert(output.size() == 224U * 224U * 3U);
    assert(std::fabs(output[0] - (-103.939F)) < 1e-3F);
    assert(std::fabs(output[1] - (-116.779F)) < 1e-3F);
    assert(std::fabs(output[2] - 131.32F) < 1e-2F);
}

void TestSiteFilterReadsExternalRules() {
    const char rules[] =
            "# comments are ignored\n"
            "0.0.0.0 Porn.Example\n"
            "||adult.example^\n";
    halalify::SiteFilterEngine filter;
    std::string error;
    assert(filter.Load(
            reinterpret_cast<const uint8_t*>(rules), sizeof(rules) - 1, &error));
    assert(filter.IsBlocked("porn.example"));
    assert(filter.IsBlocked("cdn.porn.example."));
    assert(filter.IsBlocked("adult.example"));
    assert(!filter.IsBlocked("notporn.example"));
}

}  // namespace

int main() {
    TestPolicy();
    TestPreprocessLetterboxAndRgb();
    TestPreprocessDetailRegion();
    TestNsfwPreprocessUsesBgrVggMeans();
    TestDecodeAndNms();
    TestDecodeEndToEndOutput();
    TestAudioSignalConversionAndResidualScore();
    TestSiteFilterReadsExternalRules();
    return 0;
}
