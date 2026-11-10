package com.nemap.android.core

import android.content.Context
import com.nemap.android.web.Engine
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Une pièce jointe multimodale (image ou fichier, décodé depuis base64 ou HTTP). */
class ImagePart(val mime: String, val name: String, val bytes: ByteArray)

data class Msg(
    val role: String,
    val content: String,
    val images: List<ImagePart> = emptyList()
)

/**
 * Orchestrateur du provider DeepSeek Web.
 *
 *  1. DOM  → écrire le prompt, cliquer Envoyer / Stop (actions ponctuelles)
 *  2. hook réseau (tee SSE) → lire la réponse et détecter la fin de flux
 *  3. DOM  → lecture de secours si le hook n'a rien vu
 */
object DeepSeek {

    private const val ELEMENT_MS = 15_000L
    private const val FIRST_TOKEN_MS = 60_000L
    private const val RESPONSE_MS = 1_800_000L // long streams may include several slow model phases
    private const val STABILITY_MS = 2_000L
    private const val POLL_MS = 250L
    /** Plafond de sécurité, plus jamais atteint en pratique (voir waitUpload). */
    private const val UPLOAD_MS = 45_000L
    /** Au-delà, on considère que la page garde la pièce jointe en local. */
    private const val UPLOAD_SILENCE_MS = 3_000L
    /** Taille d'un morceau de base64 transféré par evaluateJavascript. */
    private const val CHUNK = 96_000
    private const val MAX_CONTINUATIONS = 64

    /** Construit le prompt envoyé à la page à partir de l'historique OpenAI. */
    fun buildPrompt(messages: List<Msg>): String {
        val cleaned = messages.mapNotNull { m ->
            val c = m.content.trim()
            if (c.isEmpty()) null else m.role.lowercase() to c
        }
        if (cleaned.isEmpty()) throw NemApiException(
            NemApiException.PROVIDER_NOT_CONFIGURED,
            "La requête ne contient aucun message exploitable."
        )
        val lastUser = cleaned.lastOrNull { it.first == "user" }?.second
            ?: throw NemApiException(NemApiException.PROVIDER_NOT_CONFIGURED, "Aucun message utilisateur dans la requête.")

        // Un seul message utilisateur : on l'envoie tel quel (cas des agents de code,
        // où le prompt est déjà complet et ne doit pas être préfixé).
        if (cleaned.size == 1) return lastUser

        val sb = StringBuilder()
        for ((role, content) in cleaned) {
            when (role) {
                "system" -> sb.append("[consigne système]\n").append(content).append("\n\n")
                "user" -> sb.append(content).append("\n\n")
                "assistant" -> sb.append("[réponse précédente]\n").append(content).append("\n\n")
                else -> sb.append(content).append("\n\n")
            }
        }
        // Pas de plafond arbitraire sur l'historique. Les prompts longs passent
        // par Engine.setPrompt(), qui les injecte en morceaux sans troncature.
        return sb.toString().trim()
    }

    private fun isCancelled(token: () -> Boolean) = token()

    /**
     * Exécute une génération complète. `onDelta` reçoit les fragments au fil de l'eau.
     * Rend le texte complet. Lève une NemApiException avec un code stable sinon.
     */
    suspend fun chat(
        messages: List<Msg>,
        onDelta: (String) -> Unit,
        shouldCancel: () -> Boolean = { false },
        context: Context? = null,
        rawMessages: JSONArray? = null,
        tools: JSONArray? = null,
        qwenIncremental: Boolean = false,
        onKeepAlive: () -> Unit = {}
    ): String {
        if (!AppState.busy.compareAndSet(false, true)) throw NemApiException.busy()
        try {
            return run(messages, onDelta, shouldCancel, context, rawMessages, tools, qwenIncremental, onKeepAlive)
        } finally {
            AppState.busy.set(false)
        }
    }

