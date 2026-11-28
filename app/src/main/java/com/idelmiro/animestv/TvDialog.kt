package com.idelmiro.animestv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Janela no visual novo (painel escuro arredondado, itens que ficam brancos no foco, botões
 * em pílula). Tem os mesmos métodos do AlertDialog.Builder que o app usa.
 */
class TvDialog(private val act: Activity) {
    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var items: Array<out CharSequence>? = null
    private var itemsListener: DialogInterface.OnClickListener? = null
    private var single = false
    private var checked = -1
    private var custom: View? = null
    private var positive: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
    private var neutral: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
    private var negative: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
    private var cancelable = true
    private var onCancel: DialogInterface.OnCancelListener? = null

    fun setTitle(t: CharSequence?) = apply { title = t }
    fun setMessage(m: CharSequence?) = apply { message = m }
    fun setView(v: View) = apply { custom = v }
    fun setCancelable(c: Boolean) = apply { cancelable = c }
    fun setOnCancelListener(l: DialogInterface.OnCancelListener?) = apply { onCancel = l }
    fun setItems(list: Array<out CharSequence>, l: DialogInterface.OnClickListener?) = apply { items = list; itemsListener = l; single = false }
    fun setSingleChoiceItems(list: Array<out CharSequence>, sel: Int, l: DialogInterface.OnClickListener?) =
        apply { items = list; itemsListener = l; single = true; checked = sel }
    fun setPositiveButton(t: CharSequence, l: DialogInterface.OnClickListener?) = apply { positive = t to l }
    fun setNeutralButton(t: CharSequence, l: DialogInterface.OnClickListener?) = apply { neutral = t to l }
    fun setNegativeButton(t: CharSequence, l: DialogInterface.OnClickListener?) = apply { negative = t to l }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    fun show(): Dialog {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xF51E222B.toInt()) }
            setPadding(dp(14), dp(22), dp(14), dp(16))
        }
        val screenH = act.resources.displayMetrics.heightPixels
        title?.let {
            panel.addView(TextView(act).apply {
                text = it; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                setPadding(dp(12), 0, dp(12), dp(10))
            })
        }
        message?.let {
            val sv = ScrollView(act).apply { isFocusable = false; isVerticalScrollBarEnabled = false }
            sv.addView(TextView(act).apply {
                text = it; textSize = 16f; setTextColor(0xFFC9D1D9.toInt()); setLineSpacing(0f, 1.15f)
                setPadding(dp(12), 0, dp(12), dp(8))
            })
            panel.addView(sv, LinearLayout.LayoutParams(-1, -2))
            sv.post { if (sv.height > screenH * 0.5f) sv.layoutParams = sv.layoutParams.apply { height = (screenH * 0.5f).toInt() } }
        }
        custom?.let { (it.parent as? ViewGroup)?.removeView(it); panel.addView(it) }

        var firstFocus: View? = null
        items?.let { list ->
            val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isFillViewport = false }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val rows = ArrayList<TextView>()
            list.forEachIndexed { i, label ->
                val row = rowView(label)
                if (single) mark(row, i == checked)
                row.setOnClickListener {
                    if (single) { rows.forEachIndexed { k, r -> mark(r, k == i) } }
                    itemsListener?.onClick(d, i)
                    if (!single) d.dismiss()
                }
                rows += row
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
            sv.addView(col)
            panel.addView(sv, LinearLayout.LayoutParams(-1, -2))
            sv.post { if (sv.height > screenH * 0.62f) sv.layoutParams = sv.layoutParams.apply { height = (screenH * 0.62f).toInt() } }
            firstFocus = rows.getOrNull(if (single && checked in rows.indices) checked else 0)
        }

        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(14), dp(8), 0) }
        for ((pair, which, primary) in listOf(
            Triple(positive, DialogInterface.BUTTON_POSITIVE, true),
            Triple(neutral, DialogInterface.BUTTON_NEUTRAL, false),
            Triple(negative, DialogInterface.BUTTON_NEGATIVE, false))) {
            val (label, l) = pair ?: continue
            val b = pill(label, primary)
            b.setOnClickListener { l?.onClick(d, which); d.dismiss() }
            bar.addView(b)
            if (firstFocus == null) firstFocus = b
        }
        if (bar.childCount > 0) panel.addView(bar)

        d.setContentView(panel)
        d.setCancelable(cancelable)
        onCancel?.let { d.setOnCancelListener(it) }
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0.65f)
            val dm = act.resources.displayMetrics
            val portrait = act.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            w.setLayout((dm.widthPixels * if (portrait) 0.92f else 0.5f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
        firstFocus?.requestFocus()
        return d
    }

    private val textColors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(0xFF0D0F14.toInt(), Color.WHITE))

    private fun rowView(label: CharSequence) = TextView(act).apply {
        text = label
        textSize = 17f
        setTextColor(textColors)
        isFocusable = true; isClickable = true
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFFF2F3F5.toInt()) })
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    /** Opção escolhida (lista de uma escolha): marcada com ● em laranja. */
    private fun mark(row: TextView, on: Boolean) {
        val base = row.text.toString().removePrefix("●  ").removePrefix("○  ")
        row.text = (if (on) "●  " else "○  ") + base
    }

    private fun pill(label: CharSequence, primary: Boolean) = Button(act).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        setPadding(dp(20), 0, dp(20), 0)
        val h = dp(44)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = h / 2f; setColor(Color.WHITE) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = h / 2f; setColor(if (primary) 0x40FFFFFF else 0x22FFFFFF) })
        }
        setTextColor(textColors)
        layoutParams = LinearLayout.LayoutParams(-2, h).apply { marginEnd = dp(10) }
    }
}

/** Na TV, tudo um pouco menor (como nos apps de streaming): mesma tela, mais espaço. */
object TvScale {
    const val FACTOR = 0.72f

    fun wrap(base: Context): Context {
        if (!Device.isTv(base)) return base
        val res = base.resources
        val c = Configuration(res.configuration)
        val dm = res.displayMetrics
        val dpi = (c.densityDpi * FACTOR).toInt().coerceAtLeast(120)
        c.densityDpi = dpi
        val density = dpi / 160f
        c.screenWidthDp = (dm.widthPixels / density).toInt()
        c.screenHeightDp = (dm.heightPixels / density).toInt()
        c.smallestScreenWidthDp = minOf(c.screenWidthDp, c.screenHeightDp)
        return base.createConfigurationContext(c)
    }
}
