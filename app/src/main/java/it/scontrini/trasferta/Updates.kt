package it.scontrini.trasferta

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamenti: legge le versioni pubblicate su GitHub (Releases), le confronta con quella installata
 * e scarica l'APK giusto (completa o senza SMS), che si installa sopra senza perdere i dati.
 */
object Updates {
    private const val REPO = "Andycrucco/note-spese-trasferta"
    const val PAGE = "https://github.com/$REPO/releases"
    private const val P = "updates"

    class Info(val code: Int, val name: String, val notes: String, val url: String, val size: Long, val date: String)

    val current: Int get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME

    /** versione installata da Play Store: lì gli aggiornamenti li gestisce lo Store */
    val fromStore: Boolean get() = !BuildConfig.DEBUG

    private fun assetName() = if (BuildConfig.SMS) "NoteSpeseTrasferta.apk" else "NoteSpeseTrasferta-senzaSMS.apk"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "NoteSpeseTrasferta")
        try {
            if (c.responseCode != 200) throw Exception("GitHub ha risposto ${c.responseCode}")
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    /** Novità di una versione, senza le righe tecniche dei commit. */
    private fun clean(body: String): String =
        body.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Co-Authored-By", true) && !it.startsWith("Claude-Session", true) && !it.startsWith("https://claude.ai") }
            .joinToString("\n")

    /** Ultima versione pubblicata, con le novità di tutte quelle più recenti dell'installata. Fuori dal thread principale. */
    fun check(ctx: Context): Info? {
        val a = JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=15"))
        var best: Info? = null
        val notes = mutableListOf<String>()
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val name = r.optString("tag_name").removePrefix("v")
            val code = name.substringAfterLast('.').toIntOrNull() ?: continue
            if (code <= current) continue
            val assets = r.optJSONArray("assets") ?: continue
            var url = ""; var size = 0L
            for (j in 0 until assets.length()) {
                val x = assets.getJSONObject(j)
                if (x.optString("name") == assetName()) { url = x.optString("browser_download_url"); size = x.optLong("size") }
            }
            if (url.isEmpty()) continue
            clean(r.optString("body")).takeIf { it.isNotEmpty() }?.let { notes.add("$name — $it") }
            if (best == null || code > best.code) best = Info(code, name, "", url, size, r.optString("published_at").take(10))
        }
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putLong("checked", System.currentTimeMillis())
            .putInt("latest", best?.code ?: current)
            .putString("latestName", best?.name ?: currentName).apply()
        return best?.let { Info(it.code, it.name, notes.joinToString("\n\n"), it.url, it.size, it.date) }
    }

    /** Controllo automatico al massimo ogni 12 ore. */
    fun due(ctx: Context): Boolean =
        System.currentTimeMillis() - ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getLong("checked", 0L) > 12 * 3_600_000L

    /** Versione più recente trovata nell'ultimo controllo, se è più nuova di quella installata. */
    fun known(ctx: Context): String? {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        return if (p.getInt("latest", 0) > current) p.getString("latestName", null) else null
    }

    fun lastCheck(ctx: Context): Long = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getLong("checked", 0L)

    /** Scarica l'APK nella cache dell'app. Fuori dal thread principale. */
    fun download(ctx: Context, info: Info, progress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "NoteSpeseTrasferta-${info.name}.apk")
        val c = URL(info.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20_000; c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "NoteSpeseTrasferta")
        try {
            if (c.responseCode != 200) throw Exception("download non riuscito (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var n: Int; var last = -1
                    while (ins.read(buf).also { n = it } >= 0) {
                        os.write(buf, 0, n); done += n
                        if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; progress(pct) } }
                    }
                }
            }
        } finally { c.disconnect() }
        return out
    }

    /** Il telefono permette a questa app di installare l'aggiornamento? */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))

    fun installIntent(ctx: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