    private suspend fun run(
        messages: List<Msg>,
        onDelta: (String) -> Unit,
        shouldCancel: () -> Boolean,
        context: Context?,
        rawMessages: JSONArray?,
        tools: JSONArray?,
        qwenIncremental: Boolean,
        onKeepAlive: () -> Unit
    ): String {
        if (!com.nemap.android.web.WebViewHost.isCreated) {
            throw NemApiException.notReady("Le navigateur intégré n'est pas ouvert : ouvre l'app NemApi puis l'onglet du provider.")
        }

        Engine.ensureAdapter()
        val ready = Engine.adapter("ready")
        if (!ready.optBoolean("ok")) {
            if (ready.optBoolean("login_required")) throw NemApiException.login()
            val why = ready.optString("reason", "input_missing")
            throw NemApiException.notReady(
                "La page DeepSeek n'est pas prête ($why). Ouvre l'onglet du provider et vérifie que tu es connecté."
            )
        }

        val qwenPlan = if (qwenIncremental && context != null && rawMessages != null) {
            val url = try { Engine.currentUrl() } catch (_: Throwable) { AppState.pageUrl }
            QwenContext.plan(context, rawMessages, tools, QwenContext.conversationKey(url))
        } else null
        val prompt = qwenPlan?.prompt ?: buildPrompt(messages)
        AppState.promptChars.addAndGet(prompt.length.toLong())
        NemLog.i("dom", "prompt de ${prompt.length} caractères" + if (qwenPlan != null) " (${qwenPlan.mode})" else "")

        Engine.resetStream()

        // 0) pièces jointes éventuelles, AVANT le texte (le site vide le
        //    composeur à chaque fichier ajouté)
        val images = messages.flatMap { it.images }
        if (images.isNotEmpty()) attachImages(images, shouldCancel)

        // 1) écrire le prompt — avec vérification que la page l'a bien pris
        val set = Engine.setPrompt(prompt)
        if (!set.optBoolean("ok")) {
            throw NemApiException(
                NemApiException.SELECTOR_MISSING,
                "Le champ de saisie n'a pas gardé le prompt (${set.optString("reason")} : " +
                    "${set.optInt("got")} c. gardés sur ${set.optInt("want")} attendus)."
            )
        }
        NemLog.i("dom", "prompt écrit (${set.optInt("len")} c.)")

        // 2) attendre que le contrôle d'envoi devienne utilisable
        val budget = minOf(20_000L, 3_000L + prompt.length / 4L)
        val readySend = waitSendReady(budget, shouldCancel)
        if (!readySend) NemLog.w("dom", "contrôle d'envoi inactif après $budget ms → repli Entrée")

        // 3) envoyer
        val sub = Engine.adapter("submit")
        if (!sub.optBoolean("ok")) {
            throw NemApiException(
                NemApiException.SELECTOR_MISSING,
                "Impossible de déclencher l'envoi (${sub.optString("reason")})."
            )
        }
        NemLog.i("dom", "envoi déclenché via ${sub.optString("via")}")

        // 4) suivre la réponse, puis actionner « Continue » si le modèle a
        // atteint sa limite de sortie. Chaque reprise ouvre un nouveau flux;
        // son texte est dédoublonné avant d'être envoyé au client OpenAI.
        var answer = follow(prompt, onDelta, shouldCancel, onKeepAlive)
        var continuationCount = 0
        while (continuationCount < MAX_CONTINUATIONS) {
            val available = waitContinueButton(shouldCancel, if (answer.length >= 6_000) 8_000L else 2_500L, onKeepAlive)
            if (!available) break
            if (isCancelled(shouldCancel)) throw NemApiException.cancelled()
            Engine.resetStream()
            val clicked = Engine.adapter("continueGeneration")
            if (!clicked.optBoolean("ok")) break
            continuationCount++
            NemLog.i("dom", "DeepSeek a affiché « Continue » → reprise ${continuationCount}/${MAX_CONTINUATIONS}")
            val tail = ContinuationTail(answer, onDelta)
            follow(prompt, onDelta = tail::append, shouldCancel = shouldCancel, onKeepAlive = onKeepAlive)
            tail.finish()
            answer = tail.fullText()
        }
        if (continuationCount == MAX_CONTINUATIONS && waitContinueButton(shouldCancel, 8_000L, onKeepAlive)) {
            NemLog.e("dom", "DeepSeek demande encore une reprise après $MAX_CONTINUATIONS continuations")
            throw NemApiException.timeout("DeepSeek a encore affiché « Continue » après $MAX_CONTINUATIONS reprises. La réponse est interrompue par la limite de sortie de la page.")
        }
        if (qwenPlan != null && context != null) {
            val finalUrl = try { Engine.currentUrl() } catch (_: Throwable) { AppState.pageUrl }
            QwenContext.commit(context, qwenPlan, answer, QwenContext.conversationKey(finalUrl))
        }
        return answer
    }

