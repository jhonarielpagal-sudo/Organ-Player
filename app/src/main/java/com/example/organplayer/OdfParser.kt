package com.example.organplayer

import java.util.TreeMap
import kotlin.math.pow

// ---------------------------------------------------------------------------
// Modelo ng organ
// ---------------------------------------------------------------------------

/** tuneCents = PitchTuning + PitchCorrection (organ+rank+pipe). gainLinear = AmplitudeLevel x Gain. */
data class PipeDef(
    val path: String?, val releases: List<String>,
    val tuneCents: Float = 0f, val gainLinear: Float = 1f
)

/** tremMask: bit n = apektado ng tremulant slot n (ayon sa windchest ng rank). */
class Rank(val id: Int, val name: String, val firstMidiNote: Int, val pipes: List<PipeDef>, val tremMask: Int = 0)

class StopRank(val rank: Rank, val firstPipeNumber: Int, val pipeCount: Int, val firstAccessibleKey: Int)

class Stop(val id: Int, val name: String, val ranks: List<StopRank>) {
    @Volatile var enabled = false        // tumutunog (hindi naka-mute)
    @Volatile var loaded = false         // samples are in memory (or loading)
    @Volatile var ready = false          // samples finished loading
    @Volatile var manualIndex = -1
    @Volatile var status = ""
    val key: String get() = "$manualIndex.$id"
    val pipeCount: Int get() = ranks.sumOf { it.pipeCount }
}

enum class CouplerKind { NORMAL, MELODY, BASS }

/** Coupler na nakasulat sa .organ. Ang may-ari (source) ay ang manual na naglilista nito. */
class Coupler(
    val id: Int, val name: String, val destManual: Int, val keyShift: Int,
    val unisonOff: Boolean, defaultEngaged: Boolean, val kind: CouplerKind = CouplerKind.NORMAL
) {
    @Volatile var engaged = defaultEngaged
}

/** slot = 0..31 (bit sa tremMask). Synth tremulant: amplitude + bahagyang pitch wobble. */
class Tremulant(
    val id: Int, val name: String, val slot: Int, val periodMs: Float, val depthPct: Float,
    val startRate: Int, val stopRate: Int, defaultOn: Boolean
) {
    @Volatile var on = defaultOn
}

class Manual(
    val index: Int, val name: String, val isPedal: Boolean,
    val numberOfKeys: Int, val firstMidiNote: Int,
    val stops: List<Stop>, val couplers: List<Coupler>
)

class Organ(val name: String, val manuals: List<Manual>, val tremulants: List<Tremulant> = emptyList())

// ---------------------------------------------------------------------------
// INI parser (case-insensitive sections at keys, gaya ng GrandOrgue)
// ---------------------------------------------------------------------------

class IniSection(private val map: Map<String, String>) {
    fun str(key: String): String? = map[key]?.takeIf { it.isNotEmpty() }
    fun int(key: String, def: Int): Int = map[key]?.trim()?.toIntOrNull() ?: def
    fun float(key: String, def: Float): Float = map[key]?.trim()?.replace(',', '.')?.toFloatOrNull() ?: def
    fun bool(key: String, def: Boolean): Boolean = when (map[key]?.trim()?.uppercase()) {
        "Y", "YES", "TRUE", "1" -> true
        "N", "NO", "FALSE", "0" -> false
        else -> def
    }
}

class IniFile(private val sections: Map<String, Map<String, String>>) {
    operator fun get(name: String): IniSection? = sections[name]?.let { IniSection(it) }
}

object IniParser {
    private val commentTail = Regex("\\s;.*$")

    fun parse(text: String): IniFile {
        val sections = TreeMap<String, MutableMap<String, String>>(String.CASE_INSENSITIVE_ORDER)
        var cur: MutableMap<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == ';' || line[0] == '#') continue
            if (line[0] == '[') {
                val end = line.indexOf(']')
                if (end > 0) {
                    cur = sections.getOrPut(line.substring(1, end).trim()) {
                        TreeMap(String.CASE_INSENSITIVE_ORDER)
                    }
                }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0 || cur == null) continue
            cur[line.substring(0, eq).trim()] = line.substring(eq + 1).replace(commentTail, "").trim()
        }
        return IniFile(sections)
    }
}

