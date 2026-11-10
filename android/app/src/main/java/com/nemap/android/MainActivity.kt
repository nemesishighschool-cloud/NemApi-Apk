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
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatInput: android.widget.EditText
    private lateinit var attachChip: TextView
    private var pendingImage: ImagePart? = null
    private val chatHistory = ArrayList<Msg>()
    private var chatting = false
    private lateinit var logView: TextView

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
        pages = listOf(
            scroll(pilotagePage()),
            chatPage(),
            scroll(apiPage()),
            scroll(journalPage())
        )
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
            Triple("Chat", "✦", 1),
            Triple("API", "⌁", 2),
            Triple("Journal", "≡", 3)
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

        // renvoi vers l'onglet dédié
        val hint = Ui.card(this)
        hint.addView(Ui.sectionLabel(this, "Chat"))
        hint.addView(Ui.text(this, "Les essais se font dans l'onglet Chat : conversation complète, images jointes et réponse en direct.", 11f, Ui.MUTED))
        hint.addView(Ui.spaceTop(this, dp(this, 10f)))
        hint.addView(Ui.button(this, "Ouvrir le chat", accent = Ui.CYAN) { select(1) })
        page.addView(hint)

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

    // ---- onglet Chat ----------------------------------------------------

    private fun chatPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(this@MainActivity, 16f), dp(this@MainActivity, 10f), dp(this@MainActivity, 12f), dp(this@MainActivity, 6f))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, "Chat de test", 14f, Ui.TEXT, bold = true))
        col.addView(Ui.text(this, "même chemin que l'API · stream direct", 9.5f, Ui.MUTED, mono = true))
        head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(this, "effacer", 11f, Ui.CYAN, bold = true).apply {
            setPadding(dp(this@MainActivity, 12f), dp(this@MainActivity, 6f), dp(this@MainActivity, 12f), dp(this@MainActivity, 6f))
            background = Ui.rounded(Ui.alpha(Ui.CYAN, 0x16), 12f, this@MainActivity, Ui.alpha(Ui.CYAN, 0x55), 1f)
            setOnClickListener { resetChat() }
        })
        root.addView(head)

        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this@MainActivity, 12f), dp(this@MainActivity, 4f), dp(this@MainActivity, 12f), dp(this@MainActivity, 12f))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(chatList)
        }
        root.addView(chatScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // pièce jointe en attente
        attachChip = Ui.text(this, "", 10.5f, Ui.CYAN, mono = true).apply {
            setPadding(dp(this@MainActivity, 10f), dp(this@MainActivity, 6f), dp(this@MainActivity, 10f), dp(this@MainActivity, 6f))
            background = Ui.rounded(Ui.alpha(Ui.CYAN, 0x18), 10f, this@MainActivity, Ui.alpha(Ui.CYAN, 0x66), 1f)
            visibility = View.GONE
            setOnClickListener { pendingImage = null; updateAttachChip() }
        }
        root.addView(attachChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(this@MainActivity, 16f)
            bottomMargin = dp(this@MainActivity, 4f)
        })

        // barre de saisie
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(this@MainActivity, 10f), dp(this@MainActivity, 8f), dp(this@MainActivity, 10f), dp(this@MainActivity, 10f))
            setBackgroundColor(Ui.BG_SOFT)
        }
        bar.addView(attachButton())
        chatInput = Ui.input(this, "Message…", "").apply {
            minLines = 1
            maxLines = 4
            setPadding(dp(this@MainActivity, 12f), dp(this@MainActivity, 9f), dp(this@MainActivity, 12f), dp(this@MainActivity, 9f))
        }
        bar.addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(this@MainActivity, 8f)
            rightMargin = dp(this@MainActivity, 8f)
        })
        bar.addView(sendButton())
        root.addView(bar)

        chatList.addView(welcomeBubble())
        return root
    }

    private fun attachButton(): TextView = Ui.text(this, "＋", 20f, Ui.CYAN, mono = true, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(this@MainActivity, 11f), dp(this@MainActivity, 4f), dp(this@MainActivity, 11f), dp(this@MainActivity, 6f))
        background = Ui.rounded(Ui.alpha(Ui.CYAN, 0x18), 14f, this@MainActivity, Ui.alpha(Ui.CYAN, 0x66), 1f)
        setOnClickListener { chooseImage() }
    }

    private fun sendButton(): TextView = Ui.text(this, "↑", 20f, Ui.SURFACE, mono = true, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(this@MainActivity, 13f), dp(this@MainActivity, 4f), dp(this@MainActivity, 13f), dp(this@MainActivity, 6f))
        background = Ui.ripplePublic(Ui.gradient(this@MainActivity, intArrayOf(Ui.SKY, Ui.INDIGO), 14f), 0x55FFFFFF)
        setOnClickListener { chatSend() }
    }

    private fun welcomeBubble(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this@MainActivity, 14f), dp(this@MainActivity, 12f), dp(this@MainActivity, 14f), dp(this@MainActivity, 14f))
            background = Ui.gradient(this@MainActivity, intArrayOf(0xFF16233D.toInt(), 0xFF0C1526.toInt()), 18f, Ui.alpha(Ui.SKY, 0x44), 1f)
        }
        card.addView(Ui.text(this, "Pose une question, joins une image", 12.5f, Ui.TEXT, bold = true))
        card.addView(Ui.text(this, "Les réponses passent par la même page DeepSeek que l'API : streaming réel, historique conservé.", 10.5f, Ui.MUTED).apply {
            setPadding(0, dp(this@MainActivity, 4f), 0, 0)
        })
        return card
    }

    /** Bulle de message ; rend le TextView pour pouvoir le remplir en direct. */
    private fun addBubble(text: String, mine: Boolean): TextView {
        val tv = Ui.text(this, text, 13f, if (mine) 0xFF08121F.toInt() else Ui.TEXT)
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(this@MainActivity, 13f), dp(this@MainActivity, 10f), dp(this@MainActivity, 13f), dp(this@MainActivity, 10f))
        val maxW = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        tv.maxWidth = maxW
        tv.background = if (mine)
            Ui.gradient(this, intArrayOf(Ui.SKY, Ui.INDIGO), 16f)
        else
            Ui.rounded(Ui.SURFACE, 16f, this, Ui.alpha(Ui.CYAN, 0x44), 1f)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(this@MainActivity, 3f), 0, dp(this@MainActivity, 3f))
        }
        row.addView(tv)
        chatList.addView(row)
        scrollChat()
        return tv
    }

    private fun scrollChat() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun resetChat() {
        chatHistory.clear()
        chatting = false
        chatList.removeAllViews()
        chatList.addView(welcomeBubble())
        pendingImage = null
        updateAttachChip()
    }

    private fun updateAttachChip() {
        val img = pendingImage
        if (img == null) {
            attachChip.visibility = View.GONE
        } else {
            val ko = (img.bytes.size / 1024.0)
            attachChip.text = "📎 ${img.name} · ${String.format("%.0f", ko)} Ko   ✕"
            attachChip.visibility = View.VISIBLE
        }
    }

    private fun chooseImage() {
        val options = arrayOf("Choisir une image…", "Image de test (dégradé néon)")
        android.app.AlertDialog.Builder(this)
            .setTitle("Joindre une image")
            .setItems(options) { _, which ->
                if (which == 0) {
                    val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    try {
                        startActivityForResult(Intent.createChooser(i, "Choisir une image"), 900)
                    } catch (t: Throwable) {
                        pendingImage = testImage()
                        updateAttachChip()
                    }
                } else {
                    pendingImage = testImage()
                    updateAttachChip()
                }
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 900 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val mime = contentResolver.getType(uri) ?: "image/png"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                android.widget.Toast.makeText(this, "Image illisible", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            if (bytes.size > 6 * 1024 * 1024) {
                android.widget.Toast.makeText(this, "Image trop lourde (max 6 Mo)", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val ext = when {
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("webp") -> "webp"
                mime.contains("gif") -> "gif"
                else -> "png"
            }
            pendingImage = ImagePart(mime, "photo.$ext", bytes)
            updateAttachChip()
        } catch (t: Throwable) {
            android.widget.Toast.makeText(this, "Lecture impossible : ${t.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun chatSend() {
        if (chatting) return
        val text = chatInput.text.toString().trim()
        val img = pendingImage
        if (text.isEmpty() && img == null) return
        val body = text.ifEmpty { "Décris cette image." }
        val msg = Msg("user", body, if (img != null) listOf(img) else emptyList())
        chatHistory.add(msg)

        var shown = body
        if (img != null) shown += "\n📎 " + img.name
        addBubble(shown, mine = true)

        chatInput.setText("")
        pendingImage = null
        updateAttachChip()

        val reply = addBubble("· · ·", mine = false)
        chatting = true
        scope.launch {
            try {
                var acc = ""
                val out = DeepSeek.chat(chatHistory.toList(), onDelta = { d ->
                    acc += d
                    reply.text = acc
                    scrollChat()
                })
                reply.text = out.ifEmpty { "(réponse vide)" }
                chatHistory.add(Msg("assistant", out))
            } catch (e: NemApiException) {
                reply.setTextColor(Ui.ROSE)
                reply.text = "⚠︎ ${e.code}\n${e.message}"
                if (chatHistory.isNotEmpty()) chatHistory.removeAt(chatHistory.size - 1)
            } catch (t: Throwable) {
                reply.setTextColor(Ui.ROSE)
                reply.text = "⚠︎ ${t.message}"
                if (chatHistory.isNotEmpty()) chatHistory.removeAt(chatHistory.size - 1)
            } finally {
                chatting = false
                scrollChat()
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
