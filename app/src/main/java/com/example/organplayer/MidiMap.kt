package com.example.organplayer

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * MIDI mapping (MIDI learn) bawat organ. Action keys:
 *  stop:<manual>.<id>  cp:<couplerId>  trem:<id>  gc  panic  tp+  tp-  tp0  vol  preset:<n>
 */
class MidiMap(private val prefs: SharedPreferences) {
    class Binding(val type: Int, val channel: Int, val number: Int) {
        fun same(o: Binding) = type == o.type && channel == o.channel && number == o.number
        fun label(): String =
            (when (type) { NOTE -> "Note $number"; CC -> "CC $number"; else -> "PC $number" }) + " ch${channel + 1}"
    }

    private val map = LinkedHashMap<String, Binding>()
    private val last = HashMap<String, Int>()
    private var organId = ""
    @Volatile var learning: String? = null
    var onLearned: (() -> Unit)? = null

    @Synchronized
    fun load(id: String) {
        organId = id
        map.clear(); last.clear()
        val txt = prefs.getString("midimap_$id", null) ?: return
        try {
            val j = JSONObject(txt)
            for (k in j.keys()) {
                val a = j.getJSONArray(k)
                map[k] = Binding(a.getInt(0), a.getInt(1), a.getInt(2))
            }
        } catch (_: Exception) { }
    }

    private fun save() {
        val j = JSONObject()
        for ((k, b) in map) j.put(k, JSONArray(listOf(b.type, b.channel, b.number)))
        prefs.edit().putString("midimap_$organId", j.toString()).apply()
    }

    @Synchronized fun get(action: String): Binding? = map[action]

    @Synchronized
    fun bind(action: String, b: Binding) {
        map.values.removeAll { it.same(b) }          // isang control = isang action lang
        map[action] = b
        save()
    }

    @Synchronized fun clear(action: String) { if (map.remove(action) != null) save() }
    @Synchronized fun clearAll() { map.clear(); save() }

    @Synchronized
    fun find(type: Int, ch: Int, num: Int): List<String> =
        map.filter { it.value.type == type && it.value.channel == ch && it.value.number == num }.keys.toList()

    /** Para sa CC na ginagamit bilang button: isang trigger lang kapag tumawid sa >= 64. */
    @Synchronized
    fun rising(action: String, value: Int): Boolean {
        val prev = last[action] ?: 0
        last[action] = value
        return value >= 64 && prev < 64
    }

    companion object {
        const val NOTE = 0
        const val CC = 1
        const val PC = 2
    }
}
