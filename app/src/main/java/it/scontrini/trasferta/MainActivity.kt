package it.scontrini.trasferta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var showAll = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }
    /** risposta del selettore account Google del telefono */
    private var onAccount: ((String) -> Unit)? = null
    private val chooseAccount = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.let { onAccount?.invoke(it) }
    }
    private val pickLogo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { saveLogo(it) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "Note spese trasferta"
        Notifier.ensureChannel(this)
        Reminder.schedule(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(col(R.color.graphite)); isFillViewport = true }
        root = vbox().apply { setPadding(dp(16), dp(8), dp(16), dp(40)) }
        scroll.addView(root)
        setContentView(scroll)
        root.padForSystemBars()
        if (b == null) { if (Store.consentShown(this)) askPermissions() else disclosure() }
    }

    override fun onPause() {
        super.onPause()
        Lock.paused()
    }

    override fun onResume() {
        super.onResume()
        Lock.guard(this)
        Store.reload(this)
        render()
        if (Store.expenses.any { it.eurRate == null }) Thread {
            val n = try { synchronized(Store) { Fx.fill(this, Store.expenses).also { if (it > 0) Store.save(this) } } } catch (e: Exception) { 0 }
            if (n > 0) runOnUiThread { render() }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 2, 0, "Profilo e sicurezza")
        menu.add(0, 1, 1, "Impostazioni")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { settings(); return true }
        if (item.itemId == 2) { profile(first = false); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun missing(): List<String> {
        val l = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Store.useLocation) { l.add(Manifest.permission.ACCESS_FINE_LOCATION); l.add(Manifest.permission.ACCESS_COARSE_LOCATION) }
        if (Store.useLocation && Build.VERSION.SDK_INT >= 29) l.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        return l.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun askPermissions() {
        val m = missing()
        if (m.isNotEmpty()) perms.launch(m.toTypedArray())
    }

    /** Avviso chiaro, prima di chiedere i permessi, su cosa legge l'app e dove vanno i dati (richiesto da Google Play). */
    private fun disclosure() {
        val box = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        listOf(
            "SMS della banca" to "L'app legge gli SMS in arrivo per riconoscere gli addebiti con carta e ricordarti di fotografare lo scontrino. Conserva solo data, importo, esercente e ultime 4 cifre della carta; il testo degli SMS non viene salvato né inviato a nessuno.",
            "Posizione" to "Quando scatti la foto, la posizione serve a capire se sei a un distributore, in albergo o al ristorante. Viene inviata solo al servizio pubblico OpenStreetMap per trovare il locale più vicino. Puoi disattivarla in Impostazioni.",
            "Foto, PDF e spese" to "Restano sul telefono. Escono solo quando li condividi tu (per esempio su OneDrive).",
            "Commensali" to "QR, NFC e Bluetooth scambiano solo il nome che scrivi in Impostazioni, solo quando premi \"Sono a tavola\"."
        ).forEach { (t, d) ->
            box.addView(txt(t, 15f, bold = true), lp(top = dp(10)))
            box.addView(txt(d, 13.5f, R.color.ink2), lp(top = dp(2)))
        }
        val sv = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Prima di iniziare").setView(sv).setCancelable(false)
            .setPositiveButton("Ho capito, continua") { _, _ -> Store.setConsentShown(this); profile(first = true) }
            .setNegativeButton("Non ora") { _, _ -> Store.setConsentShown(this); profile(first = true) }
            .show()
    }

    private fun pickGoogleAccount(onPicked: (String) -> Unit) {
        onAccount = onPicked
        try {
            chooseAccount.launch(android.accounts.AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null))
        } catch (e: Exception) { toast("Nessun account Google sul telefono: scrivi l'email a mano.") }
    }

    /** Registrazione sul telefono: nome, email (anche dall'account Google) e blocco con impronta/PIN. */
    private fun profile(first: Boolean) {
        val box = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        box.addView(txt(if (first) "Crea il tuo profilo. Resta solo su questo telefono: nome ed email compaiono sui PDF e nel QR per i commensali." else "Il profilo resta solo su questo telefono.", 13.5f, R.color.ink2))
        val name = input("Nome e cognome", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply { setText(Store.myName) }
        val mail = input("nome@esempio.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply { setText(Store.myEmail) }
        box.addView(field("Nome e cognome", name), lp(top = dp(12)))
        box.addView(field("Email", mail), lp(top = dp(12)))
        box.addView(btn("Usa un account Google del telefono", false) { pickGoogleAccount { mail.setText(it) } }, lp(top = dp(8)))
        val lockBox = CheckBox(this).apply {
            text = "Proteggi l'app con impronta o PIN del telefono"; isChecked = Store.lockEnabled || first
            setTextColor(col(R.color.ink))
        }
        box.addView(lockBox, lp(top = dp(12)))
        Lock.unavailable(this)?.let { why -> lockBox.isEnabled = false; lockBox.isChecked = false; box.addView(txt(why, 12f, R.color.warn)) }
        val sv = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle(if (first) "Registrazione" else "Profilo e sicurezza").setView(sv).setCancelable(!first)
            .setPositiveButton("Salva") { _, _ ->
                val m = mail.text.toString().trim()
                Store.myName = name.text.toString().trim().take(60)
                Store.myEmail = if (android.util.Patterns.EMAIL_ADDRESS.matcher(m).matches()) m else ""
                val wantLock = lockBox.isChecked
                if (wantLock && !Store.lockEnabled) {
                    // conferma con impronta/PIN prima di attivare il blocco
                    Lock.prompt(this, "Conferma per attivare il blocco", {
                        Store.lockEnabled = true; Lock.unlocked(); Store.save(this); toast("Blocco attivo: all'apertura serviranno impronta o PIN."); render()
                    }, { e -> toast("Blocco non attivato: $e") })
                } else Store.lockEnabled = wantLock
                Store.save(this); render()
                if (first) askPermissions()
            }
            .apply { if (!first) setNegativeButton("Annulla", null) }
            .show()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun visible(): List<Expense> =
        if (showAll) Store.expenses.toList() else Store.expenses.filter { it.trip == Store.trip }

    private fun render() {
        root.removeAllViews()
        val gap = dp(14)

        // testata con il logo aziendale, se impostato
        val logo = Store.logoFile(this)
        if (logo.exists()) {
            BitmapFactory.decodeFile(logo.absolutePath)?.let { bmp ->
                root.addView(ImageView(this).apply {
                    setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START
                    maxHeight = dp(44)
                }, lp(h = dp(44), top = dp(4)))
            }
        }

        if (Store.myName.isNotBlank()) {
            root.addView(txt("Ciao " + Store.myName.substringBefore(' ') + (if (Store.lockEnabled) "  ·  app protetta" else ""), 13f, R.color.ink2), lp(top = dp(6)))
        }
        // trasferta
        val trip = input("Es. Città del Messico ott 2026").apply {
            setText(Store.trip); imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, _, _ -> saveTrip(v.text.toString()); false }
            setOnFocusChangeListener { v, has -> if (!has) saveTrip((v as EditText).text.toString()) }
        }
        root.addView(field("Trasferta in corso", trip), lp(top = dp(8)))

        // permessi
        val miss = missing()
        if (miss.isNotEmpty()) {
            val (c, inner) = card()
            c.strokeColor = col(R.color.warn)
            val smsMissing = miss.any { it == Manifest.permission.RECEIVE_SMS || it == Manifest.permission.READ_SMS }
            val locMissing = miss.any { it.contains("LOCATION") }
            inner.addView(txt(
                when {
                    smsMissing -> "Per avvisarti quando arriva un addebito, l'app deve poter leggere gli SMS della banca."
                    locMissing -> "Consenti la posizione: serve a capire se la foto è a un distributore, in albergo o al ristorante."
                    else -> "Consenti le notifiche per sapere quando arriva un addebito."
                }, 14f, R.color.warn
            ))
            val row = hbox()
            row.addView(btn("Consenti") { askPermissions() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            row.addView(btn("Impostazioni app", false) { openAppSettings() }, LinearLayout.LayoutParams(0, -2, 1f))
            inner.addView(row, lp(top = dp(10)))
            root.addView(c, lp(top = gap))
        }

        // azioni principali
        root.addView(btn("Nuova spesa: fotografa lo scontrino") {
            startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_CAMERA, true))
        }, lp(top = gap))
        val row = hbox()
        row.addView(btn("Dalla galleria", false) {
            startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_GALLERY, true))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row.addView(btn("A mano", false) {
            startActivity(Intent(this, ExpenseActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, lp(top = dp(8)))
        root.addView(btn("Cerca addebiti negli SMS recenti", false) { importRecent() }, lp(top = dp(8)))
        root.addView(btn(if (Table.active) "Sono a tavola: Bluetooth attivo · mostra il mio QR" else "Sono a tavola: QR, NFC e Bluetooth", false) {
            Tavolo.showMine(this) { if (!Table.active) toggleTable() }
        }, lp(top = dp(8)))

        // addebiti senza scontrino
        val pend = Store.pending()
        if (pend.isNotEmpty()) {
            root.addView(cap("Addebiti senza scontrino (${pend.size})"), lp(top = dp(22)))
            val (c, inner) = card()
            c.strokeColor = col(R.color.red)
            pend.forEachIndexed { i, s ->
                val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                val left = vbox()
                left.addView(txt(s.merchant.ifBlank { "Esercente non indicato" }, 15f, bold = true))
                left.addView(txt(listOf(Fmt.dayLabel(s.date), s.cardLabel()).filter { it.isNotEmpty() }.joinToString(" · "), 13f, R.color.ink2))
                r.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(mono(Fmt.money(s.amount) + " " + s.currency, 14f, bold = true))
                r.setOnClickListener {
                    startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id).putExtra(ExpenseActivity.EXTRA_CAMERA, true))
                }
                r.setOnLongClickListener { smsMenu(s); true }
                if (i > 0) inner.addView(View(this).apply { setBackgroundColor(col(R.color.line)) }, lp(h = dp(1)))
                inner.addView(r)
            }
            inner.addView(txt("Tocca per fotografare lo scontrino. Tieni premuto per la dichiarazione di scontrino mancante o per eliminare l'addebito.", 12f, R.color.ink2), lp(top = dp(6)))
            root.addView(c, lp(top = dp(6)))
        }

        // filtro
        root.addView(CheckBox(this).apply {
            text = "Mostra tutte le trasferte"; isChecked = showAll; setTextColor(col(R.color.ink2))
            setOnCheckedChangeListener { _, ch -> showAll = ch; render() }
        }, lp(top = dp(16)))

        val list = visible()
        if (list.isEmpty()) {
            root.addView(txt(
                if (Store.expenses.isEmpty()) "Nessuna spesa registrata. Fotografa il primo scontrino: qui vedrai i totali per carta e per categoria."
                else "Nessuna spesa in questa trasferta.", 14f, R.color.ink2
            ).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(24), dp(8), dp(24)) })
        } else {
            renderTotals(list)
            renderList(list)
            root.addView(cap("Esporta per Ztravel"), lp(top = dp(22)))
            root.addView(btn("Crea cartella per Ztravel") { exportFolder(list) }, lp(top = dp(6)))
            root.addView(txt("Ogni scontrino in ordine cronologico, con foto e PDF rinominati (001_2026-03-22_CENA_…) e il riepilogo CSV. Finisce in Download/NoteSpese e puoi inviarla a OneDrive.", 12f, R.color.ink2), lp(top = dp(4)))
            root.addView(btn("Solo CSV di riepilogo", false) { exportCsv(list) }, lp(top = dp(8)))
        }
    }

    private fun renderTotals(list: List<Expense>) {
        val m = Store.match()
        val withEur = list.filter { it.totalEur != null }
        if (list.map { it.currency }.distinct().size > 1 || list.any { it.currency != "EUR" }) {
            val (c, inner) = card()
            val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(mono("EUR", 13f, R.color.ink2, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(mono(Fmt.money(withEur.sumOf { it.totalEur ?: 0.0 }), 28f, bold = true))
            inner.addView(head)
            val miss = list.size - withEur.size
            inner.addView(txt("Controvalore in euro al cambio BCE del giorno di ogni spesa" +
                (if (miss > 0) " · $miss senza cambio (si completa quando c'è internet)" else "") + ".", 13f, R.color.ink2))
            root.addView(c, lp(top = dp(8)))
        }
        for ((cur, items) in list.groupBy { it.currency.ifBlank { "—" } }.entries.sortedByDescending { e -> e.value.sumOf { it.total } }) {
            val (c, inner) = card()
            val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(mono(cur, 13f, R.color.ink2, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(mono(Fmt.money(items.sumOf { it.total }), 28f, bold = true))
            inner.addView(head)
            val tips = items.sumOf { it.tip }
            val verified = items.count { m.e2s.containsKey(it.id) }
            inner.addView(txt(
                "${items.size} " + (if (items.size == 1) "scontrino" else "scontrini") +
                    (if (tips > 0) " · mancia " + Fmt.money(tips) else "") +
                    (if (Store.sms.isNotEmpty()) " · $verified verificati in banca" else ""), 13f, R.color.ink2
            ))
            fun group(name: String, key: (Expense) -> String) {
                inner.addView(cap(name), lp(top = dp(10)))
                for ((k, v) in items.groupBy(key).entries.sortedByDescending { e -> e.value.sumOf { it.total } }) {
                    val r = hbox()
                    r.addView(txt(k, 14f), LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(mono(Fmt.money(v.sumOf { it.total }), 14f))
                    inner.addView(r, lp(top = dp(3)))
                }
            }
            group("Per carta") { it.card.ifBlank { "Senza carta" } }
            group("Per categoria") { it.category.ifBlank { "Altro" } }
            root.addView(c, lp(top = dp(8)))
        }
    }

    private fun renderList(list: List<Expense>) {
        val m = Store.match()
        val byDay = list.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.created }).groupBy { it.date }
        for ((day, items) in byDay) {
            root.addView(cap(Fmt.dayLabel(day)), lp(top = dp(18)))
            val (c, inner) = card()
            items.forEachIndexed { i, e ->
                if (i > 0) inner.addView(View(this).apply { setBackgroundColor(col(R.color.line)) }, lp(h = dp(1)))
                val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                val left = vbox()
                left.addView(txt(e.place.ifBlank { "Senza luogo" }, 15f, bold = true))
                val meta = mutableListOf(e.category + (if (e.time.isNotEmpty()) " " + e.time else ""), e.card.ifBlank { "Senza carta" })
                if (e.tipCash > 0) meta.add("mancia a tavola " + Fmt.money(e.tipCash))
                if (e.guests.isNotEmpty()) meta.add("con " + e.guests.joinToString(", "))
                left.addView(txt(meta.joinToString(" · "), 13f, R.color.ink2))
                if (Store.sms.isNotEmpty()) {
                    val ok = m.e2s.containsKey(e.id)
                    val d = m.diff[e.id] ?: 0.0
                    left.addView(txt(
                        if (ok) "Addebito verificato" + (if (d > 0.01) " (+" + Fmt.money(d) + ")" else "") else "Senza addebito in banca",
                        12.5f, if (ok) R.color.ok else R.color.warn, true
                    ))
                }
                left.addView(mono(e.pdf ?: "Senza PDF", 11f, R.color.ink2))
                r.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                val right = vbox().apply { gravity = Gravity.END }
                right.addView(mono(Fmt.money(e.total) + " " + e.currency, 14f, bold = true))
                if (e.tipTotal > 0) right.addView(mono("mancia " + Fmt.money(e.tipTotal), 12f, R.color.ink2))
                if (e.currency != "EUR") e.totalEur?.let { right.addView(mono("≈ " + Fmt.money(it) + " EUR", 12f, R.color.ink2)) }
                if (e.declaration) right.addView(txt("dichiarazione", 11.5f, R.color.warn, true))
                r.addView(right)
                r.setOnClickListener {
                    startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_EXP, e.id))
                }
                r.setOnLongClickListener { expenseMenu(e); true }
                inner.addView(r)
            }
            root.addView(c, lp(top = dp(6)))
        }
        root.addView(txt("Tocca una spesa per modificarla. Tieni premuto per condividere il PDF o eliminarla.", 12f, R.color.ink2), lp(top = dp(8)))
    }

    private fun expenseMenu(e: Expense) {
        AlertDialog.Builder(this).setTitle(e.place)
            .setItems(arrayOf("Condividi PDF (OneDrive…)", "Modifica", "Elimina")) { _, which ->
                when (which) {
                    0 -> sharePdf(e)
                    1 -> startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_EXP, e.id))
                    2 -> AlertDialog.Builder(this).setTitle("Eliminare questa spesa?")
                        .setMessage(e.place + " · " + Fmt.money(e.total) + " " + e.currency)
                        .setPositiveButton("Elimina") { _, _ ->
                            Store.expenses.removeAll { it.id == e.id }
                            e.photo?.let { File(it).delete() }
                            Store.save(this); render()
                        }.setNegativeButton("Annulla", null).show()
                }
            }.show()
    }

    private fun smsMenu(s: SmsCharge) {
        AlertDialog.Builder(this).setTitle(Fmt.money(s.amount) + " " + s.currency + (if (s.merchant.isNotEmpty()) " · " + s.merchant else ""))
            .setItems(arrayOf("Fotografa lo scontrino", "Scontrino non disponibile: dichiarazione", "Elimina l'addebito")) { _, w ->
                when (w) {
                    0 -> startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id).putExtra(ExpenseActivity.EXTRA_CAMERA, true))
                    1 -> startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id).putExtra(ExpenseActivity.EXTRA_DECL, true))
                    2 -> deleteSms(s)
                }
            }.show()
    }

    private fun deleteSms(s: SmsCharge) {
        AlertDialog.Builder(this).setTitle("Eliminare questo addebito?")
            .setMessage(Fmt.money(s.amount) + " " + s.currency + " · " + s.merchant)
            .setPositiveButton("Elimina") { _, _ -> Store.sms.removeAll { it.id == s.id }; Store.save(this); render() }
            .setNegativeButton("Annulla", null).show()
    }

    private fun sharePdf(e: Expense) {
        Thread {
            val f = try { Exporter.pdfFor(this, e) } catch (ex: Exception) { null }
            runOnUiThread {
                if (f == null) toast("Questa spesa non ha la foto: aprila e aggiungi la foto per creare il PDF.")
                else startActivity(Exporter.shareIntent(this, f, "application/pdf", "Invia il PDF"))
            }
        }.start()
    }

    private fun exportName(ext: String): String =
        "Ztravel-" + (if (showAll) "tutte-le-trasferte" else Fmt.slug(Store.trip, 40).ifEmpty { "trasferta" }) + "." + ext

    private fun exportCsv(list: List<Expense>) {
        val f = File(Exporter.exportDir(this), exportName("csv"))
        f.writeText(Exporter.csv(list), Charsets.UTF_8)
        val saved = Exporter.toDownloads(this, f, "text/csv")
        if (saved) toast("Salvato anche in Download/Scontrini")
        startActivity(Exporter.shareIntent(this, f, "text/csv", "Invia il CSV"))
    }

    private fun exportFolder(list: List<Expense>) {
        val label = if (showAll) "tutte-le-trasferte" else Store.trip
        toast("Preparo la cartella: per i pasti scarico i dati sull'inflazione…")
        Thread {
            try {
                val r = Exporter.folder(this, list, label)
                runOnUiThread {
                    val miss = r.count - r.withPhoto
                    toast((if (r.savedToDownloads) "Cartella salvata in " + r.folder + ". " else "") +
                        "${r.count} scontrini" + (if (miss > 0) ", $miss senza foto." else "."))
                    startActivity(Exporter.shareIntent(this, r.zip, "application/zip", "Invia la cartella (ZIP) a OneDrive"))
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Non sono riuscito a creare la cartella: " + (e.message ?: "errore")) }
            }
        }.start()
    }

    private val tablePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { toggleTable() }

    private fun toggleTable() {
        if (Table.active) { Table.stop(); render(); return }
        val miss = Table.missing(this)
        if (miss.isNotEmpty()) { tablePerms.launch(miss.toTypedArray()); return }
        Table.start(this)?.let { toast(it) }
        render()
    }

    private fun importRecent() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(); return
        }
        toast("Cerco negli SMS degli ultimi 30 giorni…")
        Thread {
            var found = 0
            try {
                val since = System.currentTimeMillis() - 30L * 86_400_000L
                contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                    Telephony.Sms.DATE + " > ?", arrayOf(since.toString()), Telephony.Sms.DATE + " ASC"
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        found += SmsIngest.ingest(this, cur.getString(0) ?: "", cur.getString(1) ?: "", cur.getLong(2), false).size
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Non riesco a leggere gli SMS: controlla i permessi.") }
                return@Thread
            }
            runOnUiThread {
                toast(if (found == 0) "Nessun nuovo addebito trovato." else "Trovati $found nuovi addebiti.")
                render()
            }
        }.start()
    }

    private fun saveTrip(s: String) {
        val t = s.trim().ifEmpty { Store.defaultTrip() }
        if (t == Store.trip) return
        Store.trip = t; Store.save(this); render()
    }

    private fun saveLogo(uri: Uri) {
        Thread {
            val bmp = try { Images.load(this, uri, 600) } catch (e: Exception) { null }
            runOnUiThread {
                if (bmp == null) { toast("Non riesco ad aprire questa immagine."); return@runOnUiThread }
                Store.logoFile(this).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                toast("Logo salvato: compare qui e sui PDF.")
                render()
            }
        }.start()
    }

    private fun settings() {
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val cur = input("MXN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS).apply { setText(Store.currency) }
        val snd = input("Es. BBVA, Santander, Intesa").apply { setText(Store.senders) }
        val me = input("Nome e cognome", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply { setText(Store.myName) }
        val loc = CheckBox(this).apply { text = "Usa la posizione della foto per la categoria"; isChecked = Store.useLocation; setTextColor(col(R.color.ink)) }
        val capA = input("Es. 600", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply { if (Store.capAmount > 0) setText(Fmt.plain(Store.capAmount)) }
        val capC = input("MXN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS).apply { setText(Store.capCurrency) }
        val capY = input("Es. 2015", InputType.TYPE_CLASS_NUMBER).apply { if (Store.capYear > 0) setText(Store.capYear.toString()) }
        val dl = input("https://…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { setText(Store.downloadUrl) }
        box.addView(field("Il tuo nome (per \"Sono a tavola\")", me))
        box.addView(field("Valuta predefinita", cur), lp(top = dp(12)))
        box.addView(field("Mittenti SMS della banca", snd), lp(top = dp(12)))
        box.addView(txt("Lascia vuoto per riconoscere da solo gli SMS di addebito. Se ricevi avvisi sbagliati, scrivi il nome del mittente della banca.", 12f, R.color.ink2), lp(top = dp(4)))
        box.addView(loc, lp(top = dp(10)))
        box.addView(txt("La posizione viene confrontata con OpenStreetMap (servizio pubblico Overpass) solo per trovare il locale più vicino.", 12f, R.color.ink2))
        box.addView(cap("Massimale pasto a persona (facoltativo)"), lp(top = dp(14)))
        val capRow = hbox()
        capRow.addView(capA, LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginEnd = dp(8) })
        capRow.addView(capC, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        capRow.addView(capY, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(capRow)
        box.addView(txt("Importo, valuta, anno della policy: nei PDF dei pasti viene rivalutato con l'inflazione della Banca Mondiale e confrontato con la spesa a persona.", 12f, R.color.ink2))
        box.addView(field("Link per scaricare l'app (nel QR e nell'NFC)", dl), lp(top = dp(14)))
        box.addView(txt("Chi non ha l'app e legge il tuo QR o il tuo telefono apre questa pagina. Deve essere pubblica perché i colleghi la vedano.", 12f, R.color.ink2))
        box.addView(btn("Scegli logo aziendale", false) { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, lp(top = dp(16)))
        if (Store.logoFile(this).exists()) box.addView(btn("Rimuovi logo", false) { Store.logoFile(this).delete(); render() }, lp(top = dp(6)))
        val sv = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("Impostazioni").setView(sv)
            .setPositiveButton("Salva") { _, _ ->
                val c = cur.text.toString().trim().uppercase()
                if (Regex("^[A-Z]{3}$").matches(c)) Store.currency = c
                Store.senders = snd.text.toString().trim()
                Store.myName = me.text.toString().trim().take(60)
                Store.useLocation = loc.isChecked
                Store.capAmount = Fmt.num(capA.text.toString()) ?: 0.0
                Store.capCurrency = capC.text.toString().trim().uppercase().take(3)
                Store.capYear = capY.text.toString().trim().toIntOrNull() ?: 0
                dl.text.toString().trim().let { if (it.startsWith("https://")) Store.downloadUrl = it }
                Store.save(this); render()
            }.setNegativeButton("Annulla", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
