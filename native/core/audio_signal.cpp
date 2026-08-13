#include "audio_signal.h"

#include <algorithm>
#include <cmath>

namespace halalify {

void NormalizePcm16(const int16_t* input, size_t count, std::vector<float>* output) {
    if (output == nullptr) return;
    output->resize(count);
    if (input == nullptr) {
        std::fill(output->begin(), output->end(), 0.0F);
        return;
    }
    constexpr float kPcmScale = 32768.0F;
    for (size_t index = 0; index < count; ++index) {
        (*output)[index] = static_cast<float>(input[index]) / kPcmScale;
    }
}

void FloatToPcm16(const float* input, size_t count, int16_t* output) {
    if (input == nullptr || output == nullptr) return;
    constexpr float kPcmScale = 32768.0F;
    for (size_t index = 0; index < count; ++index) {
        const float clamped = std::clamp(input[index], -1.0F, 1.0F);
        const float scaled = clamped * kPcmScale;
        output[index] = static_cast<int16_t>(
                std::clamp(scaled, -32768.0F, 32767.0F));
    }
}

float ResidualEnergyRatio(const float* mixture, const float* speech, size_t count) {
    if (mixture == nullptr || speech == nullptr || count == 0) return 0.0F;
    double mixture_energy = 0.0;
    double residual_energy = 0.0;
    for (size_t index = 0; index < count; ++index) {
        const double source = mixture[index];
        const double residual = source - speech[index];
        mixture_energy += source * source;
        residual_energy += residual * residual;
    }
    constexpr double kSilenceFloor = 1e-10;
    if (mixture_energy <= kSilenceFloor) return 0.0F;
    return static_cast<float>(std::clamp(
            std::sqrt(residual_energy / mixture_energy), 0.0, 1.0));
}

}  // namespace halalify
