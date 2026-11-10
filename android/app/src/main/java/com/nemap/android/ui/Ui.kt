package com.nemap.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Briques visuelles NemApi : thème néon (bleu profond, cyan, indigo) repris du
 * logo et du dashboard d'administration. Tout est construit par code pour
 * rester dans un seul fichier et éviter les ressources éparpillées.
 */
object Ui {

    // ---- palette -------------------------------------------------------
    val BG = 0xFF070B14.toInt()
    val BG_SOFT = 0xFF0A1120.toInt()
    val SURFACE = 0xFF0E1626.toInt()
    val SURFACE2 = 0xFF152138.toInt()
    val STROKE = 0xFF1F2E47.toInt()
    val TEXT = 0xFFE8EFFB.toInt()
    val MUTED = 0xFF8A9BB5.toInt()
    val CYAN = 0xFF22D3EE.toInt()
    val SKY = 0xFF38BDF8.toInt()
    val INDIGO = 0xFF6366F1.toInt()
    val TEAL = 0xFF2DD4BF.toInt()
    val AMBER = 0xFFF5B544.toInt()
    val ROSE = 0xFFFB7185.toInt()
    val MONO: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun alpha(color: Int, a: Int): Int = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    // ---- formes --------------------------------------------------------
    fun rounded(
        color: Int,
        radiusDp: Float,
        ctx: Context,
        strokeColor: Int = 0,
        strokeDp: Float = 0f
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeColor != 0 && strokeDp > 0f) setStroke(dp(ctx, strokeDp), strokeColor)
    }

    fun gradient(ctx: Context, colors: IntArray, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeColor != 0 && strokeDp > 0f) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    /** Effet d'appui réutilisable (également utilisable hors de ce fichier). */
    fun ripplePublic(content: GradientDrawable, touch: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(touch), content, null)

    private fun ripple(content: GradientDrawable, touch: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(touch), content, null)

    // ---- textes --------------------------------------------------------
    fun text(ctx: Context, t: String, size: Float, color: Int = TEXT, mono: Boolean = false, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (mono) typeface = if (bold) MONO_BOLD else MONO
            else if (bold) setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            includeFontPadding = false
        }

    fun sectionLabel(ctx: Context, t: String): TextView = text(ctx, t.uppercase(), 10f, MUTED, mono = true, bold = true).apply {
        letterSpacing = 0.16f
        setPadding(0, 0, 0, dp(ctx, 8.0f))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(alpha(STROKE, 0xC0))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1.0f)).apply {
            topMargin = dp(ctx, 12.0f)
            bottomMargin = dp(ctx, 12.0f)
        }
    }

    // ---- composants ----------------------------------------------------
    fun card(ctx: Context, accent: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 16f), dp(ctx, 14f), dp(ctx, 16f), dp(ctx, 16f))
        background = rounded(
            SURFACE, 20f, ctx,
            if (accent != 0) alpha(accent, 0x66) else STROKE, 1f
        )
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 12.0f) }
    }

    /** Carte principale : dégradé subtil, liseré cyan/indigo. */
    fun heroCard(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 16f), dp(ctx, 16f), dp(ctx, 16f), dp(ctx, 16f))
        background = gradient(ctx, intArrayOf(0xFF16233D.toInt(), 0xFF0C1526.toInt()), 22f, alpha(SKY, 0x55), 1f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(ctx, 12.0f) }
    }

    fun pill(ctx: Context, label: String, color: Int): TextView =
        text(ctx, label, 10f, color, mono = true, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(dp(ctx, 10f), dp(ctx, 4f), dp(ctx, 10f), dp(ctx, 4f))
            background = rounded(alpha(color, 0x22), 999f, ctx, alpha(color, 0x88), 1f)
        }

    fun button(
        ctx: Context,
        label: String,
        primary: Boolean = false,
        accent: Int = SKY,
        onClick: () -> Unit
    ): TextView = text(ctx, label, 13f, if (primary) SURFACE else TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 14f), dp(ctx, 10f), dp(ctx, 14f), dp(ctx, 10f))
        background = if (primary)
            ripple(gradient(ctx, intArrayOf(SKY, INDIGO), 14f), 0x55FFFFFF)
        else
            ripple(rounded(alpha(accent, 0x1A), 14f, ctx, alpha(accent, 0x66), 1f), alpha(accent, 0x33))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Gros bouton de navigation (icône + libellé empilés). */
    fun tab(ctx: Context, label: String, glyph: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(ctx, 8f), 0, dp(ctx, 6f))
        addView(text(ctx, glyph, 15f, MUTED, mono = true).apply { gravity = Gravity.CENTER })
        addView(text(ctx, label, 10f, MUTED, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 3f), 0, 0)
        })
    }

    fun input(ctx: Context, hint: String, initial: String = "", multiline: Boolean = true): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setText(initial)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(TEXT)
            setHintTextColor(alpha(MUTED, 0xC0))
            typeface = MONO
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 10f))
            background = rounded(SURFACE2, 14f, ctx, STROKE, 1f)
            inputType = InputType.TYPE_CLASS_TEXT or
                if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_URI
            if (multiline) {
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
            includeFontPadding = false
        }

    /** Ligne « libellé / valeur » avec valeur en monospace sélectionnable. */
    fun kv(ctx: Context, key: String, value: String, valueColor: Int = TEXT): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 3f), 0, dp(ctx, 3f))
            addView(text(ctx, key, 11f, MUTED, mono = true), LinearLayout.LayoutParams(dp(ctx, 96f), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text(ctx, value, 11.5f, valueColor, mono = true).apply {
                setTextIsSelectable(true)
                maxLines = 3
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    fun row(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) {
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(ctx, 8f)
            })
        }
    }

    /** Espace vertical de `px` pixels (déjà converti par l'appelant). */
    fun spaceTop(ctx: Context, px: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, px)
    }

    fun spaceBottom(ctx: Context, px: Int): View = spaceTop(ctx, px)

    fun spacer(ctx: Context, hDp: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp))
    }

    fun copy(ctx: Context, value: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NemApi", value))
        } catch (_: Throwable) {
        }
    }
}
