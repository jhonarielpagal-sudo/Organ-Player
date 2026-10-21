package com.example.organplayer

/** UI language. English is the default; Filipino is optional (Settings). */
object Lang {
    @Volatile var fil = false
}

fun tr(en: String, fil: String): String = if (Lang.fil) fil else en
