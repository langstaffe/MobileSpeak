#include <jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>

extern "C" {
const void *ts_playback_acquire(void *);
void ts_playback_release(const void *);
void ts_playback_active(const void *, bool);
size_t ts_render(const void *, float *, float *, size_t, size_t);
}

struct Callback final : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
    const void *pcm;
    const int channels;
    std::atomic<bool> failed{false};
    Callback(void *core, int channels) : pcm(ts_playback_acquire(core)), channels(channels) {}
    ~Callback() override { ts_playback_release(pcm); }
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *data, int32_t frames) override {
        if (frames <= 0) return oboe::DataCallbackResult::Continue;
        auto *samples = static_cast<float *>(data);
        ts_render(pcm, samples, channels == 2 ? samples + 1 : nullptr, frames, channels);
        return oboe::DataCallbackResult::Continue;
    }
    bool onError(oboe::AudioStream *, oboe::Result) override {
        failed.store(true, std::memory_order_release);
        // Kotlin's stream owner closes/rebuilds on its ordinary management thread.
        return true;
    }
};

struct Output {
    std::shared_ptr<Callback> callback;
    std::shared_ptr<oboe::AudioStream> stream;
    ~Output() {
        ts_playback_active(callback->pcm, false);
        // close() stops and synchronizes the data callback before releasing its PCM Arc.
        if (stream) stream->close();
    }
};

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mobilespeak_mobilespeak_NativeOutput_open(JNIEnv *env, jobject, jlong core, jint channels,
                                                jboolean communication) {
    if (!core || (channels != 1 && channels != 2)) return 0;
    auto output = std::make_unique<Output>();
    output->callback = std::make_shared<Callback>(reinterpret_cast<void *>(core), channels);
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setUsage(communication ? oboe::Usage::VoiceCommunication : oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Speech)
        ->setSampleRate(48000)
        ->setChannelCount(channels)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(output->callback)
        ->setErrorCallback(output->callback);
    auto result = builder.openStream(output->stream);
    if (result == oboe::Result::OK &&
        (output->stream->getSampleRate() != 48000 ||
         output->stream->getChannelCount() != channels ||
         output->stream->getFormat() != oboe::AudioFormat::Float)) {
        result = oboe::Result::ErrorInvalidFormat;
    }
    if (result == oboe::Result::OK) {
        ts_playback_active(output->callback->pcm, true);
        result = output->stream->requestStart();
    }
    if (result != oboe::Result::OK) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), oboe::convertToText(result));
        return 0;
    }
    return reinterpret_cast<jlong>(output.release());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mobilespeak_mobilespeak_NativeOutput_close(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Output *>(handle);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mobilespeak_mobilespeak_NativeOutput_failed(JNIEnv *, jobject, jlong handle) {
    return reinterpret_cast<Output *>(handle)->callback->failed.load(std::memory_order_acquire);
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_mobilespeak_mobilespeak_NativeOutput_deviceId(JNIEnv *, jobject, jlong handle) {
    return reinterpret_cast<Output *>(handle)->stream->getDeviceId();
}
