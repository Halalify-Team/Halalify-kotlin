#ifndef HALALIFY_CORE_AUDIO_SIGNAL_H_
#define HALALIFY_CORE_AUDIO_SIGNAL_H_

#include <cstddef>
#include <cstdint>
#include <vector>

namespace halalify {

void NormalizePcm16(const int16_t* input, size_t count, std::vector<float>* output);
void FloatToPcm16(const float* input, size_t count, int16_t* output);
float ResidualEnergyRatio(const float* mixture, const float* speech, size_t count);

}  // namespace halalify

#endif  // HALALIFY_CORE_AUDIO_SIGNAL_H_
