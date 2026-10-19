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
    fun loadSample(key: Int, fd: Int): Boolean = nativeLoadSample(key, fd)
    fun unloadSample(key: Int) = nativeUnloadSample(key)
    fun pipeOn(key: Int, velocity: Int) = nativePipeOn(key, velocity)
    fun pipeOff(key: Int) = nativePipeOff(key)
    fun loadedBytes(): Long = nativeLoadedBytes()
    fun loadedCount(): Int = nativeLoadedCount()

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(note: Int, velocity: Int)
    private external fun nativeNoteOff(note: Int)
    private external fun nativeSetTranspose(semitones: Int)
    private external fun nativeLoadSample(key: Int, fd: Int): Boolean
    private external fun nativeUnloadSample(key: Int)
    private external fun nativePipeOn(key: Int, velocity: Int)
    private external fun nativePipeOff(key: Int)
    private external fun nativeLoadedBytes(): Long
    private external fun nativeLoadedCount(): Int
}
