#include "litert_backend.h"

#include <algorithm>
#include <cmath>
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
    quantized_io_ = false;
    input_scale_ = 1.0F;
    input_zero_point_ = 0;
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
    if (TfLiteInterpreterGetInputTensorCount(interpreter_) != 1) {
        if (error) *error = "Expected exactly one model input.";
        Reset();
        return false;
    }
    const TfLiteTensor* input = TfLiteInterpreterGetInputTensor(interpreter_, 0);
    if (!HasShape(input, {1, kModelHeight, kModelWidth, kModelChannels})) {
        if (error) *error = "Model input shape must be [1, 416, 416, 3].";
        Reset();
        return false;
    }
    const int output_count = TfLiteInterpreterGetOutputTensorCount(interpreter_);
    if (TfLiteTensorType(input) == kTfLiteFloat32 && output_count == 1) {
        const TfLiteTensor* output = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
        if (TfLiteTensorType(output) != kTfLiteFloat32 ||
            !HasShape(output, {1, kOutputChannels, kOutputCandidates})) {
            if (error) *error = "Model output must be float32 [1, 7, 3549].";
            Reset();
            return false;
        }
        return true;
    }
    if (TfLiteTensorType(input) != kTfLiteInt8 || output_count != 2) {
        if (error) *error = "Expected Float32 I/O or split INT8 detector I/O.";
        Reset();
        return false;
    }
    const TfLiteQuantizationParams input_quantization =
            TfLiteTensorQuantizationParams(input);
    if (!(input_quantization.scale > 0.0F) || !std::isfinite(input_quantization.scale)) {
        if (error) *error = "INT8 input quantization parameters are invalid.";
        Reset();
        return false;
    }
    const TfLiteTensor* boxes = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
    const TfLiteTensor* scores = TfLiteInterpreterGetOutputTensor(interpreter_, 1);
    if (TfLiteTensorType(boxes) != kTfLiteInt8 ||
        !HasShape(boxes, {1, 4, kOutputCandidates}) ||
        TfLiteTensorType(scores) != kTfLiteInt8 ||
        !HasShape(scores, {1, 3, kOutputCandidates})) {
        if (error) *error = "Split INT8 outputs must be [1, 4, 3549] and [1, 3, 3549].";
        Reset();
        return false;
    }
    for (const TfLiteTensor* output : {boxes, scores}) {
        const TfLiteQuantizationParams quantization =
                TfLiteTensorQuantizationParams(output);
        if (!(quantization.scale > 0.0F) || !std::isfinite(quantization.scale)) {
            if (error) *error = "INT8 output quantization parameters are invalid.";
            Reset();
            return false;
        }
    }
    quantized_io_ = true;
    input_scale_ = input_quantization.scale;
    input_zero_point_ = input_quantization.zero_point;
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
    if (!quantized_io_) {
        const size_t input_bytes = input_count * sizeof(float);
        if (TfLiteTensorByteSize(input_tensor) != input_bytes ||
            TfLiteTensorCopyFromBuffer(input_tensor, input, input_bytes) != kTfLiteOk) {
            if (error) *error = "Could not copy pixels into the LiteRT input tensor.";
            return false;
        }
    } else {
        std::vector<int8_t> quantized_input(input_count);
        for (size_t index = 0; index < input_count; ++index) {
            const int quantized = static_cast<int>(std::lrint(
                    input[index] / input_scale_ + static_cast<float>(input_zero_point_)));
            quantized_input[index] = static_cast<int8_t>(
                    std::clamp(quantized, -128, 127));
        }
        if (TfLiteTensorByteSize(input_tensor) != quantized_input.size() ||
            TfLiteTensorCopyFromBuffer(
                    input_tensor, quantized_input.data(), quantized_input.size()) != kTfLiteOk) {
            if (error) *error = "Could not copy quantized pixels into the LiteRT input tensor.";
            return false;
        }
    }
    if (TfLiteInterpreterInvoke(interpreter_) != kTfLiteOk) {
        if (error) *error = "LiteRT inference failed.";
        return false;
    }
    constexpr size_t kExpectedOutputCount =
            static_cast<size_t>(kOutputChannels) * kOutputCandidates;
    output->resize(kExpectedOutputCount);
    if (!quantized_io_) {
        const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
        const size_t output_bytes = kExpectedOutputCount * sizeof(float);
        if (TfLiteTensorByteSize(output_tensor) != output_bytes ||
            TfLiteTensorCopyToBuffer(output_tensor, output->data(), output_bytes) != kTfLiteOk) {
            if (error) *error = "Could not copy the LiteRT output tensor.";
            return false;
        }
        return true;
    }

    constexpr int kBoxChannels = 4;
    constexpr int kScoreChannels = 3;
    for (int branch = 0; branch < 2; ++branch) {
        const int channels = branch == 0 ? kBoxChannels : kScoreChannels;
        const TfLiteTensor* output_tensor =
                TfLiteInterpreterGetOutputTensor(interpreter_, branch);
        const size_t branch_count = static_cast<size_t>(channels) * kOutputCandidates;
        std::vector<int8_t> quantized_output(branch_count);
        if (TfLiteTensorByteSize(output_tensor) != branch_count ||
            TfLiteTensorCopyToBuffer(
                    output_tensor, quantized_output.data(), branch_count) != kTfLiteOk) {
            if (error) *error = "Could not copy a split INT8 output tensor.";
            return false;
        }
        const TfLiteQuantizationParams quantization =
                TfLiteTensorQuantizationParams(output_tensor);
        for (int channel = 0; channel < channels; ++channel) {
            for (int candidate = 0; candidate < kOutputCandidates; ++candidate) {
                const size_t source =
                        static_cast<size_t>(channel) * kOutputCandidates + candidate;
                const size_t destination =
                        static_cast<size_t>(branch * kBoxChannels + channel) * kOutputCandidates +
                        candidate;
                (*output)[destination] =
                        (static_cast<float>(quantized_output[source]) -
                         static_cast<float>(quantization.zero_point)) * quantization.scale;
            }
        }
    }
    return true;
}

}  // namespace halalify
