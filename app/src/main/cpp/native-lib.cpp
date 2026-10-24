// Organ engine (Oboe): wavetable test voice + WAV sample voices (attack/loop/release)
#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/stat.h>
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
    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size > 0 && (size_t) st.st_size <= kMaxFile) {
        // Known size: read straight into the buffer (no repeated reallocation / extra copy)
        const size_t cap = (size_t) st.st_size;
        buf.resize(cap);
        size_t got = 0;
        while (got < cap) {
            ssize_t r = read(fd, buf.data() + got, cap - got);
            if (r < 0) return false;
            if (r == 0) break;
            got += (size_t) r;
        }
        buf.resize(got);
        return got > 0;
    }
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
        snprintf(msg, sizeof(msg), "Not a RIFF/WAVE file (header '%s', %zu bytes)", h, b.size());
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
        err = "Corrupt WAV: missing fmt/data chunk";
        return nullptr;
    }
    const bool isPcm = fmtTag == 1 && (bits == 16 || bits == 24 || bits == 32);
    const bool isFloat = fmtTag == 3 && bits == 32;
    if (!isPcm && !isFloat) {
        snprintf(msg, sizeof(msg), "Unsupported WAV format: tag=%u bits=%u ch=%u", fmtTag, bits, channels);
        err = msg;
        return nullptr;
    }

    const size_t bytesPer = bits / 8;
    const size_t frameBytes = bytesPer * channels;
    const size_t frames = pcmBytes / frameBytes;
    if (frames < 2) { err = "WAV is too short"; return nullptr; }

    auto s = std::make_shared<Sample>();
    s->data.resize(frames);
    s->sampleRate = (int) rate;
    int16_t* dst = s->data.data();
    if (isPcm && channels == 1 && (bits == 16 || bits == 24)) {
        // Fast path for the common mono files
        if (bits == 16) {
            for (size_t f = 0; f < frames; ++f) dst[f] = (int16_t) rd16(pcm + f * 2);
        } else {
            for (size_t f = 0; f < frames; ++f) dst[f] = (int16_t) rd16(pcm + f * 3 + 1);
        }
    } else if (isPcm && channels == 2 && (bits == 16 || bits == 24)) {
        // Fast path for stereo files (averaged to mono)
        if (bits == 16) {
            for (size_t f = 0; f < frames; ++f) {
                const int l = (int16_t) rd16(pcm + f * 4), r = (int16_t) rd16(pcm + f * 4 + 2);
                dst[f] = (int16_t) ((l + r) / 2);
            }
        } else {
            for (size_t f = 0; f < frames; ++f) {
                const int l = (int16_t) rd16(pcm + f * 6 + 1), r = (int16_t) rd16(pcm + f * 6 + 4);
                dst[f] = (int16_t) ((l + r) / 2);
            }
        }
    } else
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
// Impulse response (convolution reverb): float WAV reader, FFT, partitioned convolver
// ---------------------------------------------------------------------------
static constexpr int kIrB = 512;      // block size of the convolver (wet path is delayed by one block; dry is not)

// Reads any PCM16/24/32 or float32 WAV, mixes channels down to mono float. No int16 rounding (IR tails are quiet).
static bool parseWavFloat(const std::vector<uint8_t>& b, std::vector<float>& out, int& rate, std::string& err) {
    if (b.size() < 44 || memcmp(b.data(), "RIFF", 4) != 0 || memcmp(b.data() + 8, "WAVE", 4) != 0) {
        err = "IR file is not a RIFF/WAVE file";
        return false;
    }
    uint16_t fmtTag = 0, channels = 0, bits = 0;
    uint32_t sr = 0;
    const uint8_t* pcm = nullptr;
    size_t pcmBytes = 0;
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
            sr = rd32(c + 12);
            bits = rd16(c + 22);
            if (fmtTag == 0xFFFE && len >= 26) fmtTag = rd16(c + 8 + 24);
        } else if (memcmp(c, "data", 4) == 0) {
            pcm = c + 8;
            pcmBytes = len;
        }
        p = body + len + (sz & 1);
    }
    if (!pcm || channels < 1 || sr == 0) { err = "IR WAV is corrupt (missing fmt/data)"; return false; }
    const bool isPcm = fmtTag == 1 && (bits == 16 || bits == 24 || bits == 32);
    const bool isFloat = fmtTag == 3 && bits == 32;
    if (!isPcm && !isFloat) { err = "IR WAV format not supported (use 16/24/32-bit PCM or float)"; return false; }
    const size_t bytesPer = bits / 8;
    const size_t frameBytes = bytesPer * channels;
    const size_t frames = pcmBytes / frameBytes;
    if (frames < 64) { err = "IR WAV is too short"; return false; }
    out.resize(frames);
    for (size_t f = 0; f < frames; ++f) {
        float sum = 0.0f;
        for (uint16_t ch = 0; ch < channels; ++ch) {
            const uint8_t* q = pcm + f * frameBytes + ch * bytesPer;
            float v;
            if (isFloat) { memcpy(&v, q, 4); }
            else if (bits == 16) { v = (float) (int16_t) rd16(q) * (1.0f / 32768.0f); }
            else if (bits == 24) {
                const int32_t x = (int32_t) (((uint32_t) q[0] << 8) | ((uint32_t) q[1] << 16) | ((uint32_t) q[2] << 24)) >> 8;
                v = (float) x * (1.0f / 8388608.0f);
            } else { v = (float) ((int32_t) rd32(q)) * (1.0f / 2147483648.0f); }
            sum += v;
        }
        out[f] = sum / (float) channels;
    }
    rate = (int) sr;
    return true;
}

