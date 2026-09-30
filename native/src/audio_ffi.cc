// Prevent ONNX Runtime C++ exceptions from crossing the Rust C ABI.
#include <exception>
#include <string>
struct SherpaOnnxOnlineSpeechDenoiserConfig;
struct SherpaOnnxOnlineSpeechDenoiser;
struct SherpaOnnxDenoisedAudio;
using Model = SherpaOnnxOnlineSpeechDenoiser;
using Config = SherpaOnnxOnlineSpeechDenoiserConfig;
using Audio = SherpaOnnxDenoisedAudio;
static thread_local std::string failure;
extern "C" const Model *ms_dpdf_create(const Model *(*create)(const Config *), const Config *config, const char **error) noexcept {
  try { *error = nullptr; return create(config); }
  catch (const std::exception &e) { failure=e.what(); }
  catch (...) { failure="DPDFNet initialization failed"; }
  *error=failure.c_str(); return nullptr;
}
extern "C" const Audio *ms_dpdf_run(const Audio *(*run)(const Model *, const float *, int, int), const Model *model, const float *samples, int n, const char **error) noexcept {
  try { *error=nullptr; return run(model,samples,n,48000); }
  catch (const std::exception &e) { failure=e.what(); }
  catch (...) { failure="DPDFNet processing failed"; }
  *error=failure.c_str(); return nullptr;
}
extern "C" int ms_dpdf_reset(void (*reset)(const Model *), const Model *model) noexcept {
  try { reset(model); return 0; } catch (...) { return -1; }
}
