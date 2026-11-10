package com.nemap.android.api

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import com.nemap.android.BuildConfig
import com.nemap.android.core.AppState
import com.nemap.android.core.DeepSeek
import com.nemap.android.core.ErrorTypes
import com.nemap.android.core.ImagePart
import com.nemap.android.core.Msg
import com.nemap.android.core.NemApiException
import com.nemap.android.core.NemLog
import com.nemap.android.service.BridgeService
import com.nemap.android.web.Engine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serveur HTTP minimal (sockets bruts) : API OpenAI-compatible + API
 * d'administration + dashboard. Écrit à la main pour garder la maîtrise du
 * streaming SSE (pas de dépendance, pas de buffering caché).
 */
class ApiServer(private val context: Context, private val port: Int = AppState.API_PORT) {

    private var serverSocket: ServerSocket? = null
    private var ipv6: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val seq = AtomicLong(0)

    @Volatile
    var running = false
        private set

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = ss
            running = true
            Thread({ acceptLoop() }, "nemapi-api").apply { isDaemon = true }.start()
            NemLog.i("api", "API en écoute sur 0.0.0.0:$port (IPv4 + réseau local)")
            startIpv6Listener()
            true
        } catch (t: Throwable) {
            NemLog.e("api", "démarrage impossible : ${t.message}")
            false
        }
    }

    /**
     * Second écouteur sur `::` : certains réseaux Wi-Fi n'attribuent qu'une
     * adresse IPv6. Best-effort — si le noyau refuse, l'IPv4 reste seul.
     */
    private fun startIpv6Listener() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(java.net.InetAddress.getByName("::"), port))
            ipv6 = ss
            Thread({
                while (running) {
                    try {
                        val s = ss.accept() ?: break
                        pool.execute { try { handle(s) } catch (_: Throwable) { } finally { try { s.close() } catch (_: Throwable) { } } }
                    } catch (_: Throwable) {
                        if (running) Thread.sleep(200)
                    }
                }
            }, "nemapi-api6").apply { isDaemon = true }.start()
            NemLog.i("api", "API en écoute aussi sur [::]:$port")
        } catch (t: Throwable) {
            NemLog.i("api", "pas d'écoute IPv6 (${t.javaClass.simpleName}) — IPv4 seul")
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            ipv6?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        ipv6 = null
        NemLog.i("api", "API arrêtée")
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val s = serverSocket?.accept() ?: break
                pool.execute {
                    try {
                        handle(s)
                    } catch (t: Throwable) {
                        NemLog.w("api", "requête en erreur : ${t.javaClass.simpleName} ${t.message ?: ""}")
                    } finally {
                        try {
                            s.close()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running) NemLog.w("api", "accept : ${t.message}")
            }
        }
    }

    // ---------- HTTP minimal ----------

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 180_000
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val fullPath = parts[1]
        val path = fullPath.substringBefore('?')
        val query = if (fullPath.contains('?')) fullPath.substringAfter('?') else ""

        var contentLength = 0
        var authorization = ""
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                if (name == "authorization") authorization = value
            }
        }
        val body = if (contentLength > 0) String(readN(input, contentLength), Charsets.UTF_8) else ""

        // Le dashboard est celui de NemApi web (assets/web), servi tel quel.
        val web = WebUi.handle(context, method, path, body)
        if (web != null) {
            sendBytes(out, web.code, web.type, web.body)
            out.flush()
            return
        }

        when {
            path == "/api/net" -> {
                // Diagnostic « pourquoi je ne vois pas l'API depuis mon PC ? »
                // Toutes les interfaces, leur rôle, et l'URL à essayer en premier.
                val ips = AppState.lanAddresses()
                val best = ips.firstOrNull()
                val payload = JSONObject()
                    .put("port", AppState.API_PORT)
                    .put("listening_on", "0.0.0.0")
                    .put("lan_urls", JSONArray(ips))
                    .put("try_first", if (best != null) "http://$best:${AppState.API_PORT}" else "")
                    .put("api_base", if (best != null) "http://$best:${AppState.API_PORT}/v1" else "")
                    .put("interfaces", JSONArray(AppState.interfacesJson()))
                    .put("hint", "Ouvre l'URL « try_first » depuis un appareil du même Wi-Fi. " +
                        "Si elle ne répond pas : le Wi-Fi est peut-être un réseau invité (isolation des clients), " +
                        "ou l'ordinateur est sur un autre sous-réseau.")
                sendJson(out, 200, payload.toString())
            }
            path == "/api/status" -> sendJson(out, 200, AppState.statusJson())
            path == "/api/logs" -> sendJson(out, 200, "{\"entries\":${NemLog.asJson()}}")
            path == "/api/providers" -> sendJson(out, 200, providersJson())
            path == "/api/debug/describe" -> {
                val r = runBlocking { DeepSeek.describe() }
                sendJson(out, 200, r.toString())
            }
            path == "/api/debug/stream" -> {
                val raw = runBlocking { Engine.rawStream(2000) }
                sendJson(out, 200, "{\"raw\":${JSONObject.quote(raw)}}")
            }
            path == "/api/debug/latency" -> sendJson(out, 200, AppState.latencyJson())
            path == "/api/debug/cookies" -> {
                // Outils de développement UNIQUEMENT (build debug) : sauvegarde et
                // restauration de la session provider, pour survivre à une
                // réinstallation sans redemander les identifiants.
                if (!BuildConfig.DEBUG) {
                    sendJson(out, 404, errorJson("not_found", "Route inconnue : $path"))
                } else if (method == "POST") {
                    val cookie = try {
                        JSONObject(body).optString("cookie", "")
                    } catch (t: Throwable) {
                        ""
                    }
                    if (cookie.isEmpty()) {
                        sendJson(out, 400, errorJson("invalid_request", "Champ cookie manquant."))
                    } else {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cookie.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { pair ->
                            cm.setCookie("https://chat.deepseek.com/", pair)
                        }
                        cm.flush()
                        NemLog.i("dom", "session provider restaurée (${cookie.length} c.)")
                        sendJson(out, 200, "{\"ok\":true}")
                    }
                } else {
                    val c = CookieManager.getInstance().getCookie("https://chat.deepseek.com/") ?: ""
                    NemLog.i("dom", "export de session demandé (${c.length} c.)")
                    sendJson(out, 200, "{\"host\":\"chat.deepseek.com\",\"length\":${c.length},\"cookie\":${JSONObject.quote(c)}}")
                }
            }
            path == "/api/debug/eval" -> {
                // outil de développement : évalue du JS dans la page du provider
                val js = body.ifEmpty { query.removePrefix("js=") }
                val result = runBlocking {
                    try {
                        Engine.evalJs(js, 30_000) ?: "null"
                    } catch (t: Throwable) {
                        "error: ${t.message}"
                    }
                }
                sendJson(out, 200, "{\"result\":${JSONObject.quote(result)}}")
            }
            path == "/api/debug/inject" -> {
                runBlocking { Engine.ensureAdapter() }
                sendJson(out, 200, "{\"ok\":true}")
            }
            path == "/api/service/start" -> {
                context.startForegroundService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_START))
                sendJson(out, 200, "{\"ok\":true}")
            }
            path == "/api/service/stop" -> {
                context.startService(Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
                sendJson(out, 200, "{\"ok\":true}")
            }
            (path.startsWith("/v1/") || path == "/models") && !authorized(authorization) ->
                sendJson(out, 401, errorJson("invalid_api_key", "Clé API manquante ou invalide."))
            path == "/v1/models" || path == "/models" -> sendJson(out, 200, modelsJson())
            path == "/v1/chat/completions" || path == "/chat/completions" ->
                if (method == "POST") chatCompletions(out, body) else sendJson(out, 405, errorJson("method_not_allowed", "POST attendu."))
            path == "/v1/responses" || path == "/responses" ->
                if (method == "POST") responses(out, body) else sendJson(out, 405, errorJson("method_not_allowed", "POST attendu."))
            else -> sendJson(out, 404, errorJson("not_found", "Route inconnue : $path"))
        }
        try {
            out.flush()
        } catch (_: Throwable) {
        }
    }

    private fun redirect(out: OutputStream, to: String) {
        val bytes = "Redirecting".toByteArray()
        val head = "HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
    }

    private fun sendJson(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            400 -> "400 Bad Request"
            401 -> "401 Unauthorized"
            404 -> "404 Not Found"
            405 -> "405 Method Not Allowed"
            409 -> "409 Conflict"
            500 -> "500 Internal Server Error"
            else -> "$code OK"
        }
        val head = "HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun sendBytes(out: OutputStream, code: Int, type: String, bytes: ByteArray) {
        val status = when (code) {
            200 -> "200 OK"
            404 -> "404 Not Found"
            401 -> "401 Unauthorized"
            else -> "$code OK"
        }
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET,POST,DELETE,OPTIONS\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    /** Bearer facultatif : refusé seulement si l'utilisateur a activé la protection. */
    private fun authorized(header: String): Boolean {
        if (!com.nemap.android.core.WebStore.requireAuth()) return true
        val token = header.removePrefix("Bearer ").removePrefix("bearer ").trim()
        return com.nemap.android.core.WebStore.keyValid(token)
    }

    private fun sendHtml(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun errorJson(code: String, message: String, httpCode: Int = 400): String =
        JSONObject().put(
            "error", JSONObject()
                .put("message", message)
                .put("type", ErrorTypes.typeFor(code))
                .put("code", code)
                .also { it.put("http", httpCode) }
        ).toString()

    private fun modelsJson(): String {
        val arr = JSONArray()
        listOf("deepseek-chat", "deepseek-reasoner").forEach { id ->
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "model")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("owned_by", "nemapi-android")
            )
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
    }

    private fun providersJson(): String = """
        {"providers":[
          {"id":"deepseek","label":"DeepSeek","status":"${if (AppState.activeProvider == "deepseek") "actif" else "inactif"}",
           "session":"inconnue","implementation":"DOM + hook SSE","enabled":true,"supported":true}
        ],"active":"${AppState.activeProvider}"}
    """.trimIndent()

    // ---------- OpenAI ----------

    private companion object {
        const val MAX_IMAGES = 8
        const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
    }

    private fun parseMessages(body: String): Pair<List<Msg>, JSONObject> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("messages") ?: JSONArray()
        val msgs = ArrayList<Msg>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = m.opt("content")
            val imgs = ArrayList<ImagePart>()
            val text = when (content) {
                is String -> content
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (k in 0 until content.length()) {
                        val p = content.optJSONObject(k) ?: continue
                        when (p.optString("type")) {
                            "text" -> sb.append(p.optString("text"))
                            "image_url" -> {
                                val u = p.optJSONObject("image_url")?.optString("url", "") ?: ""
                                if (imgs.size < MAX_IMAGES) decodeImage(u)?.let { imgs.add(it) }
                            }
                        }
                    }
                    sb.toString()
                }
                else -> ""
            }
            msgs.add(Msg(role, text, imgs))
        }
        return msgs to root
    }

    /** Décode `data:` (base64) ou télécharge une URL http(s) en image bornée. */
    private fun decodeImage(url: String): ImagePart? {
        if (url.isEmpty()) return null
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val head = url.substring(5, comma)
            if (!head.contains("base64")) return null
            val mime = head.substringBefore(';').ifBlank { "image/png" }
            val bytes = try {
                android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
            } catch (t: Throwable) {
                return null
            }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            return ImagePart(mime, nameFor(mime), bytes)
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "NemApi/1.0")
            val bytes = c.inputStream.use { readLimited(it, MAX_IMAGE_BYTES) }
            val mime = c.contentType?.substringBefore(';') ?: "image/png"
            if (bytes.isEmpty()) null else ImagePart(mime, nameFor(mime), bytes)
        } catch (t: Throwable) {
            NemLog.w("api", "image distante non récupérée : ${t.message}")
            null
        }
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > max) break
        }
        return buf.toByteArray()
    }

    private fun nameFor(mime: String): String = when {
        mime.contains("jpeg") || mime.contains("jpg") -> "image.jpg"
        mime.contains("webp") -> "image.webp"
        mime.contains("gif") -> "image.gif"
        else -> "image.png"
    }

    /** Estimation grossière (≈4 caractères par token) faute de compteur exact. */
    private fun usageOf(promptText: Int, completionText: Int, extraChars: Int): JSONObject {
        val pt = (promptText + extraChars) / 4
        val ct = completionText / 4
        return JSONObject()
            .put("prompt_tokens", pt)
            .put("completion_tokens", ct)
            .put("total_tokens", pt + ct)
    }

    private fun chatCompletions(out: OutputStream, body: String) {
        AppState.requests.incrementAndGet()
        AppState.lastRequestAt.set(System.currentTimeMillis())
        val started = System.currentTimeMillis()
        val (msgs, root) = try {
            parseMessages(body)
        } catch (t: Throwable) {
            sendJson(out, 400, errorJson("invalid_request", "Corps JSON invalide : ${t.message}"))
            return
        }
        val stream = root.optBoolean("stream", false)
        val model = root.optString("model", "deepseek-chat")
        val id = "chatcmpl-${seq.incrementAndGet()}"
        val created = System.currentTimeMillis() / 1000

        val cancelled = AtomicBoolean(false)
        val promptChars = msgs.sumOf { it.content.length }
        val imageCount = msgs.sumOf { it.images.size }
        val imageChars = imageCount * 1000   // ~1000 « tokens » par image, estimation

        if (!stream) {
            try {
                val text = runBlocking {
                    DeepSeek.chat(msgs, onDelta = { }) { cancelled.get() }
                }
                val resp = JSONObject()
                    .put("id", id)
                    .put("object", "chat.completion")
                    .put("created", created)
                    .put("model", model)
                    .put(
                        "choices", JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put(
                                    "message", JSONObject()
                                        .put("role", "assistant")
                                        .put("content", text)
                                )
                                .put("finish_reason", "stop")
                        )
                    )
                    .put("usage", usageOf(promptChars, text.length, imageChars))
                sendJson(out, 200, resp.toString())
                val totalMs = System.currentTimeMillis() - started
                AppState.noteLatency(stream = false, ttftMs = totalMs, totalMs = totalMs,
                    chars = text.length, chunks = 1, images = imageCount, ok = true)
                NemLog.i("api", "requête non-stream terminée en $totalMs ms (${text.length} c.)")
                com.nemap.android.core.WebStore.noteRequest("deepseek", promptChars.toLong(), text.length.toLong(), ok = true)
            } catch (e: NemApiException) {
                AppState.noteError(e.code)
                sendJson(out, httpFor(e.code), errorJson(e.code, e.message))
                NemLog.w("api", "erreur ${e.code}")
            } catch (t: Throwable) {
                AppState.noteError("upstream_error")
                sendJson(out, 500, errorJson("upstream_error", t.message ?: "Erreur interne"))
            }
            return
        }

        // ---- streaming SSE ----
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache, no-transform\r\nConnection: close\r\n" +
            "X-Accel-Buffering: no\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        try {
            out.write(head.toByteArray())
            out.flush()
        } catch (t: Throwable) {
            return
        }

        fun chunk(delta: JSONObject, finish: String?): String =
            JSONObject()
                .put("id", id).put("object", "chat.completion.chunk")
                .put("created", created).put("model", model)
                .put(
                    "choices", JSONArray().put(
                        JSONObject().put("index", 0).put("delta", delta)
                            .put("finish_reason", finish ?: JSONObject.NULL)
                    )
                ).toString()

        var firstDeltaAt = 0L
        var chunks = 0
        var chars = 0
        fun writeSse(payload: String) {
            try {
                out.write("data: $payload\n\n".toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (t: Throwable) {
                cancelled.set(true)
            }
        }

        writeSse(chunk(JSONObject().put("role", "assistant").put("content", ""), null))

        try {
            runBlocking {
                DeepSeek.chat(
                    msgs,
                    onDelta = { d ->
                        if (firstDeltaAt == 0L) firstDeltaAt = System.currentTimeMillis()
                        chunks++
                        chars += d.length
                        writeSse(chunk(JSONObject().put("content", d), null))
                    },
                    shouldCancel = { cancelled.get() }
                )
            }
            writeSse(chunk(JSONObject(), "stop"))
            writeSse("[DONE]")
            val totalMs = System.currentTimeMillis() - started
            val ttft = if (firstDeltaAt > 0) firstDeltaAt - started else totalMs
            AppState.noteLatency(stream = true, ttftMs = ttft, totalMs = totalMs,
                chars = chars, chunks = chunks, images = imageCount, ok = true)
            NemLog.i("api", "stream terminé en $totalMs ms (premier fragment à $ttft ms, $chunks morceaux)")
            com.nemap.android.core.WebStore.noteRequest("deepseek", promptChars.toLong(), chars.toLong(), ok = true)
        } catch (e: NemApiException) {
            AppState.noteError(e.code)
            writeSse(errorJson(e.code, e.message))
            writeSse("[DONE]")
            NemLog.w("api", "stream interrompu : ${e.code}")
        } catch (t: Throwable) {
            AppState.noteError("upstream_error")
            writeSse(errorJson("upstream_error", t.message ?: "Erreur interne"))
            writeSse("[DONE]")
        }
    }

    private fun httpFor(code: String): Int = when (code) {
        NemApiException.PROVIDER_BUSY -> 409
        NemApiException.TIMEOUT -> 504
        else -> 400
    }

    /** Endpoint /v1/responses : mapping minimal sur le chat. */
    private fun responses(out: OutputStream, body: String) {
        try {
            val root = JSONObject(body)
            val input = root.optString("input", root.optString("prompt", ""))
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", input))
            val synthetic = JSONObject()
                .put("model", root.optString("model", "deepseek-chat"))
                .put("messages", messages)
                .put("stream", false)
            val sw = java.io.ByteArrayOutputStream()
            val cap = object : OutputStream() {
                override fun write(b: Int) {
                    sw.write(b)
                }
            }
            chatCompletions(cap, synthetic.toString())
            val resp = String(sw.toByteArray(), Charsets.UTF_8)
            val json = JSONObject(resp.substringAfter("\r\n\r\n"))
            val text = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""
            val outJson = JSONObject()
                .put("id", "resp_${seq.incrementAndGet()}")
                .put("object", "response")
                .put("status", "completed")
                .put("output", JSONArray().put(
                    JSONObject().put("type", "message").put("role", "assistant")
                        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
                ))
            sendJson(out, 200, outJson.toString())
        } catch (t: Throwable) {
            sendJson(out, 400, errorJson("invalid_request", t.message ?: "requête invalide"))
        }
    }

    // ---------- dashboard ----------

}