struct Fft {
    int n = 0, logn = 0;
    std::vector<float> cs, sn;
    std::vector<int> rev;
    void init(int N) {
        n = N; logn = 0;
        while ((1 << logn) < N) ++logn;
        cs.resize(N / 2); sn.resize(N / 2);
        for (int i = 0; i < N / 2; ++i) {
            const double a = -2.0 * M_PI * i / N;
            cs[i] = (float) std::cos(a);
            sn[i] = (float) std::sin(a);
        }
        rev.resize(N);
        for (int i = 0; i < N; ++i) {
            int r = 0;
            for (int b = 0; b < logn; ++b) if (i & (1 << b)) r |= 1 << (logn - 1 - b);
            rev[i] = r;
        }
    }
    // In place. inverse = conjugate twiddles, NO scaling. Thread-safe (tables are read-only).
    void run(float* re, float* im, bool inverse) const {
        for (int i = 0; i < n; ++i) {
            const int j = rev[i];
            if (j > i) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
        }
        for (int len = 2; len <= n; len <<= 1) {
            const int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; ++k) {
                    const float wr = cs[k * step];
                    const float wi = inverse ? -sn[k * step] : sn[k * step];
                    const int a = i + k, c = a + half;
                    const float tr = re[c] * wr - im[c] * wi;
                    const float ti = re[c] * wi + im[c] * wr;
                    re[c] = re[a] - tr; im[c] = im[a] - ti;
                    re[a] += tr;        im[a] += ti;
                }
            }
        }
    }
};

// Uniform-partitioned overlap-save convolver. Built on a loader thread, run on the audio thread.
struct IrData {
    int P = 0;                                   // number of partitions
    std::vector<float> Hre, Him, Xre, Xim;       // P * (B+1): IR spectrum and the frequency-domain delay line
    std::vector<float> inBuf, outBuf, prev, re, im, accRe, accIm;
    int pos = 0, fdlPos = 0;
    int workP = 0, workIdx = 0;   // spread-out accumulation: next partition / its delay-line slot
    bool haveAcc = false;         // an accumulation is waiting to be turned into output
    float seconds = 0.0f;
    void clearState() {
        std::fill(Xre.begin(), Xre.end(), 0.0f);
        std::fill(Xim.begin(), Xim.end(), 0.0f);
        std::fill(inBuf.begin(), inBuf.end(), 0.0f);
        std::fill(outBuf.begin(), outBuf.end(), 0.0f);
        std::fill(prev.begin(), prev.end(), 0.0f);
        pos = 0; fdlPos = 0; workP = 0; workIdx = 0; haveAcc = false;
    }
};

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------
// Light mono reverb (Freeverb-style: 8 comb + 4 allpass) with optional pre-delay
struct Comb {
    std::vector<float> buf; size_t idx = 0; float store = 0.0f;
    void init(size_t n) { buf.assign(std::max<size_t>(1, n), 0.0f); idx = 0; store = 0.0f; }
    inline float process(float in, float damp1, float fb) {
        const float out = buf[idx];
        store = out * (1.0f - damp1) + store * damp1;
        buf[idx] = in + store * fb;
        if (++idx >= buf.size()) idx = 0;
        return out;
    }
};
struct Allpass {
    std::vector<float> buf; size_t idx = 0;
    void init(size_t n) { buf.assign(std::max<size_t>(1, n), 0.0f); idx = 0; }
    inline float process(float in) {
        const float b = buf[idx];
        const float out = -in + b;
        buf[idx] = in + b * 0.5f;
        if (++idx >= buf.size()) idx = 0;
        return out;
    }
};

class OrganEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback,
                    public std::enable_shared_from_this<OrganEngine> {
public:
    OrganEngine() {
        for (auto& g : mGroupGain) g.store(1.0f);
        mGroupCur.fill(1.0f);
        mFft.init(2 * kIrB);
    }

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
        mRateKnown.store(true);
        mVoices.reserve(kMaxVoices);
        if (mTremBuf.empty()) mTremBuf.assign((size_t) kMaxTrem * 2 * kMaxFrames, 1.0f);
        initReverb();
        rebuildIr();      // the impulse response (if one is loaded) is resampled to this stream's rate

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
        if (!ok) { setError("Cannot read file (read error or empty)"); return 1; }
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
            setError("Over the memory cap (raise it in Settings)");
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
    // tag = unique id ng stop (para magkahiwalay ang boses ng dalawang stop na may parehong pipe)
    void pipeOn(int key, int velocity, uint32_t tremMask, int tag, int group,
                float gainMul, float cents, float relMs) {
        std::shared_ptr<const Sample> s;
        {
            std::lock_guard<std::mutex> l(mSampleLock);
            auto it = mSamples.find(key);
            if (it == mSamples.end()) return;
            s = it->second;
        }
        pushCmd({0, key, std::max(1, velocity) / 127.0f, tremMask, std::move(s), tag, group, gainMul, cents, relMs});
    }
    void pipeOff(int key, int tag) { pushCmd({1, key, 0.0f, 0u, nullptr, tag, 0, 1.0f, 0.0f, 0.0f}); }
    void panic() {
        pushCmd({2, 0, 0.0f, 0u, nullptr, 0, 0, 1.0f, 0.0f, 0.0f});
        for (int n = 0; n < 128; ++n) mGate[n].store(false, std::memory_order_release);
    }

    // ---- expression / swell: gain bawat group (manual) ----
    void setGroupGain(int group, float gain) {
        if (group < 0 || group >= kMaxGroups) return;
        mGroupGain[group].store(std::min(2.0f, std::max(0.0f, gain)));
    }

    // ---- reverb ----
    void setReverb(bool on, float level, float room, float damp, float preMs) {
        mRevLevel.store(std::min(1.0f, std::max(0.0f, level)));
        mRevRoom.store(std::min(1.0f, std::max(0.0f, room)));
        mRevDamp.store(std::min(1.0f, std::max(0.0f, damp)));
        mRevPre.store(std::min(250.0f, std::max(0.0f, preMs)));
        mRevOn.store(on);
    }

    // ---- impulse response (convolution reverb) ----
    // 0 = ok, 1 = cannot read, 2 = bad WAV
    int loadIr(int fd, int maxMs) {
        std::vector<uint8_t> buf;
        const bool ok = readAll(fd, buf);
        close(fd);
        if (!ok) { setError("Cannot read the IR file (read error or empty)"); return 1; }
        std::vector<float> raw;
        int rate = 0;
        std::string err;
        if (!parseWavFloat(buf, raw, rate, err)) { setError(err); return 2; }
        buf.clear(); buf.shrink_to_fit();
        {
            std::lock_guard<std::mutex> l(mIrRawLock);
            mIrRaw = std::move(raw);
            mIrRate = rate;
        }
        return rebuildIr(maxMs);
    }

    void clearIr() {
        { std::lock_guard<std::mutex> l(mIrRawLock); mIrRaw.clear(); mIrRaw.shrink_to_fit(); mIrRate = 0; }
        { std::lock_guard<std::mutex> l(mErrLock); mIrInfoStr.clear(); }
        publishIr(nullptr);
    }

    void setIr(bool on, float level) {
        mIrLevel.store(std::min(2.0f, std::max(0.0f, level)));
        mIrOn.store(on);
    }

    std::string irInfo() { std::lock_guard<std::mutex> l(mErrLock); return mIrInfoStr; }

    // Re-creates the convolver from the stored IR (new length cap or new stream rate). Runs on the caller's thread.
    int rebuildIr(int maxMs = -1) {
        if (maxMs > 0) mIrMaxMs.store(std::max(200, std::min(10000, maxMs)));
        std::lock_guard<std::mutex> bl(mIrBuildLock);
        std::vector<float> x;
        int rate;
        { std::lock_guard<std::mutex> l(mIrRawLock); x = mIrRaw; rate = mIrRate; }
        if (x.size() < 64 || rate <= 0) return 0;
        if (!mRateKnown.load()) return 0;                // will be built when the stream starts
        const double sr = (double) mSampleRate;
        const int origRate = rate;
        const double origSec = (double) x.size() / (double) rate;

        if (rate != (int) std::lround(sr)) {              // linear resample to the stream rate
            const double ratio = (double) rate / sr;
            const size_t n = (size_t) ((double) x.size() / ratio);
            std::vector<float> y(n);
            for (size_t i = 0; i < n; ++i) {
                const double src = (double) i * ratio;
                const size_t i0 = (size_t) src;
                const size_t i1 = std::min(i0 + 1, x.size() - 1);
                const float fr = (float) (src - (double) i0);
                y[i] = x[i0] * (1.0f - fr) + x[i1] * fr;
            }
            x.swap(y);
        }
        const size_t maxLen = (size_t) ((double) mIrMaxMs.load() * 0.001 * sr);
        const bool truncated = x.size() > maxLen;
        if (truncated) x.resize(std::max<size_t>(maxLen, (size_t) kIrB));
        // soft fade at the end so a cut tail does not click
        size_t fade = std::min(x.size() / 4, (size_t) ((truncated ? 0.030 : 0.005) * sr));
        for (size_t i = 0; i < fade; ++i) {
            const float g = 0.5f + 0.5f * std::cos(3.14159265f * (float) i / (float) fade);
            x[x.size() - fade + i] *= g;
        }
        double e = 0.0;
        for (float v : x) e += (double) v * (double) v;
        if (e <= 1e-12) { setError("The IR file is silent"); publishIr(nullptr); return 2; }
        const float sc = (float) (1.0 / std::sqrt(e));    // equal-energy normalisation
        for (float& v : x) v *= sc;

        auto d = std::make_shared<IrData>();
        const int B = kIrB, N = 2 * kIrB, K = kIrB + 1;
        d->P = (int) ((x.size() + (size_t) B - 1) / (size_t) B);
        d->seconds = (float) ((double) x.size() / sr);
        const size_t bins = (size_t) d->P * (size_t) K;
        d->Hre.assign(bins, 0.0f); d->Him.assign(bins, 0.0f);
        d->Xre.assign(bins, 0.0f); d->Xim.assign(bins, 0.0f);
        d->inBuf.assign(B, 0.0f); d->outBuf.assign(B, 0.0f); d->prev.assign(B, 0.0f);
        d->re.assign(N, 0.0f); d->im.assign(N, 0.0f);
        d->accRe.assign(K, 0.0f); d->accIm.assign(K, 0.0f);
        std::vector<float> re(N), im(N);
        const float inv = 1.0f / (float) N;               // the inverse FFT scaling is folded into the IR
        for (int p = 0; p < d->P; ++p) {
            std::fill(re.begin(), re.end(), 0.0f);
            std::fill(im.begin(), im.end(), 0.0f);
            const size_t off = (size_t) p * (size_t) B;
            for (int i = 0; i < B && off + (size_t) i < x.size(); ++i) re[i] = x[off + (size_t) i];
            mFft.run(re.data(), im.data(), false);
            float* hr = &d->Hre[(size_t) p * K];
            float* hi = &d->Him[(size_t) p * K];
            for (int k = 0; k < K; ++k) { hr[k] = re[k] * inv; hi[k] = im[k] * inv; }
        }
        char msg[160];
        snprintf(msg, sizeof(msg), "IR: %.1f s file at %d Hz -> %.1f s used (%d partitions)%s",
                 origSec, origRate, (double) d->seconds, d->P, truncated ? ", cut to the max length" : "");
        { std::lock_guard<std::mutex> l(mErrLock); mIrInfoStr = msg; }
        publishIr(std::move(d));
        return 0;
    }

    // ---- tremulants (slot 0..31) ----
    void setTremulant(int slot, bool on, float periodMs, float depthPct, float startMs, float stopMs) {
        if (slot < 0 || slot >= kMaxTrem) return;
        mTremPeriod[slot].store(periodMs);
        mTremDepth[slot].store(std::min(1.0f, std::max(0.0f, depthPct / 100.0f)));
        mTremStart[slot].store(startMs);
        mTremStop[slot].store(stopMs);
        mTremOn[slot].store(on);
    }
    void clearTremulants() {
        for (int t = 0; t < kMaxTrem; ++t) mTremOn[t].store(false);
    }

    // ---- audio callback ----
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                          int32_t numFrames) override {
        auto* out = static_cast<float*>(audioData);
        std::fill(out, out + numFrames, 0.0f);

        processCommands();
        renderWavetable(out, numFrames);
        prepareTremulants(numFrames);
        prepareGroups(numFrames);
        renderSamples(out, numFrames);
        if (!processIr(out, numFrames)) processReverb(out, numFrames);

        float pk = 0.0f;
        for (int i = 0; i < numFrames; ++i) {
            const float a = std::fabs(out[i]);
            if (a > pk) pk = a;
            out[i] = std::tanh(out[i]);                                     // soft limiter
        }
        if (pk > mPeak.load(std::memory_order_relaxed)) mPeak.store(pk, std::memory_order_relaxed);
        const int nv = (int) mVoices.size();
        if (nv > mVoicePeak.load(std::memory_order_relaxed)) mVoicePeak.store(nv, std::memory_order_relaxed);
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
    static constexpr int kMaxTrem = 32;
    static constexpr int kMaxFrames = 4096;
    static constexpr int kMaxGroups = 16;

    struct Cmd { int type; int key; float vel; uint32_t mask; std::shared_ptr<const Sample> s;
                 int tag; int group; float gain; float cents; float relMs; };
    struct Voice {
        std::shared_ptr<const Sample> s;
        double pos = 0.0;
        double ratio = 1.0;
        float gain = 0.5f;
        float env = 0.0f;
        bool releasing = false;
        int key = 0;
        uint32_t tremMask = 0;
        int tag = 0;
        int group = 0;
        float relStep = 0.0f;       // 0 = gamitin ang global release
    };

    std::string infoLocked() {
        if (!mStream) return "Audio stopped";
        char buf[420];
        const auto xr = mStream->getXRunCount();
        const int xruns = xr ? xr.value() : -1;
        const float peak = mPeak.exchange(0.0f);          // since the last time this was read
        const int vpeak = mVoicePeak.exchange(0);
        int n = snprintf(buf, sizeof(buf), "Audio: %s, %d Hz, burst %d, buffer %d frames (~%.1f ms)"
                 "\nxruns (glitches): %d | voices: peak %d of %zu max, dropped %d | peak level %.2f",
                 oboe::convertToText(mStream->getAudioApi()), mStream->getSampleRate(),
                 mStream->getFramesPerBurst(), mStream->getBufferSizeInFrames(),
                 mStream->getBufferSizeInFrames() * 1000.0 / mStream->getSampleRate(),
                 xruns, vpeak, mMaxVoices.load(), mDropped.load(), (double) peak);
        if (peak > 1.0f && n > 0 && n < (int) sizeof(buf))
            snprintf(buf + n, sizeof(buf) - (size_t) n, " (over 1.0: the soft limiter is squashing the sound - lower Master volume)");
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
                              * std::exp2((static_cast<double>(v.s->pitchCents) + gc + c.cents) / 1200.0);
                    v.gain = (1.0f - sens * (1.0f - c.vel)) * v.s->gain * c.gain;
                    v.key = c.key;
                    v.tremMask = c.mask;
                    v.tag = c.tag;
                    v.group = std::min(kMaxGroups - 1, std::max(0, c.group));
                    v.relStep = c.relMs > 0.0f ? 1.0f / (c.relMs * 0.001f * mSampleRate) : 0.0f;
                    mVoices.push_back(std::move(v));
                } else if (c.s) {
                    mDropped.fetch_add(1, std::memory_order_relaxed);   // voice cap reached: this pipe stays silent
                }
            } else if (c.type == 1) {
                for (auto& v : mVoices) if (v.key == c.key && v.tag == c.tag) v.releasing = true;
            } else {                                            // panic
                for (auto& v : mVoices) v.releasing = true;
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

    // Kinakalkula ang amplitude/pitch multiplier ng bawat tremulant na aktibo para sa buong buffer.
    void prepareTremulants(int numFrames) {
        mTremActive = 0;
        if (numFrames > kMaxFrames || mTremBuf.empty()) return;
        const float sr = mSampleRate;
        for (int t = 0; t < kMaxTrem; ++t) {
            const bool on = mTremOn[t].load(std::memory_order_relaxed);
            float level = mTremLevel[t];
            if (!on && level <= 0.0f) continue;
            const float depth = mTremDepth[t].load(std::memory_order_relaxed);
            const float period = std::max(20.0f, mTremPeriod[t].load(std::memory_order_relaxed));
            const float upStep = 1.0f / (std::max(1.0f, mTremStart[t].load(std::memory_order_relaxed)) * 0.001f * sr);
            const float dnStep = 1.0f / (std::max(1.0f, mTremStop[t].load(std::memory_order_relaxed)) * 0.001f * sr);
            const float dPhase = 1.0f / (period * 0.001f * sr);
            float phase = mTremPhase[t];
            float* amp = &mTremBuf[(size_t) (t * 2) * kMaxFrames];
            float* pit = &mTremBuf[(size_t) (t * 2 + 1) * kMaxFrames];
            for (int i = 0; i < numFrames; ++i) {
                level = on ? std::min(1.0f, level + upStep) : std::max(0.0f, level - dnStep);
                const float s = std::sin(6.2831853f * phase);
                amp[i] = 1.0f + depth * level * 0.5f * s;
                pit[i] = 1.0f + depth * level * 0.0087f * s;     // ~ +-15 cents sa 100% depth
                phase += dPhase;
                if (phase >= 1.0f) phase -= 1.0f;
            }
            mTremPhase[t] = phase;
            mTremLevel[t] = level;
            mTremActive |= (1u << t);
        }
    }

    // Smooth na pagbabago ng swell/expression gain sa loob ng buffer (iwas "zipper noise")
    void prepareGroups(int numFrames) {
        const float k = std::min(1.0f, static_cast<float>(numFrames) / (mSampleRate * 0.02f));
        for (int g = 0; g < kMaxGroups; ++g) {
            const float tgt = mGroupGain[g].load(std::memory_order_relaxed);
            const float cur = mGroupCur[g];
            const float end = cur + (tgt - cur) * k;
            mGroupStart[g] = cur;
            mGroupStep[g] = (end - cur) / static_cast<float>(numFrames);
            mGroupCur[g] = end;
        }
    }

    void publishIr(std::shared_ptr<IrData> d) {
        std::shared_ptr<IrData> old;
        {
            std::lock_guard<std::mutex> l(mIrLock);
            old = std::move(mIrPending);
            mIrPending = std::move(d);
            mIrDirty = true;
        }
        // 'old' (the previous convolver) is freed here, on the loader thread, never on the audio thread
    }

    void quietReverb() {
        if (!mRevIdle) { clearReverb(); mRevIdle = true; }
        mRevMixCur = 0.0f;
    }

    // Partitioned convolution, spread over time so the audio callback never gets a CPU spike (low latency
    // needs small buffers, and a spike would cause crackles). The wet path is delayed by two blocks; the dry
    // sound you play is never delayed.
    //   boundary (every B samples): finish the previous accumulation -> IFFT -> output block,
    //                               then FFT the new input block and start accumulating it.
    //   slice (every few samples):  a small share of the partition multiply-adds.
    void irSlice(IrData& d, int count) {
        const int K = kIrB + 1;
        float* __restrict ar = d.accRe.data();
        float* __restrict ai = d.accIm.data();
        int p = d.workP, idx = d.workIdx;
        const int end = std::min(d.P, p + count);
        for (; p < end; ++p) {
            const float* __restrict hr = &d.Hre[(size_t) p * K];
            const float* __restrict hi = &d.Him[(size_t) p * K];
            const float* __restrict xr = &d.Xre[(size_t) idx * K];
            const float* __restrict xi = &d.Xim[(size_t) idx * K];
            for (int k = 0; k < K; ++k) {
                ar[k] += hr[k] * xr[k] - hi[k] * xi[k];
                ai[k] += hr[k] * xi[k] + hi[k] * xr[k];
            }
            if (--idx < 0) idx = d.P - 1;
        }
        d.workP = p; d.workIdx = idx;
    }

    void irBoundary(IrData& d) {
        const int B = kIrB, N = 2 * kIrB, K = kIrB + 1;
        float* __restrict re = d.re.data();
        float* __restrict im = d.im.data();
        const float* ar = d.accRe.data();
        const float* ai = d.accIm.data();
        if (d.haveAcc) {
            irSlice(d, d.P);                                   // normally already done; guarantees correctness
            for (int k = 0; k < K; ++k) { re[k] = ar[k]; im[k] = ai[k]; }
            for (int k = 1; k < B; ++k) { re[N - k] = ar[k]; im[N - k] = -ai[k]; }   // Hermitian symmetry
            mFft.run(re, im, true);
            std::memcpy(d.outBuf.data(), re + B, (size_t) B * sizeof(float));
        }
        std::memcpy(re, d.prev.data(), (size_t) B * sizeof(float));
        std::memcpy(re + B, d.inBuf.data(), (size_t) B * sizeof(float));
        std::memcpy(d.prev.data(), d.inBuf.data(), (size_t) B * sizeof(float));
        std::fill(im, im + N, 0.0f);
        mFft.run(re, im, false);
        std::memcpy(&d.Xre[(size_t) d.fdlPos * K], re, (size_t) K * sizeof(float));
        std::memcpy(&d.Xim[(size_t) d.fdlPos * K], im, (size_t) K * sizeof(float));
        std::fill(d.accRe.begin(), d.accRe.end(), 0.0f);
        std::fill(d.accIm.begin(), d.accIm.end(), 0.0f);
        d.workP = 0; d.workIdx = d.fdlPos; d.haveAcc = true;
        if (++d.fdlPos >= d.P) d.fdlPos = 0;
    }

    // true = the impulse response is handling the reverb (the built-in reverb is bypassed)
    bool processIr(float* out, int numFrames) {
        if (mIrLock.try_lock()) {
            if (mIrDirty) {
                mIrActive.swap(mIrPending);
                mIrDirty = false;
                mIrIdle = true;
                mIrMixCur = 0.0f;
            }
            mIrLock.unlock();
        }
        IrData* d = mIrActive.get();
        if (!d) return false;
        const float target = mIrOn.load(std::memory_order_relaxed) ? mIrLevel.load(std::memory_order_relaxed) : 0.0f;
        if (target <= 0.0f && mIrMixCur <= 0.0001f) {
            mIrMixCur = 0.0f;
            if (!mIrIdle) { d->clearState(); mIrIdle = true; }
            return false;
        }
        mIrIdle = false;
        quietReverb();
        const int B = kIrB;
        constexpr int kSliceEvery = 32;                                   // samples between work slices
        const int perSlice = std::max(1, (d->P * kSliceEvery + B - 1) / B);
        const float step = (target - mIrMixCur) / (float) numFrames;
        for (int i = 0; i < numFrames; ++i) {
            const float dry = out[i];
            d->inBuf[d->pos] = dry;
            const float wet = d->outBuf[d->pos];
            ++d->pos;
            if (d->pos >= B) { irBoundary(*d); d->pos = 0; }
            else if ((d->pos & (kSliceEvery - 1)) == 0 && d->haveAcc) irSlice(*d, perSlice);
            mIrMixCur += step;
            out[i] = dry + wet * mIrMixCur;
        }
        mIrMixCur = target;
        return true;
    }

    void initReverb() {
        static const int combTune[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int apTune[4] = {556, 441, 341, 225};
        const float sc = mSampleRate / 44100.0f;
        for (int i = 0; i < 8; ++i) mComb[i].init((size_t) (combTune[i] * sc));
        for (int i = 0; i < 4; ++i) mAp[i].init((size_t) (apTune[i] * sc));
        mPre.assign((size_t) (mSampleRate * 0.26f) + 2, 0.0f);
        mPreIdx = 0;
        mRevMixCur = 0.0f;
        mRevIdle = true;
    }

    void clearReverb() {
        for (auto& c : mComb) { std::fill(c.buf.begin(), c.buf.end(), 0.0f); c.store = 0.0f; }
        for (auto& a : mAp) std::fill(a.buf.begin(), a.buf.end(), 0.0f);
        std::fill(mPre.begin(), mPre.end(), 0.0f);
    }

    void processReverb(float* out, int numFrames) {
        const float target = mRevOn.load(std::memory_order_relaxed) ? mRevLevel.load(std::memory_order_relaxed) : 0.0f;
        if (target <= 0.0f && mRevMixCur <= 0.0001f) {
            mRevMixCur = 0.0f;
            if (!mRevIdle) { clearReverb(); mRevIdle = true; }
            return;
        }
        mRevIdle = false;
        const float fb = mRevRoom.load(std::memory_order_relaxed) * 0.28f + 0.7f;
        const float damp1 = mRevDamp.load(std::memory_order_relaxed) * 0.4f;
        const size_t psz = mPre.size();
        size_t pre = static_cast<size_t>(mRevPre.load(std::memory_order_relaxed) * 0.001f * mSampleRate);
        if (pre >= psz) pre = psz - 1;
        const float step = (target - mRevMixCur) / static_cast<float>(numFrames);
        for (int i = 0; i < numFrames; ++i) {
            const float dry = out[i];
            mPre[mPreIdx] = dry;
            const size_t rd = (mPreIdx >= pre) ? (mPreIdx - pre) : (mPreIdx + psz - pre);
            const float in = mPre[rd] * 0.015f;
            if (++mPreIdx >= psz) mPreIdx = 0;
            float sum = 0.0f;
            for (int c = 0; c < 8; ++c) sum += mComb[c].process(in, damp1, fb);
            for (int a = 0; a < 4; ++a) sum = mAp[a].process(sum);
            mRevMixCur += step;
            out[i] = dry + sum * 3.0f * mRevMixCur;
        }
        mRevMixCur = target;
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
            const uint32_t tm = v.tremMask & mTremActive;
            bool done = false;
            for (int i = 0; i < numFrames; ++i) {
                const int idx = static_cast<int>(v.pos);
                if (idx >= size - 1) { done = true; break; }
                const float fr = static_cast<float>(v.pos - idx);
                int n1 = idx + 1;
                if (loop && n1 >= s.loopEnd) n1 = s.loopStart;
                const float smp = (d[idx] * (1.0f - fr) + d[n1] * fr) * (1.0f / 32768.0f);
                if (v.releasing) {
                    v.env -= (v.relStep > 0.0f ? v.relStep : releaseStep);
                    if (v.env <= 0.0f) { done = true; break; }
                } else if (v.env < 1.0f) {
                    v.env = std::min(1.0f, v.env + attackStep);
                }
                float amp = 1.0f, pit = 1.0f;
                if (tm) {
                    uint32_t b = tm;
                    while (b) {
                        const int t = __builtin_ctz(b);
                        b &= b - 1;
                        amp *= mTremBuf[(size_t) (t * 2) * kMaxFrames + i];
                        pit *= mTremBuf[(size_t) (t * 2 + 1) * kMaxFrames + i];
                    }
                }
                const float gm = mGroupStart[v.group] + mGroupStep[v.group] * static_cast<float>(i);
                out[i] += smp * v.env * v.gain * master * amp * gm;
                v.pos += v.ratio * pit;
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

    // tremulants
    std::array<std::atomic<bool>, kMaxTrem> mTremOn{};
    std::array<std::atomic<float>, kMaxTrem> mTremPeriod{};
    std::array<std::atomic<float>, kMaxTrem> mTremDepth{};
    std::array<std::atomic<float>, kMaxTrem> mTremStart{};
    std::array<std::atomic<float>, kMaxTrem> mTremStop{};
    std::array<float, kMaxTrem> mTremPhase{};     // audio thread lang
    std::array<float, kMaxTrem> mTremLevel{};     // audio thread lang
    std::vector<float> mTremBuf;                  // [slot*2] = amp, [slot*2+1] = pitch
    uint32_t mTremActive = 0;

    // swell / expression groups
    std::array<std::atomic<float>, kMaxGroups> mGroupGain{};
    std::array<float, kMaxGroups> mGroupCur{};
    std::array<float, kMaxGroups> mGroupStart{};
    std::array<float, kMaxGroups> mGroupStep{};

    // reverb
    std::atomic<bool> mRevOn{false};
    std::atomic<float> mRevLevel{0.3f};
    std::atomic<float> mRevRoom{0.5f};
    std::atomic<float> mRevDamp{0.5f};
    std::atomic<float> mRevPre{20.0f};
    Comb mComb[8];
    Allpass mAp[4];
    std::vector<float> mPre;
    size_t mPreIdx = 0;
    float mRevMixCur = 0.0f;
    bool mRevIdle = true;

    // diagnostics (shown in Settings > audio info)
    std::atomic<float> mPeak{0.0f};
    std::atomic<int> mVoicePeak{0};
    std::atomic<int> mDropped{0};

    // impulse response
    Fft mFft;
    std::atomic<bool> mRateKnown{false};
    std::atomic<bool> mIrOn{false};
    std::atomic<float> mIrLevel{0.4f};
    std::atomic<int> mIrMaxMs{3000};
    std::mutex mIrRawLock, mIrBuildLock, mIrLock;
    std::vector<float> mIrRaw;                    // IR as read from the file (mono float)
    int mIrRate = 0;
    std::shared_ptr<IrData> mIrPending;           // handed to the audio thread through mIrLock / mIrDirty
    bool mIrDirty = false;
    std::shared_ptr<IrData> mIrActive;            // audio thread only
    float mIrMixCur = 0.0f;                       // audio thread only
    bool mIrIdle = true;                          // audio thread only
    std::string mIrInfoStr;                       // guarded by mErrLock

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
Java_com_example_organplayer_AudioEngine_nativePipeOn(JNIEnv*, jobject, jint key, jint velocity, jint mask,
                                                      jint tag, jint group, jfloat gain, jfloat cents, jfloat relMs) {
    if (gEngine) gEngine->pipeOn(key, velocity, (uint32_t) mask, tag, group, gain, cents, relMs);
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeSetGroupGain(JNIEnv*, jobject, jint group, jfloat gain) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    gEngine->setGroupGain(group, gain);
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeSetReverb(JNIEnv*, jobject, jboolean on, jfloat level,
                                                         jfloat room, jfloat damp, jfloat preMs) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    gEngine->setReverb(on != JNI_FALSE, level, room, damp, preMs);
}
JNIEXPORT jint JNICALL
Java_com_example_organplayer_AudioEngine_nativeLoadIr(JNIEnv*, jobject, jint fd, jint maxMs) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    return gEngine->loadIr(fd, maxMs);
}
JNIEXPORT jint JNICALL
Java_com_example_organplayer_AudioEngine_nativeRebuildIr(JNIEnv*, jobject, jint maxMs) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    return gEngine->rebuildIr(maxMs);
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeClearIr(JNIEnv*, jobject) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    gEngine->clearIr();
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeSetIr(JNIEnv*, jobject, jboolean on, jfloat level) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    gEngine->setIr(on != JNI_FALSE, level);
}
JNIEXPORT jstring JNICALL
Java_com_example_organplayer_AudioEngine_nativeIrInfo(JNIEnv* env, jobject) {
    std::string e = gEngine ? gEngine->irInfo() : std::string();
    return env->NewStringUTF(e.c_str());
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativePanic(JNIEnv*, jobject) {
    if (gEngine) gEngine->panic();
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeSetTremulant(JNIEnv*, jobject, jint slot, jboolean on,
                                                            jfloat periodMs, jfloat depthPct,
                                                            jfloat startMs, jfloat stopMs) {
    if (!gEngine) gEngine = std::make_shared<OrganEngine>();
    gEngine->setTremulant(slot, on != JNI_FALSE, periodMs, depthPct, startMs, stopMs);
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativeClearTremulants(JNIEnv*, jobject) {
    if (gEngine) gEngine->clearTremulants();
}
JNIEXPORT void JNICALL
Java_com_example_organplayer_AudioEngine_nativePipeOff(JNIEnv*, jobject, jint key, jint tag) {
    if (gEngine) gEngine->pipeOff(key, tag);
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
    std::string e = gEngine ? gEngine->lastError() : std::string("no engine");
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
    std::string e = gEngine ? gEngine->info() : std::string("No audio engine");
    return env->NewStringUTF(e.c_str());
}

}  // extern "C"
