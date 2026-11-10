package com.nemap.android.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Contexte incrémental Qwen Code côté APK.
 *
 * Qwen Code renvoie l'historique entier à chaque appel. La première requête
 * initialise la discussion DeepSeek avec le contexte complet ; les appels
 * suivants comparent les empreintes stables et ne tapent que les nouveaux
 * messages, les nouvelles lignes système et les nouvelles définitions d'outil.
 * On persiste exclusivement des empreintes et des compteurs, jamais le contenu
 * des conversations.
 */
object QwenContext {
    private const val PREF = "nemapi_qwen_incremental"
    private const val KEY_SESSION = "session"
    private const val KEY_COUNTERS = "counters"
    private const val TOOL_REMINDER = "Reminder: use the available tools only when needed; otherwise answer normally."
    private const val NUDGE = "Please answer my last message above."

    data class Plan(
        val prompt: String,
        val mode: String,
        val sessionKey: String,
        val incoming: List<String>,
        val systemKeys: List<String>,
        val systemLines: Map<String, String>,
        val toolKeys: List<String>,
        val charsFull: Int,
        val charsSent: Int,
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Stable identity for DeepSeek's SPA chat URL; "draft" until it creates a chat id. */
    fun conversationKey(url: String): String {
        val path = try { java.net.URI(url).path.orEmpty() } catch (_: Throwable) { url }
        val m = Regex("/(?:a/)?chat/s/([^/?#]+)").find(path)
        return m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "draft"
    }

    private fun hash(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(v.opt(it))
        }
        is JSONArray -> (0 until v.length()).joinToString(prefix = "[", postfix = "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }

    private fun textOf(v: Any?): String = when (v) {
        is String -> v
        is JSONArray -> buildString {
            for (i in 0 until v.length()) {
                val p = v.optJSONObject(i) ?: continue
                when (p.optString("type")) {
                    "text", "input_text" -> append(p.optString("text"))
                    "image_url", "input_image" -> append("[image attachment]")
                    "file", "input_file" -> {
                        val f = p.optJSONObject("file")
                        append("[file attachment: ").append(f?.optString("filename") ?: p.optString("filename", "document")).append(']')
                    }
                }
            }
        }
        else -> ""
    }

    private fun role(m: JSONObject): String = m.optString("role", "user").lowercase(Locale.US).let {
        if (it == "developer") "system" else it
    }

    private fun callFingerprint(m: JSONObject): String {
        val calls = m.optJSONArray("tool_calls")
        val fc = m.optJSONObject("function_call")
        if ((calls != null && calls.length() > 0) || fc != null) {
            val list = mutableListOf<String>()
            if (calls != null) for (i in 0 until calls.length()) {
                val f = calls.optJSONObject(i)?.optJSONObject("function") ?: continue
                list += f.optString("name") + ":" + canonical(try { JSONObject(f.optString("arguments", "{}")) } catch (_: Throwable) { f.optString("arguments", "") })
            }
            if (fc != null) list += fc.optString("name") + ":" + canonical(fc.opt("arguments"))
            return hash("assistant-tool|" + list.joinToString("|"))
        }
        val extra = m.optJSONArray("content")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("type") !in listOf("text", "input_text") }
                .joinToString("|") { it.optString("type") + ":" + (it.optJSONObject("file")?.optString("filename") ?: "") }
        } ?: ""
        val toolRef = m.optString("tool_call_id", m.optString("name", ""))
        return hash("${role(m)}|${textOf(m.opt("content"))}|$extra|$toolRef")
    }

