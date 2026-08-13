#include "audio_litert_backend.h"

#include "tensorflow/lite/c/c_api.h"

namespace halalify {

AudioLiteRtBackend::~AudioLiteRtBackend() {
    Reset();
}

void AudioLiteRtBackend::Reset() {
    if (interpreter_ != nullptr) TfLiteInterpreterDelete(interpreter_);
    if (options_ != nullptr) TfLiteInterpreterOptionsDelete(options_);
    if (model_ != nullptr) TfLiteModelDelete(model_);
    interpreter_ = nullptr;
    options_ = nullptr;
    model_ = nullptr;
    frame_samples_ = 0;
    model_bytes_.clear();
}

bool AudioLiteRtBackend::Load(
        const uint8_t* model_data,
        size_t model_size,
        size_t frame_samples,
        int num_threads,
        std::string* error) {
    Reset();
    if (model_data == nullptr || model_size == 0 || frame_samples == 0) {
        if (error) *error = "The audio model buffer or frame size is empty.";
        return false;
    }
    model_bytes_.assign(model_data, model_data + model_size);
    model_ = TfLiteModelCreate(model_bytes_.data(), model_bytes_.size());
    options_ = TfLiteInterpreterOptionsCreate();
    if (model_ == nullptr || options_ == nullptr) {
        if (error) *error = "LiteRT could not parse the audio model.";
        Reset();
        return false;
    }
    TfLiteInterpreterOptionsSetNumThreads(options_, num_threads);
    interpreter_ = TfLiteInterpreterCreate(model_, options_);
    if (interpreter_ == nullptr || TfLiteInterpreterAllocateTensors(interpreter_) != kTfLiteOk) {
        if (error) *error = "LiteRT could not allocate the audio model tensors.";
        Reset();
        return false;
    }
    if (TfLiteInterpreterGetInputTensorCount(interpreter_) != 1 ||
        TfLiteInterpreterGetOutputTensorCount(interpreter_) != 1) {
        if (error) *error = "Audio model must have exactly one input and one output.";
        Reset();
        return false;
    }
    const TfLiteTensor* input = TfLiteInterpreterGetInputTensor(interpreter_, 0);
    const TfLiteTensor* output = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
    const size_t expected_bytes = frame_samples * sizeof(float);
    if (TfLiteTensorType(input) != kTfLiteFloat32 ||
        TfLiteTensorType(output) != kTfLiteFloat32 ||
        TfLiteTensorByteSize(input) != expected_bytes ||
        TfLiteTensorByteSize(output) != expected_bytes) {
        if (error) {
            *error = "Audio model tensors must be float32 and match the configured frame size.";
        }
        Reset();
        return false;
    }
    frame_samples_ = frame_samples;
    return true;
}

bool AudioLiteRtBackend::Invoke(
        const float* input,
        size_t input_count,
        std::vector<float>* output,
        std::string* error) {
    if (interpreter_ == nullptr || input == nullptr || output == nullptr ||
        input_count != frame_samples_) {
        if (error) *error = "Audio inference input is invalid.";
        return false;
    }
    TfLiteTensor* input_tensor = TfLiteInterpreterGetInputTensor(interpreter_, 0);
    const size_t bytes = frame_samples_ * sizeof(float);
    if (TfLiteTensorCopyFromBuffer(input_tensor, input, bytes) != kTfLiteOk ||
        TfLiteInterpreterInvoke(interpreter_) != kTfLiteOk) {
        if (error) *error = "LiteRT audio inference failed.";
        return false;
    }
    output->resize(frame_samples_);
    const TfLiteTensor* output_tensor = TfLiteInterpreterGetOutputTensor(interpreter_, 0);
    if (TfLiteTensorCopyToBuffer(output_tensor, output->data(), bytes) != kTfLiteOk) {
        if (error) *error = "Could not read the speech-only tensor.";
        return false;
    }
    return true;
}

}  // namespace halalify
