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
            val date = p.date.ifEmpty { Fmt.isoOf(whenMs) }
            val id = "s" + Fmt.hash(listOf(date, Fmt.dot(p.amount), p.currency, p.card, Fmt.slug(p.merchant, 20)).joinToString("|"))
            if (Store.smsById(id) != null) continue
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

    fun charge(ctx: Context, s: SmsCharge) {
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
            .setContentTitle("Addebito " + Fmt.money(s.amount) + " " + s.currency)
            .setContentText((if (text.isNotEmpty()) "$text — " else "") + "fotografa lo scontrino")
            .setStyle(NotificationCompat.BigTextStyle().bigText((if (text.isNotEmpty()) "$text\n" else "") + "Tocca per fotografare lo scontrino e abbinarlo."))
            .setContentIntent(open)
            .addAction(0, "Fotografa", cam)
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
