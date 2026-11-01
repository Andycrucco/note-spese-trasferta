package it.scontrini.trasferta

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/** Ogni sera alle 21 ricorda gli addebiti della banca ancora senza scontrino. */
object Reminder {
    private const val CH = "promemoria"
    private const val ID = 2100

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, ID, Intent(ctx, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.timeInMillis, AlarmManager.INTERVAL_DAY, pi)
    }

    fun fire(ctx: Context) {
        Store.reload(ctx)
        val pend = Store.pending()
        if (pend.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "Promemoria serale", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(ctx, ID, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val list = pend.take(4).joinToString("\n") { Fmt.money(it.amount) + " " + it.currency + (if (it.merchant.isNotEmpty()) " · " + it.merchant else "") }
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFD6001C.toInt())
            .setContentTitle("${pend.size} " + (if (pend.size == 1) "addebito" else "addebiti") + " senza scontrino")
            .setContentText("Fotografali finché li hai in tasca, oppure fai la dichiarazione.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(list + "\nTocca per fotografarli o fare la dichiarazione."))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(ID, n) } catch (_: SecurityException) {}
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { Reminder.schedule(ctx); return }
        val p = goAsync()
        Thread { try { Reminder.fire(ctx.applicationContext) } catch (_: Exception) {} finally { p.finish() } }.start()
    }
}
