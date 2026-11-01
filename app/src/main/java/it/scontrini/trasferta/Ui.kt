package it.scontrini.trasferta

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.col(id: Int): Int = ContextCompat.getColor(this, id)

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h).apply { topMargin = top }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

fun Context.txt(s: CharSequence, size: Float = 15f, color: Int = R.color.ink, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(col(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.cap(s: String): TextView = txt(s.uppercase(), 11.5f, R.color.ink2).apply { letterSpacing = 0.07f }

fun Context.mono(s: CharSequence, size: Float = 15f, color: Int = R.color.ink, bold: Boolean = false): TextView =
    txt(s, size, color, bold).apply { typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL) }

fun Context.btn(s: String, primary: Boolean = true, onClick: (View) -> Unit): MaterialButton {
    val b = if (primary) MaterialButton(this)
    else MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setTextColor(col(R.color.ink)); strokeColor = ColorStateList.valueOf(col(R.color.line))
    }
    b.text = s; b.isAllCaps = false; b.textSize = 15f
    b.minHeight = dp(52); b.insetTop = 0; b.insetBottom = 0
    b.cornerRadius = dp(8)
    b.setOnClickListener(onClick)
    return b
}

fun Context.card(): Pair<MaterialCardView, LinearLayout> {
    val c = MaterialCardView(this).apply {
        radius = dp(10).toFloat(); cardElevation = 0f
        setCardBackgroundColor(col(R.color.surface))
        strokeColor = col(R.color.line); strokeWidth = dp(1)
    }
    val inner = vbox().apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
    c.addView(inner)
    return c to inner
}

fun Context.input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES): EditText =
    EditText(this).apply {
        this.hint = hint; inputType = type; setSingleLine(true); textSize = 16f
        setTextColor(col(R.color.ink)); setHintTextColor(col(R.color.ink2))
        backgroundTintList = ColorStateList.valueOf(col(R.color.line))
    }

/** Etichetta piccola sopra un campo. */
fun Context.field(label: String, view: View): LinearLayout = vbox().apply {
    addView(cap(label))
    addView(view, lp())
}