    private fun systemLines(messages: JSONArray): LinkedHashMap<String, String> {
        val out = linkedMapOf<String, String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (role(m) !in listOf("system", "developer")) break
            for (line in textOf(m.opt("content")).lines()) {
                val normalized = line.trim().replace(Regex("\\s+"), " ")
                if (normalized.isNotEmpty()) out.putIfAbsent(hash("sys|$normalized"), line.trimEnd())
            }
        }
        return out
    }

    private fun toolMap(tools: JSONArray?): LinkedHashMap<String, JSONObject> {
        val out = linkedMapOf<String, JSONObject>()
        if (tools == null) return out
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val f = t.optJSONObject("function") ?: t
            val name = f.optString("name")
            if (name.isBlank()) continue
            val c = JSONObject().put("name", name).put("description", f.optString("description"))
                .put("parameters", f.opt("parameters") ?: JSONObject())
            out[hash("tool|${canonical(c)}")] = c
        }
        return out
    }

    private fun messagesPrompt(messages: JSONArray, positions: List<Int>, tools: JSONArray?, first: Boolean,
                               newSystem: List<String>, newTools: List<JSONObject>): String {
        val parts = mutableListOf<String>()
        val sys = systemLines(messages)
        if (first) {
            val toolText = serializeTools(tools)
            if (toolText.isNotEmpty()) parts += toolText
            if (sys.isNotEmpty()) parts += sys.values.joinToString("\n")
        } else {
            if (newSystem.isNotEmpty()) parts += "System instructions update:\n" + newSystem.joinToString("\n")
            if (newTools.isNotEmpty()) parts += "Updated available tools (use these definitions for this task):\n" + serializeToolObjects(newTools)
        }

        val callNames = mutableMapOf<String, String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (role(m) == "assistant") {
                val calls = m.optJSONArray("tool_calls") ?: continue
                for (j in 0 until calls.length()) {
                    val c = calls.optJSONObject(j) ?: continue
                    val f = c.optJSONObject("function") ?: continue
                    c.optString("id").takeIf { it.isNotEmpty() }?.let { callNames[it] = f.optString("name", "tool") }
                }
            }
        }
        val conversation = mutableListOf<String>()
        for (pos in positions) {
            val m = messages.optJSONObject(pos) ?: continue
            val r = role(m)
            val text = textOf(m.opt("content")).trim()
            when (r) {
                "system" -> if (!first && text.isNotEmpty()) conversation += "System: $text"
                "user" -> if (text.isNotEmpty()) conversation += if (positions.size == 1 && newSystem.isEmpty() && newTools.isEmpty()) text else "User: $text"
                "tool", "function" -> conversation += "Tool result (${callNames[m.optString("tool_call_id")] ?: m.optString("name", "tool")}): ${text.ifEmpty { "(no output)" }}"
                "assistant" -> {
                    if (text.isNotEmpty()) conversation += "Assistant: $text"
                    val calls = m.optJSONArray("tool_calls")
                    if (calls != null) for (j in 0 until calls.length()) {
                        val f = calls.optJSONObject(j)?.optJSONObject("function") ?: continue
                        conversation += "Assistant tool call: ${f.optString("name")} ${f.optString("arguments", "{}")}"
                    }
                }
                else -> if (text.isNotEmpty()) conversation += text
            }
        }
        if (conversation.isNotEmpty()) parts += conversation.joinToString("\n\n")
        if (!first && positions.any { pos -> role(messages.optJSONObject(pos) ?: JSONObject()) in listOf("tool", "function") }) {
            parts += "Continue using the tool results above. Do not repeat successful tool calls; take the next step or give the final answer."
        }
        if (!first && tools != null && tools.length() > 0 && newTools.isEmpty()) parts += TOOL_REMINDER
        return parts.filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { NUDGE }
    }

    private fun serializeTools(tools: JSONArray?): String {
        if (tools == null || tools.length() == 0) return ""
        val list = (0 until tools.length()).mapNotNull { i ->
            val t = tools.optJSONObject(i) ?: return@mapNotNull null
            val f = t.optJSONObject("function") ?: t
            val name = f.optString("name")
            if (name.isBlank()) null else "- $name: ${f.optString("description")}\n  parameters: ${canonical(f.opt("parameters") ?: JSONObject())}"
        }
        if (list.isEmpty()) return ""
        return "You can call tools when they are relevant. Available OpenAI tool definitions:\n" + list.joinToString("\n") +
            "\nReturn normal text when no tool is needed."
    }

    private fun serializeToolObjects(tools: List<JSONObject>): String = tools.joinToString("\n") {
        "- ${it.optString("name")}: ${it.optString("description")}\n  parameters: ${canonical(it.opt("parameters"))}"
    }

    private fun readSession(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString(KEY_SESSION, "{}") ?: "{}")
    } catch (_: Throwable) { JSONObject() }

    /** Build full first-turn prompt or the minimal delta for a known session. */
    @Synchronized
    fun plan(ctx: Context, messages: JSONArray, tools: JSONArray?, conversationKey: String): Plan {
        val current = readSession(ctx)
        val incoming = mutableListOf<String>()
        val roles = mutableListOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (role(m) in listOf("system", "developer")) continue
            incoming += callFingerprint(m)
            roles += role(m)
        }
        val sys = systemLines(messages)
        val toolDefs = toolMap(tools)
        val sameConversation = current.optString("conversation", "") == conversationKey && conversationKey.isNotBlank()
        val first = !sameConversation || current.optJSONArray("sent") == null
        val oldView = current.optJSONArray("view").toStrings()
        val oldSent = current.optJSONArray("sent").toStrings()

        val newPositions = mutableListOf<Int>()
        if (first) {
            for (i in 0 until messages.length()) {
                val r = role(messages.optJSONObject(i) ?: JSONObject())
                if (r !in listOf("system", "developer")) newPositions += i
            }
        } else {
            val chainPositions = mutableListOf<Int>()
            for (i in 0 until messages.length()) if (role(messages.optJSONObject(i) ?: JSONObject()) !in listOf("system", "developer")) chainPositions += i
            if (oldView.isNotEmpty() && incoming.size >= oldView.size && incoming.take(oldView.size) == oldView) {
                newPositions += chainPositions.drop(oldView.size).filter { role(messages.optJSONObject(it) ?: JSONObject()) != "assistant" }
            } else {
                // History compacted/rewritten by Qwen: multiset subtraction retains repeated identical user turns.
                val remaining = oldSent.groupingBy { it }.eachCount().toMutableMap()
                for (i in chainPositions) {
                    val m = messages.optJSONObject(i) ?: continue
                    if (role(m) == "assistant") continue
                    val fp = callFingerprint(m)
                    val n = remaining[fp] ?: 0
                    if (n > 0) remaining[fp] = n - 1 else newPositions += i
                }
            }
        }

        val oldSys = current.optJSONArray("system_keys").toStrings().toSet()
        val newSysKeys = sys.keys.filter { it !in oldSys }
        val oldToolKeys = current.optJSONArray("tool_keys").toStrings().toSet()
        val newToolDefs = toolDefs.filterKeys { it !in oldToolKeys }.values.toList()
        val prompt = messagesPrompt(messages, newPositions, tools, first, newSysKeys.mapNotNull { sys[it] }, newToolDefs)
        val fullChars = buildString {
            val fullSys = sys.values.joinToString("\n")
            if (fullSys.isNotEmpty()) append(fullSys).append("\n\n")
            append(serializeTools(tools)).append("\n\n")
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                val r = role(m)
                if (r !in listOf("system", "developer")) append(r).append(": ").append(textOf(m.opt("content"))).append("\n")
            }
        }.length
        val mode = if (first) "full" else if (newPositions.isEmpty() && newSysKeys.isEmpty() && newToolDefs.isEmpty()) "nudge" else "delta"
        val sentAfter = oldSent.toMutableList()
        for (pos in newPositions) {
            val fp = callFingerprint(messages.optJSONObject(pos) ?: continue)
            sentAfter += fp
        }
        val counters = try { JSONObject(prefs(ctx).getString(KEY_COUNTERS, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        val sentChars = counters.optInt("chars_sent") + prompt.length
        NemLog.i("context", "Qwen incremental: ${if (first) "initialisation complète" else mode}, ${newPositions.size} nouveau(x) message(s), ${prompt.length}c envoyés / ${fullChars}c historiques")
        return Plan(prompt, mode, conversationKey, incoming, sys.keys.toList(), sys, toolDefs.keys.toList(), fullChars, prompt.length)
    }

    @Synchronized
    fun commit(ctx: Context, plan: Plan, answer: String, finalConversationKey: String) {
        val current = readSession(ctx)
        val view = JSONArray()
        plan.incoming.forEach { view.put(it) }
        val assistantFp = hash("assistant|" + answer.trim().replace(Regex("\\s+"), " "))
        view.put(assistantFp)
        val sent = JSONArray()
        val old = current.optJSONArray("sent").toStrings().toMutableList()
        plan.incoming.forEach { fp -> if (fp !in old) old += fp }
        if (assistantFp !in old) old += assistantFp
        old.forEach { sent.put(it) }
        val s = JSONObject().put("conversation", finalConversationKey.ifBlank { plan.sessionKey })
            .put("view", view).put("sent", sent)
            .put("system_keys", JSONArray(plan.systemKeys))
            .put("system_lines", JSONObject().apply { plan.systemLines.forEach { (k, v) -> put(k, v) } })
            .put("tool_keys", JSONArray(plan.toolKeys))
            .put("turns", current.optInt("turns") + 1)
            .put("last_prompt_chars", plan.charsSent)
            .put("updated_at", System.currentTimeMillis())
        prefs(ctx).edit().putString(KEY_SESSION, s.toString()).apply()
        val c = try { JSONObject(prefs(ctx).getString(KEY_COUNTERS, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        c.put(plan.mode, c.optInt(plan.mode) + 1)
        c.put("chars_sent", c.optLong("chars_sent") + plan.charsSent)
        c.put("chars_full", c.optLong("chars_full") + plan.charsFull)
        c.put("chars_saved", c.optLong("chars_saved") + (plan.charsFull - plan.charsSent).coerceAtLeast(0))
        prefs(ctx).edit().putString(KEY_COUNTERS, c.toString()).apply()
    }

    fun reset(ctx: Context): Int {
        val before = readSession(ctx).optInt("turns")
        prefs(ctx).edit().remove(KEY_SESSION).remove(KEY_COUNTERS).apply()
        NemLog.i("context", "session Qwen réinitialisée")
        return before
    }

    fun summary(ctx: Context): JSONObject {
        val s = readSession(ctx)
        val c = try { JSONObject(prefs(ctx).getString(KEY_COUNTERS, "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        val active = s.optInt("turns") > 0
        val sessions = JSONObject()
        if (active) sessions.put("deepseek", JSONObject().put("turns", s.optInt("turns"))
            .put("messages", s.optJSONArray("sent").lengthOrZero()).put("busy", AppState.busy.get()))
        val requests = JSONObject().put("full", c.optInt("full")).put("delta", c.optInt("delta"))
            .put("cached", c.optInt("cached")).put("local", 0).put("side", 0)
        return JSONObject().put("enabled", active).put("sessions", sessions).put("requests", requests)
            .put("chars_sent", c.optLong("chars_sent")).put("chars_full", c.optLong("chars_full"))
            .put("chars_saved", c.optLong("chars_saved"))
    }

    private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }
    private fun JSONArray?.lengthOrZero() = this?.length() ?: 0
}
