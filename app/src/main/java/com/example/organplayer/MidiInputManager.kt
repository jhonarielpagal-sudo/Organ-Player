package com.example.organplayer

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Nagde-detect ng USB MIDI devices (plug-and-play) at nagpapasa ng Note On/Off sa Listener.
 * Tandaan: sa MIDI API, ang "output port" ng device ang nagbibigay ng data sa app.
 */
class MidiInputManager(context: Context, private val listener: Listener) {

    interface Listener {
        // Tinatawag sa MIDI thread (hindi UI thread) para mababa ang latency.
        fun onNoteOn(channel: Int, note: Int, velocity: Int, lagMs: Double)
        fun onNoteOff(channel: Int, note: Int, velocity: Int, lagMs: Double)
        fun onControlChange(channel: Int, controller: Int, value: Int)
        fun onProgramChange(channel: Int, program: Int)
        // Tinatawag sa main thread.
        fun onStatus(message: String)
    }

    private class OpenDevice(val device: MidiDevice, val port: MidiOutputPort, val receiver: MidiReceiver)

    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val handler = Handler(Looper.getMainLooper())
    private val open = HashMap<Int, OpenDevice>()
    private val pending = HashSet<Int>()

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = openDevice(info)
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            closeDevice(info.id)
            listener.onStatus("Natanggal: ${nameOf(info)}")
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        midiManager.registerDeviceCallback(deviceCallback, handler)
        midiManager.devices.forEach { openDevice(it) }   // mga nakasaksak na bago mag-launch
        if (midiManager.devices.isEmpty()) listener.onStatus("Walang MIDI device. Isaksak ang keyboard (OTG).")
    }

    fun stop() {
        midiManager.unregisterDeviceCallback(deviceCallback)
        open.keys.toList().forEach { closeDevice(it) }
    }

    private fun nameOf(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device #${info.id}"

    private fun openDevice(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 || open.containsKey(info.id) || !pending.add(info.id)) return
        val name = nameOf(info)

        midiManager.openDevice(info, MidiManager.OnDeviceOpenedListener { device ->
            pending.remove(info.id)
            if (device == null) {
                listener.onStatus("Hindi mabuksan: $name")
            } else {
                try {
                    val port = device.openOutputPort(0)
                    if (port == null) {
                        device.close()
                        listener.onStatus("Walang available na port: $name")
                    } else {
                        val receiver = NoteReceiver()
                        port.connect(receiver)
                        open[info.id] = OpenDevice(device, port, receiver)
                        listener.onStatus("Nakakonekta: $name")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "openOutputPort failed", e)
                    device.close()
                    listener.onStatus("Error sa $name: ${e.message}")
                }
            }
        }, handler)
    }

    private fun closeDevice(id: Int) {
        open.remove(id)?.let {
            try {
                it.port.disconnect(it.receiver)
                it.port.close()
                it.device.close()
            } catch (e: Exception) {
                Log.w(TAG, "close failed", e)
            }
        }
    }

    private inner class NoteReceiver : MidiReceiver() {
        private var runningStatus = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            // timestamp ay nasa System.nanoTime() base; 0 = walang timestamp
            val lagMs = if (timestamp != 0L) (System.nanoTime() - timestamp) / 1_000_000.0 else -1.0
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> { i++; continue }                    // realtime (clock, etc.)
                    b >= 0xF0 -> { runningStatus = 0; i++; continue } // sysex/system common
                    b >= 0x80 -> { runningStatus = b; i++ }           // bagong status byte
                }
                if (runningStatus == 0) { i++; continue }

                val type = runningStatus and 0xF0
                val ch = runningStatus and 0x0F
                val need = if (type == 0xC0 || type == 0xD0) 1 else 2
                if (i + need > end) break
                val d1 = msg[i].toInt() and 0x7F
                val d2 = if (need == 2) msg[i + 1].toInt() and 0x7F else 0
                i += need

                when (type) {
                    0x90 -> if (d2 > 0) listener.onNoteOn(ch, d1, d2, lagMs)
                            else listener.onNoteOff(ch, d1, 0, lagMs)   // Note On vel 0 = Note Off
                    0x80 -> listener.onNoteOff(ch, d1, d2, lagMs)
                    0xB0 -> listener.onControlChange(ch, d1, d2)
                    0xC0 -> listener.onProgramChange(ch, d1)
                }
            }
        }
    }

    companion object { private const val TAG = "OrganMIDI" }
}
