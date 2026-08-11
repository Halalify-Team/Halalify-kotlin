#include "litert_backend.h"

#include <cstring>

#include "core/frame.h"
#include "tensorflow/lite/c/c_api.h"

namespace halalify {
namespace {

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

LiteRtBackend::~LiteRtBackend() {
    Reset();
}

void LiteRtBackend::Reset() {
    if (interpreter_ != nullptr) TfLiteInterpreterDelete(interpreter_);
    if (options_ != nullptr) TfLiteInterpreterOptionsDelete(options_);
    if (model_ != nullptr) TfLiteModelDelete(model_);
    interpreter_ = nullptr;
    options_ = nullptr;
    model_ = nullptr;
    model_bytes_.clear();
}

bool LiteRtBackend::Load(
        const uint8_t* model_data,
        size_t model_size,
        int num_threads,
        std::string* error) {
    Reset();
    if (model_data == nullptr || model_size == 0) {
        if (error) *error = "The TFLite model buffer is empty.";
        return false;
    }
    model_bytes_.assign(model_data, model_data + model_size);
    model_ = TfLiteModelCreate(model_bytes_.data(), model_bytes_.size());
    if (model_ == nullptr) {
        if (error) *error = "LiteRT could not parse the TFLite model.";
        Reset();
        return false;
    }
    options_ = TfLiteInterpreterOptionsCreate();
    if (options_ == nullptr) {
        if (error) *error = "LiteRT could not create interpreter options.";
        Reset();
        return false;
    }
    TfLiteInterpreterOptionsSetNumThreads(options_, num_threads > 0 ? num_threads : 2);
    interpreter_ = TfLiteInterpreterCreate(model_, options_);
    if (interpreter_ == nullptr || TfLiteInterpreterAllocateTensors(interpreter_) != kTfLiteOk) {
        if (error) *error = "LiteRT could not create or allocate the interpreter.";
        Reset();
        return false;
    }
    if (TfLiteInterpreterGetInputTensorCount(interpreter_) != 1 ||
        TfLiteInterpreterGetOutputTensorCount(interpreter_) != 1) {
        if (error) *error = "Expected exactly one model input and one model output.";
        Reset();
        return false;
    }
    const TfLiteTensor* input = TfLiteInterpreterGetInputTensor(interpreter_, 0);
    const TfLiteTensor* output = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
    if (TfLiteTensorType(input) != kTfLiteFloat32 ||
        !HasShape(input, {1, kModelHeight, kModelWidth, kModelChannels})) {
        if (error) *error = "Model input must be float32 [1, 416, 416, 3].";
        Reset();
        return false;
    }
    if (TfLiteTensorType(output) != kTfLiteFloat32 ||
        !HasShape(output, {1, kOutputChannels, kOutputCandidates})) {
        if (error) *error = "Model output must be float32 [1, 7, 3549].";
        Reset();
        return false;
    }
    return true;
}

bool LiteRtBackend::Invoke(
        const float* input,
        size_t input_count,
        std::vector<float>* output,
        std::string* error) {
    if (interpreter_ == nullptr || input == nullptr || output == nullptr) {
        if (error) *error = "LiteRT backend is not initialized.";
        return false;
    }
    constexpr size_t kExpectedInputCount =
            static_cast<size_t>(kModelWidth) * kModelHeight * kModelChannels;
    if (input_count != kExpectedInputCount) {
        if (error) *error = "Preprocessed input size is invalid.";
        return false;
    }
    TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(interpreter_, 0);
    const size_t input_bytes = input_count * sizeof(float);
    if (TfLiteTensorByteSize(input_tensor) != input_bytes ||
        TfLiteTensorCopyFromBuffer(input_tensor, input, input_bytes) != kTfLiteOk) {
        if (error) *error = "Could not copy pixels into the LiteRT input tensor.";
        return false;
    }
    if (TfLiteInterpreterInvoke(interpreter_) != kTfLiteOk) {
        if (error) *error = "LiteRT inference failed.";
        return false;
    }
    const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
    constexpr size_t kExpectedOutputCount =
            static_cast<size_t>(kOutputChannels) * kOutputCandidates;
    output->resize(kExpectedOutputCount);
    const size_t output_bytes = kExpectedOutputCount * sizeof(float);
    if (TfLiteTensorByteSize(output_tensor) != output_bytes ||
        TfLiteTensorCopyToBuffer(output_tensor, output->data(), output_bytes) != kTfLiteOk) {
        if (error) *error = "Could not copy the LiteRT output tensor.";
        return false;
    }
    return true;
}

}  // namespace halalify
