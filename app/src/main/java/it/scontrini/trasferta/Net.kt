package it.scontrini.trasferta

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sqrt

private const val UA = "NoteSpeseTrasferta/1.0 (app personale Android)"

private fun httpGet(url: String, timeoutMs: Int = 9000): String? = try {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
    c.setRequestProperty("User-Agent", UA)
    c.setRequestProperty("Accept", "application/json")
    if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
} catch (e: Exception) { null }

/** Dove è stata fatta la foto. */
object Where {
    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Posizione salvata nella foto (se la fotocamera la registra). */
    fun fromPhoto(ctx: Context, uri: Uri): Pair<Double, Double>? = try {
        ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).latLong }?.let { it[0] to it[1] }
    } catch (e: Exception) { null }

    /** Posizione attuale del telefono (al massimo 8 secondi di attesa). Da chiamare fuori dal thread principale. */
    @SuppressLint("MissingPermission")
    fun now(ctx: Context): Pair<Double, Double>? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } }
        val recent = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
            .minByOrNull { it.accuracy }
        if (recent != null && recent.accuracy < 80) return recent.latitude to recent.longitude
        if (Build.VERSION.SDK_INT >= 30) {
            val p = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return recent?.let { it.latitude to it.longitude }
            val latch = CountDownLatch(1); var got: Location? = null
            val cancel = CancellationSignal()
            try {
                lm.getCurrentLocation(p, cancel, ctx.mainExecutor) { got = it; latch.countDown() }
                latch.await(8, TimeUnit.SECONDS)
            } catch (e: Exception) {
            } finally { if (got == null) cancel.cancel() }
            got?.let { return it.latitude to it.longitude }
        }
        return recent?.let { it.latitude to it.longitude }
    }

    /** Codice del paese (MX, PL, IT…) dalla posizione. */
    @Suppress("DEPRECATION")
    fun country(ctx: Context, lat: Double, lon: Double): String = try {
        Geocoder(ctx, Locale.ITALY).getFromLocation(lat, lon, 1)?.firstOrNull()?.countryCode?.uppercase() ?: ""
    } catch (e: Exception) { "" }

    /**
     * Il luogo più vicino tra distributori, alberghi, noleggi auto, ristoranti, bar, parcheggi
     * (OpenStreetMap, servizio Overpass). Serve internet; se manca restituisce null.
     */
    fun nearestPlace(lat: Double, lon: Double): Map<String, String>? {
        val q = """[out:json][timeout:8];(
nwr(around:70,$lat,$lon)[amenity~"^(fuel|restaurant|fast_food|food_court|cafe|bar|pub|parking|car_rental|taxi)$"];
nwr(around:70,$lat,$lon)[tourism~"^(hotel|motel|hostel|guest_house|apartment)$"];
nwr(around:60,$lat,$lon)[barrier=toll_booth];
nwr(around:300,$lat,$lon)[aeroway=terminal];
nwr(around:150,$lat,$lon)[railway=station];
);out center tags 30;"""
        val body = httpGet("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8")) ?: return null
        return try {
            val els = JSONObject(body).optJSONArray("elements") ?: return null
            var best: Map<String, String>? = null; var bestD = Double.MAX_VALUE
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                val la = if (e.has("lat")) e.getDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
                val lo = if (e.has("lon")) e.getDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
                val dy = (la - lat) * 111_320; val dx = (lo - lon) * 111_320 * cos(Math.toRadians(lat))
                val d = sqrt(dx * dx + dy * dy)
                val tags = e.optJSONObject("tags") ?: continue
                val m = HashMap<String, String>()
                tags.keys().forEach { k -> m[k] = tags.optString(k) }
                if (Categorizer.fromTags(m) == null) continue
                if (d < bestD) { bestD = d; best = m }
            }
            best
        } catch (e: Exception) { null }
    }
}

/** Indice dei prezzi al consumo della Banca Mondiale (FP.CPI.TOTL, 2010 = 100), con copia sul telefono. */
object Cpi {
    class Series(val country: String, val name: String, val points: List<CpiPoint>, val lastUpdated: String, val fetchedAt: Long)

    private fun cache(ctx: Context, iso2: String) = File(ctx.filesDir, "cpi_$iso2.json")

    fun get(ctx: Context, iso2: String): Series? {
        if (iso2.isEmpty()) return null
        val f = cache(ctx, iso2)
        val cached = if (f.exists()) parse(iso2, f.readText(), f.lastModified()) else null
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 30L * 86_400_000L) return cached
        val body = httpGet("https://api.worldbank.org/v2/country/$iso2/indicator/FP.CPI.TOTL?format=json&per_page=100&date=2000:2035")
        val fresh = body?.let { parse(iso2, it, System.currentTimeMillis()) }
        if (fresh != null && fresh.points.size >= 3) { try { f.writeText(body) } catch (_: Exception) {}; return fresh }
        return cached
    }

    fun parse(iso2: String, body: String, at: Long): Series? = try {
        val arr = JSONArray(body)
        val meta = arr.optJSONObject(0)
        val rows = arr.optJSONArray(1)
        val pts = mutableListOf<CpiPoint>(); var name = iso2
        if (rows != null) for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            name = r.optJSONObject("country")?.optString("value") ?: name
            if (r.isNull("value")) continue
            val y = r.optString("date").toIntOrNull() ?: continue
            pts.add(CpiPoint(y, r.getDouble("value")))
        }
        if (pts.isEmpty()) null else Series(iso2, name, pts.sortedBy { it.year }, meta?.optString("lastupdated") ?: "", at)
    } catch (e: Exception) { null }
}
