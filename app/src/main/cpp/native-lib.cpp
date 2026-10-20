// Organ engine (Oboe): wavetable test voice + WAV sample voices (attack/loop/release)
#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <unistd.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#define LOG_TAG "OrganEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// WAV loading (PCM16/24/32 at float32; mono-mixdown sa int16; may 'smpl' loop at pitch)
// ---------------------------------------------------------------------------
struct Sample {
    std::vector<int16_t> data;
    int sampleRate = 44100;
    int loopStart = -1;
    int loopEnd = -1;        // exclusive
    float pitchCents = 0.0f; // correction: ODF PitchTuning - paglihis ng sample sa pinakamalapit na ET note
    float gain = 1.0f;       // AmplitudeLevel x Gain mula sa ODF
    bool hasLoop() const { return loopStart >= 0 && loopEnd > loopStart + 1; }
};

static uint32_t rd32(const uint8_t* p) {
    return (uint32_t) p[0] | ((uint32_t) p[1] << 8) | ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24);
}
static uint16_t rd16(const uint8_t* p) { return (uint16_t) (p[0] | (p[1] << 8)); }

static bool readAll(int fd, std::vector<uint8_t>& buf) {
    static constexpr size_t kMaxFile = 256u * 1024u * 1024u;
    uint8_t tmp[65536];
    for (;;) {
        ssize_t r = read(fd, tmp, sizeof(tmp));
        if (r < 0) return false;
        if (r == 0) break;
        buf.insert(buf.end(), tmp, tmp + r);
        if (buf.size() > kMaxFile) return false;
    }
    return !buf.empty();
}

