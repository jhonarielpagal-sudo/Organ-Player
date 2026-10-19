package com.example.organplayer

/** Kotlin wrapper para sa C++ Oboe engine (liborgan.so). */
object AudioEngine {
    init { System.loadLibrary("organ") }

    fun start(): Boolean = nativeStart()
    fun stop() = nativeStop()
    fun noteOn(note: Int, velocity: Int) = nativeNoteOn(note, velocity)
    fun noteOff(note: Int) = nativeNoteOff(note)
    /** Hook para sa Transpose feature (semitones, hal. -12..+12). */
    fun setTranspose(semitones: Int) = nativeSetTranspose(semitones)

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(note: Int, velocity: Int)
    private external fun nativeNoteOff(note: Int)
    private external fun nativeSetTranspose(semitones: Int)
}
