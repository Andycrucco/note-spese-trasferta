package it.scontrini.trasferta

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Strategy

/**
 * "Sono a tavola": solo mentre è attivo, il telefono annuncia il tuo nome ai colleghi vicini
 * che hanno l'app e il tasto attivo, e riceve i loro. Nessun server, nessuna connessione, nessun dato salvato
 * fuori dal telefono. Si spegne da solo dopo 10 minuti.
 */
object Table {
    private const val SERVICE = "it.scontrini.trasferta.tavolo"
    private const val DURATION_MS = 10 * 60_000L

    private var client: ConnectionsClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, String>()
    var active = false; private set
    var until = 0L; private set
    var onChange: (() -> Unit)? = null

    fun permissions(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES
        )
        Build.VERSION.SDK_INT >= 31 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION
        )
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun missing(ctx: Context) = permissions().filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    /** Nomi visti al tavolo nelle ultime 3 ore (anche dopo lo spegnimento). */
    fun recentNames(): List<String> {
        val cut = System.currentTimeMillis() - 3 * 3_600_000L
        return Store.tableSeen.filter { it.value >= cut }.entries.sortedByDescending { it.value }.map { it.key }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) { client?.rejectConnection(id) }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {}
        override fun onDisconnected(id: String) {}
    }

    private fun discovery(ctx: Context) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            val name = info.endpointName.trim().take(60)
            if (name.isEmpty()) return
            found[id] = name
            Store.tableSeen[name] = System.currentTimeMillis()
            try { Store.save(ctx) } catch (_: Exception) {}
            onChange?.invoke()
        }
        override fun onEndpointLost(id: String) {}
    }

    /** Restituisce un messaggio d'errore, oppure null se è partito. */
    fun start(ctx: Context): String? {
        val me = Store.myName.trim()
        if (me.isEmpty()) return "Prima scrivi il tuo nome in Impostazioni: è quello che vedranno i colleghi al tavolo."
        if (missing(ctx).isNotEmpty()) return "Servono i permessi Bluetooth e dispositivi nelle vicinanze."
        val app = ctx.applicationContext
        val c = Nearby.getConnectionsClient(app)
        client = c
        stopInternal()
        val strategy = Strategy.P2P_CLUSTER
        c.startAdvertising(me, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
        c.startDiscovery(SERVICE, discovery(app), DiscoveryOptions.Builder().setStrategy(strategy).build())
        active = true
        until = System.currentTimeMillis() + DURATION_MS
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stop() }, DURATION_MS)
        onChange?.invoke()
        return null
    }

    private fun stopInternal() {
        try { client?.stopAdvertising(); client?.stopDiscovery(); client?.stopAllEndpoints() } catch (_: Exception) {}
    }

    fun stop() {
        stopInternal()
        handler.removeCallbacksAndMessages(null)
        active = false
        onChange?.invoke()
    }
}
