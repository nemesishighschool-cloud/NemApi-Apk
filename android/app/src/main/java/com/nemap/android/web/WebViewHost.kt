package com.nemap.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nemap.android.core.AppState
import com.nemap.android.core.Cookies
import com.nemap.android.core.NemLog

/**
 * Hôte unique du WebView : créé une fois, jamais détruit, détaché de
 * l'Activity quand elle s'arrête. C'est ce qui permet à la page de continuer
 * à travailler quand l'utilisateur quitte l'application (le ForegroundService
 * garde le processus vivant).
 */
object WebViewHost {

    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    @Volatile
    var webView: WebView? = null
        private set

    @Volatile
    var currentUrl: String = "https://chat.deepseek.com/"
        private set

    private var container: ViewGroup? = null
    private var appContext: Context? = null

    val isCreated: Boolean get() = webView != null

    fun ensure(context: Context): WebView {
        webView?.let { attach(it); return it }
        val ctx = context.applicationContext
        appContext = ctx
        Engine.init(ctx.assets)

        val wv = WebView(ctx)
        @SuppressLint("SetJavaScriptEnabled")
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.userAgentString = DESKTOP_UA
        s.mediaPlaybackRequiresUserGesture = false
        @Suppress("DEPRECATION")
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(false)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        // si le magasin de cookies a été vidé, on remet la copie de réserve
        Cookies.restoreIfMissing(ctx)

        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Hook réseau installé AVANT les scripts de la page (document-start).
        installDocumentStart(wv)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                currentUrl = url ?: currentUrl
                AppState.pageUrl = currentUrl
                // repli si le document-start n'est pas supporté par le provider WebView
                view?.evaluateJavascript(Engine.asset("js/hook.js"), null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                currentUrl = url ?: currentUrl
                AppState.pageUrl = currentUrl
                view?.evaluateJavascript(Engine.asset("js/hook.js"), null)
                view?.evaluateJavascript(Engine.asset("js/adapter-deepseek.js"), null)
                NemLog.i("dom", "page chargée : ${hostOf(currentUrl)}")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }

        webView = wv
        AppState.webViewProvider = describeProvider()
        AppState.webViewIsGoogle = AppState.webViewProvider.contains("Google", true)
        NemLog.i("dom", "WebView provider : ${AppState.webViewProvider}")

        attach(wv)
        wv.loadUrl(currentUrl)
        return wv
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (t: Throwable) {
        url
    }

    private fun installDocumentStart(wv: WebView) {
        val script = Engine.asset("js/hook.js")
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
                NemLog.i("dom", "hook réseau : document-start (API dédiée)")
            } else {
                NemLog.w("dom", "document-start indisponible → injection onPageStarted")
            }
        } catch (t: Throwable) {
            NemLog.w("dom", "document-start échoué : ${t.message}")
        }
    }

    private fun attach(wv: WebView) {
        val c = container ?: return
        if (wv.parent === c) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        c.addView(wv)
    }

    fun bindContainer(c: ViewGroup) {
        container = c
        webView?.let { attach(it) }
    }

    fun unbindContainer() {
        val wv = webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        container = null
    }

    fun load(url: String) {
        currentUrl = url
        AppState.pageUrl = url
        webView?.loadUrl(url)
    }

    fun reload() {
        webView?.reload()
    }

    /** Version du provider WebView installé (Google WebView ou AOSP). */
    fun describeProvider(): String {
        return try {
            val pkg = WebView.getCurrentWebViewPackage()
            if (pkg == null) "inconnu"
            else "${pkg.packageName} ${pkg.versionName}"
        } catch (t: Throwable) {
            "inconnu (${t.message})"
        }
    }

    /** Adresse de la page courante, pour l'affichage. */
    fun pageTitle(): String = try {
        webView?.title ?: ""
    } catch (t: Throwable) {
        ""
    }
}
