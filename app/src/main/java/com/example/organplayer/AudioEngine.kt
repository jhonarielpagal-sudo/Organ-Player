package com.example.organplayer

/** Kotlin wrapper para sa C++ Oboe engine (liborgan.so). */
object AudioEngine {
    init { System.loadLibrary("organ") }

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()

    // Wavetable test voice (Test C4 / walang organ na naka-load)
    fun noteOn(note: Int, velocity: Int) = nativeNoteOn(note, velocity)
    fun noteOff(note: Int) = nativeNoteOff(note)
    fun setTranspose(semitones: Int) = nativeSetTranspose(semitones)

    // Samples. Ang native ang magsasara ng fd (galing sa ParcelFileDescriptor.detachFd()).
    // Return: 0 = ok, 1 = hindi mabasa, 2 = hindi suportadong WAV, 3 = sobra sa memory cap
    fun loadSample(key: Int, fd: Int, tuneCents: Float, gain: Float): Int = nativeLoadSample(key, fd, tuneCents, gain)
    fun unloadSample(key: Int) = nativeUnloadSample(key)
    fun pipeOn(key: Int, velocity: Int, tremMask: Int, tag: Int, group: Int, gain: Float, cents: Float, relMs: Float) =
        nativePipeOn(key, velocity, tremMask, tag, group, gain, cents, relMs)
    fun pipeOff(key: Int, tag: Int) = nativePipeOff(key, tag)

    /** Swell / expression: gain (0..1+) ng isang group (= manual index, 0..15) */
    fun setGroupGain(group: Int, gain: Float) = nativeSetGroupGain(group, gain)

    /** Reverb: level/room/damp 0..1, preMs 0..250 */
    fun setReverb(on: Boolean, level: Float, room: Float, damp: Float, preMs: Float) =
        nativeSetReverb(on, level, room, damp, preMs)
    fun panic() = nativePanic()

    // Tremulant slots (0..31): amplitude + bahagyang pitch wobble
    fun setTremulant(slot: Int, on: Boolean, periodMs: Float, depthPct: Float, startMs: Float, stopMs: Float) =
        nativeSetTremulant(slot, on, periodMs, depthPct, startMs, stopMs)
    fun clearTremulants() = nativeClearTremulants()

    fun loadedBytes(): Long = nativeLoadedBytes()
    fun loadedCount(): Int = nativeLoadedCount()
    fun lastError(): String = nativeLastError()

    // General settings
    fun setParams(master: Float, attackMs: Float, releaseMs: Float, velSens: Float,
                  globalCents: Float, maxVoices: Int, memCapMb: Int) =
        nativeSetParams(master, attackMs, releaseMs, velSens, globalCents, maxVoices, memCapMb)
    fun setBufferBursts(n: Int): Float = nativeSetBufferBursts(n)
    fun info(): String = nativeInfo()

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(note: Int, velocity: Int)
    private external fun nativeNoteOff(note: Int)
    private external fun nativeSetTranspose(semitones: Int)
    private external fun nativeLoadSample(key: Int, fd: Int, tuneCents: Float, gain: Float): Int
    private external fun nativeUnloadSample(key: Int)
    private external fun nativePipeOn(key: Int, velocity: Int, tremMask: Int, tag: Int, group: Int,
                                      gain: Float, cents: Float, relMs: Float)
    private external fun nativeSetGroupGain(group: Int, gain: Float)
    private external fun nativeSetReverb(on: Boolean, level: Float, room: Float, damp: Float, preMs: Float)
    private external fun nativePanic()
    private external fun nativeSetTremulant(slot: Int, on: Boolean, periodMs: Float, depthPct: Float, startMs: Float, stopMs: Float)
    private external fun nativeClearTremulants()
    private external fun nativePipeOff(key: Int, tag: Int)
    private external fun nativeLoadedBytes(): Long
    private external fun nativeLoadedCount(): Int
    private external fun nativeLastError(): String
    private external fun nativeSetParams(master: Float, attackMs: Float, releaseMs: Float, velSens: Float,
                                         globalCents: Float, maxVoices: Int, memCapMb: Int)
    private external fun nativeSetBufferBursts(n: Int): Float
    private external fun nativeInfo(): String
}
