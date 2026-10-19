// Low-latency organ engine gamit ang Oboe.
// Wavetable voice per MIDI note (0-127). Madaling palitan ng sample playback (.wav) mamaya.
#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <vector>

#define LOG_TAG "OrganEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class OrganEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback,
                    public std::enable_shared_from_this<OrganEngine> {
public:
    bool start() {
        std::lock_guard<std::mutex> lock(mLock);
        if (mTable.empty()) buildTable();

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Mono)
               ->setDataCallback(shared_from_this())
               ->setErrorCallback(shared_from_this());

        oboe::Result r = builder.openStream(mStream);
        if (r != oboe::Result::OK) {
            LOGE("openStream failed: %s", oboe::convertToText(r));
            return false;
        }
        mSampleRate = static_cast<float>(mStream->getSampleRate());
        mAttack  = 1.0f - std::exp(-1.0f / (mSampleRate * 0.010f)); // ~10 ms attack
        mRelease = 1.0f - std::exp(-1.0f / (mSampleRate * 0.080f)); // ~80 ms release

        // 2 bursts = mababang latency pero stable sa karamihan ng devices
        mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * 2);

        r = mStream->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("requestStart failed: %s", oboe::convertToText(r));
            return false;
        }
        LOGI("Audio started: %d Hz, burst=%d, buffer=%d frames (~%.1f ms), api=%s",
             mStream->getSampleRate(), mStream->getFramesPerBurst(),
             mStream->getBufferSizeInFrames(),
             mStream->getBufferSizeInFrames() * 1000.0 / mStream->getSampleRate(),
             oboe::convertToText(mStream->getAudioApi()));
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mLock);
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();   // sinisira ang reference cycle (stream <-> callback)
        }
    }

    void noteOn(int note, int velocity) {
        if (note < 0 || note > 127) return;
        mVel[note].store(std::max(1, velocity) / 127.0f, std::memory_order_relaxed);
        mGate[note].store(true, std::memory_order_release);
    }

    void noteOff(int note) {
        if (note < 0 || note > 127) return;
        mGate[note].store(false, std::memory_order_release);
    }

    void setTranspose(int semitones) { mTranspose.store(semitones); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                          int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);
        std::fill(out, out + numFrames, 0.0f);

        const int transpose = mTranspose.load(std::memory_order_relaxed);
        const float tableSize = static_cast<float>(kTableSize);

        for (int n = 0; n < 128; ++n) {
            const bool on = mGate[n].load(std::memory_order_acquire);
            if (!on && mEnv[n] < 0.0001f) { mEnv[n] = 0.0f; continue; }

            const float target = on ? mVel[n].load(std::memory_order_relaxed) : 0.0f;
            const float coef = on ? mAttack : mRelease;
            const float freq = 440.0f * std::exp2((n + transpose - 69) / 12.0f);
            const float inc = tableSize * freq / mSampleRate;

            float phase = mPhase[n];
            float env = mEnv[n];
            for (int i = 0; i < numFrames; ++i) {
                env += (target - env) * coef;
                out[i] += mTable[static_cast<int>(phase)] * env * kVoiceGain;
                phase += inc;
                if (phase >= tableSize) phase -= tableSize;
            }
            mPhase[n] = phase;
            mEnv[n] = env;
        }
        for (int i = 0; i < numFrames; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);
        return oboe::DataCallbackResult::Continue;
    }

    // Kapag nag-disconnect ang audio device (hal. headphones), buksan ulit ang stream.
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        LOGE("Stream error: %s - restarting", oboe::convertToText(error));
        start();
    }

private:
    static constexpr int kTableSize = 2048;
    static constexpr float kVoiceGain = 0.12f;

    // Organ-like na waveform: additive (drawbar-style) harmonics
    void buildTable() {
        static const float harmonics[][2] = {
            {1, 1.0f}, {2, 0.8f}, {3, 0.5f}, {4, 0.4f}, {6, 0.25f}, {8, 0.2f}};
        mTable.assign(kTableSize, 0.0f);
        float peak = 0.0f;
        for (int i = 0; i < kTableSize; ++i) {
            float s = 0.0f;
            for (auto& h : harmonics)
                s += h[1] * std::sin(2.0f * float(M_PI) * h[0] * i / kTableSize);
            mTable[i] = s;
            peak = std::max(peak, std::fabs(s));
        }
        for (auto& v : mTable) v /= peak;
    }

    std::mutex mLock;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::vector<float> mTable;
    float mSampleRate = 48000.0f, mAttack = 0.01f, mRelease = 0.01f;

    std::array<std::atomic<bool>, 128> mGate{};
    std::array<std::atomic<float>, 128> mVel{};
    std::atomic<int> mTranspose{0};
    std::array<float, 128> mPhase{};   // audio thread lang
    std::array<float, 128> mEnv{};     // audio thread lang
};

static std::shared_ptr<OrganEngine> gEngine;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_organplayer_AudioEngine_nativeStart(JNIEnv*, jobject) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    return gEngine->start();
}

JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeStop(JNIEnv*, jobject) {
    if (gEngine) gEngine->stop();
}

JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeNoteOn(JNIEnv*, jobject, jint note, jint velocity) {
    if (gEngine) gEngine->noteOn(note, velocity);
}

JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeNoteOff(JNIEnv*, jobject, jint note) {
    if (gEngine) gEngine->noteOff(note);
}

JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeSetTranspose(JNIEnv*, jobject, jint semitones) {
    if (gEngine) gEngine->setTranspose(semitones);
}

}  // extern "C"
