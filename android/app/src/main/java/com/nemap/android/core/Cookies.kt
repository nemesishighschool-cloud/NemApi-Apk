package com.nemap.android.core

import android.content.Context
import android.webkit.CookieManager

/**
 * Conservation de la session du provider dans les préférences de l'app.
 *
 * `CookieManager` persiste déjà les cookies sur le disque, mais ils sont perdus
 * si les données de l'application sont effacées (réinstallation, remise à zéro
 * de l'émulateur). On garde donc une copie de la chaîne de cookies et on la
 * réinjecte au démarrage du WebView.
 *
 * Ce n'est pas un contournement : la copie ne contient que les cookies déjà
 * présents dans l'app, et une session révoquée côté serveur reste révoquée.
 */
object Cookies {

    private const val PREFS = "nemapi_session"
    private const val KEY = "deepseek_cookie"
    private const val DOMAIN = "https://chat.deepseek.com/"

    /** Cookies actuellement détenus par le WebView. */
    fun snapshot(): String = try {
        CookieManager.getInstance().getCookie(DOMAIN) ?: ""
    } catch (t: Throwable) {
        ""
    }

    /** Copie de secours dans les préférences (appelée périodiquement). */
    fun save(ctx: Context): Boolean {
        val current = snapshot()
        if (current.length < 40) return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, "") == current) return false
        prefs.edit().putString(KEY, current).apply()
        NemLog.i("dom", "session mise en réserve (${current.length} c.)")
        return true
    }

    fun saved(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    /**
     * Réinjecte la copie si le magasin de cookies est vide ou plus pauvre que
     * la réserve. Rend `true` si une réinjection a eu lieu.
     */
    fun restoreIfMissing(ctx: Context): Boolean {
        val saved = saved(ctx)
        if (saved.isEmpty()) return false
        if (snapshot().length >= saved.length) return false
        return try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            saved.split(";").map { it.trim() }.filter { it.contains("=") }
                .forEach { cm.setCookie(DOMAIN, it) }
            cm.flush()
            NemLog.i("dom", "session réinjectée depuis les préférences (${saved.length} c.)")
            true
        } catch (t: Throwable) {
            NemLog.w("dom", "réinjection impossible : ${t.message}")
            false
        }
    }

    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
