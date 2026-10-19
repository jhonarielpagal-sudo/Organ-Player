package com.example.organplayer

import java.util.TreeMap

// ---------------------------------------------------------------------------
// Modelo ng organ (stage 1: structure lang, wala pang sample na naka-load)
// ---------------------------------------------------------------------------

data class PipeDef(val path: String?, val releases: List<String>)

class Rank(val id: Int, val name: String, val firstMidiNote: Int, val pipes: List<PipeDef>)

/** Reference ng isang Stop sa (bahagi ng) isang Rank. */
class StopRank(val rank: Rank, val firstPipeNumber: Int, val pipeCount: Int, val firstAccessibleKey: Int)

class Stop(val id: Int, val name: String, val ranks: List<StopRank>) {
    /** Naka-off by default. Sa stage 2, sample ng enabled stops lang ang ilo-load. */
    @Volatile var enabled = false
    val pipeCount: Int get() = ranks.sumOf { it.pipeCount }
}

class Manual(
    val index: Int, val name: String, val isPedal: Boolean,
    val numberOfKeys: Int, val firstMidiNote: Int, val stops: List<Stop>
)

class Organ(val name: String, val manuals: List<Manual>)

// ---------------------------------------------------------------------------
// INI parser (case-insensitive sections at keys, gaya ng GrandOrgue)
// ---------------------------------------------------------------------------

class IniSection(private val map: Map<String, String>) {
    fun str(key: String): String? = map[key]?.takeIf { it.isNotEmpty() }
    fun int(key: String, def: Int): Int = map[key]?.trim()?.toIntOrNull() ?: def
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
    private fun n3(i: Int) = i.toString().padStart(3, '0')

    fun parse(text: String, fallbackName: String): Organ {
        val ini = IniParser.parse(text)
        val org = ini["Organ"]
            ?: throw IllegalArgumentException("Walang [Organ] section - hindi ito GrandOrgue .organ file")

        // 1) Mga Rank
        val ranks = HashMap<Int, Rank>()
        for (i in 1..org.int("NumberOfRanks", 0)) {
            val s = ini["Rank${n3(i)}"] ?: continue
            ranks[i] = Rank(
                i, s.str("Name") ?: "Rank $i",
                s.int("FirstMidiNoteNumber", 36),
                parsePipes(s, s.int("NumberOfLogicalPipes", 0))
            )
        }

        // 2) Mga Manual (Manual000 = pedal kung HasPedals=Y; tolerant sa dalawang numbering)
        val hasPedals = org.bool("HasPedals", false)
        val manuals = ArrayList<Manual>()
        for (mi in 0..org.int("NumberOfManuals", 0)) {
            val s = ini["Manual${n3(mi)}"] ?: continue
            val stops = ArrayList<Stop>()
            for (si in 1..s.int("NumberOfStops", 0)) {
                val stopId = s.int("Stop${n3(si)}", 0)
                val ss = ini["Stop${n3(stopId)}"] ?: continue
                stops += parseStop(stopId, ss, ranks)
            }
            manuals += Manual(
                mi, s.str("Name") ?: "Manual $mi", mi == 0 && hasPedals,
                s.int("NumberOfLogicalKeys", 61),
                s.int("FirstAccessibleKeyMIDINoteNumber", 36),
                stops
            )
        }
        val name = org.str("ChurchName") ?: fallbackName
        return Organ(name, manuals)
    }

    private fun parseStop(id: Int, s: IniSection, ranks: Map<Int, Rank>): Stop {
        val name = s.str("Name") ?: "Stop $id"
        val n = s.int("NumberOfRanks", 0)
        val refs = ArrayList<StopRank>()
        if (n == 0) {
            // "inline" rank: ang mga Pipe ay nasa mismong [StopNNN] section
            val count = s.int("NumberOfLogicalPipes", 0)
            val rank = Rank(-id, name, s.int("FirstMidiNoteNumber", 36), parsePipes(s, count))
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

    private fun parsePipes(s: IniSection, count: Int): List<PipeDef> {
        val list = ArrayList<PipeDef>(count)
        for (i in 1..count) {
            val p = "Pipe${n3(i)}"
            val releases = ArrayList<String>()
            var r = 1
            while (true) {
                releases += s.str("${p}Release${n3(r)}") ?: break
                r++
            }
            list += PipeDef(s.str(p), releases)   // path ay maaaring "REF:..." - aayusin sa stage 2
        }
        return list
    }
}