    /**
     * Dépose les images dans le sélecteur de fichiers du composeur, puis
     * attend que le site ait fini de les téléverser (vignettes visibles,
     * aucun indicateur de chargement). Sans cette attente, le site renvoie
     * le message avant que l'image soit prête.
     */
    private suspend fun attachImages(images: List<ImagePart>, shouldCancel: () -> Boolean) {
        Engine.adapter("attachBegin")
        var sent = 0
        for (img in images) {
            val b64 = android.util.Base64.encodeToString(img.bytes, android.util.Base64.NO_WRAP)
            var off = 0
            while (off < b64.length) {
                if (isCancelled(shouldCancel)) throw NemApiException.cancelled()
                val end = minOf(off + CHUNK, b64.length)
                val payload = JSONObject()
                    .put("mime", img.mime)
                    .put("name", img.name)
                    .put("first", off == 0)
                    .put("b64", b64.substring(off, end))
                    .toString()
                // `first` distingue une nouvelle image du morceau suivant
                val r = Engine.adapter("attachChunk", payload, 30_000)
                if (!r.optBoolean("ok")) throw NemApiException(
                    NemApiException.PROVIDER_NOT_CONFIGURED,
                    "Transfert de la pièce jointe interrompu (${r.optString("reason")})."
                )
                off = end
            }
            sent++
        }

        val commit = Engine.adapter("attachCommit", null, 30_000)
        if (!commit.optBoolean("ok")) {
            NemLog.w("dom", "dépôt des pièces jointes refusé (${commit.optString("reason")}) → envoi du texte seul")
            Engine.adapter("attachBegin")
            return
        }
        // `in_input` retombe souvent à 0 : la page vide l'input dès qu'elle a lu
        // les fichiers. C'est `wanted` qui donne le nombre de pièces transmises.
        val inInput = commit.optInt("in_input")
        val wanted = commit.optInt("wanted", 0)
        NemLog.i(
            "dom",
            if (inInput > 0) "pièce(s) jointe(s) déposée(s) : $inInput fichier(s) dans l'entrée"
            else "pièce(s) jointe(s) déposée(s) : $wanted fichier(s) transmis au composeur"
        )

        waitUpload(shouldCancel)
    }

    /**
     * Attend la fin du téléversement des pièces jointes.
     *
     * Avant : on interrogeait le DOM (vignettes, `input.files`) ; quand les
     * sélecteurs ne correspondaient pas, on attendait **toujours** le plafond
     * de 45 s — d'où un premier token figé autour de 46 s pour chaque image.
     *
     * Maintenant : on écoute le réseau (requêtes de téléversement captées par
     * `hook.js`). On sort dès que la requête est terminée, et au bout de
     * [UPLOAD_SILENCE_MS] si la page ne fait aucune requête (pièce jointe
     * traitée en local). Le DOM ne sert plus que de secours.
     */
    private suspend fun waitUpload(shouldCancel: () -> Boolean) {
        try {
            Engine.evalJs("window.__napUploadReset && window.__napUploadReset()")
        } catch (_: Throwable) {
        }
        val t0 = System.currentTimeMillis()
        var lastNote = ""
        while (System.currentTimeMillis() - t0 < UPLOAD_MS) {
            if (isCancelled(shouldCancel)) throw NemApiException.cancelled()
            val st = try {
                Engine.uploadState()
            } catch (_: Throwable) {
                JSONObject().put("seen", false).put("active", 0)
            }
            val seen = st.optBoolean("seen")
            val active = st.optInt("active")
            val elapsed = System.currentTimeMillis() - t0

            if (seen && active == 0) {
                val ok = st.isNull("ok") || st.optBoolean("ok", true)
                NemLog.i("dom", "téléversement terminé en ${elapsed} ms " +
                    "(HTTP ${st.optInt("status")}, ${st.optInt("total")} requête(s))")
                return
            }

            // aucun téléversement réseau : la page gère la pièce jointe en local
            if (!seen && elapsed > UPLOAD_SILENCE_MS) {
                NemLog.i("dom", "aucune requête de téléversement en ${elapsed} ms — pièce jointe traitée en local")
                return
            }

            // secours DOM (vignette déjà affichée)
            if (seen && active > 0 && elapsed > UPLOAD_SILENCE_MS) {
                val dom = try {
                    Engine.adapter("uploadState")
                } catch (_: Throwable) {
                    JSONObject()
                }
                if (dom.optBoolean("ready")) {
                    NemLog.i("dom", "téléversement confirmé par le DOM en ${elapsed} ms")
                    return
                }
                lastNote = "requête active depuis ${elapsed} ms"
            }
            delay(POLL_MS)
        }
        NemLog.w("dom", "téléversement non confirmé après $UPLOAD_MS ms ($lastNote) — envoi quand même")
    }