// ---------------------------------------------------------------------------
// ODF (.organ) parser -> Organ
// ---------------------------------------------------------------------------

object OdfParser {
    private class Level(val cents: Float, val gain: Float) {
        operator fun times(o: Level) = Level(cents + o.cents, gain * o.gain)
    }

    private fun n3(i: Int) = i.toString().padStart(3, '0')

    /** Pitch tuning (cents) at amplitude/gain na nakasulat sa isang section (prefix "" o "Pipe001"). */
    private fun level(s: IniSection, prefix: String): Level {
        val cents = s.float("${prefix}PitchTuning", 0f) + s.float("${prefix}PitchCorrection", 0f)
        val amp = s.float("${prefix}AmplitudeLevel", 100f) / 100f
        val db = s.float("${prefix}Gain", 0f)
        return Level(cents.coerceIn(-1200f, 1200f), (amp * 10f.pow(db / 20f)).coerceIn(0f, 4f))
    }

    fun parse(text: String, fallbackName: String): Organ {
        val ini = IniParser.parse(text)
        val org = ini["Organ"]
            ?: throw IllegalArgumentException("No [Organ] section - this is not a GrandOrgue .organ file")
        val organLevel = level(org, "")

        // 0) Mga Tremulant at kung aling windchest ang apektado
        val tremulants = ArrayList<Tremulant>()
        val nTrem = minOf(32, org.int("NumberOfTremulants", 0))
        for (i in 1..nTrem) {
            val s = ini["Tremulant${n3(i)}"] ?: continue
            val wave = s.str("Type")?.equals("Wave", ignoreCase = true) == true
            tremulants += Tremulant(
                i, s.str("Name") ?: "Tremulant $i", i - 1,
                if (wave) 250f else s.float("Period", 250f).coerceIn(40f, 2000f),
                if (wave) 10f else s.float("AmpModDepth", 10f).coerceIn(0f, 100f),
                s.int("StartRate", 44), s.int("StopRate", 44), s.bool("DefaultToEngaged", false)
            )
        }
        val chestMask = HashMap<Int, Int>()
        for (w in 1..org.int("NumberOfWindchestGroups", 0)) {
            val ws = ini["Windchest${n3(w)}"] ?: continue
            var mask = 0
            for (k in 1..ws.int("NumberOfTremulants", 0)) {
                val tid = ws.int("Tremulant${n3(k)}", 0)
                if (tid in 1..nTrem) mask = mask or (1 shl (tid - 1))
            }
            chestMask[w] = mask
        }
        val allMask = if (nTrem >= 32) -1 else (1 shl nTrem) - 1
        // Kung walang windchest na nag-lista ng tremulant, lahat ng rank ang apektado
        val anyRef = chestMask.values.any { it != 0 }
        val maskOf = { chest: Int -> if (!anyRef) allMask else (chestMask[chest] ?: 0) }

        // 1) Mga Rank
        val ranks = HashMap<Int, Rank>()
        for (i in 1..org.int("NumberOfRanks", 0)) {
            val s = ini["Rank${n3(i)}"] ?: continue
            ranks[i] = Rank(
                i, s.str("Name") ?: "Rank $i",
                s.int("FirstMidiNoteNumber", 36),
                parsePipes(s, s.int("NumberOfLogicalPipes", 0), organLevel * level(s, "")),
                maskOf(s.int("WindchestGroup", 1))
            )
        }

        // 2) Mga Manual (Manual000 = pedal kung HasPedals=Y)
        val hasPedals = org.bool("HasPedals", false)
        val manuals = ArrayList<Manual>()
        for (mi in 0..org.int("NumberOfManuals", 0)) {
            val s = ini["Manual${n3(mi)}"] ?: continue
            val stops = ArrayList<Stop>()
            for (si in 1..s.int("NumberOfStops", 0)) {
                val stopId = s.int("Stop${n3(si)}", 0)
                val ss = ini["Stop${n3(stopId)}"] ?: continue
                stops += parseStop(stopId, ss, ranks, organLevel, maskOf).also { it.manualIndex = mi }
            }
            val couplers = ArrayList<Coupler>()
            for (ci in 1..s.int("NumberOfCouplers", 0)) {
                val cid = s.int("Coupler${n3(ci)}", 0)
                val cs = ini["Coupler${n3(cid)}"] ?: continue
                val cname = cs.str("Name") ?: "Coupler $cid"
                val kind = when (cs.str("CouplerType")?.trim()?.lowercase()) {
                    "melody" -> CouplerKind.MELODY
                    "bass" -> CouplerKind.BASS
                    "normal" -> CouplerKind.NORMAL
                    else -> when {      // walang CouplerType: hulaan sa pangalan
                        Regex("\\bmelody\\b", RegexOption.IGNORE_CASE).containsMatchIn(cname) -> CouplerKind.MELODY
                        Regex("\\bbass\\b", RegexOption.IGNORE_CASE).containsMatchIn(cname) -> CouplerKind.BASS
                        else -> CouplerKind.NORMAL
                    }
                }
                couplers += Coupler(
                    cid, cname,
                    cs.int("DestinationManual", mi), cs.int("DestinationKeyshift", 0),
                    cs.bool("UnisonOff", false), cs.bool("DefaultToEngaged", false), kind
                )
            }
            manuals += Manual(
                mi, s.str("Name") ?: "Manual $mi", mi == 0 && hasPedals,
                s.int("NumberOfLogicalKeys", 61),
                s.int("FirstAccessibleKeyMIDINoteNumber", 36),
                stops, couplers
            )
        }
        return Organ(org.str("ChurchName") ?: fallbackName, manuals, tremulants)
    }

