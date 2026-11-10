package com.nemap.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nemap.android.core.AppState
import com.nemap.android.core.DeepSeek
import com.nemap.android.core.ImagePart
import com.nemap.android.core.Msg
import com.nemap.android.core.NemApiException
import com.nemap.android.core.NemLog
import com.nemap.android.service.BridgeService
import com.nemap.android.ui.Ui
import com.nemap.android.ui.Ui.dp
import com.nemap.android.web.WebViewHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Écran principal : thème néon NemApi, trois onglets (Pilotage, API, Journal).
 * Tout est rafraîchi toutes les 2 s à partir de AppState.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusPill: TextView
    private lateinit var tabs: List<Pair<View, View>>       // (conteneur, indicateur)
    private lateinit var pages: List<View>
    private var current = 0

    private lateinit var vService: TextView
    private lateinit var vProvider: TextView
    private lateinit var vMode: TextView
    private lateinit var vPage: TextView
    private lateinit var vReqs: TextView
    private lateinit var vBase: TextView
    private lateinit var vDash: TextView
    private lateinit var vTtft: TextView
    private lateinit var vTotal: TextView
    private lateinit var vChunks: TextView
    private lateinit var vStats: TextView
    private lateinit var vQuick: TextView
    private lateinit var logView: TextView
    private lateinit var promptInput: android.widget.EditText

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        askNotifications()
        handler.post(refresh)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Extras de développement : `start_service` et `open_url` (adb, tests). */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra("start_service", false) == true) startServiceCompat(BridgeService.ACTION_START)
        i.getStringExtra("open_url")?.let { openBrowser(it) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppState.webViewProvider = WebViewHost.describeProvider()
        render()
    }

    // =====================================================================
    //  Construction de l'interface
    // =====================================================================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        root.addView(header())

        val content = FrameLayout(this)
        pages = listOf(scroll(pilotagePage()), scroll(apiPage()), scroll(journalPage()))
        pages.forEach {
            content.addView(it, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { it.visibility = View.GONE })
        }
        pages[0].visibility = View.VISIBLE
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(tabBar())
        root.addView(footer())
        return root
    }

    private fun header(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(this@MainActivity, 18f), dp(this@MainActivity, 18f), dp(this@MainActivity, 16f), dp(this@MainActivity, 10f))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.logo_nemapi)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        bar.addView(logo, LinearLayout.LayoutParams(dp(this, 44f), dp(this, 44f)))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this@MainActivity, 12f), 0, 0, 0)
        }
        col.addView(Ui.text(this, "NemApi", 22f, Ui.TEXT, bold = true))
        col.addView(Ui.text(this, "passerelle OpenAI locale · DeepSeek web", 10.5f, Ui.MUTED, mono = true))
        bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        statusPill = Ui.pill(this, "ARRÊTÉ", Ui.ROSE)
        bar.addView(statusPill)
        return bar
    }

    private fun tabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BG_SOFT)
        }
        val defs = listOf(
            Triple("Pilotage", "◈", 0),
            Triple("API", "⌁", 1),
            Triple("Journal", "≡", 2)
        )
        val list = ArrayList<Pair<View, View>>()
        for ((label, glyph, index) in defs) {
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                setOnClickListener { select(index) }
            }
            val tab = Ui.tab(this, label, glyph)
            holder.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val indicator = View(this).apply {
                setBackgroundColor(if (index == 0) Ui.CYAN else Color.TRANSPARENT)
            }
            holder.addView(indicator, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 2f)
            ))
            bar.addView(holder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.add(holder to indicator)
        }
        tabs = list
        return bar
    }

    private fun footer(): View =
        Ui.text(this, "v${AppState.VERSION} · port ${AppState.API_PORT} · un seul provider actif", 9.5f, Ui.alpha(Ui.MUTED, 0xB0), mono = true)
            .apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(this@MainActivity, 6f), 0, dp(this@MainActivity, 8f))
                setBackgroundColor(Ui.BG_SOFT)
            }

    private fun select(index: Int) {
        current = index
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, (holder, indicator) ->
            indicator.setBackgroundColor(if (i == index) Ui.CYAN else Color.TRANSPARENT)
            val tab = (holder as LinearLayout).getChildAt(0) as LinearLayout
            (tab.getChildAt(0) as TextView).setTextColor(if (i == index) Ui.CYAN else Ui.MUTED)
            (tab.getChildAt(1) as TextView).setTextColor(if (i == index) Ui.TEXT else Ui.MUTED)
        }
        render()
    }

    private fun scroll(view: View): ScrollView = ScrollView(this).apply {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        addView(view)
    }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(this@MainActivity, 16f), dp(this@MainActivity, 8f), dp(this@MainActivity, 16f), dp(this@MainActivity, 20f))
    }

    // ---- onglet Pilotage ------------------------------------------------
    private fun pilotagePage(): View {
        val page = page()

        // état du service
        val hero = Ui.heroCard(this)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.text(this, "Service", 15f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        vService = Ui.pill(this, "—", Ui.MUTED)
        head.addView(vService)
        hero.addView(head)
        hero.addView(Ui.text(this, "ForegroundService + WebView persistant : l'app continue de répondre quand l'écran est ailleurs.", 11f, Ui.MUTED).apply {
            setPadding(0, dp(this@MainActivity, 6f), 0, dp(this@MainActivity, 12f))
        })
        hero.addView(
            Ui.row(
                this,
                Ui.button(this, "Démarrer", primary = true) { startServiceCompat(BridgeService.ACTION_START) },
                Ui.button(this, "Arrêter", accent = Ui.ROSE) { startServiceCompat(BridgeService.ACTION_STOP) }
            )
        )
        hero.addView(Ui.spaceBottom(this, dp(this, 10f)))
        hero.addView(Ui.kv(this, "trafic", "—").also { vReqs = it.getChildAt(1) as TextView })
        page.addView(hero)

        // provider
        val prov = Ui.card(this, Ui.CYAN)
        prov.addView(Ui.sectionLabel(this, "Provider"))
        val prow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(this@MainActivity, 10f))
        }
        prow.addView(Ui.pill(this, "DEEPSEEK", Ui.CYAN), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        prow.addView(Ui.text(this, "seul provider actif", 10f, Ui.MUTED, mono = true).apply {
            setPadding(dp(this@MainActivity, 10f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        prov.addView(prow)
        prov.addView(Ui.kv(this, "état", "—").also { vProvider = it.getChildAt(1) as TextView })
        prov.addView(Ui.kv(this, "mode", "—").also { vMode = it.getChildAt(1) as TextView })
        prov.addView(Ui.kv(this, "page", "—").also { vPage = it.getChildAt(1) as TextView })
        prov.addView(Ui.spaceTop(this, dp(this, 12f)))
        prov.addView(Ui.button(this, "Ouvrir le navigateur DeepSeek", primary = true) {
            openBrowser("https://chat.deepseek.com/")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(prov)

        // test rapide
        val test = Ui.card(this)
        test.addView(Ui.sectionLabel(this, "Test rapide"))
        promptInput = Ui.input(this, "Écris un prompt…", "Réponds en une phrase : quel jour sommes-nous ?")
        test.addView(promptInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        test.addView(Ui.spaceTop(this, dp(this, 10f)))
        test.addView(
            Ui.row(
                this,
                Ui.button(this, "Envoyer", primary = true) { quickTest(image = false) },
                Ui.button(this, "Tester une image", accent = Ui.INDIGO) { quickTest(image = true) }
            )
        )
        vQuick = Ui.text(this, "", 11.5f, Ui.SKY, mono = true).apply { setPadding(0, dp(this@MainActivity, 10f), 0, 0) }
        test.addView(vQuick)
        page.addView(test)

        return page
    }

    // ---- onglet API -----------------------------------------------------
    private fun apiPage(): View {
        val page = page()

        val access = Ui.card(this, Ui.INDIGO)
        access.addView(Ui.sectionLabel(this, "Point d'accès OpenAI"))
        access.addView(Ui.kv(this, "base_url", "—").also { vBase = it.getChildAt(1) as TextView })
        access.addView(Ui.kv(this, "dashboard", "—").also { vDash = it.getChildAt(1) as TextView })
        access.addView(Ui.spaceTop(this, dp(this, 12f)))
        access.addView(
            Ui.row(
                this,
                Ui.button(this, "Copier l'URL", accent = Ui.SKY) { Ui.copy(this@MainActivity, vBase.text.toString()) },
                Ui.button(this, "Dashboard", accent = Ui.TEAL) {
                    openBrowser("http://127.0.0.1:${AppState.API_PORT}/dashboard")
                }
            )
        )
        page.addView(access)

        val models = Ui.card(this)
        models.addView(Ui.sectionLabel(this, "Modèles exposés"))
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(this@MainActivity, 2f), 0, 0)
        }
        chips.addView(Ui.pill(this, "deepseek-chat", Ui.CYAN))
        chips.addView(Ui.pill(this, "deepseek-reasoner", Ui.INDIGO).apply {
            setPadding(dp(this@MainActivity, 10f), dp(this@MainActivity, 4f), dp(this@MainActivity, 10f), dp(this@MainActivity, 4f))
            setBackgroundColor(Color.TRANSPARENT)
        })
        models.addView(chips)
        models.addView(Ui.text(this, "POST /v1/chat/completions · /v1/responses · GET /v1/models", 10f, Ui.MUTED, mono = true).apply {
            setPadding(0, dp(this@MainActivity, 10f), 0, 0)
        })
        page.addView(models)

        val lat = Ui.card(this, Ui.TEAL)
        lat.addView(Ui.sectionLabel(this, "Latence"))
        val trio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        trio.addView(metric("premier fragment", "—").let { vTtft = it.second; it.first }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trio.addView(metric("total", "—").let { vTotal = it.second; it.first }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trio.addView(metric("fragments", "—").let { vChunks = it.second; it.first }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        lat.addView(trio)
        vStats = Ui.text(this, "—", 10f, Ui.MUTED, mono = true).apply { setPadding(0, dp(this@MainActivity, 10f), 0, 0) }
        lat.addView(vStats)
        page.addView(lat)

        return page
    }

    private fun metric(label: String, value: String): Pair<LinearLayout, TextView> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(this@MainActivity, 4f), 0, 0)
        }
        val v = Ui.text(this, value, 17f, Ui.TEAL, mono = true, bold = true)
        col.addView(v)
        col.addView(Ui.text(this, label, 9.5f, Ui.MUTED, mono = true).apply {
            setPadding(0, dp(this@MainActivity, 2f), 0, 0)
        })
        return col to v
    }

    // ---- onglet Journal -------------------------------------------------
    private fun journalPage(): View {
        val page = page()
        val card = Ui.card(this)
        card.addView(Ui.sectionLabel(this, "Journal technique"))
        logView = Ui.text(this, "—", 10.5f, Ui.MUTED, mono = true)
        card.addView(logView)
        card.addView(Ui.spaceTop(this, dp(this, 12f)))
        card.addView(Ui.button(this, "Rafraîchir", accent = Ui.CYAN) { render() })
        page.addView(card)

        val note = Ui.card(this)
        note.addView(Ui.sectionLabel(this, "Confidentialité"))
        note.addView(Ui.text(this, "Aucun contenu de conversation n'est journalisé : seules les étapes techniques, les tailles et les codes d'erreur apparaissent.", 11f, Ui.MUTED))
        page.addView(note)
        return page
    }

    // =====================================================================
    //  Actions
    // =====================================================================

    private fun startServiceCompat(action: String) {
        val intent = Intent(this, BridgeService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun openBrowser(url: String? = null) {
        val i = Intent(this, BrowserActivity::class.java)
        if (url != null) i.putExtra("url", url)
        startActivity(i)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    /** Petite image PNG générée à la volée (dégradé néon + motif) pour tester l'envoi. */
    private fun testImage(): ImagePart {
        val w = 320
        val h = 320
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Ui.SKY, Ui.INDIGO, Ui.CYAN), null, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.textSize = 54f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        canvas.drawText("7", w / 2f, h / 2f + 40f, paint)
        paint.textSize = 22f
        canvas.drawText("NemApi", w / 2f, h / 2f + 90f, paint)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return ImagePart("image/png", "nemapi-test.png", bos.toByteArray())
    }

    private fun quickTest(image: Boolean) {
        val prompt = promptInput.text.toString().trim().ifEmpty { "Décris cette image en une phrase." }
        vQuick.setTextColor(Ui.AMBER)
        vQuick.text = if (image) "Envoi avec image…" else "Envoi en cours…"
        scope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val imgs = if (image) listOf(testImage()) else emptyList()
                val ask = if (image) "$prompt (image jointe : un carré dégradé bleu-cyan avec le chiffre 7 et le mot NemApi)" else prompt
                val out = DeepSeek.chat(listOf(Msg("user", ask, imgs)), onDelta = {})
                val ms = System.currentTimeMillis() - t0
                vQuick.setTextColor(Ui.TEAL)
                vQuick.text = "OK en $ms ms · ${out.length} c.${if (image) " · image jointe" else ""}\n\n${out.take(400)}"
            } catch (e: NemApiException) {
                vQuick.setTextColor(Ui.ROSE)
                vQuick.text = "Erreur ${e.code} : ${e.message}"
            } catch (t: Throwable) {
                vQuick.setTextColor(Ui.ROSE)
                vQuick.text = "Erreur : ${t.message}"
            }
        }
    }

    // =====================================================================
    //  Rafraîchissement
    // =====================================================================

    private fun render() {
        val running = AppState.serviceRunning
        statusPill.text = if (running) "ACTIF" else "ARRÊTÉ"
        statusPill.setTextColor(if (running) Ui.TEAL else Ui.ROSE)
        statusPill.background = Ui.rounded(
            Ui.alpha(if (running) Ui.TEAL else Ui.ROSE, 0x22), 999f, this,
            Ui.alpha(if (running) Ui.TEAL else Ui.ROSE, 0x88), 1f
        )

        vService.text = if (running) "en marche" else "à l'arrêt"
        vService.setTextColor(if (running) Ui.TEAL else Ui.ROSE)

        val ips = AppState.ipAddresses()
        val ip = ips.firstOrNull() ?: "127.0.0.1"

        vReqs.text = "${AppState.requests.get()} requêtes · ${AppState.errors.size} code(s) d'erreur"
        vProvider.text = "${AppState.providerLabel} (${AppState.activeProvider})"
        vMode.text = AppState.observationMode
        vPage.text = AppState.pageUrl.ifEmpty { "—" }
        vBase.text = "http://$ip:${AppState.API_PORT}/v1"
        vDash.text = "http://$ip:${AppState.API_PORT}/dashboard"

        val t = AppState.lastTtftMs.get()
        val tot = AppState.lastTotalMs.get()
        vTtft.text = if (t > 0) "$t ms" else "—"
        vTotal.text = if (tot > 0) "$tot ms" else "—"
        vChunks.text = if (AppState.lastChunks.get() > 0) "${AppState.lastChunks.get()}" else "—"

        vStats.text = try {
            val j = JSONObject(AppState.latencyJson())
            fun stat(name: String): String {
                val o = j.optJSONObject(name) ?: return "$name —"
                return "$name  moy ${o.optLong("avg")} ms · p50 ${o.optLong("p50")} · p90 ${o.optLong("p90")} · min ${o.optLong("min")} · max ${o.optLong("max")} (n=${o.optInt("n")})"
            }
            if (j.optJSONObject("ttft_ms") == null) "aucun appel mesuré pour l'instant"
            else stat("ttft_ms") + "\n" + stat("total_ms")
        } catch (e: Throwable) {
            "—"
        }

        logView.text = NemLog.tail(20).reversed().joinToString("\n") {
            "${it.at} ${it.level.first()} [${it.category}] ${it.message}"
        }
    }
}
