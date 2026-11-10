package com.nemap.android.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * État partagé entre l'API et le **dashboard web d'origine** (les pages
 * `proxy/web` de NemApi, servies telles quelles par l'app).
 *
 * On ne réinvente rien : on fournit aux pages les mêmes endpoints et les mêmes
 * formes de réponse que le proxy FastAPI, alimentés par les compteurs réels de
 * l'application. Aucun contenu de conversation n'est stocké ici — uniquement
 * des compteurs, des horodatages et des réglages.
 */
object WebStore {

    private const val PREF = "nemapi_web"
    private const val HISTORY_DAYS = 14

    private var sp: SharedPreferences? = null
    private val startedAt = AtomicLong(0L)

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (startedAt.get() == 0L) startedAt.set(System.currentTimeMillis())
    }

    private fun prefs(): SharedPreferences? = sp

    private fun iso(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(ts))

    private fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ------------------------------------------------------------------ réglages

    private val SETTING_DEFAULTS = linkedMapOf(
        "stream_enabled" to true,
        "auto_config" to true,
        "fresh_chat" to false,
        "premium_md" to true,
    )

    fun setting(key: String): Boolean {
        val p = prefs() ?: return SETTING_DEFAULTS[key] ?: false
        return p.getBoolean("set_$key", SETTING_DEFAULTS[key] ?: false)
    }

    fun setSetting(key: String, value: Boolean): JSONObject {
        prefs()?.edit()?.putBoolean("set_$key", value)?.apply()
        NemLog.i("web", "réglage $key = $value")
        return settingsJson()
    }

    fun settingsJson(): JSONObject = JSONObject().apply {
        SETTING_DEFAULTS.keys.forEach { k -> put(k, setting(k)) }
    }

    // --------------------------------------------------------- agents (intégrations)

    /** Catalogue livré avec l'app (mêmes fiches que NemApi web). */
    fun catalogJson(ctx: Context): JSONArray {
        val raw = try {
            ctx.assets.open("web/agents.json").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            "{\"agents\":[]}"
        }
        return try {
            JSONObject(raw).optJSONArray("agents") ?: JSONArray()
        } catch (t: Throwable) {
            JSONArray()
        }
    }

    private fun agentState(id: String): JSONObject {
        val raw = prefs()?.getString("agent_$id", null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject()
        }
    }

    /** Fiche complète, fusionnée avec l'état enregistré. */
    fun agentsJson(ctx: Context): JSONArray {
        val out = JSONArray()
        val catalog = catalogJson(ctx)
        for (i in 0 until catalog.length()) {
            val base = catalog.optJSONObject(i) ?: continue
            val id = base.optString("id")
            val st = agentState(id)
            val enabled = st.optBoolean("enabled", false)
            val featState = st.optJSONObject("features") ?: JSONObject()
            val feats = JSONArray()
            val baseFeats = base.optJSONArray("features") ?: JSONArray()
            for (j in 0 until baseFeats.length()) {
                val f = baseFeats.optJSONObject(j) ?: continue
                val fid = f.optString("id")
                feats.put(
                    JSONObject()
                        .put("id", fid)
                        .put("label", f.optString("label"))
                        .put("tag", f.optString("tag"))
                        .put("description", f.optString("description"))
                        .put("enabled", featState.has(fid) && featState.optBoolean(fid) || !featState.has(fid) && f.optBoolean("enabled", true))
                )
            }
            out.put(
                JSONObject(base.toString())
                    .put("enabled", enabled)
                    .put("features", feats)
                    .put("detected", detectedFor(id))
            )
        }
        return out
    }

    fun setAgent(ctx: Context, id: String, enabled: Boolean?, features: JSONObject?): JSONObject? {
        val catalog = agentsJson(ctx)
        var found = false
        for (i in 0 until catalog.length()) if (catalog.optJSONObject(i)?.optString("id") == id) found = true
        if (!found) return null
        val st = agentState(id)
        if (enabled != null) st.put("enabled", enabled)
        if (features != null) {
            val fs = st.optJSONObject("features") ?: JSONObject()
            features.keys().forEach { k -> fs.put(k, features.optBoolean(k)) }
            st.put("features", fs)
        }
        prefs()?.edit()?.putString("agent_$id", st.toString())?.apply()
        NemLog.i("web", "intégration $id : ${if (enabled != null) "enabled=$enabled" else "options"}")
        val fresh = agentsJson(ctx)   // relu après sauvegarde : l'état renvoyé doit être le nouveau
        for (i in 0 until fresh.length()) {
            val a = fresh.optJSONObject(i) ?: continue
            if (a.optString("id") == id) {
                a.put("detected", detectedFor(id))
                return a
            }
        }
        return null
    }

    fun agentEnabled(ctx: Context, id: String): Boolean = agentState(id).optBoolean("enabled", false)

    fun agentFeatureEnabled(ctx: Context, id: String, feature: String): Boolean {
        val state = agentState(id)
        val overrides = state.optJSONObject("features")
        if (overrides?.has(feature) == true) return overrides.optBoolean(feature)
        val agents = catalogJson(ctx)
        for (i in 0 until agents.length()) {
            val a = agents.optJSONObject(i) ?: continue
            if (a.optString("id") != id) continue
            val fs = a.optJSONArray("features") ?: continue
            for (j in 0 until fs.length()) {
                val f = fs.optJSONObject(j) ?: continue
                if (f.optString("id") == feature) return f.optBoolean("enabled", true)
            }
        }
        return false
    }

    private fun detectedFor(id: String): JSONObject {
        val n = prefs()?.getInt("agent_req_$id", 0) ?: 0
        val at = prefs()?.getLong("agent_at_$id", 0L) ?: 0L
        if (n == 0 || at == 0L) return JSONObject()
        return JSONObject()
            .put("requests", n)
            .put("seconds_ago", ((System.currentTimeMillis() - at) / 1000).toInt())
            .put("reason", "en-tête X-NemApi-Agent")
    }

    fun noteAgentRequest(ctx: Context, id: String) {
        val p = prefs() ?: return
        p.edit()
            .putInt("agent_req_$id", (p.getInt("agent_req_$id", 0)) + 1)
            .putLong("agent_at_$id", System.currentTimeMillis())
            .apply()
    }

    // --------------------------------------------------------------- clés API

    fun keys(): JSONArray {
        val raw = prefs()?.getString("api_keys", null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (t: Throwable) {
            JSONArray()
        }
    }

    private fun saveKeys(arr: JSONArray) {
        prefs()?.edit()?.putString("api_keys", arr.toString())?.apply()
    }

    fun requireAuth(): Boolean = prefs()?.getBoolean("require_auth", false) ?: false

    fun setRequireAuth(on: Boolean): JSONObject {
        prefs()?.edit()?.putBoolean("require_auth", on)?.apply()
        AppState.bearerEnabled = on
        NemLog.i("web", "protection par clé API : $on")
        val out = JSONObject().put("ok", true).put("enabled", on)
        if (on && keys().length() == 0) out.put("api_key", createKey("auto").optString("api_key"))
        return out
    }

    fun createKey(name: String): JSONObject {
        val arr = keys()
        val key = "nemapi-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 28)
        val obj = JSONObject()
            .put("id", java.util.UUID.randomUUID().toString().substring(0, 8))
            .put("name", name)
            .put("key", key)
            .put("created_at", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        arr.put(obj)
        saveKeys(arr)
        NemLog.i("web", "clé API créée (${if (name.isEmpty()) "sans nom" else name})")
        return JSONObject().put("ok", true).put("api_key", key).put("id", obj.optString("id"))
    }

    fun deleteKey(id: String): Boolean {
        val arr = keys()
        val out = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) removed = true else out.put(o)
        }
        if (removed) {
            saveKeys(out)
            NemLog.i("web", "clé API supprimée")
        }
        return removed
    }

    fun keyValid(bearer: String): Boolean {
        if (bearer.isEmpty()) return false
        val arr = keys()
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("key") == bearer) return true
        }
        return false
    }

    // ------------------------------------------------------------------- stats

    /** 1 token ≈ 4 caractères : c'est aussi l'estimation du proxy web. */
    private fun tokens(chars: Long): Long = chars / 4

    fun noteRequest(provider: String, promptChars: Long, completionChars: Long, ok: Boolean) {
        val p = prefs() ?: return
        val reqs = p.getInt("st_requests", 0) + if (ok) 1 else 0
        val pin = p.getLong("st_prompt_tokens", 0L) + tokens(promptChars)
        val pout = p.getLong("st_completion_tokens", 0L) + tokens(completionChars)
        val d = day()
        val e = p.edit()
            .putInt("st_requests", reqs)
            .putLong("st_prompt_tokens", pin)
            .putLong("st_completion_tokens", pout)
            .putString("st_updated_at", iso())
        if (ok) {
            val prefix = "d_${d}_"
            e.putInt(prefix + "requests", p.getInt(prefix + "requests", 0) + 1)
            e.putLong(prefix + "prompt_tokens", p.getLong(prefix + "prompt_tokens", 0L) + tokens(promptChars))
            e.putLong(prefix + "completion_tokens", p.getLong(prefix + "completion_tokens", 0L) + tokens(completionChars))
            val provPrefix = "p_${provider}_"
            e.putInt(provPrefix + "requests", p.getInt(provPrefix + "requests", 0) + 1)
            e.putLong(provPrefix + "prompt_tokens", p.getLong(provPrefix + "prompt_tokens", 0L) + tokens(promptChars))
            e.putLong(provPrefix + "completion_tokens", p.getLong(provPrefix + "completion_tokens", 0L) + tokens(completionChars))
        }
        e.apply()
    }

    fun clearStats() {
        val p = prefs() ?: return
        val e = p.edit()
        p.all.keys.filter {
            it.startsWith("st_") || it.startsWith("d_") || it.startsWith("p_")
        }.forEach { e.remove(it) }
        e.apply()
        NemLog.i("web", "historique des statistiques effacé")
    }

    fun uptimeSeconds(): Long {
        val base = if (AppState.startedAt > 0) AppState.startedAt else startedAt.get()
        return if (base <= 0) 0 else ((System.currentTimeMillis() - base) / 1000).coerceAtLeast(1)
    }

    fun statsJson(): JSONObject {
        val p = prefs()
        val reqs = p?.getInt("st_requests", 0) ?: 0
        val pin = p?.getLong("st_prompt_tokens", 0L) ?: 0L
        val pout = p?.getLong("st_completion_tokens", 0L) ?: 0L
        val up = uptimeSeconds()

        val providersObj = JSONObject()
        for (pid in listOf("deepseek")) {
            val prefix = "p_${pid}_"
            val r = p?.getInt(prefix + "requests", 0) ?: 0
            val ppin = p?.getLong(prefix + "prompt_tokens", 0L) ?: 0L
            val ppout = p?.getLong(prefix + "completion_tokens", 0L) ?: 0L
            providersObj.put(
                pid, JSONObject()
                    .put("requests", r)
                    .put("prompt_tokens", ppin)
                    .put("completion_tokens", ppout)
                    .put("cost", 0.0)
                    .put("avg_prompt_tokens", if (r > 0) (ppin.toDouble() / r) else 0.0)
                    .put("avg_completion_tokens", if (r > 0) (ppout.toDouble() / r) else 0.0)
            )
        }

        val daily = JSONArray()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = java.util.Calendar.getInstance()
        val dates = ArrayList<String>()
        for (i in (HISTORY_DAYS - 1) downTo 0) {
            cal.time = Date()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            dates.add(fmt.format(cal.time))
        }
        dates.forEach { d ->
            val prefix = "d_${d}_"
            daily.put(
                JSONObject()
                    .put("date", d)
                    .put("requests", p?.getInt(prefix + "requests", 0) ?: 0)
                    .put("prompt_tokens", p?.getLong(prefix + "prompt_tokens", 0L) ?: 0L)
                    .put("completion_tokens", p?.getLong(prefix + "completion_tokens", 0L) ?: 0L)
                    .put("cost", 0.0)
            )
        }

        return JSONObject()
            .put("version", AppState.VERSION)
            .put("uptime_seconds", up)
            .put("total_requests", reqs)
            .put("requests_per_minute", if (up >= 60) Math.round(reqs * 600.0 / up) / 10.0 else reqs)
            .put("prompt_tokens", pin)
            .put("completion_tokens", pout)
            .put("total_tokens", pin + pout)
            .put("estimated_cost", 0.0)
            .put("providers", providersObj)
            .put("daily", daily)
            .put("history_days", HISTORY_DAYS)
            .put("pricing", JSONObject())
            .put("pricing_checked_at", "")
            .put("updated_at", p?.getString("st_updated_at", null) ?: JSONObject.NULL)
            .put("persisted", true)
    }
}