static std::shared_ptr<Sample> parseWav(const std::vector<uint8_t>& b, std::string& err) {
    char msg[128];
    if (b.size() < 44 || memcmp(b.data(), "RIFF", 4) != 0 || memcmp(b.data() + 8, "WAVE", 4) != 0) {
        char h[5] = {0, 0, 0, 0, 0};
        for (int i = 0; i < 4 && i < (int) b.size(); ++i) h[i] = (b[i] >= 32 && b[i] < 127) ? (char) b[i] : '?';
        snprintf(msg, sizeof(msg), "Hindi RIFF/WAVE ang file (header '%s', %zu bytes)", h, b.size());
        err = msg;
        return nullptr;
    }
    uint16_t fmtTag = 0, channels = 0, bits = 0;
    uint32_t rate = 0;
    const uint8_t* pcm = nullptr;
    size_t pcmBytes = 0;
    int64_t loopS = -1, loopE = -1;
    uint32_t unity = 0, fraction = 0;

    size_t p = 12;
    while (p + 8 <= b.size()) {
        const uint8_t* c = b.data() + p;
        uint32_t sz = rd32(c + 4);
        size_t body = p + 8;
        size_t avail = b.size() - body;
        size_t len = sz > avail ? avail : sz;
        if (memcmp(c, "fmt ", 4) == 0 && len >= 16) {
            fmtTag = rd16(c + 8);
            channels = rd16(c + 10);
            rate = rd32(c + 12);
            bits = rd16(c + 22);
            if (fmtTag == 0xFFFE && len >= 26) fmtTag = rd16(c + 8 + 24);
        } else if (memcmp(c, "data", 4) == 0) {
            pcm = c + 8;
            pcmBytes = len;
        } else if (memcmp(c, "smpl", 4) == 0 && len >= 36) {
            unity = rd32(c + 8 + 12);
            fraction = rd32(c + 8 + 16);
            if (len >= 60 && rd32(c + 8 + 28) > 0) {
                loopS = rd32(c + 8 + 44);
                loopE = (int64_t) rd32(c + 8 + 48) + 1;
            }
        }
        p = body + len + (sz & 1);
    }
    if (!pcm || channels < 1 || rate == 0) {
        err = "Sira ang WAV: walang fmt/data chunk";
        return nullptr;
    }
    const bool isPcm = fmtTag == 1 && (bits == 16 || bits == 24 || bits == 32);
    const bool isFloat = fmtTag == 3 && bits == 32;
    if (!isPcm && !isFloat) {
        snprintf(msg, sizeof(msg), "Hindi suportadong WAV format: tag=%u bits=%u ch=%u", fmtTag, bits, channels);
        err = msg;
        return nullptr;
    }

    const size_t bytesPer = bits / 8;
    const size_t frameBytes = bytesPer * channels;
    const size_t frames = pcmBytes / frameBytes;
    if (frames < 2) { err = "Masyadong maikli ang WAV"; return nullptr; }

    auto s = std::make_shared<Sample>();
    s->data.resize(frames);
    s->sampleRate = (int) rate;
    for (size_t f = 0; f < frames; ++f) {
        int sum = 0;
        for (uint16_t ch = 0; ch < channels; ++ch) {
            const uint8_t* q = pcm + f * frameBytes + ch * bytesPer;
            int v;
            if (isFloat) {
                float x;
                memcpy(&x, q, 4);
                v = (int) std::lround(std::max(-1.0f, std::min(1.0f, x)) * 32767.0f);
            } else if (bits == 16) {
                v = (int16_t) rd16(q);
            } else if (bits == 24) {
                v = (int16_t) rd16(q + 1);
            } else {
                v = (int16_t) rd16(q + 2);
            }
            sum += v;
        }
        s->data[f] = (int16_t) (sum / channels);
    }
    if (loopS >= 0 && loopE > loopS + 1 && loopS < (int64_t) frames) {
        s->loopStart = (int) loopS;
        s->loopEnd = (int) std::min<int64_t>(loopE, (int64_t) frames);
    }
    // Pitch info ng sample: itutuwid sa pinakamalapit na equal-temperament note
    if (unity > 0 || fraction > 0) {
        const double semis = (double) unity + (double) fraction / 4294967296.0;
        const double dev = (semis - std::round(semis)) * 100.0;   // cents (+ = masyadong mataas)
        s->pitchCents = (float) -dev;
    }
    return s;
}

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------
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
        mAttack  = 1.0f - std::exp(-1.0f / (mSampleRate * 0.010f));
        mRelease = 1.0f - std::exp(-1.0f / (mSampleRate * 0.080f));
        mVoices.reserve(kMaxVoices);

        mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * mBursts.load());

        r = mStream->requestStart();
        if (r != oboe::Result::OK) {
            LOGE("requestStart failed: %s", oboe::convertToText(r));
            return false;
        }
        LOGI("Audio started: %s", infoLocked().c_str());
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mLock);
        if (mStream) {
            mStream->stop();
            mStream->close();
            mStream.reset();
        }
        mVoices.clear();                       // safe: stopped na ang stream
        std::lock_guard<std::mutex> cl(mCmdLock);
        mPending.clear();
    }

    // ---- general settings ----
    void setParams(float master, float attackMs, float releaseMs, float velSens,
                   float globalCents, int maxVoices, int memCapMb) {
        mMaster.store(std::max(0.0f, master));
        mAttackMs.store(std::max(0.5f, attackMs));
        mReleaseMs.store(std::max(5.0f, releaseMs));
        mVelSens.store(std::min(1.0f, std::max(0.0f, velSens)));
        mGlobalCents.store(globalCents);
        mMaxVoices.store((size_t) std::max(8, std::min((int) kMaxVoices, maxVoices)));
        mMaxBytes.store((size_t) std::max(32, memCapMb) * 1024u * 1024u);
    }

    float setBufferBursts(int n) {
        n = std::max(1, std::min(16, n));
        mBursts.store(n);
        std::lock_guard<std::mutex> lock(mLock);
        if (!mStream) return 0.0f;
        mStream->setBufferSizeInFrames(mStream->getFramesPerBurst() * n);
        return mStream->getBufferSizeInFrames() * 1000.0f / (float) mStream->getSampleRate();
    }

    std::string info() {
        std::lock_guard<std::mutex> lock(mLock);
        return infoLocked();
    }

    // ---- wavetable test voice ----
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

    // ---- sample management (loader threads) ----
    // 0 = ok, 1 = hindi mabasa, 2 = hindi suportadong WAV, 3 = sobra sa memory cap
    int loadSample(int key, int fd, float odfCents, float odfGain) {
        std::vector<uint8_t> buf;
        bool ok = readAll(fd, buf);
        close(fd);
        if (!ok) { setError("Hindi mabasa ang file (read error o walang laman)"); return 1; }
        std::string err;
        std::shared_ptr<Sample> s = parseWav(buf, err);
        buf.clear();
        buf.shrink_to_fit();
        if (!s) { setError(err); return 2; }
        s->pitchCents += odfCents;
        s->gain = odfGain;
        const size_t bytes = s->data.size() * sizeof(int16_t);
        std::lock_guard<std::mutex> l(mSampleLock);
        if (mLoadedBytes + bytes > mMaxBytes.load()) {
            setError("Sobra sa memory cap (taasan sa Settings)");
            return 3;
        }
        auto it = mSamples.find(key);
        if (it != mSamples.end()) {
            mLoadedBytes -= it->second->data.size() * sizeof(int16_t);
            it->second = s;
        } else {
            mSamples[key] = s;
        }
        mLoadedBytes += bytes;
        return 0;
    }

    void unloadSample(int key) {
        std::lock_guard<std::mutex> l(mSampleLock);
        auto it = mSamples.find(key);
        if (it == mSamples.end()) return;
        mLoadedBytes -= it->second->data.size() * sizeof(int16_t);
        mSamples.erase(it);
    }

    long long loadedBytes() { std::lock_guard<std::mutex> l(mSampleLock); return (long long) mLoadedBytes; }
    int loadedCount() { std::lock_guard<std::mutex> l(mSampleLock); return (int) mSamples.size(); }

    std::string lastError() { std::lock_guard<std::mutex> l(mErrLock); return mLastError; }

    // ---- pipe trigger (MIDI thread) ----
    void pipeOn(int key, int velocity) {
        std::shared_ptr<const Sample> s;
        {
            std::lock_guard<std::mutex> l(mSampleLock);
            auto it = mSamples.find(key);
            if (it == mSamples.end()) return;
            s = it->second;
        }
        pushCmd({0, key, std::max(1, velocity) / 127.0f, std::move(s)});
    }
    void pipeOff(int key) { pushCmd({1, key, 0.0f, nullptr}); }

    // ---- audio callback ----
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                          int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);
        std::fill(out, out + numFrames, 0.0f);

        processCommands();
        renderWavetable(out, numFrames);
        renderSamples(out, numFrames);

        for (int i = 0; i < numFrames; ++i) out[i] = std::tanh(out[i]);   // soft limiter
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        LOGE("Stream error: %s - restarting", oboe::convertToText(error));
        start();
    }

