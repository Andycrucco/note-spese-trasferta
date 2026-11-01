package it.scontrini.trasferta

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Riceve gli SMS appena arrivano e, se sono addebiti della banca, li registra e manda la notifica. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (e: Exception) { null } ?: return
        val bySender = msgs.groupBy { it.displayOriginatingAddress ?: "" }
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                for ((sender, parts) in bySender) {
                    val body = parts.joinToString("") { it.displayMessageBody ?: "" }
                    SmsIngest.ingest(app, sender, body, parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis(), true)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * Routine per MacroDroid: "Invia intent" → Broadcast, azione it.scontrini.trasferta.SALVA, extra "testo" = [sms_message].
 * Salva l'addebito in "Da completare" senza aprire nulla e manda una notifica con "Fotografa ora".
 */
class SaveReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "it.scontrini.trasferta.SALVA"
        private const val P = "macrodroid"

        fun record(ctx: Context, result: String) {
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                .putLong("last", System.currentTimeMillis()).putString("result", result).apply()
        }

        /** "" se MacroDroid non ha mai mandato nulla, altrimenti ora e risultato dell'ultimo invio */
        fun status(ctx: Context): String {
            val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            val t = p.getLong("last", 0L); if (t == 0L) return ""
            val f = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY).format(java.util.Date(t))
            return "$f · " + (p.getString("result", "") ?: "")
        }

        /** Salva il testo ricevuto; restituisce gli addebiti nuovi. Fuori dal thread principale. */
        fun save(ctx: Context, sender: String, text: String): List<SmsCharge> {
            if (text.isBlank()) { record(ctx, "collegato, ma il testo era vuoto (era una prova?)"); return emptyList() }
            val ns = if (text.contains("NS1|")) SmsIngest.importNs(ctx, text).added else emptyList()
            val rest = text.lines().filterNot { Ns.isOwnLine(it) }.joinToString("\n").trim()
            val added = ns + (if (rest.isNotEmpty()) SmsIngest.ingest(ctx, sender, rest, System.currentTimeMillis(), notify = false, exactTime = true) else emptyList())
            record(ctx, when {
                added.isNotEmpty() -> "salvato " + added.joinToString(", ") { Fmt.money(it.amount) + " " + it.currency }
                !SmsParser.looksLikeCharge(rest) -> "SMS ricevuto, ma non è un addebito"
                else -> "addebito già presente o di un'altra carta"
            })
            return added
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val text = intent.getStringExtra("testo") ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val sender = intent.getStringExtra("mittente") ?: ""
        val app = ctx.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val added = save(app, sender, text)
                val pend = Store.pending().map { it.id }.toSet()
                for (s in added) if (s.id in pend) Notifier.charge(app, s, saved = true)
            } catch (_: Exception) {
                record(app, "errore nel salvataggio")
            } finally { pending.finish() }
        }.start()
    }
}

object SmsIngest {
    fun accept(sender: String, body: String): Boolean {
        val list = Store.senders.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isNotEmpty() && list.none { sender.contains(it, true) || body.contains(it, true) }) return false
        return SmsParser.looksLikeCharge(body)
    }

    /** Restituisce gli addebiti nuovi trovati nel messaggio. */
    @Synchronized
    fun ingest(ctx: Context, sender: String, body: String, whenMs: Long, notify: Boolean, exactTime: Boolean = true): List<SmsCharge> {
        Store.load(ctx)
        if (!accept(sender, body)) return emptyList()
        val added = mutableListOf<SmsCharge>()
        for (p in SmsParser.parse(body)) {
            // un'altra carta (personale o di un collega): non è una spesa da rendicontare
            if (Store.myCard.isNotEmpty() && p.card.isNotEmpty() && p.card != Store.myCard) continue
            val date = p.date.ifEmpty { Fmt.isoOf(whenMs) }
            val id = "s" + Fmt.hash(listOf(date, Fmt.dot(p.amount), p.currency, p.card, Fmt.slug(p.merchant, 20)).joinToString("|"))
            if (Store.smsById(id) != null) continue
            // già arrivato da un estratto conto o dalla versione web
            if (Dedupe.find(SmsCharge(id, date, p.amount, p.currency.ifEmpty { Store.currency }, p.merchant, p.card, p.network, 0L), Store.sms, emptySet(), strictSms = true) != null) continue
            // ora dell'addebito: dal testo; altrimenti l'ora di arrivo, se l'SMS è arrivato davvero ora sul telefono
            val time = Fmt.time(body).ifEmpty {
                if (exactTime && date == Fmt.isoOf(whenMs)) java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(whenMs)) else ""
            }
            val s = SmsCharge(id, date, p.amount, p.currency.ifEmpty { Store.currency }, p.merchant, p.card, p.network,
                if (exactTime) whenMs else System.currentTimeMillis(), time)
            Store.sms.add(s)
            added.add(s)
        }
        if (added.isNotEmpty()) {
            Store.save(ctx)
            if (notify) for (s in added) {
                if (Match.expenseFor(s, Store.expenses, Store.sms) == null) Notifier.charge(ctx, s)
            }
        }
        return added
    }

    class Result(val added: List<SmsCharge>, val dup: Int)

    /** Righe NS1 dalla versione web (o da un altro telefono): salva le nuove, salta i doppioni. */
    @Synchronized
    fun importNs(ctx: Context, text: String): Result {
        Store.load(ctx)
        val used = HashSet<String>(); val added = mutableListOf<SmsCharge>(); var dup = 0
        for (c in Ns.parse(text)) {
            if (Store.myCard.isNotEmpty() && c.card.isNotEmpty() && c.card != Store.myCard) continue
            val d = Dedupe.find(c, Store.sms, used, strictSms = false)
            if (d != null) { used.add(d.id); dup++; continue }
            Store.sms.add(c); used.add(c.id); added.add(c)
        }
        if (added.isNotEmpty()) Store.save(ctx)
        return Result(added, dup)
    }
}

object Notifier {
    private const val CH = "addebiti"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "Addebiti della banca", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Avvisa quando arriva un addebito senza scontrino"
                    }
                )
            }
        }
    }

    fun id(s: SmsCharge): Int = s.id.hashCode()

    fun charge(ctx: Context, s: SmsCharge, saved: Boolean = false) {
        ensureChannel(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            ctx, id(s),
            Intent(ctx, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val cam = PendingIntent.getActivity(
            ctx, id(s) + 1,
            Intent(ctx, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id)
                .putExtra(ExpenseActivity.EXTRA_CAMERA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val text = listOf(s.merchant, s.cardLabel()).filter { it.isNotEmpty() }.joinToString(" · ")
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFE10A1E.toInt())
            .setContentTitle((if (saved) "Salvato in Da completare: " else "Addebito ") + Fmt.money(s.amount) + " " + s.currency)
            .setContentText((if (text.isNotEmpty()) "$text — " else "") + (if (saved) "aggiungi la foto quando vuoi" else "fotografa lo scontrino"))
            .setStyle(NotificationCompat.BigTextStyle().bigText((if (text.isNotEmpty()) "$text\n" else "") +
                (if (saved) "È tra le transazioni da completare. Fotografa ora o più tardi dall'app." else "Tocca per fotografare lo scontrino e abbinarlo.")))
            .setContentIntent(open)
            .addAction(0, if (saved) "Fotografa ora" else "Fotografa", cam)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id(s), n) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context, s: SmsCharge) {
        NotificationManagerCompat.from(ctx).cancel(id(s))
    }
}
