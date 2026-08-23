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

bool IsSupportedSingleOutput(const TfLiteTensor* tensor) {
    if (tensor == nullptr || TfLiteTensorNumDims(tensor) != 3 ||
        TfLiteTensorDim(tensor, 0) != 1) {
        return false;
    }
    const int rows = TfLiteTensorDim(tensor, 1);
    const int columns = TfLiteTensorDim(tensor, 2);
    return (rows == kOutputChannels && columns > 0) || (rows > 0 && columns == 6);
}

bool HasValidQuantization(const TfLiteTensor* tensor) {
    const TfLiteQuantizationParams quantization = TfLiteTensorQuantizationParams(tensor);
    return quantization.scale > 0.0F && std::isfinite(quantization.scale);
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
    input_quantized_ = false;
    split_quantized_output_ = false;
    single_quantized_output_ = false;
    input_scale_ = 1.0F;
    input_zero_point_ = 0;
    output_element_count_ = 0;
    output_candidate_count_ = 0;
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
    const TfLiteType input_type = TfLiteTensorType(input);
    if (input_type != kTfLiteFloat32 && input_type != kTfLiteInt8) {
        if (error) *error = "Detector input must be Float32 or INT8.";
        Reset();
        return false;
    }
    if (input_type == kTfLiteInt8) {
        if (!HasValidQuantization(input)) {
            if (error) *error = "INT8 input quantization parameters are invalid.";
            Reset();
            return false;
        }
        const TfLiteQuantizationParams input_quantization = TfLiteTensorQuantizationParams(input);
        input_quantized_ = true;
        input_scale_ = input_quantization.scale;
        input_zero_point_ = input_quantization.zero_point;
    }

    const int output_count = TfLiteInterpreterGetOutputTensorCount(interpreter_);
    if (output_count == 1) {
        const TfLiteTensor* output = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
        const TfLiteType output_type = TfLiteTensorType(output);
        if (!IsSupportedSingleOutput(output) ||
            (output_type != kTfLiteFloat32 && output_type != kTfLiteInt8) ||
            (output_type == kTfLiteInt8 && !HasValidQuantization(output))) {
            if (error) {
                *error = "Single output must be Float32/INT8 raw [1, 7, N] or end-to-end [1, N, 6].";
            }
            Reset();
            return false;
        }
        output_element_count_ = static_cast<size_t>(TfLiteTensorDim(output, 1)) *
                TfLiteTensorDim(output, 2);
        single_quantized_output_ = output_type == kTfLiteInt8;
        return true;
    }
    if (output_count != 2) {
        if (error) *error = "Expected one detector output or two split INT8 outputs.";
        Reset();
        return false;
    }
    const TfLiteTensor* boxes = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
    const TfLiteTensor* scores = TfLiteInterpreterGetOutputTensor(interpreter_, 1);
    const bool boxes_shape = boxes != nullptr && TfLiteTensorNumDims(boxes) == 3 &&
            TfLiteTensorDim(boxes, 0) == 1 && TfLiteTensorDim(boxes, 1) == 4 &&
            TfLiteTensorDim(boxes, 2) > 0;
    const bool scores_shape = scores != nullptr && TfLiteTensorNumDims(scores) == 3 &&
            TfLiteTensorDim(scores, 0) == 1 && TfLiteTensorDim(scores, 1) == 3 &&
            TfLiteTensorDim(scores, 2) > 0;
    if (TfLiteTensorType(boxes) != kTfLiteInt8 || !boxes_shape ||
        TfLiteTensorType(scores) != kTfLiteInt8 || !scores_shape ||
        TfLiteTensorDim(boxes, 2) != TfLiteTensorDim(scores, 2)) {
        if (error) *error = "Split INT8 outputs must be [1, 4, N] and [1, 3, N].";
        Reset();
        return false;
    }
    for (const TfLiteTensor* output : {boxes, scores}) {
        if (!HasValidQuantization(output)) {
            if (error) *error = "INT8 output quantization parameters are invalid.";
            Reset();
            return false;
        }
    }
    split_quantized_output_ = true;
    output_candidate_count_ = TfLiteTensorDim(boxes, 2);
    output_element_count_ = static_cast<size_t>(kOutputChannels) * output_candidate_count_;
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
    if (!input_quantized_) {
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
    output->resize(output_element_count_);
    if (!split_quantized_output_) {
        const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
        if (!single_quantized_output_) {
            const size_t output_bytes = output_element_count_ * sizeof(float);
            if (TfLiteTensorByteSize(output_tensor) != output_bytes ||
                TfLiteTensorCopyToBuffer(output_tensor, output->data(), output_bytes) != kTfLiteOk) {
                if (error) *error = "Could not copy the LiteRT output tensor.";
                return false;
            }
        } else {
            std::vector<int8_t> quantized_output(output_element_count_);
            if (TfLiteTensorByteSize(output_tensor) != quantized_output.size() ||
                TfLiteTensorCopyToBuffer(
                        output_tensor, quantized_output.data(), quantized_output.size()) != kTfLiteOk) {
                if (error) *error = "Could not copy the quantized LiteRT output tensor.";
                return false;
            }
            const TfLiteQuantizationParams quantization =
                    TfLiteTensorQuantizationParams(output_tensor);
            for (size_t index = 0; index < output_element_count_; ++index) {
                (*output)[index] =
                        (static_cast<float>(quantized_output[index]) - quantization.zero_point) *
                        quantization.scale;
            }
        }
        return true;
    }

    constexpr int kBoxChannels = 4;
    constexpr int kScoreChannels = 3;
    for (int branch = 0; branch < 2; ++branch) {
        const int channels = branch == 0 ? kBoxChannels : kScoreChannels;
        const TfLiteTensor* output_tensor =
                TfLiteInterpreterGetOutputTensor(interpreter_, branch);
        const size_t branch_count = static_cast<size_t>(channels) * output_candidate_count_;
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
            for (int candidate = 0; candidate < output_candidate_count_; ++candidate) {
                const size_t source =
                        static_cast<size_t>(channel) * output_candidate_count_ + candidate;
                const size_t destination =
                        static_cast<size_t>(branch * kBoxChannels + channel) * output_candidate_count_ +
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
