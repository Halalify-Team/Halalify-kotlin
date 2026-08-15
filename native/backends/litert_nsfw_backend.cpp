#include "litert_nsfw_backend.h"

#include <cmath>
#include <vector>

#include "tensorflow/lite/c/c_api.h"

namespace halalify {
namespace {

constexpr int kInputWidth = 224;
constexpr int kInputHeight = 224;
constexpr int kInputChannels = 3;
constexpr int kOutputClasses = 2;

bool HasShape(const TfLiteTensor* tensor, const std::vector<int>& expected) {
    if (tensor == nullptr || TfLiteTensorNumDims(tensor) != static_cast<int>(expected.size())) {
        return false;
    }
    for (int index = 0; index < static_cast<int>(expected.size()); ++index) {
        if (TfLiteTensorDim(tensor, index) != expected[index]) return false;
    }
    return true;
}

}  // namespace

LiteRtNsfwBackend::~LiteRtNsfwBackend() {
    Reset();
}

void LiteRtNsfwBackend::Reset() {
    if (interpreter_ != nullptr) TfLiteInterpreterDelete(interpreter_);
    if (options_ != nullptr) TfLiteInterpreterOptionsDelete(options_);
    if (model_ != nullptr) TfLiteModelDelete(model_);
    interpreter_ = nullptr;
    options_ = nullptr;
    model_ = nullptr;
    model_bytes_.clear();
}

bool LiteRtNsfwBackend::Load(
        const uint8_t* model_data,
        size_t model_size,
        int num_threads,
        std::string* error) {
    Reset();
    if (model_data == nullptr || model_size == 0) {
        if (error) *error = "The NSFW TFLite model buffer is empty.";
        return false;
    }
    model_bytes_.assign(model_data, model_data + model_size);
    model_ = TfLiteModelCreate(model_bytes_.data(), model_bytes_.size());
    if (model_ == nullptr) {
        if (error) *error = "LiteRT could not parse the NSFW TFLite model.";
        Reset();
        return false;
    }
    options_ = TfLiteInterpreterOptionsCreate();
    if (options_ == nullptr) {
        if (error) *error = "LiteRT could not create NSFW interpreter options.";
        Reset();
        return false;
    }
    TfLiteInterpreterOptionsSetNumThreads(options_, num_threads > 0 ? num_threads : 1);
    interpreter_ = TfLiteInterpreterCreate(model_, options_);
    if (interpreter_ == nullptr || TfLiteInterpreterAllocateTensors(interpreter_) != kTfLiteOk) {
        if (error) *error = "LiteRT could not allocate the NSFW interpreter.";
        Reset();
        return false;
    }
    if (TfLiteInterpreterGetInputTensorCount(interpreter_) != 1 ||
        TfLiteInterpreterGetOutputTensorCount(interpreter_) != 1) {
        if (error) *error = "NSFW model must have exactly one input and one output.";
        Reset();
        return false;
    }
    const TfLiteTensor* input = TfLiteInterpreterGetInputTensor(interpreter_, 0);
    const TfLiteTensor* output = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
    if (TfLiteTensorType(input) != kTfLiteFloat32 ||
        !HasShape(input, {1, kInputHeight, kInputWidth, kInputChannels}) ||
        TfLiteTensorType(output) != kTfLiteFloat32 ||
        !HasShape(output, {1, kOutputClasses})) {
        if (error) *error = "NSFW model tensors must be float32 [1,224,224,3] -> [1,2].";
        Reset();
        return false;
    }
    return true;
}

bool LiteRtNsfwBackend::Invoke(
        const float* input,
        size_t input_count,
        std::vector<float>* output,
        std::string* error) {
    if (interpreter_ == nullptr || input == nullptr || output == nullptr) {
        if (error) *error = "NSFW LiteRT backend is not initialized.";
        return false;
    }
    constexpr size_t kExpectedInputCount =
            static_cast<size_t>(kInputWidth) * kInputHeight * kInputChannels;
    if (input_count != kExpectedInputCount) {
        if (error) *error = "NSFW preprocessed input size is invalid.";
        return false;
    }
    TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(interpreter_, 0);
    const size_t input_bytes = input_count * sizeof(float);
    if (TfLiteTensorByteSize(input_tensor) != input_bytes ||
        TfLiteTensorCopyFromBuffer(input_tensor, input, input_bytes) != kTfLiteOk) {
        if (error) *error = "Could not copy pixels into the NSFW LiteRT input tensor.";
        return false;
    }
    if (TfLiteInterpreterInvoke(interpreter_) != kTfLiteOk) {
        if (error) *error = "NSFW LiteRT inference failed.";
        return false;
    }
    const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
    const size_t output_bytes = static_cast<size_t>(kOutputClasses) * sizeof(float);
    if (TfLiteTensorByteSize(output_tensor) != output_bytes) {
        if (error) *error = "NSFW LiteRT output tensor has an invalid size.";
        return false;
    }
    output->resize(kOutputClasses);
    if (TfLiteTensorCopyToBuffer(output_tensor, output->data(), output_bytes) != kTfLiteOk) {
        if (error) *error = "Could not copy the NSFW LiteRT output tensor.";
        return false;
    }
    if (!std::isfinite((*output)[0]) || !std::isfinite((*output)[1])) {
        if (error) *error = "NSFW LiteRT output contains non-finite values.";
        return false;
    }
    return true;
}

}  // namespace halalify
