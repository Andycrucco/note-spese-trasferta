package it.scontrini.trasferta

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Riceve il testo di uno o più SMS di addebito:
 * - da MacroDroid, azione "Invia intent" (Activity) con action it.scontrini.trasferta.ADDEBITO ed extra "testo";
 * - da Condividi nell'app Messaggi (testo semplice).
 * Registra gli addebiti e apre subito la foto dello scontrino del primo senza scontrino.
 */
class ShareActivity : AppCompatActivity() {
    companion object {
        const val ACTION = "it.scontrini.trasferta.ADDEBITO"

        /** photo = false: salva soltanto, la foto si aggiunge dopo da "Da completare" */
        fun handle(act: Activity, text: String, photo: Boolean = true): Boolean {
            if (text.isBlank()) { Toast.makeText(act, "Nessun testo ricevuto.", Toast.LENGTH_LONG).show(); return false }
            Store.load(act)
            val ns = if (text.contains("NS1|")) SmsIngest.importNs(act, text) else null
            val rest = text.lines().filterNot { Ns.isOwnLine(it) }.joinToString("\n").trim()
            val added = (ns?.added ?: emptyList()) +
                (if (rest.isNotEmpty()) SmsIngest.ingest(act, "", rest, System.currentTimeMillis(), notify = false, exactTime = false) else emptyList())
            if (ns != null && rest.isEmpty() || !photo) {
                val n = added.size; val d = ns?.dup ?: 0
                Toast.makeText(act, when {
                    n == 0 && d > 0 -> "Già presenti: niente di nuovo."
                    n == 0 -> "Non trovo un addebito in questo testo."
                    else -> (if (n == 1) "1 transazione salvata" else "$n transazioni salvate") + " in Da completare" + (if (d > 0) " ($d già presenti)" else "") + "."
                }, Toast.LENGTH_LONG).show()
                if (act !is MainActivity) act.startActivity(Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                return n > 0
            }
            val pend = Store.pending()
            val target = added.firstOrNull { s -> pend.any { it.id == s.id } } ?: pend.firstOrNull()
            return when {
                added.isEmpty() && target == null -> { Toast.makeText(act, "Non trovo un addebito in questo testo.", Toast.LENGTH_LONG).show(); false }
                target == null -> { Toast.makeText(act, "Addebito già abbinato a uno scontrino.", Toast.LENGTH_LONG).show(); false }
                else -> {
                    act.startActivity(Intent(act, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, target.id).putExtra(ExpenseActivity.EXTRA_CAMERA, true))
                    true
                }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val text = intent?.getStringExtra("testo") ?: intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString?.substringAfter("testo=", "") ?: ""
        handle(this, text)
        finish()
    }
}