    private fun parseStop(
        id: Int, s: IniSection, ranks: Map<Int, Rank>, organLevel: Level, maskOf: (Int) -> Int
    ): Stop {
        val name = s.str("Name") ?: "Stop $id"
        val n = s.int("NumberOfRanks", 0)
        val refs = ArrayList<StopRank>()
        if (n == 0) {
            val count = s.int("NumberOfLogicalPipes", 0)
            val rank = Rank(-id, name, s.int("FirstMidiNoteNumber", 36),
                parsePipes(s, count, organLevel * level(s, "")), maskOf(s.int("WindchestGroup", 1)))
            refs += StopRank(
                rank,
                s.int("FirstAccessiblePipeLogicalPipeNumber", 1),
                s.int("NumberOfAccessiblePipes", count),
                s.int("FirstAccessiblePipeLogicalKeyNumber", 1)
            )
        } else {
            for (r in 1..n) {
                val p = "Rank${n3(r)}"
                val rank = ranks[s.int(p, 0)] ?: continue
                val first = s.int("${p}FirstPipeNumber", 1)
                refs += StopRank(
                    rank, first,
                    s.int("${p}PipeCount", rank.pipes.size - first + 1),
                    s.int("${p}FirstAccessibleKeyNumber", 1)
                )
            }
        }
        return Stop(id, name, refs)
    }

    private fun parsePipes(s: IniSection, count: Int, base: Level): List<PipeDef> {
        val list = ArrayList<PipeDef>(count)
        for (i in 1..count) {
            val p = "Pipe${n3(i)}"
            val lv = base * level(s, p)
            val releases = ArrayList<String>()
            var r = 1
            while (true) {
                releases += s.str("${p}Release${n3(r)}") ?: break
                r++
            }
            list += PipeDef(s.str(p), releases, lv.cents, lv.gain)
        }
        return list
    }
}