private:
    static constexpr int kTableSize = 2048;
    static constexpr float kVoiceGain = 0.12f;
    static constexpr size_t kMaxVoices = 512;
    static constexpr size_t kMaxPending = 4096;

    struct Cmd { int type; int key; float vel; std::shared_ptr<const Sample> s; };
    struct Voice {
        std::shared_ptr<const Sample> s;
        double pos = 0.0;
        double ratio = 1.0;
        float gain = 0.5f;
        float env = 0.0f;
        bool releasing = false;
        int key = 0;
    };

    std::string infoLocked() {
        if (!mStream) return "Naka-stop ang audio";
        char buf[200];
        snprintf(buf, sizeof(buf), "Audio: %s, %d Hz, burst %d, buffer %d frames (~%.1f ms)",
                 oboe::convertToText(mStream->getAudioApi()), mStream->getSampleRate(),
                 mStream->getFramesPerBurst(), mStream->getBufferSizeInFrames(),
                 mStream->getBufferSizeInFrames() * 1000.0 / mStream->getSampleRate());
        return buf;
    }

    void setError(const std::string& e) { std::lock_guard<std::mutex> l(mErrLock); mLastError = e; }

    void pushCmd(Cmd c) {
        std::lock_guard<std::mutex> l(mCmdLock);
        if (mPending.size() < kMaxPending) mPending.push_back(std::move(c));
    }

    void processCommands() {
        if (!mCmdLock.try_lock()) return;      // huwag mag-block sa audio thread
        mLocal.swap(mPending);
        mCmdLock.unlock();
        const float sens = mVelSens.load(std::memory_order_relaxed);
        const double gc = mGlobalCents.load(std::memory_order_relaxed);
        const size_t maxV = mMaxVoices.load(std::memory_order_relaxed);
        for (auto& c : mLocal) {
            if (c.type == 0) {
                if (mVoices.size() < maxV && c.s) {
                    Voice v;
                    v.s = std::move(c.s);
                    v.ratio = (static_cast<double>(v.s->sampleRate) / static_cast<double>(mSampleRate))
                              * std::exp2((static_cast<double>(v.s->pitchCents) + gc) / 1200.0);
                    v.gain = (1.0f - sens * (1.0f - c.vel)) * v.s->gain;
                    v.key = c.key;
                    mVoices.push_back(std::move(v));
                }
            } else {
                for (auto& v : mVoices) if (v.key == c.key) v.releasing = true;
            }
        }
        mLocal.clear();
    }

    void renderWavetable(float* out, int numFrames) {
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
    }

    void renderSamples(float* out, int numFrames) {
        const float attackStep = 1.0f / (mAttackMs.load(std::memory_order_relaxed) * 0.001f * mSampleRate);
        const float releaseStep = 1.0f / (mReleaseMs.load(std::memory_order_relaxed) * 0.001f * mSampleRate);
        const float master = mMaster.load(std::memory_order_relaxed);
        for (size_t vi = 0; vi < mVoices.size();) {
            Voice& v = mVoices[vi];
            const Sample& s = *v.s;
            const int16_t* d = s.data.data();
            const int size = static_cast<int>(s.data.size());
            const bool loop = s.hasLoop();
            bool done = false;
            for (int i = 0; i < numFrames; ++i) {
                const int idx = static_cast<int>(v.pos);
                if (idx >= size - 1) { done = true; break; }
                const float fr = static_cast<float>(v.pos - idx);
                int n1 = idx + 1;
                if (loop && n1 >= s.loopEnd) n1 = s.loopStart;
                const float smp = (d[idx] * (1.0f - fr) + d[n1] * fr) * (1.0f / 32768.0f);
                if (v.releasing) {
                    v.env -= releaseStep;
                    if (v.env <= 0.0f) { done = true; break; }
                } else if (v.env < 1.0f) {
                    v.env = std::min(1.0f, v.env + attackStep);
                }
                out[i] += smp * v.env * v.gain * master;
                v.pos += v.ratio;
                if (loop) {
                    const double len = static_cast<double>(s.loopEnd - s.loopStart);
                    while (v.pos >= s.loopEnd) v.pos -= len;
                }
            }
            if (done) {
                mVoices[vi] = std::move(mVoices.back());
                mVoices.pop_back();
            } else {
                ++vi;
            }
        }
    }

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

    // settings
    std::atomic<float> mMaster{0.30f};
    std::atomic<float> mAttackMs{4.0f};
    std::atomic<float> mReleaseMs{120.0f};
    std::atomic<float> mVelSens{0.0f};
    std::atomic<float> mGlobalCents{0.0f};
    std::atomic<size_t> mMaxVoices{256};
    std::atomic<size_t> mMaxBytes{700u * 1024u * 1024u};
    std::atomic<int> mBursts{2};

    // wavetable test voice
    std::array<std::atomic<bool>, 128> mGate{};
    std::array<std::atomic<float>, 128> mVel{};
    std::atomic<int> mTranspose{0};
    std::array<float, 128> mPhase{};
    std::array<float, 128> mEnv{};

    // samples
    std::mutex mSampleLock;
    std::unordered_map<int, std::shared_ptr<const Sample>> mSamples;
    size_t mLoadedBytes = 0;
    std::mutex mErrLock;
    std::string mLastError;

    // MIDI thread -> audio thread
    std::mutex mCmdLock;
    std::vector<Cmd> mPending;
    std::vector<Cmd> mLocal;      // audio thread lang
    std::vector<Voice> mVoices;   // audio thread lang
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
JNIEXPORT jint JNICALL
Java_com_example_organplayer_AudioEngine_nativeLoadSample(JNIEnv*, jobject, jint key, jint fd,
                                                          jfloat tuneCents, jfloat gain) {
    if (!gEngine) { close(fd); return 1; }
    return gEngine->loadSample(key, fd, tuneCents, gain);
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeUnloadSample(JNIEnv*, jobject, jint key) {
    if (gEngine) gEngine->unloadSample(key);
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativePipeOn(JNIEnv*, jobject, jint key, jint velocity) {
    if (gEngine) gEngine->pipeOn(key, velocity);
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativePipeOff(JNIEnv*, jobject, jint key) {
    if (gEngine) gEngine->pipeOff(key);
}
JNIEXPORT jlong JNICALL
Java_com_example_organplayer_AudioEngine_nativeLoadedBytes(JNIEnv*, jobject) {
    return gEngine ? (jlong) gEngine->loadedBytes() : 0;
}
JNIEXPORT jint JNICALL
Java_com_example_organplayer_AudioEngine_nativeLoadedCount(JNIEnv*, jobject) {
    return gEngine ? gEngine->loadedCount() : 0;
}
JNIEXPORT jstring JNICALL
Java_com_example_organplayer_AudioEngine_nativeLastError(JNIEnv* env, jobject) {
    std::string e = gEngine ? gEngine->lastError() : std::string("walang engine");
    return env->NewStringUTF(e.c_str());
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeSetParams(JNIEnv*, jobject, jfloat master, jfloat attackMs,
                                                         jfloat releaseMs, jfloat velSens, jfloat globalCents,
                                                         jint maxVoices, jint memCapMb) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    gEngine->setParams(master, attackMs, releaseMs, velSens, globalCents, maxVoices, memCapMb);
}
JNIEXPORT jfloat JNICALL
Java_com_example_organplayer_AudioEngine_nativeSetBufferBursts(JNIEnv*, jobject, jint n) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    return gEngine->setBufferBursts(n);
}
JNIEXPORT jstring JNICALL
Java_com_example_organplayer_AudioEngine_nativeInfo(JNIEnv* env, jobject) {
    std::string e = gEngine ? gEngine->info() : std::string("Walang audio engine");
    return env->NewStringUTF(e.c_str());
}

}  // extern "C"
