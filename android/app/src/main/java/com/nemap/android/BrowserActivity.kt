package com.nemap.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nemap.android.core.AppState
import com.nemap.android.core.NemLog
import com.nemap.android.ui.Ui
import com.nemap.android.ui.Ui.dp
import com.nemap.android.web.WebViewHost

/**
 * Navigateur intégré : le WebView du provider, encadré proprement (coins
 * arrondis, liseré cyan, ombre portée). Le WebView appartient au singleton
 * WebViewHost : il survit à la fermeture de l'Activity, la page continue de
 * travailler pour l'API.
 */
class BrowserActivity : Activity() {

    private lateinit var container: FrameLayout
    private lateinit var urlLabel: TextView
    private lateinit var statusLabel: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            urlLabel.text = shortUrl(WebViewHost.currentUrl)
            statusLabel.text = buildString {
                append("${AppState.observationMode} · ${AppState.requests.get()} req")
                val t = AppState.lastTtftMs.get()
                if (t > 0) append(" · 1er fragment ${t} ms")
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        root.addView(header())
        root.addView(toolbar())

        // ---- cadre du WebView ------------------------------------------
        val wrap = FrameLayout(this).apply {
            setPadding(dp(this@BrowserActivity, 12f), dp(this@BrowserActivity, 4f), dp(this@BrowserActivity, 12f), dp(this@BrowserActivity, 8f))
        }
        container = FrameLayout(this).apply {
            // liseré + coins arrondis + clip des enfants = encadrement net
            background = Ui.gradient(
                this@BrowserActivity,
                intArrayOf(0xFF101B2E.toInt(), 0xFF0B1322.toInt()),
                18f, Ui.alpha(Ui.SKY, 0x70), 1f
            )
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(this@BrowserActivity, 18f).toFloat())
                }
            }
            setPadding(dp(this@BrowserActivity, 2f), dp(this@BrowserActivity, 2f), dp(this@BrowserActivity, 2f), dp(this@BrowserActivity, 2f))
        }
        wrap.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(bottomBar())
        setContentView(root)

        WebViewHost.ensure(this)
        val url = intent?.getStringExtra("url")
        if (url != null && url != WebViewHost.currentUrl) WebViewHost.load(url)
        WebViewHost.bindContainer(container)
        ui.post(tick)
        NemLog.i("dom", "navigateur intégré ouvert")
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(this@BrowserActivity, 16f), dp(this@BrowserActivity, 14f), dp(this@BrowserActivity, 16f), dp(this@BrowserActivity, 8f))

        addView(ImageView(this@BrowserActivity).apply {
            setImageResource(R.drawable.logo_nemapi)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(this@BrowserActivity, 30f), dp(this@BrowserActivity, 30f)))

        val col = LinearLayout(this@BrowserActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(this@BrowserActivity, 10f), 0, 0, 0)
        }
        col.addView(Ui.text(this@BrowserActivity, "DeepSeek", 15f, Ui.TEXT, bold = true))
        col.addView(Ui.text(this@BrowserActivity, "session web · UA ordinateur", 9.5f, Ui.MUTED, mono = true))
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(Ui.pill(this@BrowserActivity, "MODE PC", Ui.INDIGO))

        // quitter le navigateur sans toucher à l'historique de la page
        addView(Ui.text(this@BrowserActivity, "✕", 15f, Ui.ROSE, mono = true, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(this@BrowserActivity, 11f), dp(this@BrowserActivity, 5f), dp(this@BrowserActivity, 11f), dp(this@BrowserActivity, 5f))
            background = Ui.rounded(Ui.alpha(Ui.ROSE, 0x18), 12f, this@BrowserActivity, Ui.alpha(Ui.ROSE, 0x66), 1f)
            isClickable = true
            setOnClickListener { leave() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(this@BrowserActivity, 8f) }
        })
    }

    /**
     * Sortie volontaire : on referme l'écran SANS revenir en arrière dans la
     * page. Le WebView reste vivant, la conversation affichée reste celle en
     * cours.
     */
    private fun leave() {
        NemLog.i("dom", "sortie du navigateur (conversation conservée)")
        finish()
    }

    private fun toolbar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.rounded(Ui.SURFACE, 16f, this@BrowserActivity, Ui.STROKE, 1f)
        setPadding(dp(this@BrowserActivity, 6f), dp(this@BrowserActivity, 5f), dp(this@BrowserActivity, 6f), dp(this@BrowserActivity, 5f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(this@BrowserActivity, 12f)
            rightMargin = dp(this@BrowserActivity, 12f)
        }

        addView(nav("←") { goBack() })
        addView(nav("→") { WebViewHost.webView?.goForward() })
        addView(nav("⟳") { WebViewHost.reload() })

        urlLabel = Ui.text(this@BrowserActivity, shortUrl(WebViewHost.currentUrl), 10.5f, Ui.MUTED, mono = true).apply {
            maxLines = 1
            setPadding(dp(this@BrowserActivity, 8f), 0, dp(this@BrowserActivity, 8f), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(urlLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(nav("◈") {
            WebViewHost.load("https://chat.deepseek.com/")
        })
    }

    private fun nav(glyph: String, onClick: () -> Unit): TextView =
        Ui.text(this, glyph, 14f, Ui.CYAN, mono = true, bold = true).apply {
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.alpha(Ui.CYAN, 0x14), 10f, this@BrowserActivity, Ui.alpha(Ui.CYAN, 0x44), 1f)
            setPadding(dp(this@BrowserActivity, 9f), dp(this@BrowserActivity, 5f), dp(this@BrowserActivity, 9f), dp(this@BrowserActivity, 5f))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(this@BrowserActivity, 5f) }
        }

    private fun bottomBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(this@BrowserActivity, 16f), dp(this@BrowserActivity, 4f), dp(this@BrowserActivity, 12f), dp(this@BrowserActivity, 10f))
        statusLabel = Ui.text(this@BrowserActivity, "—", 9.5f, Ui.MUTED, mono = true)
        addView(statusLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Ui.text(this@BrowserActivity, "Pilotage", 11f, Ui.CYAN, bold = true).apply {
            setPadding(dp(this@BrowserActivity, 12f), dp(this@BrowserActivity, 7f), dp(this@BrowserActivity, 12f), dp(this@BrowserActivity, 7f))
            background = Ui.rounded(Ui.alpha(Ui.CYAN, 0x16), 12f, this@BrowserActivity, Ui.alpha(Ui.CYAN, 0x55), 1f)
            setOnClickListener { leave() }
        })
    }

    private fun shortUrl(u: String): String {
        if (u.isEmpty()) return "—"
        return u.removePrefix("https://").removePrefix("http://").let {
            if (it.length > 42) it.take(40) + "…" else it
        }
    }

    private fun goBack() {
        val wv = WebViewHost.webView
        if (wv != null && wv.canGoBack()) wv.goBack() else finish()
    }

    override fun onResume() {
        super.onResume()
        WebViewHost.bindContainer(container)
        urlLabel.text = shortUrl(WebViewHost.currentUrl)
    }

    override fun onDestroy() {
        // Le WebView n'est PAS détruit : il est seulement détaché de la fenêtre.
        // Le ForegroundService garde le processus vivant, la page continue de vivre.
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
        ui.removeCallbacks(tick)
        WebViewHost.unbindContainer()
        NemLog.i("dom", "navigateur fermé (WebView conservé, page toujours active)")
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val url = intent?.getStringExtra("url") ?: return
        WebViewHost.load(url)
        urlLabel.text = shortUrl(url)
    }

    /**
     * Le bouton Retour du téléphone quitte le navigateur. Il ne navigue
     * JAMAIS dans l'historique : revenir en arrière sur chat.deepseek.com
     * ouvrait une nouvelle conversation, ce qui cassait la session en cours.
     * La navigation dans la page reste possible avec la flèche ← de la barre.
     */
    override fun onBackPressed() {
        leave()
    }
}
