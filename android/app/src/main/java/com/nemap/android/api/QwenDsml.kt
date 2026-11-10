package com.nemap.android.api

import android.content.Context
import com.nemap.android.core.NemLog
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID


/** Converts DeepSeek's DSML response dialect to OpenAI tool_calls, for Qwen Code requests only. */
internal object QwenDsml {
    const val CORRECTION = "The preceding DSML format is invalid for tool calls. For all subsequent tool calls, follow the tool-call format defined in the system instructions; do not use DSML."
    private const val PREFS = "nemapi_qwen_dsml"
    private const val PENDING = "pending_calls"
    private val bar = "[｜|│┃]{1,2}"
    private val invokeRx = Regex(
        "<\\s*$bar\\s*DSML\\s*$bar\\s*invoke\\s+name\\s*=\\s*[\\\"'“”]?([^\\\"'“”>\\s]+)[\\\"'“”]?[^>]*>([\\s\\S]*?)(?=<\\s*/?\\s*$bar\\s*DSML\\s*\\s*$bar\\s*(?:invoke|tool_calls|function_calls|calls|tool_call)\\b|\\z)",
        RegexOption.IGNORE_CASE
    )
    private val parameterRx = Regex(
        "<\\s*$bar\\s*DSML\\s*$bar\\s*parameter\\s+name\\s*=\\s*[\\\"']?([^\\\"'\\s>]+)[\\\"']?([^>]*)>([\\s\\S]*?)<\\s*/\\s*$bar\\s*DSML\\s*$bar\\s*parameter\\s*>",
        RegexOption.IGNORE_CASE
    )
    private val tagRx = Regex("<\\s*/?\\s*$bar\\s*DSML\\s*[^>]*>", RegexOption.IGNORE_CASE)

    data class Parsed(val content: String, val toolCalls: JSONArray)
    private data class ToolSpec(val name: String, val parameters: JSONObject)

