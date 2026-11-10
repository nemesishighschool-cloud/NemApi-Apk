package com.nemap.android.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal technique — JAMAIS de contenu de conversation, cookie, jeton ou
 * en-tête d'authentification (règle du projet, cf. plan §9.5).
 */
object NemLog {

    data class Entry(val at: String, val level: String, val category: String, val message: String)

    private const val MAX = 400
    private val buffer = ArrayDeque<Entry>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(cat: String, msg: String) = add("info", cat, msg)
    fun w(cat: String, msg: String) = add("warn", cat, msg)
    fun e(cat: String, msg: String) = add("error", cat, msg)

    private fun add(level: String, cat: String, msg: String) {
        val line = "[${cat.uppercase()}] $msg"
        when (level) {
            "error" -> Log.e("NemApi", line)
            "warn" -> Log.w("NemApi", line)
            else -> Log.i("NemApi", line)
        }
        synchronized(buffer) {
            buffer.addLast(Entry(fmt.format(Date()), level, cat, scrub(msg)))
            while (buffer.size > MAX) buffer.removeFirst()
        }
    }

    /** Dernière barrière : les secrets ne doivent pas sortir même par accident. */
    private fun scrub(msg: String): String = msg
        .replace(Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE), "$1***")
        .replace(Regex("(api[_-]?key=)[^&\\s]+", RegexOption.IGNORE_CASE), "$1***")

    fun tail(n: Int = 120): List<Entry> = synchronized(buffer) { buffer.takeLast(n) }

    fun asJson(): String {
        val sb = StringBuilder("[")
        tail().forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"at\":\"").append(e.at)
                .append("\",\"level\":\"").append(e.level)
                .append("\",\"category\":\"").append(e.category)
                .append("\",\"message\":").append(org.json.JSONObject.quote(e.message))
                .append('}')
        }
        return sb.append(']').toString()
    }
}
