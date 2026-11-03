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
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var drawer: DrawerLayout
    private lateinit var toggle: ActionBarDrawerToggle
    private lateinit var menuPanel: LinearLayout
    private var todoAnchor: View? = null
    private var exportAnchor: View? = null
    @Volatile private var checkingUpdates = false
    /** trasferta mostrata: null = quella in corso, ALL = tutte divise per trasferta, altrimenti una dello storico */
    private var viewTrip: String? = null
    private val ALL = "\u0000tutte"

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }
    /** risposta del selettore account Google del telefono */
    private var onAccount: ((String) -> Unit)? = null
    private val chooseAccount = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.let { onAccount?.invoke(it) }
    }
    private val pickStatement = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importStatement(it) } }
    private val pickLogo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { saveLogo(it) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "Note spese trasferta"
        Notifier.ensureChannel(this)
        Reminder.schedule(this)
        scroll = ScrollView(this).apply { setBackgroundColor(col(R.color.graphite)); isFillViewport = true }
        root = vbox().apply { setPadding(dp(16), dp(8), dp(16), dp(40)) }
        scroll.addView(root)
        // menu laterale classico (☰): tutte le sezioni dell'app, compresi gli aggiornamenti
        drawer = DrawerLayout(this)
        drawer.addView(scroll, DrawerLayout.LayoutParams(-1, -1))
        menuPanel = vbox().apply { setPadding(0, 0, 0, dp(24)) }
        val menuScroll = ScrollView(this).apply { setBackgroundColor(col(R.color.surface)); addView(menuPanel) }
        drawer.addView(menuScroll, DrawerLayout.LayoutParams(dp(300), -1).apply { gravity = GravityCompat.START })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(menuPanel) { v, ins ->
            val bars = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom + dp(24)); ins
        }
        setContentView(drawer)
        toggle = ActionBarDrawerToggle(this, drawer, R.string.menu_apri, R.string.menu_chiudi)
        drawer.addDrawerListener(toggle)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toggle.syncState()
        root.padForSystemBars()
        if (b == null) { if (Store.consentShown(this)) askPermissions() else disclosure() }
    }

    override fun onPostCreate(b: Bundle?) {
        super.onPostCreate(b)
        toggle.syncState()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START)
        else @Suppress("DEPRECATION") super.onBackPressed()
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
        askCardOnce()
        Sync.onDone = { runOnUiThread { if (!isFinishing) render() } }
        Sync.later(this, force = false)
        if (!Updates.fromStore && Updates.due(this) && !checkingUpdates) {
            checkingUpdates = true
            Thread {
                try { Updates.check(this) } catch (_: Exception) {}
                checkingUpdates = false
                runOnUiThread { if (!isFinishing) render() }
            }.start()
        }
        if (Store.expenses.any { it.eurRate == null }) Thread {
            val n = try { synchronized(Store) { Fx.fill(this, Store.expenses).also { if (it > 0) Store.save(this) } } } catch (e: Exception) { 0 }
            if (n > 0) runOnUiThread { render() }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 2, 0, "Profilo e sicurezza")
        menu.add(0, 1, 1, "Impostazioni")
        menu.add(0, 3, 2, "Collega MacroDroid")
        menu.add(0, 4, 0, "Repository condiviso")
        menu.add(0, 5, 3, "Aggiornamenti")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) return true
        if (item.itemId == 5) { updatesDialog(); return true }
        if (item.itemId == 1) { settings(); return true }
        if (item.itemId == 2) { profile(first = false); return true }
        if (item.itemId == 3) { macroDroid(); return true }
        if (item.itemId == 4) { repoDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun missing(): List<String> {
        val l = if (BuildConfig.SMS) mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS) else mutableListOf()
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
            "SMS della banca" to (if (BuildConfig.SMS) "L'app legge gli SMS in arrivo per riconoscere gli addebiti con carta e ricordarti di fotografare lo scontrino." else "Questa versione non legge gli SMS da sola: li ricevi da MacroDroid o con Condividi dall'app Messaggi.") + " Conserva solo data, importo, esercente e ultime 4 cifre della carta; il testo degli SMS non viene salvato né inviato a nessuno.",
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

    // ---------- menu laterale ----------
    private fun buildMenu() {
        menuPanel.removeAllViews()
        val head = vbox().apply { setPadding(dp(20), dp(20), dp(20), dp(16)); setBackgroundColor(col(R.color.red)) }
        val logoF = Store.logoFile(this)
        val logoBmp = if (logoF.exists()) BitmapFactory.decodeFile(logoF.absolutePath) else null
        if (logoBmp != null) {
            // logo aziendale caricato in Impostazioni, su fondo bianco come nei PDF
            head.addView(ImageView(this).apply {
                setImageBitmap(logoBmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START
                setBackgroundColor(android.graphics.Color.WHITE); setPadding(dp(8), dp(6), dp(8), dp(6)); maxHeight = dp(48)
            }, LinearLayout.LayoutParams(-2, dp(48)))
        } else head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        head.addView(txt("Note spese trasferta", 18f, R.color.ink, true), lp(top = dp(10)))
        head.addView(txt(listOf(Store.myName, if (Store.myCard.isNotEmpty()) "carta " + Store.myCard else "").filter { it.isNotEmpty() }.joinToString(" · ")
            .ifEmpty { "Versione " + Updates.currentName }, 13f, R.color.ink))
        menuPanel.addView(head)
        fun section(t: String) { menuPanel.addView(cap(t).apply { setPadding(dp(20), dp(18), dp(20), dp(4)) }) }
        fun item(t: String, badge: String? = null, go: () -> Unit) {
            val r = hbox().apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48); setPadding(dp(20), 0, dp(16), 0)
                isClickable = true; isFocusable = true
                val tv = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
                setOnClickListener { drawer.closeDrawer(GravityCompat.START); drawer.postDelayed({ go() }, 220) }
            }
            r.addView(txt(t, 15.5f), LinearLayout.LayoutParams(0, -2, 1f))
            if (badge != null) r.addView(txt(badge, 12f, R.color.ink, true).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2)); setBackgroundColor(col(R.color.red))
            })
            menuPanel.addView(r)
        }
        fun scrollTo(v: View?) { v?.let { a -> scroll.post { scroll.smoothScrollTo(0, maxOf(0, a.top - dp(12))) } } }
        val pend = Store.pending().size
        section("Spese")
        item("Nuova spesa: fotografa") { startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_CAMERA, true)) }
        item("Da completare", if (pend > 0) pend.toString() else null) { scrollTo(todoAnchor) }
        item("Storico trasferte") { tripHistory() }
        item("Esporta per Ztravel") { if (exportAnchor != null) scrollTo(exportAnchor) else toast("Nessuna spesa da esportare in questa trasferta.") }
        section("Dati e collegamenti")
        item("Repository condiviso", if (Sync.connected) null else "nuovo") { repoDialog() }
        item("Carica estratto conto") {
            try { pickStatement.launch(arrayOf("text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream")) }
            catch (e: Exception) { toast("Nessuna app per scegliere i file.") }
        }
        item("Incolla SMS della banca") { pasteSms() }
        item("Collega MacroDroid") { macroDroid() }
        section("App")
        item("Profilo e sicurezza") { profile(first = false) }
        item("Impostazioni") { settings() }
        item("Aggiornamenti", if (!Updates.fromStore) Updates.known(this) else null) { updatesDialog() }
        item("Informativa privacy") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Andycrucco/note-spese-trasferta/blob/main/PRIVACY.md"))) } catch (_: Exception) {}
        }
        menuPanel.addView(txt("Versione " + Updates.currentName, 12f, R.color.ink2).apply { setPadding(dp(20), dp(14), dp(20), 0) })
    }

    // ---------- aggiornamenti ----------
    private fun updatesDialog() {
        if (Updates.fromStore) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))) }
            return
        }
        val box = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        box.addView(txt("Installata: versione " + Updates.currentName, 14f, bold = true))
        val st = txt("Controllo se ci sono versioni nuove…", 13.5f, R.color.ink2)
        box.addView(st, lp(top = dp(8)))
        val notes = txt("", 13f, R.color.ink2)
        box.addView(notes, lp(top = dp(8)))
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply { visibility = View.GONE; max = 100 }
        box.addView(bar, lp(top = dp(12)))
        val dlg = AlertDialog.Builder(this).setTitle("Aggiornamenti").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Scarica e installa", null).setNegativeButton("Chiudi", null)
            .setNeutralButton("Tutte le versioni") { _, _ -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.PAGE))) } catch (_: Exception) {} }
            .create()
        var info: Updates.Info? = null
        dlg.setOnShowListener {
            val go = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            go.isEnabled = false
            go.setOnClickListener {
                val i = info ?: return@setOnClickListener
                if (!Updates.canInstall(this)) {
                    toast("Consenti \"Installa app sconosciute\" per Note spese, poi torna qui e tocca di nuovo Scarica e installa.")
                    try { startActivity(Updates.allowInstallIntent(this)) } catch (_: Exception) {}
                    return@setOnClickListener
                }
                go.isEnabled = false; bar.visibility = View.VISIBLE; bar.isIndeterminate = false
                st.text = "Scarico la versione ${i.name}…"
                Thread {
                    try {
                        val f = Updates.download(this, i) { pct -> runOnUiThread { bar.setProgressCompat(pct, true) } }
                        runOnUiThread {
                            st.text = "Scaricata. Conferma l'installazione: i tuoi dati restano."
                            try { startActivity(Updates.installIntent(this, f)) } catch (e: Exception) { st.text = "Non riesco ad aprire l'installazione: scaricala da Tutte le versioni." }
                            go.isEnabled = true; go.text = "Installa di nuovo"
                        }
                    } catch (e: Exception) {
                        runOnUiThread { st.text = "Download non riuscito: controlla la connessione e riprova."; go.isEnabled = true; bar.visibility = View.GONE }
                    }
                }.start()
            }
            Thread {
                val res = try { Result.success(Updates.check(this)) } catch (e: Exception) { Result.failure(e) }
                runOnUiThread {
                    res.onFailure { st.text = "Non riesco a controllare adesso (serve internet). Riprova più tardi." }
                    res.onSuccess { i ->
                        info = i
                        if (i == null) {
                            st.text = "Hai già l'ultima versione."; st.setTextColor(col(R.color.ok))
                        } else {
                            st.text = "Disponibile la versione ${i.name}" + (if (i.date.isNotEmpty()) " del " + Fmt.dmy(i.date) else "") +
                                (if (i.size > 0) " · " + String.format(java.util.Locale.ITALY, "%.0f MB", i.size / 1_048_576.0) else "") +
                                ". Si installa sopra a questa e i dati restano."
                            st.setTextColor(col(R.color.ink))
                            if (i.notes.isNotEmpty()) notes.text = "Novità\n" + i.notes
                            go.isEnabled = true
                        }
                        render()
                    }
                }
            }.start()
        }
        dlg.show()
    }

    /** Repository condiviso: stesso archivio su più telefoni (GitHub privato). */
    private fun repoDialog() {
        val box = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        box.addView(txt(if (Sync.connected) Sync.status() else "Spese, foto e addebiti finiscono in un tuo repository GitHub privato e ogni telefono collegato li vede. Nessun altro server.", 13f,
            if (Store.syncMsg.startsWith("non riuscita")) R.color.warn else R.color.ink2))
        val repoIn = input("proprietario/note-spese-dati").apply { setText(Store.repo.ifEmpty { "Andycrucco/note-spese-dati" }) }
        val tokIn = input("github_pat_…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { setText(Store.repoToken) }
        box.addView(field("Repository (privato)", repoIn), lp(top = dp(12)))
        box.addView(field("Chiave di accesso (token)", tokIn), lp(top = dp(12)))
        box.addView(txt("Su GitHub: Settings › Developer settings › Fine-grained tokens › solo questo repository › Contents: Read and write. Sull'altro telefono non serve riscriverla: usa il QR.", 12f, R.color.ink2), lp(top = dp(4)))
        lateinit var dlg: AlertDialog
        fun doConnect(repo: String, tok: String) {
            if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo) || tok.length < 20) { toast("Controlla nome del repository (proprietario/nome) e chiave."); return }
            toast("Controllo il repository…")
            Thread {
                val err = try { Sync.check(repo, tok); null } catch (e: Exception) { (e as? Sync.Error)?.message ?: "Senza connessione: riprova." }
                runOnUiThread {
                    if (err != null) { toast(err); return@runOnUiThread }
                    Sync.connect(repo, tok); Store.save(this); dlg.dismiss()
                    toast("Collegato. Sincronizzo spese, foto e addebiti…")
                    Thread {
                        val r = try { Sync.run(applicationContext) } catch (e: Exception) { "non riuscita: " + ((e as? Sync.Error)?.message ?: "errore") }
                        runOnUiThread { toast("Sincronizzazione: $r"); render() }
                    }.start()
                }
            }.start()
        }
        // il QR per collegare un altro telefono: sempre visibile, attivo dopo il primo collegamento
        if (!Sync.connected) box.addView(btn("Mostra il QR per l'altro telefono", false) {
            toast("Prima collega questo telefono: scrivi repository e chiave e tocca Collega. Poi qui comparirà il QR da far leggere agli altri telefoni.")
        }.apply { alpha = 0.55f }, lp(top = dp(14)))
        if (Sync.connected) {
            box.addView(btn("Sincronizza ora") {
                toast("Sincronizzo…")
                Thread {
                    val r = try { Sync.run(applicationContext) } catch (e: Exception) { "non riuscita: " + ((e as? Sync.Error)?.message ?: "errore") }
                    runOnUiThread { toast("Sincronizzazione: $r"); render() }
                }.start()
            }, lp(top = dp(14)))
            box.addView(btn("Mostra il QR per l'altro telefono", false) {
                val v = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0); gravity = Gravity.CENTER_HORIZONTAL }
                v.addView(ImageView(this).apply {
                    setImageBitmap(Tavolo.qr(Sync.qrPayload())); adjustViewBounds = true
                    setBackgroundColor(android.graphics.Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
                }, lp(w = dp(260), h = dp(260)))
                v.addView(txt("Sull'altro telefono: menu ⋮ › Repository condiviso › Leggi il QR. Contiene la chiave: mostralo solo ai tuoi telefoni.", 12.5f, R.color.warn).apply { gravity = Gravity.CENTER }, lp(top = dp(8)))
                AlertDialog.Builder(this).setTitle("Collega l'altro telefono").setView(v).setPositiveButton("Chiudi", null).show()
            }, lp(top = dp(8)))
        }
        box.addView(btn("Leggi il QR dall'altro telefono", false) {
            val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, opts).startScan()
                .addOnSuccessListener { b ->
                    val p = Sync.parseQr(b.rawValue ?: "")
                    if (p == null) toast("Questo QR non è un repository di Note spese.") else { repoIn.setText(p.first); tokIn.setText(p.second); doConnect(p.first, p.second) }
                }
                .addOnFailureListener { toast("Lettore QR non disponibile. Serve internet la prima volta per scaricarlo.") }
        }, lp(top = dp(8)))
        dlg = AlertDialog.Builder(this).setTitle("Repository condiviso").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (Sync.connected) "Salva" else "Collega", null)
            .setNegativeButton("Chiudi", null)
            .apply { if (Sync.connected) setNeutralButton("Scollega") { _, _ -> Sync.disconnect(); Store.save(this@MainActivity); render(); toast("Scollegato: i dati restano su questo telefono e nel repository.") } }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val r = repoIn.text.toString().trim().removePrefix("https://github.com/").removeSuffix(".git").trimEnd('/')
                val t = tokIn.text.toString().trim()
                if (Sync.connected && r == Store.repo && t == Store.repoToken) dlg.dismiss() else doConnect(r, t)
            }
        }
        dlg.show()
    }

    /** Istruzioni per la routine MacroDroid: ogni SMS della banca finisce in Da completare, senza aprire nulla. */
    private fun macroDroid() {
        val box = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        val st = SaveReceiver.status(this)
        box.addView(txt(if (st.isEmpty()) "Stato: nessun messaggio ricevuto finora da MacroDroid." else "Ultimo messaggio da MacroDroid: $st", 13.5f,
            if (st.isEmpty()) R.color.warn else R.color.ok, true))
        fun copyRow(label: String, value: String) {
            val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            val left = vbox()
            left.addView(txt(label, 12f, R.color.ink2))
            left.addView(mono(value, 13f, bold = true))
            r.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(txt("Copia", 13.5f, R.color.red, true).apply {
                setPadding(dp(12), dp(8), 0, dp(8))
                setOnClickListener {
                    getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                    toast("Copiato: $value")
                }
            })
            box.addView(r, lp(top = dp(6)))
        }
        fun step(n: String, t: String) { box.addView(txt(n, 14.5f, bold = true), lp(top = dp(14))); box.addView(txt(t, 13f, R.color.ink2), lp(top = dp(2))) }
        step("1. Trigger", "In MacroDroid: Aggiungi macro › Trigger › Telefonia › SMS ricevuto. Da: il numero o il nome della banca (o Qualsiasi). Contenuto: Qualsiasi.")
        step("2. Azione", "Azioni › Applicazioni › Invia intent. Destinazione: Broadcast. Compila così:")
        copyRow("Azione", SaveReceiver.ACTION)
        copyRow("Pacchetto", packageName)
        copyRow("Classe", "it.scontrini.trasferta.SaveReceiver")
        copyRow("Extra 1 · nome", "testo")
        box.addView(txt("Extra 1 · valore: tocca ··· › Testo magico › Messaggio SMS, cioè [sms_message].", 12.5f, R.color.ink2), lp(top = dp(4)))
        copyRow("Extra 2 · nome (facoltativo)", "mittente")
        box.addView(txt("Extra 2 · valore: Testo magico › Numero SMS, cioè [sms_number].", 12.5f, R.color.ink2), lp(top = dp(4)))
        step("3. Togli le azioni vecchie", "Se la macro di prima apriva la versione web, la fotocamera o l'azione ADDEBITO, eliminale: ora l'SMS viene solo salvato e ti arriva la notifica \"Salvato in Da completare\" con \"Fotografa ora\".")
        step("4. Prova", "Salva la macro e usa Prova azioni (o mandati un SMS di prova). Riapri questa schermata: lo stato in alto deve diventare verde.")
        box.addView(txt("Se lo stato resta vuoto: in Invia intent controlla che la Destinazione sia Broadcast e che Pacchetto e Classe siano copiati esatti. In alternativa scegli Destinazione Attività con la stessa azione: funziona uguale, ma lo schermo lampeggia un attimo.", 12f, R.color.ink2), lp(top = dp(12)))
        if (Store.myCard.isNotEmpty()) box.addView(txt("Vengono tenuti solo gli addebiti della carta che finisce con ${Store.myCard}.", 12f, R.color.ink2), lp(top = dp(8)))
        AlertDialog.Builder(this).setTitle("Routine MacroDroid").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Chiudi") { _, _ -> render() }
            .setNeutralButton("Prova l'app") { _, _ ->
                sendBroadcast(Intent(SaveReceiver.ACTION).setPackage(packageName).putExtra("testo", ""))
                toast("Messaggio di prova inviato all'app: riapri Collega MacroDroid per vedere lo stato.")
            }.show()
    }

    /** Chi aveva già il profilo: chiedo una volta le ultime 4 cifre della carta aziendale. */
    private fun askCardOnce() {
        val pr = getSharedPreferences("ui", MODE_PRIVATE)
        if (Store.myCard.isNotEmpty() || !Store.consentShown(this) || pr.getBoolean("cardAsked", false)) return
        pr.edit().putBoolean("cardAsked", true).apply()
        val inp = input("Es. 1234", InputType.TYPE_CLASS_NUMBER).apply { filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(4)) }
        val wrap = vbox().apply { setPadding(dp(22), dp(8), dp(22), 0); addView(inp) }
        wrap.addView(txt("L'app terrà solo gli addebiti di questa carta e la proporrà nelle nuove spese. Puoi cambiarla in Profilo e sicurezza.", 12.5f, R.color.ink2), lp(top = dp(6)))
        AlertDialog.Builder(this).setTitle("Ultime 4 cifre della carta aziendale").setView(wrap)
            .setPositiveButton("Salva") { _, _ ->
                inp.text.toString().filter { it.isDigit() }.takeIf { it.length == 4 }?.let { Store.myCard = it; Store.save(this); render() }
            }
            .setNegativeButton("Più tardi", null).show()
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
        val cardIn = input("Es. 1234", InputType.TYPE_CLASS_NUMBER).apply {
            setText(Store.myCard); filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(4))
        }
        box.addView(field("Ultime 4 cifre della carta aziendale", cardIn), lp(top = dp(12)))
        box.addView(txt("Ognuno usa la sua: l'app tiene solo gli addebiti di questa carta e la propone nelle nuove spese.", 12f, R.color.ink2), lp(top = dp(2)))
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
                Store.myCard = cardIn.text.toString().filter { it.isDigit() }.takeIf { it.length == 4 } ?: ""
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

    private fun visible(): List<Expense> = when (val v = viewTrip) {
        null -> Store.expenses.filter { it.trip == Store.trip }
        ALL -> Store.expenses.toList()
        else -> Store.expenses.filter { it.trip == v }
    }

    private fun tripLabel(t: String) = t.ifBlank { "Senza trasferta" }

    /** trasferte con almeno una spesa, dalla più recente */
    private fun trips(): List<Pair<String, List<Expense>>> =
        Store.expenses.groupBy { it.trip }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Expense>>> { e -> e.value.maxOf { it.date } }.thenBy { it.key })
            .map { it.key to it.value }

    private fun shortDay(iso: String): String {
        val p = iso.split("-")
        if (p.size != 3) return iso
        val mesi = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
        return (p[2].toIntOrNull() ?: 0).toString() + " " + (mesi.getOrNull((p[1].toIntOrNull() ?: 1) - 1) ?: "") + " " + p[0]
    }

    private fun dateRange(items: List<Expense>): String {
        val a = items.minOf { it.date }; val b = items.maxOf { it.date }
        return if (a == b) shortDay(a) else shortDay(a) + " – " + shortDay(b)
    }

    private fun totalsLine(items: List<Expense>): String {
        val byCur = items.groupBy { it.currency.ifBlank { "—" } }.entries.sortedByDescending { e -> e.value.sumOf { it.total } }
            .joinToString(" · ") { (c, v) -> Fmt.money(v.sumOf { it.total }) + " " + c }
        val eur = if (items.any { it.currency != "EUR" } && items.any { it.totalEur != null })
            "  (≈ " + Fmt.money(items.sumOf { it.totalEur ?: 0.0 }) + " EUR)" else ""
        return byCur + eur
    }

    /** Storico: elenco delle trasferte con date, scontrini e totali; tocca per aprirne una. */
    private fun tripHistory() {
        val all = trips()
        if (all.isEmpty()) { toast("Ancora nessuna spesa registrata."); return }
        val box = vbox().apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        lateinit var dlg: AlertDialog
        fun entry(title: String, sub: String, tot: String, current: Boolean, onTap: () -> Unit) {
            val (c, inner) = card()
            if (current) c.strokeColor = col(R.color.red)
            val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(txt(title, 15.5f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (current) head.addView(txt("IN CORSO", 11f, R.color.red, true))
            inner.addView(head)
            inner.addView(txt(sub, 13f, R.color.ink2), lp(top = dp(2)))
            inner.addView(mono(tot, 13.5f, bold = true), lp(top = dp(4)))
            c.isClickable = true
            c.setOnClickListener { dlg.dismiss(); onTap() }
            box.addView(c, lp(top = dp(8)))
        }
        for ((t, items) in all) {
            val n = items.size
            entry(tripLabel(t), dateRange(items) + " · $n " + (if (n == 1) "scontrino" else "scontrini"), totalsLine(items), t == Store.trip) {
                viewTrip = if (t == Store.trip) null else t; render()
            }
        }
        if (all.size > 1) entry("Tutte le trasferte", dateRange(Store.expenses) + " · ${Store.expenses.size} scontrini, divisi per trasferta", totalsLine(Store.expenses), false) {
            viewTrip = ALL; render()
        }
        dlg = AlertDialog.Builder(this).setTitle("Storico trasferte (${all.size})")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Chiudi", null).show()
    }

    private fun renameTrip(old: String) {
        val inp = input("Nome della trasferta").apply { setText(old) }
        val wrap = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(inp) }
        wrap.addView(txt("Se scrivi il nome di un'altra trasferta, le spese vengono unite.", 12f, R.color.ink2), lp(top = dp(6)))
        AlertDialog.Builder(this).setTitle("Rinomina trasferta").setView(wrap)
            .setPositiveButton("Salva") { _, _ ->
                val nu = inp.text.toString().trim()
                if (nu.isEmpty() || nu == old) return@setPositiveButton
                Store.expenses.filter { it.trip == old }.forEach { it.trip = nu }
                if (Store.trip == old) Store.trip = nu
                viewTrip = if (nu == Store.trip) null else nu
                Store.save(this); render()
            }.setNegativeButton("Annulla", null).show()
    }

    private fun render() {
        root.removeAllViews()
        val gap = dp(14)
        buildMenu()
        if (!Updates.fromStore) Updates.known(this)?.let { v ->
            val (c, inner) = card()
            c.strokeColor = col(R.color.red)
            val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            r.addView(txt("Nuova versione $v disponibile", 14.5f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(txt("Aggiorna", 14.5f, R.color.red, true).apply { setPadding(dp(10), dp(6), 0, dp(6)) })
            inner.addView(r)
            c.setOnClickListener { updatesDialog() }
            root.addView(c, lp(top = dp(6)))
        }

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

        if (Sync.connected) root.addView(txt(Sync.status(), 12f, if (Store.syncMsg.startsWith("non riuscita")) R.color.warn else R.color.ink2).apply {
            setPadding(0, dp(4), 0, 0); setOnClickListener { repoDialog() }
        })
        else if (Store.expenses.isNotEmpty()) root.addView(txt("Usi più telefoni? Collega il repository condiviso ›", 12.5f, R.color.red, true).apply {
            setPadding(0, dp(4), 0, 0); setOnClickListener { repoDialog() }
        })
        if (Store.myName.isNotBlank()) {
            root.addView(txt("Ciao " + Store.myName.substringBefore(' ') + (if (Store.myCard.isNotEmpty()) "  ·  carta " + Store.myCard else "") + (if (Store.lockEnabled) "  ·  app protetta" else ""), 13f, R.color.ink2), lp(top = dp(6)))
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
        if (BuildConfig.SMS) root.addView(btn("Cerca addebiti negli SMS recenti", false) { importRecent() }, lp(top = dp(8)))
        root.addView(btn(if (Table.active) "Sono a tavola: Bluetooth attivo · mostra il mio QR" else "Sono a tavola: QR, NFC e Bluetooth", false) {
            Tavolo.showMine(this) { if (!Table.active) toggleTable() }
        }, lp(top = dp(8)))

        // transazioni da completare: SMS ed estratto conto senza scontrino
        val pend = Store.pending()
        root.addView(cap("Da completare (${pend.size})").also { todoAnchor = it }, lp(top = dp(22)))
        val todoRow = hbox()
        todoRow.addView(btn("Incolla SMS", false) { pasteSms() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        todoRow.addView(btn("Estratto conto", false) {
            try { pickStatement.launch(arrayOf("text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream")) }
            catch (e: Exception) { toast("Nessuna app per scegliere i file.") }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(todoRow, lp(top = dp(6)))
        val doneElse = Store.sms.filter { it.done }
        SaveReceiver.status(this).takeIf { it.isNotEmpty() }?.let { st ->
            root.addView(txt("MacroDroid: $st", 12f, R.color.ink2).apply { setPadding(0, dp(6), 0, 0); setOnClickListener { macroDroid() } })
        }
        if (pend.isEmpty()) {
            root.addView(txt("Niente da completare. Gli SMS salvati senza foto, quelli ricevuti dalla versione web e i movimenti nuovi dell'estratto conto compaiono qui.", 12.5f, R.color.ink2), lp(top = dp(6)))
        } else {
            val (c, inner) = card()
            c.strokeColor = col(R.color.red)
            pend.forEachIndexed { i, s ->
                val r = hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                val left = vbox()
                left.addView(txt(s.merchant.ifBlank { "Esercente non indicato" }, 15f, bold = true))
                left.addView(txt(listOf(Fmt.dayLabel(s.date) + (if (s.time.isNotEmpty()) " " + s.time else ""), s.cardLabel(),
                    if (s.isStmt) "da estratto conto" else if (s.inStmt) "SMS · in estratto conto" else "SMS").filter { it.isNotEmpty() }.joinToString(" · "), 13f, R.color.ink2))
                r.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(mono(Fmt.money(s.amount) + " " + s.currency, 14f, bold = true))
                r.setOnClickListener {
                    startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id).putExtra(ExpenseActivity.EXTRA_CAMERA, true))
                }
                r.setOnLongClickListener { smsMenu(s); true }
                if (i > 0) inner.addView(View(this).apply { setBackgroundColor(col(R.color.line)) }, lp(h = dp(1)))
                inner.addView(r)
            }
            inner.addView(txt("Tocca per fotografare o scegliere la foto dello scontrino. Tieni premuto per la dichiarazione, per segnarla come completata nella versione web o per eliminarla.", 12f, R.color.ink2), lp(top = dp(6)))
            root.addView(c, lp(top = dp(6)))
            root.addView(btn("Invia alla versione web", false) { sendToWeb(pend) }, lp(top = dp(8)))
        }
        if (doneElse.isNotEmpty()) root.addView(txt("Completate nella versione web: ${doneElse.size} · tocca per rimetterle da completare", 12.5f, R.color.ink2).apply {
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { restoreDone(doneElse) }
        })

        // storico trasferte
        val nTrips = trips().size
        if (viewTrip != null && viewTrip != ALL && Store.expenses.none { it.trip == viewTrip }) viewTrip = null
        val v = viewTrip
        if (v == null) {
            if (nTrips > 0) root.addView(btn("Storico trasferte ($nTrips)", false) { tripHistory() }, lp(top = dp(16)))
        } else {
            val (c, inner) = card()
            c.strokeColor = col(R.color.red)
            inner.addView(cap(if (v == ALL) "Storico · tutte le trasferte" else "Storico trasferta"))
            if (v != ALL) inner.addView(txt(tripLabel(v), 17f, bold = true), lp(top = dp(2)))
            val r1 = hbox()
            r1.addView(btn("Torna a quella in corso") { viewTrip = null; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            r1.addView(btn("Altre trasferte", false) { tripHistory() }, LinearLayout.LayoutParams(0, -2, 1f))
            inner.addView(r1, lp(top = dp(10)))
            if (v != ALL) {
                val r2 = hbox()
                r2.addView(btn("Riprendi questa", false) {
                    Store.trip = v; viewTrip = null; Store.save(this); render(); toast("Le nuove spese andranno in \"" + tripLabel(v) + "\"")
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                r2.addView(btn("Rinomina", false) { renameTrip(v) }, LinearLayout.LayoutParams(0, -2, 1f))
                inner.addView(r2, lp(top = dp(8)))
            }
            root.addView(c, lp(top = dp(16)))
        }

        val list = visible()
        if (list.isEmpty()) {
            root.addView(txt(
                if (Store.expenses.isEmpty()) "Nessuna spesa registrata. Fotografa il primo scontrino: qui vedrai i totali per carta e per categoria."
                else "Nessuna spesa in questa trasferta." + (if (nTrips > 0) " Le spese delle trasferte precedenti sono nello Storico." else ""), 14f, R.color.ink2
            ).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(24), dp(8), dp(24)) })
        } else if (v == ALL) {
            renderTotals(list)
            for ((t, items) in trips()) {
                val (c, inner) = card()
                c.strokeColor = col(R.color.red)
                val head = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
                head.addView(txt(tripLabel(t), 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                if (t == Store.trip) head.addView(txt("IN CORSO", 11f, R.color.red, true))
                inner.addView(head)
                inner.addView(txt(dateRange(items) + " · ${items.size} " + (if (items.size == 1) "scontrino" else "scontrini"), 13f, R.color.ink2), lp(top = dp(2)))
                inner.addView(mono(totalsLine(items), 13.5f, bold = true), lp(top = dp(4)))
                inner.addView(txt("Apri questa trasferta ›", 12.5f, R.color.red, true), lp(top = dp(6)))
                c.isClickable = true
                c.setOnClickListener { viewTrip = if (t == Store.trip) null else t; render() }
                root.addView(c, lp(top = dp(26)))
                renderList(items, hint = false)
            }
            root.addView(txt("Tocca una spesa per modificarla. Tieni premuto per condividere il PDF o eliminarla.", 12f, R.color.ink2), lp(top = dp(8)))
            root.addView(cap("Esporta per Ztravel"), lp(top = dp(22)))
            root.addView(btn("Crea cartella per Ztravel") { exportFolder(list) }, lp(top = dp(6)))
            root.addView(btn("Solo CSV di riepilogo", false) { exportCsv(list) }, lp(top = dp(8)))
        } else {
            val label = v ?: Store.trip
            if (Exporter.needsReexport(this, label, list)) {
                val (c, inner) = card()
                c.strokeColor = col(R.color.warn)
                inner.addView(txt("Numerazione aggiornata", 15f, R.color.warn, true))
                inner.addView(txt("Da quando hai esportato la cartella sono arrivati SMS o scontrini nuovi: i numeri seguono di nuovo l'ordine delle transazioni in banca. Riesporta per avere la cartella in ordine sul PC.", 13f, R.color.ink2), lp(top = dp(2)))
                inner.addView(btn("Riesporta la cartella") { exportFolder(list) }, lp(top = dp(10)))
                root.addView(c, lp(top = dp(12)))
            }
            renderTotals(list)
            renderList(list)
            root.addView(cap("Esporta per Ztravel").also { exportAnchor = it }, lp(top = dp(22)))
            root.addView(btn("Crea cartella per Ztravel") { exportFolder(list) }, lp(top = dp(6)))
            root.addView(txt("Dentro trovi la sottocartella Ztravel: un PDF per spesa sotto i 600 KB con la pagina inflazione per pasti e pernotti (N. Alleg.), il PDF unico per la modalità massiva, il PDF \"nessun documento\" e le righe con le Mance separate. Gli altri file numerati nell'ordine della banca sono il tuo archivio. Se poi aggiungi un SMS perso, i numeri si riordinano da soli: riesporta e la cartella viene rifatta. Finisce in Download/NoteSpese e puoi inviarla a OneDrive.", 12f, R.color.ink2), lp(top = dp(4)))
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

    private fun renderList(list: List<Expense>, hint: Boolean = true) {
        val m = Store.match()
        val nums = Numbering.numbers(list)
        val width = if (list.size >= 100) 3 else 2
        val byDay = list.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.created }).groupBy { it.date }
        for ((day, items) in byDay) {
            root.addView(cap(Fmt.dayLabel(day)), lp(top = dp(18)))
            val (c, inner) = card()
            items.forEachIndexed { i, e ->
                if (i > 0) inner.addView(View(this).apply { setBackgroundColor(col(R.color.line)) }, lp(h = dp(1)))
                val r = hbox().apply { setPadding(0, dp(8), 0, dp(8)) }
                val left = vbox()
                val title = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
                nums[e.id]?.let { title.addView(mono(it.toString().padStart(width, '0'), 13f, R.color.red, true), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }) }
                Ztravel.capCheck(e)?.over?.let { over ->
                    title.addView(View(this).apply {
                        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(col(if (over) R.color.red else R.color.ok)) }
                    }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(6) })
                }
                title.addView(txt(e.place.ifBlank { "Senza luogo" }, 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                left.addView(title)
                val meta = mutableListOf(Ztravel.voce(e) + (if (e.time.isNotEmpty()) " " + e.time else ""), e.card.ifBlank { "Senza carta" } + (if (Ztravel.personal(e)) " (personale)" else ""))

                if (e.tipCash > 0) meta.add("mancia a tavola " + Fmt.money(e.tipCash))
                if (e.guests.isNotEmpty()) meta.add("con " + e.guests.joinToString(", "))
                left.addView(txt(meta.joinToString(" · "), 13f, R.color.ink2))
                Ztravel.capCheck(e)?.takeIf { it.over != null }?.let { cc ->
                    val unit = if (cc.kind == "vitto") (if (e.people > 1) " a persona (" + e.people + " a tavola)" else " a persona") else " a notte"
                    left.addView(txt((if (cc.over == true) "● Oltre il massimale: " else "● Entro il massimale: ") + Fmt.money(cc.value) + unit + " su " + Fmt.money(cc.cap) + " " + cc.cur,
                        12.5f, if (cc.over == true) R.color.red else R.color.ok, true))
                }
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
        if (hint) root.addView(txt("Tocca una spesa per modificarla. Tieni premuto per condividere il PDF o eliminarla.", 12f, R.color.ink2), lp(top = dp(8)))
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
                            Store.forgetExpense(e)
                            e.photo?.let { File(it).delete() }
                            Store.save(this); render()
                        }.setNegativeButton("Annulla", null).show()
                }
            }.show()
    }

    private fun smsMenu(s: SmsCharge) {
        AlertDialog.Builder(this).setTitle(Fmt.money(s.amount) + " " + s.currency + (if (s.merchant.isNotEmpty()) " · " + s.merchant else ""))
            .setItems(arrayOf("Fotografa lo scontrino", "Scegli la foto dalla galleria", "Scontrino non disponibile: dichiarazione", "Completata nella versione web", "Elimina l'addebito")) { _, w ->
                when (w) {
                    0 -> startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id).putExtra(ExpenseActivity.EXTRA_CAMERA, true))
                    1 -> startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id).putExtra(ExpenseActivity.EXTRA_GALLERY, true))
                    2 -> startActivity(Intent(this, ExpenseActivity::class.java).putExtra(ExpenseActivity.EXTRA_SMS, s.id).putExtra(ExpenseActivity.EXTRA_DECL, true))
                    3 -> { s.done = true; Store.save(this); render() }
                    4 -> deleteSms(s)
                }
            }.show()
    }

    private fun deleteSms(s: SmsCharge) {
        AlertDialog.Builder(this).setTitle("Eliminare questo addebito?")
            .setMessage(Fmt.money(s.amount) + " " + s.currency + " · " + s.merchant)
            .setPositiveButton("Elimina") { _, _ -> Store.forgetSms(s); Store.save(this); render(); Sync.later(this) }
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
        "Ztravel-" + (if (viewTrip == ALL) "tutte-le-trasferte" else Fmt.slug(viewTrip ?: Store.trip, 40).ifEmpty { "trasferta" }) + "." + ext

    private fun exportCsv(list: List<Expense>) {
        val f = File(Exporter.exportDir(this), exportName("csv"))
        f.writeText(Exporter.csv(list), Charsets.UTF_8)
        val saved = Exporter.toDownloads(this, f, "text/csv")
        if (saved) toast("Salvato anche in Download/Scontrini")
        startActivity(Exporter.shareIntent(this, f, "text/csv", "Invia il CSV"))
    }

    private fun exportFolder(list: List<Expense>) {
        val label = if (viewTrip == ALL) "tutte-le-trasferte" else (viewTrip ?: Store.trip)
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

    /** Versione senza permesso SMS: incolla uno o più SMS copiati (anche da MacroDroid). */
    private fun pasteSms() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val box = input("Incolla qui gli SMS di addebito", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            setSingleLine(false); minLines = 4; maxLines = 10; setText(clip)
        }
        val wrap = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(box) }
        wrap.addView(txt("Puoi incollare più SMS insieme o l'elenco copiato dalla versione web. \"Salva\" li mette in Da completare: la foto la aggiungi quando vuoi.", 12f, R.color.ink2), lp(top = dp(6)))
        AlertDialog.Builder(this).setTitle("SMS della banca").setView(wrap)
            .setPositiveButton("Salva") { _, _ -> ShareActivity.handle(this, box.text.toString(), photo = false); render() }
            .setNeutralButton("Salva e fotografa") { _, _ -> ShareActivity.handle(this, box.text.toString(), photo = true); render() }
            .setNegativeButton("Annulla", null).show()
    }

    private fun sendToWeb(list: List<SmsCharge>) {
        val text = Ns.text(list)
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Invia alla versione web"))
        } catch (e: Exception) {
            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Note spese", text))
            toast("Copiato: incollalo nella versione web, scheda Da fare.")
        }
    }

    private fun restoreDone(list: List<SmsCharge>) {
        val labels = list.map { Fmt.money(it.amount) + " " + it.currency + " · " + it.merchant.ifBlank { "?" } + " · " + Fmt.dmy(it.date) }.toTypedArray()
        val checked = BooleanArray(list.size)
        AlertDialog.Builder(this).setTitle("Rimetti da completare")
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Rimetti") { _, _ -> list.forEachIndexed { i, s -> if (checked[i]) s.done = false }; Store.save(this); render() }
            .setNegativeButton("Annulla", null).show()
    }

    /** Estratto conto: legge il file, mostra i movimenti nuovi (spuntati) e salta i doppioni. */
    private fun importStatement(uri: Uri) {
        toast("Leggo l'estratto conto…")
        Thread {
            val res = try {
                val text = Statement.readText(this, uri)
                Result.success(Statement.prepare(Statement.parse(text, Store.currency)
                    .filter { Store.myCard.isEmpty() || it.card.isEmpty() || it.card == Store.myCard }))
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                res.onFailure { e -> toast(if (e is Statement.Unsupported) e.message ?: "" else "Non riesco a leggere questo file. Prova il CSV o l'Excel dell'estratto conto.") }
                res.onSuccess { rows -> statementPreview(rows) }
            }
        }.start()
    }

    private fun statementPreview(rows: List<Statement.Row>) {
        if (rows.isEmpty()) { toast("Non ho trovato movimenti di spesa in questo file."); return }
        val fresh = rows.filter { it.dup == null }
        val dups = rows.filter { it.dup != null }
        fun label(r: Statement.Row) = Fmt.money(r.charge.amount) + " " + r.charge.currency + " · " + r.charge.merchant.ifBlank { "Movimento" } + " · " + Fmt.dmy(r.charge.date) +
            (r.expense?.let { "  (verifica \"" + it.place + "\")" } ?: "")
        fun markInStmt() { dups.forEach { r -> r.dup?.let { if (!it.isStmt) it.inStmt = true } } }
        if (fresh.isEmpty()) {
            markInStmt(); Store.save(this)
            AlertDialog.Builder(this).setTitle("Estratto conto").setMessage("${rows.size} movimenti letti: sono già tutti presenti. Niente da aggiungere.")
                .setPositiveButton("OK", null).show(); return
        }
        val checked = BooleanArray(fresh.size) { true }
        AlertDialog.Builder(this).setTitle("${fresh.size} nuovi · ${dups.size} già presenti")
            .setMultiChoiceItems(fresh.map { label(it) }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Aggiungi") { _, _ ->
                var n = 0
                fresh.forEachIndexed { i, r -> if (checked[i] && Store.smsById(r.charge.id) == null) { Store.sms.add(r.charge); n++ } }
                markInStmt(); Store.save(this); render()
                toast((if (n == 1) "Aggiunto 1 movimento" else "Aggiunti $n movimenti") + ". Quelli senza scontrino sono in Da completare.")
            }
            .setNeutralButton("Già presenti") { _, _ ->
                AlertDialog.Builder(this).setTitle("Già presenti, non aggiunti").setItems(dups.map { label(it) }.toTypedArray(), null)
                    .setPositiveButton("Indietro") { _, _ -> statementPreview(rows) }.show()
            }
            .setNegativeButton("Annulla", null).show()
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
        box.addView(cap("Massimali aziendali (vitto e soggiorno, 2015)"), lp(top = dp(14)))
        val zoneNames = listOf("Automatici (dal paese o dalla valuta)") + Ztravel.ZONES.map { it.name + " · pasto " + Fmt.plain(it.vitto) + " " + it.cur + " · notte " + Fmt.plain(it.soggiorno) + " " + it.cur }
        val zoneSp = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, zoneNames)
            setSelection(Ztravel.ZONES.indexOfFirst { it.name == Store.capZone } + 1)
        }
        box.addView(zoneSp, lp(top = dp(4)))
        box.addView(txt("Pranzo e cena a persona (mancia esclusa) e pernotto a notte, come il Controllo massimali di Ztravel. Il pallino rosso segna le spese oltre il massimale.", 12f, R.color.ink2))
        box.addView(cap("Altro massimale pasto (facoltativo, sostituisce quello aziendale)"), lp(top = dp(14)))
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
                Store.capZone = Ztravel.ZONES.getOrNull(zoneSp.selectedItemPosition - 1)?.name ?: ""
                dl.text.toString().trim().let { if (it.startsWith("https://")) Store.downloadUrl = it }
                Store.save(this); render()
            }.setNegativeButton("Annulla", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
