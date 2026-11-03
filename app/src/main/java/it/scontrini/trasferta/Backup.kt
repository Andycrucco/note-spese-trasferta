package it.scontrini.trasferta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup su file (ZIP) per chi non usa il repository condiviso: spese, addebiti, foto e logo.
 * Il ripristino unisce i dati a quelli del telefono (vince la versione modificata più di recente), senza cancellare nulla.
 * Il token del repository non entra mai nel backup.
 */
object Backup {
    class Result(val added: Int, val updated: Int, val sms: Int, val photos: Int)

    private const val P = "ui"
    fun lastBackup(ctx: Context): Long = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getLong("lastBackup", 0L)

    fun fileName(): String = "NoteSpese_backup_" + Fmt.today() + ".zip"

    fun write(ctx: Context, out: OutputStream): Int {
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            val o = JSONObject()
            o.put("app", "note-spese-trasferta"); o.put("v", 1); o.put("created", System.currentTimeMillis())
            o.put("expenses", JSONArray().apply {
                Store.expenses.forEach { e ->
                    val j = Store.expJson(e)
                    j.put("photo", if (e.photo?.let { File(it).exists() } == true) "foto/${e.id}.jpg" else JSONObject.NULL)
                    j.put("pdf", JSONObject.NULL)
                    put(j)
                }
            })
            o.put("sms", JSONArray().apply { Store.sms.forEach { put(Store.smsJson(it)) } })
            o.put("settings", JSONObject().apply {
                put("trip", Store.trip); put("currency", Store.currency); put("senders", Store.senders)
                put("myName", Store.myName); put("myEmail", Store.myEmail); put("myCard", Store.myCard)
                put("capZone", Store.capZone); put("capAmount", Store.capAmount); put("capCurrency", Store.capCurrency); put("capYear", Store.capYear)
                put("useLocation", Store.useLocation)
            })
            z.putNextEntry(ZipEntry("data.json")); z.write(o.toString(1).toByteArray(Charsets.UTF_8)); z.closeEntry()
            for (e in Store.expenses) {
                val f = e.photo?.let { File(it) }?.takeIf { it.exists() } ?: continue
                z.putNextEntry(ZipEntry("foto/${e.id}.jpg")); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++
            }
            Store.logoFile(ctx).takeIf { it.exists() }?.let { f -> z.putNextEntry(ZipEntry("logo.png")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putLong("lastBackup", System.currentTimeMillis()).apply()
        return n
    }

    private val SAFE_ID = Regex("^[A-Za-z0-9_.-]{1,80}$")

    fun read(ctx: Context, inp: InputStream): Result {
        var data: JSONObject? = null
        val photos = HashMap<String, File>()
        val tmpDir = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var logo: File? = null
        ZipInputStream(inp.buffered()).use { z ->
            while (true) {
                val en = z.nextEntry ?: break
                val name = en.name
                when {
                    name == "data.json" -> data = JSONObject(String(z.readBytes(), Charsets.UTF_8))
                    name.startsWith("foto/") && name.endsWith(".jpg") -> {
                        val id = name.removePrefix("foto/").removeSuffix(".jpg")
                        if (SAFE_ID.matches(id)) File(tmpDir, "$id.jpg").also { f -> f.outputStream().use { z.copyTo(it) }; photos[id] = f }
                    }
                    name == "logo.png" -> logo = File(tmpDir, "logo.png").also { f -> f.outputStream().use { z.copyTo(it) } }
                }
                z.closeEntry()
            }
        }
        val o = data ?: throw Exception("non è un backup di Note spese (manca data.json)")
        if (o.optString("app") != "note-spese-trasferta") throw Exception("non è un backup di Note spese")
        val wasEmpty = Store.expenses.isEmpty()
        var added = 0; var updated = 0; var smsN = 0; var ph = 0
        val arr = o.optJSONArray("expenses") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val e = Store.expParse(j)
            if (!SAFE_ID.matches(e.id)) continue
            val local = Store.expense(e.id)
            if (local != null && local.updated >= e.updated) continue
            photos[e.id]?.let { src ->
                val dst = File(Store.photoDir(ctx), "${e.id}.jpg")
                if (!dst.exists() || local == null || local.photo == null) { src.copyTo(dst, overwrite = true); ph++ }
                e.photo = dst.absolutePath
            } ?: run { e.photo = local?.photo }
            e.pdf = null
            if (local == null) { Store.expenses.add(e); added++ }
            else { Store.expenses[Store.expenses.indexOf(local)] = e; updated++ }
        }
        val sa = o.optJSONArray("sms") ?: JSONArray()
        for (i in 0 until sa.length()) {
            val s = Store.smsParse(sa.getJSONObject(i))
            if (Store.smsById(s.id) == null) { Store.sms.add(s); smsN++ }
        }
        o.optJSONObject("settings")?.let { st ->
            if (Store.myName.isBlank()) Store.myName = st.optString("myName")
            if (Store.myEmail.isBlank()) Store.myEmail = st.optString("myEmail")
            if (Store.myCard.isBlank()) Store.myCard = st.optString("myCard")
            if (Store.capZone.isBlank()) Store.capZone = st.optString("capZone")
            if (Store.capAmount <= 0.0 && st.optDouble("capAmount", 0.0) > 0.0) {
                Store.capAmount = st.optDouble("capAmount"); Store.capCurrency = st.optString("capCurrency"); Store.capYear = st.optInt("capYear")
            }
            if (Store.senders.isBlank()) Store.senders = st.optString("senders")
            if (wasEmpty) { st.optString("trip").takeIf { it.isNotBlank() }?.let { Store.trip = it }; st.optString("currency").takeIf { it.isNotBlank() }?.let { Store.currency = it } }
        }
        logo?.let { if (!Store.logoFile(ctx).exists()) it.copyTo(Store.logoFile(ctx), overwrite = true) }
        Store.save(ctx)
        tmpDir.deleteRecursively()
        return Result(added, updated, smsN, ph)
    }
}
