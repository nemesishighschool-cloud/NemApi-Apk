package com.nemap.android.web

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import com.nemap.android.core.NemApiException
import com.nemap.android.core.NemLog
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Moteur d'évaluation JS : tout passe par le thread principal du WebView,
 * chaque appel est une coroutine suspendue avec timeout et libération garantie.
 */
object Engine {

    private val main = Handler(Looper.getMainLooper())

    /** Assets déjà chargés en mémoire. */
    private val cache = HashMap<String, String>()
    private var assets: AssetManager? = null

    fun init(am: AssetManager) {
        assets = am
    }

    fun asset(path: String): String = cache.getOrPut(path) {
        assets?.open(path)?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun unwrap(value: String?): String? {
        if (value == null) return null
        val v = value.trim()
        if (v.isEmpty() || v == "null" || v == "undefined") return null
        return try {
            if (v.startsWith("\"")) {
                (JSONTokener(v).nextValue() as? String) ?: v
            } else v
        } catch (t: Throwable) {
            v
        }
    }

    /** Évalue du JS et rend la valeur déballée (String) ou null. */
    suspend fun evalJs(js: String, timeoutMs: Long = 25_000): String? {
        val wv = WebViewHost.webView
            ?: throw NemApiException.notReady("Le navigateur intégré n'a pas encore été ouvert.")
        val done = AtomicBoolean(false)
        return suspendCancellableCoroutine { cont ->
            val timer = Runnable {
                if (done.compareAndSet(false, true) && cont.isActive) {
                    cont.resumeWithException(NemApiException.timeout("Le moteur DOM n'a pas répondu dans le délai imparti."))
                }
            }
            main.postDelayed(timer, timeoutMs)
            main.post {
                try {
                    wv.evaluateJavascript(js) { value ->
                        if (done.compareAndSet(false, true) && cont.isActive) {
                            main.removeCallbacks(timer)
                            cont.resume(unwrap(value))
                        }
                    }
                } catch (t: Throwable) {
                    if (done.compareAndSet(false, true) && cont.isActive) {
                        main.removeCallbacks(timer)
                        cont.resumeWithException(NemApiException.notReady("Évaluation JS impossible : ${t.message}"))
                    }
                }
            }
            cont.invokeOnCancellation {
                done.set(true)
                main.removeCallbacks(timer)
            }
        }
    }

    /** Évalue du JS qui produit une chaîne JSON, et la désérialise. */
    suspend fun evalJson(js: String, timeoutMs: Long = 25_000): JSONObject {
        val raw = evalJs(js, timeoutMs)
        if (raw == null) return JSONObject("{\"ok\":false,\"reason\":\"empty\"}")
        return try {
            JSONObject(raw)
        } catch (t: Throwable) {
            JSONObject("{\"ok\":true,\"value\":${JSONObject.quote(raw)}}")
        }
    }

    /** Garantit que l'adaptateur du provider est présent dans la page. */
    suspend fun ensureAdapter() {
        val present = evalJs("typeof window.__napDS") ?: "undefined"
        if (present != "object") {
            NemLog.i("dom", "adaptateur absent → injection")
            evalJs(asset("js/adapter-deepseek.js"))
        }
    }

    /** Appel d'une méthode de l'adaptateur : window.__napDS.<method>(<arg?>) */
    suspend fun adapter(method: String, arg: String? = null, timeoutMs: Long = 25_000): JSONObject {
        val js = if (arg == null) {
            "window.__napDS ? window.__napDS.$method() : JSON.stringify({ok:false,reason:'adapter_missing'})"
        } else {
            "window.__napDS ? window.__napDS.$method(${JSONObject.quote(arg)}) : JSON.stringify({ok:false,reason:'adapter_missing'})"
        }
        return evalJson(js, timeoutMs)
    }

    /**
     * Écrit les prompts longs par morceaux sous le plafond pratique du pont
     * evaluateJavascript (sans tronquer ni imposer de taille maximale métier).
     */
    suspend fun setPrompt(prompt: String): JSONObject {
        adapter("promptBegin", timeoutMs = 10_000)
        var off = 0
        val chunkSize = 18_000
        while (off < prompt.length) {
            var end = minOf(off + chunkSize, prompt.length)
            // Ne coupe pas une paire UTF-16 entre ses deux demi-surrogates.
            if (end < prompt.length && end > off && Character.isHighSurrogate(prompt[end - 1]) && Character.isLowSurrogate(prompt[end])) end--
            val r = adapter("promptChunk", prompt.substring(off, end), 30_000)
            if (!r.optBoolean("ok")) return r
            off = end
        }
        return adapter("promptCommit", timeoutMs = 90_000)
    }

    suspend fun currentUrl(): String = evalJs("location.href", 5_000) ?: ""

    /** État du flux observé par le hook réseau. */
    suspend fun streamState(): JSONObject =
        evalJson("window.__nap ? window.__nap.state() : JSON.stringify({started:false})")

    /**
     * Suivi des téléversements de pièces jointes, vus depuis le réseau
     * (requêtes interceptées par `hook.js`). Beaucoup plus fiable que de
     * deviner l'état du DOM : on sait quand la page envoie le fichier et
     * quand elle a fini.
     */
    suspend fun uploadState(): JSONObject = try {
        evalJson("window.__napUpload ? window.__napUpload() : JSON.stringify({seen:false,active:false})")
    } catch (t: Throwable) {
        JSONObject().put("seen", false).put("active", false)
    }

    suspend fun resetStream() {
        evalJs("window.__nap && window.__nap.reset()")
    }

    suspend fun rawStream(n: Int = 3000): String =
        evalJs("window.__nap ? window.__nap.raw($n) : ''") ?: ""
}