    fun systemPrompt(tools: JSONArray): String {
        val lines = mutableListOf<String>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val f = t.optJSONObject("function") ?: t
            val name = f.optString("name").trim()
            if (name.isNotEmpty()) lines += "- $name: ${f.optString("description")}\n  parameters: ${(f.opt("parameters") ?: JSONObject()).toString()}"
        }
        if (lines.isEmpty()) return ""
        return "Use the following tools when appropriate. Return tool calls using only the OpenAI tool_calls format described in the system instructions. Never write DSML markup as text.\n" + lines.joinToString("\n") + "\nIf no tool is needed, answer normally."
    }

    fun parse(text: String, tools: JSONArray?): Parsed? {
        if (text.isBlank() || tools == null || tools.length() == 0) return null
        if (!text.contains("DSML", ignoreCase = true)) return null
        var source = text
        if (Regex("&(?:amp;)?lt;[^\\n]{0,160}DSML", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
            repeat(2) {
                source = source.replace("&amp;lt;", "<", ignoreCase = true)
                    .replace("&amp;gt;", ">", ignoreCase = true)
                    .replace("&lt;", "<", ignoreCase = true)
                    .replace("&gt;", ">", ignoreCase = true)
                    .replace("&quot;", "\"", ignoreCase = true)
                    .replace("&#39;", "'", ignoreCase = true)
            }
        }
        val specs = linkedMapOf<String, ToolSpec>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: t
            val name = fn.optString("name").trim()
            if (name.isNotEmpty()) specs[normalize(name)] = ToolSpec(name, fn.optJSONObject("parameters") ?: JSONObject())
        }
        if (specs.isEmpty()) return null
        val matches = invokeRx.findAll(source).toList()
        if (matches.isEmpty()) return null
        val resultCalls = JSONArray()
        val ranges = mutableListOf<IntRange>()
        for (m in matches) {
            val rawName = m.groupValues[1].trim().trim('"', '\'', '`')
            val spec = canonical(rawName, specs) ?: return null
            val body = m.groupValues[2]
            val args = JSONObject()
            val params = parameterRx.findAll(body).toList()
            if (params.isNotEmpty()) {
                for (p in params) {
                    val key = p.groupValues[1]
                    val attrs = p.groupValues[2]
                    val raw = p.groupValues[3]
                    val value = paramValue(raw, attrs)
                    args.put(key, coerce(value, spec.parameters.optJSONObject("properties")?.optJSONObject(key)))
                }
            } else {
                val raw = tagRx.replace(body, "").trim()
                if (raw.isNotEmpty()) {
                    val parsed = parseJsonValue(raw)
                    if (parsed is JSONObject) {
                        val props = spec.parameters.optJSONObject("properties")
                        val keys = parsed.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            args.put(key, coerce(parsed.opt(key), props?.optJSONObject(key)))
                        }
                    } else args.put("raw", raw)
                }
            }
            resultCalls.put(JSONObject()
                .put("id", "call_" + UUID.randomUUID().toString().replace("-", "").take(12))
                .put("type", "function")
                .put("function", JSONObject().put("name", spec.name).put("arguments", args.toString())))
            ranges += m.range
        }
        val out = StringBuilder()
        var at = 0
        for (r in ranges) {
            if (r.first < at) continue
            out.append(source.substring(at, r.first))
            at = r.last + 1
        }
        out.append(source.substring(at))
        val cleaned = tagRx.replace(out.toString(), "")
            .replace(Regex("(?m)^\\s*```(?:tool|tool_call)?\\s*$"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        NemLog.i("api", "Qwen: ${resultCalls.length()} appel(s) DSML converti(s) en tool_calls")
        return Parsed(cleaned, resultCalls)
    }

    @Synchronized
    fun remember(ctx: Context, calls: JSONArray) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = try { JSONArray(prefs.getString(PENDING, "[]") ?: "[]") } catch (_: Throwable) { JSONArray() }
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val fn = call.optJSONObject("function") ?: continue
            pending.put(JSONObject().put("id", call.optString("id")).put("name", fn.optString("name")))
        }
        prefs.edit().putString(PENDING, pending.toString()).apply()
    }

    /** Appends the corrective reminder to the matching tool result before it is sent back to DeepSeek. */
    @Synchronized
    fun annotateToolResults(ctx: Context, messages: JSONArray): Boolean {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = try { JSONArray(prefs.getString(PENDING, "[]") ?: "[]") } catch (_: Throwable) { JSONArray() }
        if (stored.length() == 0) return false
        val remaining = mutableListOf<JSONObject>()
        for (i in 0 until stored.length()) stored.optJSONObject(i)?.let { remaining += it }
        var changed = false
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role").lowercase()
            if (role != "tool" && role != "function") continue
            val callId = m.optString("tool_call_id")
            val toolName = m.optString("name")
            val idx = remaining.indexOfFirst { pending ->
                (callId.isNotBlank() && pending.optString("id") == callId) ||
                    (toolName.isNotBlank() && pending.optString("name").equals(toolName, true))
            }
            if (idx < 0) continue
            val old = m.optString("content", "")
            m.put("content", if (old.isBlank()) CORRECTION else "$old\n\n$CORRECTION")
            remaining.removeAt(idx)
            changed = true
        }
        val updated = JSONArray()
        remaining.forEach { updated.put(it) }
        prefs.edit().putString(PENDING, updated.toString()).apply()
        if (changed) NemLog.i("api", "Qwen: rappel DSML ajouté au résultat d'outil")
        return changed
    }

    private fun canonical(name: String, specs: Map<String, ToolSpec>): ToolSpec? {
        val direct = specs[normalize(name)]
        if (direct != null) return direct
        val tail = name.substringAfterLast('.').substringAfterLast(':')
        return specs[normalize(tail)]
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun paramValue(raw: String, attrs: String): Any? {
        var value = if (raw.contains('\n')) raw.trim('\r', '\n') else raw.trim()
        repeat(2) {
            value = value.replace("&amp;", "&", ignoreCase = true)
                .replace("&lt;", "<", ignoreCase = true).replace("&gt;", ">", ignoreCase = true)
                .replace("&quot;", "\"", ignoreCase = true).replace("&#39;", "'", ignoreCase = true)
        }
        val isString = Regex("string\\s*=\\s*['\\\"]?false", RegexOption.IGNORE_CASE).containsMatchIn(attrs)
        if (isString && value.isNotBlank()) return parseJsonValue(value) ?: value
        return value
    }

    private fun parseJsonValue(raw: String): Any? = try {
        JSONTokener(raw.trim()).nextValue()
    } catch (_: Throwable) { null }

    private fun coerce(value: Any?, schema: JSONObject?): Any? {
        if (schema == null || value !is String) return value
        val s = schema.optString("type").lowercase()
        val text = value.trim()
        return try {
            when (s) {
                "integer" -> text.toLong()
                "number" -> text.toDouble()
                "boolean" -> when (text.lowercase()) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> value }
                "object", "array" -> {
                    val parsed = parseJsonValue(text)
                    if ((s == "object" && parsed is JSONObject) || (s == "array" && parsed is JSONArray)) parsed else value
                }
                else -> value
            }
        } catch (_: Throwable) { value }
    }
}
