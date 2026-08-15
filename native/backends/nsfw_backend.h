#ifndef HALALIFY_BACKENDS_NSFW_BACKEND_H_
#define HALALIFY_BACKENDS_NSFW_BACKEND_H_

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace halalify {

// Platform-neutral contract for a small NSFW classifier. Android implements
// this with LiteRT; iOS can provide a Core ML or LiteRT implementation without
// changing the AI Engine orchestration.
class NsfwBackend {
public:
    virtual ~NsfwBackend() = default;
    virtual bool Load(
            const uint8_t* model_data,
            size_t model_size,
            int num_threads,
            std::string* error) = 0;
    virtual bool Invoke(
            const float* input,
            size_t input_count,
            std::vector<float>* output,
            std::string* error) = 0;
};

}  // namespace halalify

#endif  // HALALIFY_BACKENDS_NSFW_BACKEND_H_
