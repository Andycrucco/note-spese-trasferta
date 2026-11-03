package it.scontrini.trasferta

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Animatable
import android.view.Gravity
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * Il disco del logo usato in tutta l'app: più cose restano da fare, più il disco si scalda
 * (freddo = tutto a posto, incandescente = addebiti senza scontrino o spese da sistemare).
 * Mentre l'app lavora il disco gira e pulsa.
 */
object Disc {
    val HEAT = intArrayOf(R.drawable.disc_heat_0, R.drawable.disc_heat_1, R.drawable.disc_heat_2, R.drawable.disc_heat_3)
    /** come HEAT ma sul cerchio rosso dell'icona, per il widget sulla Home */
    val WIDGET = intArrayOf(R.drawable.widget_heat_0, R.drawable.widget_heat_1, R.drawable.widget_heat_2, R.drawable.widget_heat_3)

    /** 0 freddo · 1 tiepido · 2 caldo · 3 incandescente */
    fun level(todo: Int): Int = when { todo <= 0 -> 0; todo <= 2 -> 1; todo <= 5 -> 2; else -> 3 }

    fun heatIcon(ctx: Context, todo: Int, sizeDp: Int = 28): ImageView = ImageView(ctx).apply {
        setImageResource(HEAT[level(todo)])
        layoutParams = android.widget.LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        contentDescription = if (todo <= 0) "Tutto a posto" else "$todo da fare"
    }

    /** disco che gira e pulsa, già avviato */
    fun spinner(ctx: Context, sizeDp: Int = 56): ImageView = ImageView(ctx).apply {
        setImageResource(R.drawable.avd_busy)
        layoutParams = android.widget.LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        contentDescription = "Attendi"
        post { (drawable as? Animatable)?.start() }
    }

    /** Finestra di attesa con il disco che frena: per esportazione, backup, estratto conto. */
    class Busy(act: Activity, text: String) {
        private val dlg: AlertDialog
        private val label: TextView
        init {
            val box = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(22), act.dp(20), act.dp(22), act.dp(12)) }
            box.addView(spinner(act, 56))
            label = act.txt(text, 15f).apply { setPadding(act.dp(16), 0, 0, 0) }
            box.addView(label, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            dlg = AlertDialog.Builder(act).setView(box).setCancelable(false).create()
            dlg.show()
        }
        fun text(t: String) { label.text = t }
        fun dismiss() { try { dlg.dismiss() } catch (_: Exception) {} }
    }

    /** icona animata a sinistra di un testo (es. "Leggo lo scontrino…"); null la toglie */
    fun spinText(tv: TextView, on: Boolean) {
        if (!on) { tv.setCompoundDrawablesRelative(null, null, null, null); return }
        val d = androidx.core.content.ContextCompat.getDrawable(tv.context, R.drawable.avd_busy) ?: return
        val s = tv.context.dp(22); d.setBounds(0, 0, s, s)
        tv.compoundDrawablePadding = tv.context.dp(8)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        (d as? Animatable)?.start()
    }

    /** aggiorna il widget sulla schermata Home */
    fun refreshWidget(ctx: Context) {
        try {
            val am = AppWidgetManager.getInstance(ctx)
            val ids = am.getAppWidgetIds(ComponentName(ctx, DiscWidget::class.java))
            if (ids.isNotEmpty()) DiscWidget.draw(ctx, am, ids)
        } catch (_: Exception) {}
    }
}

/**
 * Widget "Disco" per la schermata Home: il disco si scalda con gli addebiti senza scontrino
 * e si raffredda quando hai completato tutto. Tocco: Da completare (o fotocamera se è tutto a posto).
 */
class DiscWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, am: AppWidgetManager, ids: IntArray) = draw(ctx, am, ids)

    companion object {
        fun draw(ctx: Context, am: AppWidgetManager, ids: IntArray) {
            Store.load(ctx)
            val todo = Store.pending().size
            val v = RemoteViews(ctx.packageName, R.layout.widget_disco)
            v.setImageViewResource(R.id.disco, Disc.WIDGET[Disc.level(todo)])
            v.setTextViewText(R.id.conta, if (todo > 0) todo.toString() else "✓")
            v.setContentDescription(R.id.disco, if (todo > 0) "$todo addebiti senza scontrino" else "Tutto a posto: fotografa uno scontrino")
            val act = if (todo > 0) MainActivity.ACT_TODO else MainActivity.ACT_FOTO
            val pi = PendingIntent.getActivity(ctx, 41, Intent(ctx, MainActivity::class.java).setAction(act)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.radice, pi)
            am.updateAppWidget(ids, v)
        }
    }
}