    private suspend fun waitContinueButton(
        shouldCancel: () -> Boolean,
        timeoutMs: Long,
        onKeepAlive: () -> Unit
    ): Boolean {
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        var disabledDeadline = 0L
        var lastKeepAlive = started
        while (true) {
            val now = System.currentTimeMillis()
            if (now >= deadline && (disabledDeadline == 0L || now >= disabledDeadline)) break
            if (isCancelled(shouldCancel)) throw NemApiException.cancelled()
            try {
                val status = Engine.adapter("continueAvailable", timeoutMs = 1_500)
                if (status.optBoolean("available")) {
                    if (status.optBoolean("enabled", true)) return true
                    if (disabledDeadline == 0L) {
                        disabledDeadline = now + 20_000L
                        NemLog.i("dom", "action « Continue » détectée mais encore désactivée — attente de son activation")
                    }
                }
            } catch (_: Throwable) {
            }
            if (now - lastKeepAlive >= 2_000L) {
                try { onKeepAlive() } catch (_: Throwable) {}
                lastKeepAlive = now
            }
            delay(250)
        }
        if (disabledDeadline != 0L) NemLog.w("dom", "action « Continue » restée désactivée pendant 20 s")
        return false
    }

    private suspend fun waitSendReady(budgetMs: Long, shouldCancel: () -> Boolean): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < budgetMs) {
            if (isCancelled(shouldCancel)) throw NemApiException.cancelled()
            val r = Engine.adapter("sendReady")
            if (r.optBoolean("found") && !r.optBoolean("disabled")) return true
            delay(120)
        }
        return false
    }

    private suspend fun follow(
        prompt: String,
        onDelta: (String) -> Unit,
        shouldCancel: () -> Boolean,
        onKeepAlive: () -> Unit = {}
    ): String {
        val t0 = System.currentTimeMillis()
        var lastKeepAlive = t0
        var acc = ""
        var mode = "hook"
        var lastDom = ""
        var stableSince = 0L
        var sawStream = false
        var lastContinueCheck = 0L

        while (true) {
            if (isCancelled(shouldCancel)) {
                stopPage()
                throw NemApiException.cancelled()
            }
            val now = System.currentTimeMillis()
            if (now - lastKeepAlive >= 10_000L) {
                try { onKeepAlive() } catch (_: Throwable) {}
                lastKeepAlive = now
            }

            if (mode == "hook") {
                val st = Engine.streamState()
                if (st.optBoolean("started")) {
                    sawStream = true
                    val text = st.optString("text", "")
                    if (text.length > acc.length) {
                        onDelta(text.substring(acc.length))
                        acc = text
                    }
                    if (st.optBoolean("done")) {
                        // JSONObject.optString rend la chaîne "null" pour un JSON null :
                        // on teste la nullité réelle du champ.
                        val err = if (st.isNull("error")) "" else st.optString("error", "")
                        if (err.isNotEmpty() && acc.isEmpty()) {
                            val domAnswer = Engine.adapter("answer")
                            val fromDom = if (domAnswer.optBoolean("ok")) domAnswer.optString("text", "") else ""
                            if (fromDom.isNotEmpty()) {
                                NemLog.w("hook", "flux en erreur ($err) mais réponse présente dans le DOM → lecture DOM")
                                onDelta(fromDom)
                                acc = fromDom
                                break
                            }
                            throw NemApiException(
                                if (err == "networkError" || err == "network_error") NemApiException.NETWORK_ERROR else NemApiException.UPSTREAM_ERROR,
                                "Le provider a interrompu la réponse (" +
                                    (if (st.isNull("errorMessage")) err else st.optString("errorMessage", err)) + ")."
                            )
                        }
                        break
                    }
                    // flux muet mais page redevenue inactive → considéré terminé
                    if (now - t0 > FIRST_TOKEN_MS && st.optLong("idle", 0) > STABILITY_MS) {
                        val gen = Engine.adapter("generating").optBoolean("generating")
                        if (!gen) break
                    }
                } else if (now - t0 > FIRST_TOKEN_MS) {
                    NemLog.w("hook", "aucun flux observé après $FIRST_TOKEN_MS ms → lecture DOM")
                    mode = "dom"
                }
            }

            if (mode == "dom") {
                val a = Engine.adapter("answer")
                val txt = if (a.optBoolean("ok")) a.optString("text", "") else ""
                if (txt.isNotEmpty() && txt != lastDom) {
                    lastDom = txt
                    stableSince = now
                }
                val gen = Engine.adapter("generating").optBoolean("generating")
                if (!gen && lastDom.isNotEmpty() && now - stableSince > STABILITY_MS) {
                    if (lastDom.length > acc.length) {
                        onDelta(lastDom.substring(minOf(acc.length, lastDom.length)))
                        acc = lastDom
                    }
                    break
                }
            }

            // Continue may appear before the network wrapper reports [DONE].
            // Watch for it during the stream so a slow finalization cannot leave
            // the client hanging until its own read timeout.
            if ((sawStream || mode == "dom") && now - lastContinueCheck >= 750L) {
                lastContinueCheck = now
                val continuation = try { Engine.adapter("continueAvailable", timeoutMs = 1_000) } catch (_: Throwable) { JSONObject() }
                if (continuation.optBoolean("available") && continuation.optBoolean("enabled", true)) {
                    if (mode == "hook") {
                        val latest = Engine.streamState()
                        val latestText = latest.optString("text", "")
                        if (latestText.length > acc.length) {
                            onDelta(latestText.substring(acc.length))
                            acc = latestText
                        }
                    } else {
                        val latest = Engine.adapter("answer")
                        val latestText = if (latest.optBoolean("ok")) latest.optString("text", "") else ""
                        if (latestText.length > acc.length) {
                            onDelta(latestText.substring(acc.length))
                            acc = latestText
                        }
                    }
                    NemLog.i("dom", "action « Continue » active détectée pendant le flux → reprise immédiate")
                    break
                }
            }

            if (now - t0 > RESPONSE_MS) {
                stopPage()
                throw NemApiException.timeout("Le provider n'a pas terminé la génération dans le délai imparti (${RESPONSE_MS / 1000} s).")
            }
            delay(POLL_MS)
        }

        // fin de flux : si le hook n'a rien donné, on tente le DOM
        if (acc.isBlank()) {
            val a = Engine.adapter("answer")
            if (a.optBoolean("ok")) acc = a.optString("text", "")
        }
        if (acc.isBlank()) {
            throw NemApiException(
                NemApiException.STREAM_INTERRUPTED,
                if (sawStream) "La réponse du provider était vide." else "Aucune réponse détectée sur la page."
            )
        }
        AppState.completionChars.addAndGet(acc.length.toLong())
        return acc
    }

    suspend fun stopPage() {
        try {
            Engine.adapter("stop")
            NemLog.i("dom", "Stop envoyé à la page")
        } catch (t: Throwable) {
            NemLog.w("dom", "Stop impossible : ${t.message}")
        }
    }

    /** Réponse de diagnostic (structure de page, sans contenu de conversation). */
    suspend fun describe(): JSONObject = Engine.adapter("describe")
}
