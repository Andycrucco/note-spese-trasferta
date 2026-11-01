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

        fun handle(act: Activity, text: String): Boolean {
            if (text.isBlank()) { Toast.makeText(act, "Nessun testo ricevuto.", Toast.LENGTH_LONG).show(); return false }
            Store.load(act)
            val added = SmsIngest.ingest(act, "", text, System.currentTimeMillis(), notify = false, exactTime = false)
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
