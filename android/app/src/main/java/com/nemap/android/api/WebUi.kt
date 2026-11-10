package com.nemap.android.api

import android.content.Context
import com.nemap.android.core.AppState
import com.nemap.android.core.NemLog
import com.nemap.android.core.WebStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Sert **le dashboard web d'origine de NemApi** (les pages `proxy/web` du dépôt,
 * copiées telles quelles dans `assets/web/`) et les endpoints JSON que ces pages
 * appellent.
 *
 * Rien n'est réinventé côté interface : c'est le même HTML/CSS/JS que la version
 * web ; seule la source des données change (l'app Android au lieu du proxy
 * FastAPI).
 */
object WebUi {

    class Resp(val code: Int, val type: String, val body: ByteArray)

    private const val T_PAGES = "text/html; charset=utf-8"
    private const val T_JSON = "application/json; charset=utf-8"
    private const val T_CSS = "text/css; charset=utf-8"
    private const val T_JS = "application/javascript; charset=utf-8"
    private const val T_PNG = "image/png"
    private const val T_SVG = "image/svg+xml"

    /** Nom du fichier d'asset pour une route de page, ou null. */
    private val PAGES = mapOf(
        "/" to "web/admin.html",
        "/index.html" to "web/admin.html",
        "/admin.html" to "web/admin.html",
        "/dashboard" to "web/admin.html",
        "/config" to "web/config.html",
        "/integrations" to "web/integrations.html",
        "/chat" to "web/chat.html",
        "/analytics" to "web/analytics.html",
        "/api-keys-page" to "web/api-keys.html",
    )

    fun handle(ctx: Context, method: String, path: String, body: String): Resp? {
        WebStore.init(ctx)

        // ---------- pages statiques ----------
        PAGES[path]?.let { file ->
            return fileAsset(ctx, file, T_PAGES) ?: Resp(404, T_JSON, jsonError("not_found", "Page indisponible"))
        }
        when {
            path == "/admin.css" -> return fileAsset(ctx, "web/admin.css", T_CSS)
            path == "/admin.js" -> return fileAsset(ctx, "web/admin.js", T_JS)
            path.startsWith("/icons/") -> {
                if (path.contains("..")) return Resp(404, T_JSON, jsonError("not_found", "Chemin invalide"))
                val type = when {
                    path.endsWith(".png") -> T_PNG
                    path.endsWith(".svg") -> T_SVG
                    else -> "application/octet-stream"
                }
                return fileAsset(ctx, "web$path", type)
            }
            path == "/favicon.ico" || path == "/favicon.png" ->
                return fileAsset(ctx, "web/icons/logo-32.png", T_PNG)
            path == "/extension/icons/logo-32.png" -> return fileAsset(ctx, "web/icons/logo-32.png", T_PNG)
        }

        // ---------- endpoints JSON du dashboard ----------
        return when {
            path == "/health" -> json(
                JSONObject()
                    .put("ok", true)
                    .put("version", AppState.VERSION)
                    .put("app_version", AppState.VERSION)
                    .put("extension", AppState.serviceRunning)
                    .put("android", true)
            )

            path == "/status" -> json(statusJson(ctx))

            path == "/stats" -> json(WebStore.statsJson())
            path == "/stats/clear" && method == "POST" -> {
                WebStore.clearStats()
                json(JSONObject().put("ok", true).put("message", "Stats cleared"))
            }

            path == "/providers" -> json(JSONObject().put("providers", providersJson()))

            path == "/settings" -> {
                if (method == "POST") {
                    val o = try {
                        JSONObject(body.ifEmpty { "{}" })
                    } catch (t: Throwable) {
                        JSONObject()
                    }
                    o.keys().forEach { k ->
                        if (k in listOf("stream_enabled", "auto_config", "fresh_chat", "premium_md")) {
                            WebStore.setSetting(k, o.optBoolean(k))
                        }
                    }
                }
                json(WebStore.settingsJson())
            }

            path == "/agent-integrations" -> json(integrationsJson(ctx))
            path.startsWith("/agent-integrations/") && method == "POST" -> {
                val id = path.removePrefix("/agent-integrations/")
                val o = try {
                    JSONObject(body.ifEmpty { "{}" })
                } catch (t: Throwable) {
                    JSONObject()
                }
                val agent = WebStore.setAgent(
                    ctx, id,
                    if (o.has("enabled")) o.optBoolean("enabled") else null,
                    o.optJSONObject("features")
                )
                if (agent == null) Resp(404, T_JSON, jsonError("not_found", "Agent inconnu : $id"))
                else json(agent)
            }

            path == "/sessions/reset" && method == "POST" -> {
                val reset = com.nemap.android.core.QwenContext.reset(ctx)
                val summary = com.nemap.android.core.QwenContext.summary(ctx)
                summary.put("ok", true).put("reset", reset)
                json(summary)
            }
            path == "/sessions" -> json(com.nemap.android.core.QwenContext.summary(ctx))

            path == "/stop" && method == "POST" -> json(JSONObject().put("ok", true).put("cancelled", 0))

            path == "/config-full" -> json(
                JSONObject()
                    .put(
                        "api_keys", JSONObject()
                            .put("enabled", WebStore.requireAuth())
                            .put("count", WebStore.keys().length())
                            .put("keys", WebStore.keys())
                    )
                    .put("settings", WebStore.settingsJson())
            )

            // ---------- état vu par les pages (sidebar, bandeaux) ----------
            path == "/extension/state" -> json(extensionState(ctx))
            path == "/extension/config" -> json(
                JSONObject()
                    .put("targetTabs", JSONObject().put("deepseek", 1))
                    .put("autoConfig", WebStore.setting("auto_config"))
                    .put("freshChat", WebStore.setting("fresh_chat"))
                    .put("premiumMd", WebStore.setting("premium_md"))
                    .put("jobTimeoutMs", 300_000)
            )
            path == "/extension/log" && method == "POST" -> json(JSONObject().put("ok", true))
            path == "/extension/tabs" && method == "POST" -> json(JSONObject().put("ok", true))

            // ---------- clés API ----------
            path == "/api-keys/config" -> json(
                JSONObject()
                    .put("enabled", WebStore.requireAuth())
                    .put("require_auth", WebStore.requireAuth())
                    .put("key_count", WebStore.keys().length())
            )
            path == "/api-keys" -> {
                if (method == "POST") {
                    val name = try {
                        JSONObject(body.ifEmpty { "{}" }).optString("name", "")
                    } catch (t: Throwable) {
                        ""
                    }
                    json(WebStore.createKey(name))
                } else {
                    json(
                        JSONObject()
                            .put("api_keys", WebStore.keys())
                            .put("require_auth", WebStore.requireAuth())
                    )
                }
            }
            path == "/api-keys/enable" && method == "POST" -> json(WebStore.setRequireAuth(true))
            path == "/api-keys/disable" && method == "POST" -> json(WebStore.setRequireAuth(false))
            path.startsWith("/api-keys/") && (method == "DELETE" || method == "POST") -> {
                val id = path.removePrefix("/api-keys/")
                if (WebStore.deleteKey(id)) json(JSONObject().put("ok", true))
                else Resp(404, T_JSON, jsonError("not_found", "Clé API non trouvée"))
            }

            else -> null
        }
    }

