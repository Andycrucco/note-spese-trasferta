package it.scontrini.trasferta

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Repository condiviso tra i tuoi telefoni (e la versione web): un repository GitHub privato.
 *   spese/<id>.json   una spesa (senza il percorso locale della foto)
 *   foto/<id>.jpg     la foto dello scontrino
 *   sms/<id>.json     un addebito della banca o un movimento dell'estratto conto
 * A ogni sincronizzazione scarico ciò che è cambiato sugli altri dispositivi e carico ciò che è cambiato qui.
 * Se la stessa spesa cambia su due telefoni vince la modifica più recente; le cancellazioni si propagano.
 */
object Sync {
    class Error(msg: String) : Exception(msg)

    @Volatile private var running = false
    private var lastAuto = 0L
    var onDone: (() -> Unit)? = null

    val connected: Boolean get() = Store.repo.isNotEmpty() && Store.repoToken.isNotEmpty()

    /** Sincronizza in background (se collegato); onDone viene chiamato alla fine. */
    fun later(ctx: Context, force: Boolean = true) {
        if (!connected || running) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAuto < 30_000) return
        lastAuto = now
        val app = ctx.applicationContext
        Thread {
            try { run(app) } catch (_: Exception) {}
            onDone?.invoke()
        }.start()
    }

    // ---------- GitHub ----------
    private class Gh(val token: String, val repo: String) {
        fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
            val c = URL("https://api.github.com/repos/$repo$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "NoteSpeseTrasferta")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val txt = try { (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "" } catch (_: Exception) { "" }
            c.disconnect()
            return code to txt
        }

        fun branch(): String {
            val (code, txt) = call("GET", "")
            when (code) {
                200 -> return JSONObject(txt).optString("default_branch", "main").ifEmpty { "main" }
                401 -> throw Error("La chiave non è valida o è scaduta: creane una nuova su GitHub.")
                403 -> throw Error("La chiave non ha accesso a questo repository: controlla \"Contents: Read and write\".")
                404 -> throw Error("Repository non trovato: controlla il nome (proprietario/nome) e che la chiave lo includa.")
                else -> throw Error("GitHub non risponde (errore $code). Riprova più tardi.")
            }
        }

        /** percorso → sha di tutti i file; vuoto se il repository non ha ancora file */
        fun tree(branch: String): Map<String, String> {
            val (code, txt) = call("GET", "/git/trees/${enc(branch)}?recursive=1")
            if (code == 409 || code == 404) return emptyMap()
            if (code != 200) throw Error("Non riesco a leggere il repository (errore $code).")
            val a = JSONObject(txt).optJSONArray("tree") ?: return emptyMap()
            val out = HashMap<String, String>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optString("type") == "blob") out[o.getString("path")] = o.getString("sha")
            }
            return out
        }

        fun blob(sha: String): ByteArray {
            val (code, txt) = call("GET", "/git/blobs/$sha")
            if (code != 200) throw Error("Download non riuscito (errore $code).")
            return Base64.decode(JSONObject(txt).optString("content"), Base64.DEFAULT)
        }

        /** carica o aggiorna un file; restituisce il nuovo sha, null se nel frattempo è cambiato altrove */
        fun put(branch: String, path: String, bytes: ByteArray, sha: String?): String? {
            val body = JSONObject().put("message", "Note spese: $path").put("branch", branch)
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            if (sha != null) body.put("sha", sha)
            val (code, txt) = call("PUT", "/contents/" + path.split('/').joinToString("/") { enc(it) }, body)
            if (code == 409 || code == 422) return null
            if (code !in 200..201) throw Error("Caricamento non riuscito (errore $code).")
            return JSONObject(txt).optJSONObject("content")?.optString("sha")
        }

        fun delete(branch: String, path: String, sha: String): Boolean {
            val body = JSONObject().put("message", "Note spese: elimina $path").put("branch", branch).put("sha", sha)
            val (code, _) = call("DELETE", "/contents/" + path.split('/').joinToString("/") { enc(it) }, body)
            return code == 200 || code == 404
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    /** Verifica nome e chiave prima di salvarli. Fuori dal thread principale. */
    fun check(repo: String, token: String) { Gh(token, repo).branch() }

    // ---------- contenuti locali ----------
    private fun expBytes(e: Expense): ByteArray {
        val o = Store.expJson(e)
        o.remove("photo")
        return o.toString(1).toByteArray(Charsets.UTF_8)
    }

    private fun smsBytes(s: SmsCharge): ByteArray = Store.smsJson(s).toString(1).toByteArray(Charsets.UTF_8)

    private fun hash(b: ByteArray): String = Fmt.hash(String(b, Charsets.ISO_8859_1)) + ":" + b.size
    private fun fileHash(f: File): String = "f" + f.length() + ":" + f.lastModified()

    private class Local(val hash: String, val bytes: () -> ByteArray)

    private fun locals(ctx: Context): Map<String, Local> {
        val m = HashMap<String, Local>()
        for (e in Store.expenses) {
            val b = expBytes(e)
            m["spese/${e.id}.json"] = Local(hash(b)) { b }
            e.photo?.let { File(it) }?.takeIf { it.exists() }?.let { f -> m["foto/${e.id}.jpg"] = Local(fileHash(f)) { f.readBytes() } }
        }
        for (s in Store.sms) { val b = smsBytes(s); m["sms/${s.id}.json"] = Local(hash(b)) { b } }
        return m
    }

    private fun idOf(path: String) = path.substringAfter('/').substringBeforeLast('.')

    // ---------- sincronizzazione ----------
    @Synchronized
    fun run(ctx: Context): String {
        if (!connected) return ""
        running = true
        var down = 0; var up = 0; var del = 0
        try {
            Store.load(ctx)
            val gh = Gh(Store.repoToken, Store.repo)
            val branch = gh.branch()
            val remote = gh.tree(branch).filterKeys { it.startsWith("spese/") || it.startsWith("foto/") || it.startsWith("sms/") }

            // 1. scarico quello che è cambiato altrove (prima le spese, poi le foto, poi gli addebiti)
            var local = synchronized(Store) { locals(ctx) }
            for (path in remote.keys.sortedBy { when { it.startsWith("spese/") -> 0; it.startsWith("foto/") -> 1; else -> 2 } }) {
                val sha = remote[path]!!
                if (Store.syncSha[path] == sha) continue
                if (Store.tombstones.containsKey(path)) continue
                val changedHere = local[path]?.let { it.hash != Store.syncHash[path] } ?: false
                val bytes = gh.blob(sha)
                synchronized(Store) {
                    when {
                        path.startsWith("spese/") -> {
                            val o = JSONObject(String(bytes, Charsets.UTF_8))
                            val r = Store.expParse(o)
                            val i = Store.expenses.indexOfFirst { it.id == r.id }
                            val l = if (i >= 0) Store.expenses[i] else null
                            if (l == null || !changedHere || r.updated >= l.updated) {
                                val ph = File(Store.photoDir(ctx), "${r.id}.jpg")
                                r.photo = if (ph.exists()) ph.absolutePath else l?.photo?.takeIf { File(it).exists() }
                                if (l?.pdf != null && r.pdf == null) r.pdf = l.pdf
                                if (i >= 0) Store.expenses[i] = r else Store.expenses.add(r)
                                Store.syncHash[path] = hash(expBytes(r))
                                down++
                            }
                        }
                        path.startsWith("foto/") -> {
                            val id = idOf(path)
                            if (!changedHere) {
                                val f = File(Store.photoDir(ctx), "$id.jpg")
                                f.writeBytes(bytes)
                                Store.expenses.firstOrNull { it.id == id }?.photo = f.absolutePath
                                Store.syncHash[path] = fileHash(f)
                                down++
                            }
                        }
                        else -> {
                            val r = Store.smsParse(JSONObject(String(bytes, Charsets.UTF_8)))
                            val l = Store.smsById(r.id)
                            if (l == null) Store.sms.add(r) else { l.done = l.done || r.done; l.inStmt = l.inStmt || r.inStmt }
                            Store.syncHash[path] = hash(smsBytes(Store.smsById(r.id)!!))
                            down++
                        }
                    }
                    Store.syncSha[path] = sha
                }
            }

            // 2. file tolti altrove: li tolgo anche qui, se qui non sono cambiati
            synchronized(Store) {
                local = locals(ctx)
                for (path in Store.syncSha.keys.toList()) {
                    if (remote.containsKey(path)) continue
                    val l = local[path]
                    if (l == null || l.hash == Store.syncHash[path]) {
                        val id = idOf(path)
                        when {
                            path.startsWith("spese/") -> Store.expenses.firstOrNull { it.id == id }?.let { e -> Store.expenses.remove(e); e.photo?.let { File(it).delete() }; del++ }
                            path.startsWith("sms/") -> if (Store.sms.removeAll { it.id == id }) del++
                        }
                        Store.syncHash.remove(path)
                    }
                    Store.syncSha.remove(path)
                }
                // le foto sono cambiate di nome/percorso dopo il download: aggiorno l'impronta della spesa
                local = locals(ctx)
                Store.save(ctx)
            }

            // 3. cancellazioni fatte qui
            for ((path, _) in Store.tombstones.toList()) {
                val sha = remote[path]
                if (sha == null || gh.delete(branch, path, sha)) {
                    synchronized(Store) { Store.tombstones.remove(path); Store.syncSha.remove(path); Store.syncHash.remove(path) }
                    if (sha != null) del++
                }
            }

            // 4. carico quello che è cambiato qui
            for ((path, l) in local) {
                if (l.hash == Store.syncHash[path] && remote.containsKey(path)) continue
                val prev = remote[path] ?: Store.syncSha[path]
                val sha = gh.put(branch, path, l.bytes(), prev) ?: continue
                synchronized(Store) { Store.syncSha[path] = sha; Store.syncHash[path] = l.hash }
                up++
            }

            val msg = when {
                down + up + del == 0 -> "tutto allineato"
                else -> listOfNotNull(
                    if (down > 0) "$down ricevuti" else null,
                    if (up > 0) "$up inviati" else null,
                    if (del > 0) "$del eliminati" else null
                ).joinToString(", ")
            }
            synchronized(Store) { Store.lastSync = System.currentTimeMillis(); Store.syncMsg = msg; Store.save(ctx) }
            return msg
        } catch (e: Exception) {
            val msg = (e as? Error)?.message ?: "senza connessione, riprovo più tardi"
            synchronized(Store) { Store.syncMsg = "non riuscita: $msg"; Store.save(ctx) }
            throw e
        } finally {
            running = false
        }
    }

    /** dati da passare all'altro telefono col QR: nome del repository e chiave */
    fun qrPayload(): String =
        "notespese-sync:" + Base64.encodeToString(JSONObject().put("repo", Store.repo).put("token", Store.repoToken).toString().toByteArray(), Base64.NO_WRAP)

    fun parseQr(s: String): Pair<String, String>? {
        if (!s.startsWith("notespese-sync:")) return null
        return try {
            val o = JSONObject(String(Base64.decode(s.removePrefix("notespese-sync:"), Base64.DEFAULT)))
            o.getString("repo") to o.getString("token")
        } catch (_: Exception) { null }
    }

    fun status(): String {
        if (!connected) return ""
        val t = if (Store.lastSync > 0) java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ITALY).format(java.util.Date(Store.lastSync)) else "mai"
        return "Repository ${Store.repo} · ultima sincronizzazione $t" + (if (Store.syncMsg.isNotEmpty()) " · ${Store.syncMsg}" else "")
    }

    /** primo collegamento: dimentico lo stato precedente così tutto viene confrontato da capo */
    fun connect(repo: String, token: String) {
        synchronized(Store) {
            Store.repo = repo; Store.repoToken = token
            Store.syncSha.clear(); Store.syncHash.clear(); Store.tombstones.clear()
            Store.lastSync = 0L; Store.syncMsg = ""
        }
    }

    fun disconnect() {
        synchronized(Store) {
            Store.repo = ""; Store.repoToken = ""
            Store.syncSha.clear(); Store.syncHash.clear(); Store.tombstones.clear(); Store.syncMsg = ""
        }
    }
}
