package it.scontrini.trasferta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

private fun JSONObject.optStr(k: String): String? = if (!has(k) || isNull(k)) null else getString(k).ifEmpty { null }

/** Tutti i dati restano sul telefono, in un file JSON dell'app. */
object Store {
    val expenses = mutableListOf<Expense>()
    val sms = mutableListOf<SmsCharge>()
    var trip = ""
    var currency = "MXN"
    var senders = ""
    var myName = ""
    var myEmail = ""
    /** ultime 4 cifre della carta aziendale: gli SMS e i movimenti di altre carte vengono ignorati */
    var myCard = ""
    /** zona dei massimali aziendali ("" = automatica dal paese o dalla valuta) */
    var capZone = ""
    /** repository condiviso (GitHub privato "proprietario/nome") e chiave di accesso */
    var repo = ""
    var repoToken = ""
    var lastSync = 0L
    var syncMsg = ""
    /** stato della sincronizzazione: sha remoto e impronta locale dell'ultimo allineamento, file cancellati da togliere dal repository */
    val syncSha = HashMap<String, String>()
    val syncHash = HashMap<String, String>()
    val tombstones = HashMap<String, Long>()
    /** blocco con impronta/PIN all'apertura */
    var lockEnabled = false
    var useLocation = true
    /** massimale pasto per persona, nella valuta e nell'anno indicati, rivalutato con il CPI */
    var capAmount = 0.0
    var capCurrency = ""
    var capYear = 0
    val tableSeen = HashMap<String, Long>()
    /** pagina da cui i colleghi senza app la scaricano (si apre dal QR o dall'NFC) */
    var downloadUrl = DEFAULT_DOWNLOAD
    const val DEFAULT_DOWNLOAD = "https://github.com/Andycrucco/note-spese-trasferta/releases/latest"
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "data.json")
    fun logoFile(ctx: Context) = File(ctx.filesDir, "logo.png")
    fun photoDir(ctx: Context) = File(ctx.filesDir, "photos").apply { mkdirs() }
    fun pdfDir(ctx: Context) = File(ctx.filesDir, "pdf").apply { mkdirs() }

    fun defaultTrip(): String {
        val c = Calendar.getInstance()
        return "Trasferta " + MESI[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR)
    }

    fun newId(prefix: String): String =
        prefix + System.currentTimeMillis().toString(36) + (Math.random() * 1_000_000).toInt().toString(36)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        expenses.clear(); sms.clear(); tableSeen.clear(); syncSha.clear(); syncHash.clear(); tombstones.clear()
        val f = file(ctx)
        if (f.exists()) {
            try {
                val raw = f.readBytes()
                val text = if (Vault.isEncrypted(raw)) String(Vault.decrypt(raw), Charsets.UTF_8) else String(raw, Charsets.UTF_8)
                val o = JSONObject(text)
                o.optJSONArray("expenses")?.let { a -> for (i in 0 until a.length()) expenses.add(expFrom(a.getJSONObject(i))) }
                o.optJSONArray("sms")?.let { a -> for (i in 0 until a.length()) sms.add(smsFrom(a.getJSONObject(i))) }
                trip = o.optString("trip", "")
                currency = o.optString("currency", "MXN").ifEmpty { "MXN" }
                senders = o.optString("senders", "")
                myName = o.optString("myName", "")
                myEmail = o.optString("myEmail", "")
                myCard = o.optString("myCard", "")
                capZone = o.optString("capZone", "")
                repo = o.optString("repo", ""); repoToken = o.optString("repoToken", "")
                lastSync = o.optLong("lastSync", 0L); syncMsg = o.optString("syncMsg", "")
                o.optJSONObject("syncSha")?.let { t -> t.keys().forEach { k -> syncSha[k] = t.optString(k) } }
                o.optJSONObject("syncHash")?.let { t -> t.keys().forEach { k -> syncHash[k] = t.optString(k) } }
                o.optJSONObject("tombstones")?.let { t -> t.keys().forEach { k -> tombstones[k] = t.optLong(k) } }
                lockEnabled = o.optBoolean("lockEnabled", false)
                useLocation = o.optBoolean("useLocation", true)
                capAmount = o.optDouble("capAmount", 0.0)
                capCurrency = o.optString("capCurrency", "")
                capYear = o.optInt("capYear", 0)
                downloadUrl = o.optString("downloadUrl", DEFAULT_DOWNLOAD).ifBlank { DEFAULT_DOWNLOAD }
                o.optJSONObject("tableSeen")?.let { t -> t.keys().forEach { k -> tableSeen[k] = t.optLong(k) } }
            } catch (e: Exception) {
                try { f.copyTo(File(ctx.filesDir, "data.rotto.json"), overwrite = true) } catch (_: Exception) {}
            }
        }
        if (trip.isBlank()) trip = defaultTrip()
        loaded = true
    }

    @Synchronized
    fun reload(ctx: Context) { loaded = false; load(ctx) }

    @Synchronized
    fun save(ctx: Context) {
        val o = JSONObject()
        o.put("expenses", JSONArray().apply { expenses.forEach { put(expTo(it)) } })
        o.put("sms", JSONArray().apply { sms.forEach { put(smsTo(it)) } })
        o.put("trip", trip); o.put("currency", currency); o.put("senders", senders)
        o.put("myName", myName); o.put("myEmail", myEmail); o.put("myCard", myCard); o.put("capZone", capZone); o.put("lockEnabled", lockEnabled); o.put("useLocation", useLocation)
        o.put("capAmount", capAmount); o.put("capCurrency", capCurrency); o.put("capYear", capYear)
        o.put("downloadUrl", downloadUrl)
        o.put("repo", repo); o.put("repoToken", repoToken); o.put("lastSync", lastSync); o.put("syncMsg", syncMsg)
        o.put("syncSha", JSONObject(syncSha as Map<*, *>)); o.put("syncHash", JSONObject(syncHash as Map<*, *>))
        o.put("tombstones", JSONObject().apply { tombstones.forEach { (k, v) -> put(k, v) } })
        val cut = System.currentTimeMillis() - 30L * 86_400_000L
        o.put("tableSeen", JSONObject().apply { tableSeen.filter { it.value >= cut }.forEach { (k, v) -> put(k, v) } })
        // dati cifrati con la chiave del Keystore di Android; se il telefono non la offre, restano nella memoria privata dell'app
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        val out = try { Vault.encrypt(bytes) } catch (e: Exception) { bytes }
        val tmp = File(ctx.filesDir, "data.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file(ctx))) { file(ctx).writeBytes(out); tmp.delete() }
        Disc.refreshWidget(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun consentShown(ctx: Context) = prefs(ctx).getBoolean("consent", false)
    fun setConsentShown(ctx: Context) { prefs(ctx).edit().putBoolean("consent", true).apply() }

    fun expense(id: String?): Expense? = expenses.find { it.id == id }
    fun smsById(id: String?): SmsCharge? = sms.find { it.id == id }

    fun pending(): List<SmsCharge> = Match.pending(expenses, sms)
    fun match(): Match.Result = Match.all(expenses, sms)

    /**
     * Commensali consigliati: prima chi hai visto al tavolo con "Sono a tavola" nelle ultime 3 ore,
     * poi un punteggio che somma frequenza storica e recenza (peso dimezzato ogni 7 giorni),
     * con un piccolo vantaggio per chi è già stato a tavola con te in questa trasferta.
     */
    fun knownGuests(): List<String> {
        val now = System.currentTimeMillis()
        val score = HashMap<String, Pair<String, Double>>()
        for (e in expenses) {
            val ts = if (e.created > 0) e.created else now
            val ageDays = (now - ts) / 86_400_000.0
            val w = 1.0 + 2.0 * Math.pow(0.5, ageDays / 7.0) + (if (e.trip == trip) 1.0 else 0.0)
            for (g in e.guests) {
                val k = g.lowercase()
                score[k] = (score[k]?.first ?: g) to ((score[k]?.second ?: 0.0) + w)
            }
        }
        val table = Table.recentNames()
        val rest = score.values.sortedByDescending { it.second }.map { it.first }
            .filter { n -> table.none { it.equals(n, true) } }
        return (table + rest).take(12)
    }

    fun knownCards(): List<String> = expenses.map { it.card }.filter { it.isNotBlank() }.distinct().take(8)

    /** spesa eliminata: la tolgo anche dal repository condiviso alla prossima sincronizzazione */
    fun forgetExpense(e: Expense) {
        expenses.removeAll { it.id == e.id }
        if (repo.isNotEmpty()) { tombstones["spese/${e.id}.json"] = System.currentTimeMillis(); tombstones["foto/${e.id}.jpg"] = System.currentTimeMillis() }
    }

    fun forgetSms(s: SmsCharge) {
        sms.removeAll { it.id == s.id }
        if (repo.isNotEmpty()) tombstones["sms/${s.id}.json"] = System.currentTimeMillis()
    }

    fun expJson(e: Expense): JSONObject = expTo(e)
    fun expParse(o: JSONObject): Expense = expFrom(o)
    fun smsJson(s: SmsCharge): JSONObject = smsTo(s)
    fun smsParse(o: JSONObject): SmsCharge = smsFrom(o)

    private fun expTo(e: Expense) = JSONObject().apply {
        put("id", e.id); put("trip", e.trip); put("date", e.date); put("place", e.place)
        put("amount", e.amount); put("tip", e.tip); put("currency", e.currency); put("card", e.card)
        put("category", e.category); put("guests", JSONArray(e.guests))
        put("pdf", e.pdf ?: JSONObject.NULL); put("photo", e.photo ?: JSONObject.NULL)
        put("bankId", e.bankId ?: JSONObject.NULL); put("created", e.created)
        put("time", e.time); put("tipCash", e.tipCash)
        put("lat", e.lat ?: JSONObject.NULL); put("lon", e.lon ?: JSONObject.NULL)
        put("country", e.country); put("poi", e.poi); put("catReason", e.catReason)
        put("eurRate", e.eurRate ?: JSONObject.NULL); put("eurDate", e.eurDate); put("declaration", e.declaration)
        put("updated", e.updated)
        put("zvoce", e.zvoce); put("pay", e.pay); put("nights", e.nights); put("split", e.split)
        put("provider", e.provider); put("route", e.route); put("note", e.note)
    }

    private fun expFrom(o: JSONObject) = Expense(
        id = o.getString("id"), trip = o.optString("trip"), date = o.optString("date"), place = o.optString("place"),
        amount = o.optDouble("amount", 0.0), tip = o.optDouble("tip", 0.0), currency = o.optString("currency"),
        card = o.optString("card"), category = Categorizer.migrate(o.optString("category", "Altro"), o.optString("time")),
        guests = o.optJSONArray("guests")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        pdf = o.optStr("pdf"), photo = o.optStr("photo"), bankId = o.optStr("bankId"), created = o.optLong("created", 0L),
        time = o.optString("time"), tipCash = o.optDouble("tipCash", 0.0),
        lat = if (o.isNull("lat")) null else o.optDouble("lat"), lon = if (o.isNull("lon")) null else o.optDouble("lon"),
        country = o.optString("country"), poi = o.optString("poi"), catReason = o.optString("catReason"),
        eurRate = if (o.isNull("eurRate")) null else o.optDouble("eurRate"), eurDate = o.optString("eurDate"),
        declaration = o.optBoolean("declaration", false), updated = o.optLong("updated", 0L),
        zvoce = o.optString("zvoce"), pay = o.optString("pay"), nights = o.optInt("nights", 1).coerceAtLeast(1),
        split = o.optBoolean("split", false), provider = o.optString("provider"), route = o.optString("route"), note = o.optString("note")
    )

    private fun smsTo(s: SmsCharge) = JSONObject().apply {
        put("id", s.id); put("date", s.date); put("amount", s.amount); put("currency", s.currency)
        put("merchant", s.merchant); put("card", s.card); put("network", s.network); put("created", s.created)
        put("time", s.time); put("source", s.source); put("done", s.done); put("inStmt", s.inStmt)
    }

    private fun smsFrom(o: JSONObject) = SmsCharge(
        id = o.getString("id"), date = o.optString("date"), amount = o.optDouble("amount", 0.0),
        currency = o.optString("currency"), merchant = o.optString("merchant"), card = o.optString("card"),
        network = o.optString("network"), created = o.optLong("created", 0L),
        time = o.optString("time"), source = o.optString("source"),
        done = o.optBoolean("done", false), inStmt = o.optBoolean("inStmt", false)
    )
}