    // ------------------------------------------------------------------ contenus

    private fun fileAsset(ctx: Context, name: String, type: String): Resp? = try {
        val bytes = ctx.assets.open(name).use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
        Resp(200, type, bytes)
    } catch (t: Throwable) {
        null
    }

    private fun json(o: JSONObject): Resp = Resp(200, T_JSON, o.toString().toByteArray(Charsets.UTF_8))

    private fun jsonError(code: String, message: String): ByteArray =
        JSONObject().put("error", JSONObject().put("message", message).put("code", code)).toString()
            .toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------ charges

    private fun providersJson(): JSONArray = JSONArray().put(
        JSONObject()
            .put("id", "deepseek")
            .put("models", JSONArray().put("deepseek-chat").put("deepseek-reasoner"))
            .put("display_name", "DeepSeek")
    )

    private fun statusJson(ctx: Context): JSONObject {
        val ips = AppState.lanAddresses()
        val stats = WebStore.statsJson()
        return JSONObject()
            .put("status", if (AppState.serviceRunning) "ready" else "waiting_extension")
            .put("version", AppState.VERSION)
            .put("app_version", AppState.VERSION)
            .put("extension", AppState.serviceRunning)
            .put("uptime_seconds", stats.optLong("uptime_seconds"))
            .put("providers_ready", if (AppState.serviceRunning) 1 else 0)
            .put("target_tabs", JSONObject().put("deepseek", 1))
            .put("open_tabs", JSONArray().put(
                JSONObject().put("id", 1).put("provider", "deepseek").put("title", AppState.pageUrl.take(60))
            ))
            .put("jobs", JSONObject().put("pending", 0).put("running", if (AppState.busy.get()) 1 else 0))
            .put("api_keys_enabled", WebStore.requireAuth())
            .put("settings", WebStore.settingsJson())
            .put("incremental", JSONObject())
            .put("last_error", AppState.lastError ?: JSONObject.NULL)
            .put("android", true)
            .put("port", AppState.API_PORT)
            .put("lan_urls", JSONArray(ips))
            .put("base_url", if (ips.isNotEmpty()) "http://${ips[0]}:${AppState.API_PORT}/v1" else "http://127.0.0.1:${AppState.API_PORT}/v1")
    }

    private fun integrationsJson(ctx: Context): JSONObject {
        val agents = WebStore.agentsJson(ctx)
        val anyEnabled = (0 until agents.length()).any { agents.optJSONObject(it)?.optBoolean("enabled") == true }
        return JSONObject()
            .put("agents", agents)
            .put("clients", JSONArray())
            .put("discussion_owners", JSONObject())
            .put("sessions", JSONObject().put("enabled", anyEnabled).put("sessions", JSONObject())
                .put("requests", JSONObject().put("full", 0).put("delta", 0).put("cached", 0).put("local", 0).put("side", 0))
                .put("chars_saved", 0))
    }

    private fun extensionState(ctx: Context): JSONObject {
        val logs = JSONArray()
        NemLog.tail(60).forEach { e ->
            logs.put(
                JSONObject()
                    .put("t", e.at)
                    .put("level", e.level)
                    .put("msg", "${e.category} · ${e.message}")
            )
        }
        val tabs = JSONArray().put(
            JSONObject()
                .put("id", 1)
                .put("provider", "deepseek")
                .put("title", if (AppState.pageUrl.isEmpty()) "Page du provider" else "DeepSeek — session active")
                .put("url", AppState.pageUrl)
        )
        return JSONObject()
            .put("connected", AppState.serviceRunning)
            .put("busy", AppState.busy.get())
            .put("version", AppState.VERSION)
            .put("tabs", tabs)
            .put("targetTabs", JSONObject().put("deepseek", 1))
            .put("logs", logs)
            .put("providers_ready", if (AppState.serviceRunning) 1 else 0)
            .put("lastExtraction", JSONObject().put("source", "stream-hook").put("bridge", true))
    }
}
