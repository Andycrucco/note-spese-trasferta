package it.scontrini.trasferta

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Calendar

class ExpenseActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SMS = "sms"
        const val EXTRA_EXP = "exp"
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_GALLERY = "gallery"
    }

    private var editing: Expense? = null
    private var sms: SmsCharge? = null
    private lateinit var expId: String
    private var photo: File? = null
    private var date = ""
    private var dupAck = false
    private var busy = false
    private var camUri: Uri? = null

    // indizi per la categoria
    private var receiptText = ""
    private var tags: Map<String, String>? = null
    private var lat: Double? = null
    private var lon: Double? = null
    private var country = ""
    private var poi = ""
    private var catReason = ""
    private var autoCat: String? = null
    private var userCat = false

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var dateField: TextView
    private lateinit var time: EditText
    private lateinit var place: EditText
    private lateinit var amount: EditText
    private lateinit var tip: EditText
    private lateinit var tipCash: EditText
    private lateinit var tipCashBox: View
    private lateinit var tipInfo: TextView
    private lateinit var cur: EditText
    private lateinit var card: EditText
    private lateinit var cat: Spinner
    private lateinit var catInfo: TextView
    private lateinit var guests: EditText
    private lateinit var guestBox: LinearLayout
    private lateinit var chips: ChipGroup
    private lateinit var tableBtn: MaterialButton
    private lateinit var smsInfo: TextView
    private lateinit var fname: TextView
    private lateinit var photoBtns: LinearLayout
    private lateinit var pdfBtn: MaterialButton
    private lateinit var saveBtn: MaterialButton

    private val takePic = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) camUri?.let { process(it, fromCamera = true) }
    }
    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { process(it, fromCamera = false) }
    }
    private val afterShare = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { next() }
    private val tablePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { toggleTable(true) }
    private val locPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.load(this)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        editing = Store.expense(intent.getStringExtra(EXTRA_EXP))
        sms = Store.smsById(intent.getStringExtra(EXTRA_SMS))
        expId = editing?.id ?: (b?.getString("expId") ?: Store.newId("x"))
        camUri = b?.getString("camUri")?.let { Uri.parse(it) }
        title = when {
            editing != null -> "Modifica spesa"
            sms != null -> "Abbina scontrino"
            else -> "Nuova spesa"
        }
        build()
        fill(b)
        sms?.let { Notifier.cancel(this, it) }
        if (b == null && Store.useLocation && !Where.hasPermission(this)) {
            locPerms.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (b == null && editing == null) {
            when {
                intent.getBooleanExtra(EXTRA_CAMERA, false) -> preview.post { camera() }
                intent.getBooleanExtra(EXTRA_GALLERY, false) -> preview.post { gallery() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Table.onChange = { runOnUiThread { renderChips(); renderTableBtn() } }
        renderTableBtn()
    }

    override fun onPause() {
        super.onPause()
        Table.onChange = null
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("expId", expId)
        camUri?.let { out.putString("camUri", it.toString()) }
        out.putString("date", date)
        photo?.let { out.putString("photo", it.absolutePath) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ---------- interfaccia ----------
    private fun build() {
        val scroll = ScrollView(this).apply { setBackgroundColor(col(R.color.graphite)) }
        val root = vbox().apply { setPadding(dp(16), dp(8), dp(16), dp(40)) }
        scroll.addView(root)
        setContentView(scroll)

        sms?.let { s ->
            val (c, inner) = card()
            c.strokeColor = col(R.color.red)
            inner.addView(cap("Addebito in banca"))
            inner.addView(mono(Fmt.money(s.amount) + " " + s.currency, 22f, bold = true))
            inner.addView(txt(listOf(s.merchant, Fmt.dayLabel(s.date), s.cardLabel()).filter { it.isNotEmpty() }.joinToString(" · "), 13f, R.color.ink2))
            root.addView(c, lp())
        }

        val top = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE
            setBackgroundColor(col(R.color.surface_high))
        }
        top.addView(preview, LinearLayout.LayoutParams(dp(84), dp(120)).apply { marginEnd = dp(12) })
        status = txt("", 14f, R.color.ink2)
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top, lp(top = dp(12)))

        photoBtns = hbox()
        photoBtns.addView(btn("Scatta foto") { camera() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        photoBtns.addView(btn("Galleria / Google Foto", false) { gallery() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(photoBtns, lp(top = dp(10)))

        dateField = txt("", 16f).apply {
            minHeight = dp(46); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0); setBackgroundColor(col(R.color.surface))
            setOnClickListener { pickDate() }
        }
        time = input("HH:MM", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        place = input("Esercizio, città")
        amount = input("0,00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        tip = input("0,00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        tipCash = input("0,00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        cur = input("MXN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        card = input("VISA 1234 oppure Contanti")
        cat = Spinner(this).apply {
            adapter = ArrayAdapter(this@ExpenseActivity, android.R.layout.simple_spinner_dropdown_item, CATS)
        }
        guests = input("Es. Mario Rossi, Anna Bianchi", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        chips = ChipGroup(this)

        fun two(a: View, b: View): LinearLayout = hbox().apply {
            addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(two(field("Data", dateField), field("Ora", time)), lp(top = dp(16)))
        root.addView(field("Luogo", place), lp(top = dp(12)))
        root.addView(two(field("Categoria", cat), field("Valuta", cur)), lp(top = dp(12)))
        catInfo = txt("", 12.5f, R.color.ink2)
        root.addView(catInfo, lp(top = dp(4)))
        root.addView(two(field("Importo scontrino", amount), field("Mancia su scontrino", tip)), lp(top = dp(12)))
        tipCashBox = field("Mancia lasciata in contanti a tavola", tipCash)
        root.addView(tipCashBox, lp(top = dp(12)))
        tipInfo = txt("", 13f, R.color.ink2)
        root.addView(tipInfo, lp(top = dp(4)))
        root.addView(field("Carta", card), lp(top = dp(12)))

        tableBtn = btn("Sono a tavola: mostra il mio QR", false) { Tavolo.showMine(this) { toggleTable(true) } }
        val addBtn = btn("Aggiungi commensale (QR o NFC)", false) { Tavolo.addFrom(this) { n -> addGuest(n) } }
        guestBox = vbox().apply {
            addView(cap("Commensali (con chi eri a tavola)"))
            addView(guests, lp())
            addView(chips, lp(top = dp(4)))
            addView(addBtn, lp(top = dp(6)))
            addView(tableBtn, lp(top = dp(6)))
            addView(txt("Tre modi, solo tra colleghi con l'app: inquadri il loro QR, avvicini i telefoni (NFC) o li trovi via Bluetooth con \"Sono a tavola\" (10 minuti). Nessun server.", 11.5f, R.color.ink2), lp(top = dp(4)))
        }
        root.addView(guestBox, lp(top = dp(12)))

        smsInfo = txt("", 13.5f, R.color.ink2).apply { visibility = View.GONE }
        root.addView(smsInfo, lp(top = dp(12)))

        root.addView(cap("Nome del file"), lp(top = dp(16)))
        fname = mono("", 12f, R.color.ink2).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(col(R.color.surface)) }
        root.addView(fname, lp(top = dp(4)))

        pdfBtn = btn("Crea PDF e salva") { save(true) }
        saveBtn = btn("Salva senza PDF", false) { save(false) }
        root.addView(pdfBtn, lp(top = dp(18)))
        root.addView(saveBtn, lp(top = dp(8)))

        listOf(place, amount, tip, tipCash, cur, card).forEach { it.doAfterTextChanged { dupAck = false; refresh() } }
        time.doAfterTextChanged { if (!userCat) recompute() else refresh() }
        guests.doAfterTextChanged { renderChips(); refresh() }
        cat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (autoCat != null && CATS[pos] != autoCat) { userCat = true; catInfo.text = "Categoria scelta da te." }
                refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun fill(b: Bundle?) {
        val e = editing
        val s = sms
        cur.setText(Store.currency)
        setDate(Fmt.today())
        if (e != null) {
            setDate(e.date); time.setText(e.time); place.setText(e.place)
            amount.setText(Fmt.plain(e.amount)); if (e.tip > 0) tip.setText(Fmt.plain(e.tip))
            if (e.tipCash > 0) tipCash.setText(Fmt.plain(e.tipCash))
            cur.setText(e.currency); card.setText(e.card)
            setCat(e.category, auto = false); userCat = true
            guests.setText(e.guests.joinToString(", "))
            lat = e.lat; lon = e.lon; country = e.country; poi = e.poi; catReason = e.catReason
            catInfo.text = if (e.catReason.isNotEmpty()) "Categoria da: " + e.catReason else ""
            e.photo?.let { File(it) }?.takeIf { it.exists() }?.let { showPhoto(it) }
            status.text = "Modifica i dati e salva."
        } else if (s != null) {
            if (s.date.isNotEmpty()) setDate(s.date)
            place.setText(s.merchant); amount.setText(Fmt.plain(s.amount))
            if (s.currency.isNotEmpty()) cur.setText(s.currency)
            card.setText(s.cardLabel().removePrefix("carta "))
            status.text = "Fotografa lo scontrino: lo leggo e controllo che coincida con l'addebito."
            recompute()
        } else {
            status.text = "Fotografa lo scontrino o scegli la foto: leggo io data, importo, mancia e carta."
        }
        b?.getString("date")?.let { if (it.isNotEmpty()) setDate(it) }
        b?.getString("photo")?.let { File(it) }?.takeIf { it.exists() }?.let { showPhoto(it) }
        renderChips(); refresh()
    }

    private fun setDate(iso: String) {
        date = Fmt.isoValid(iso)
        dateField.text = if (date.isEmpty()) "Scegli la data" else Fmt.dmy(date)
    }

    private fun setCat(c: String, auto: Boolean) {
        val i = CATS.indexOf(c).coerceAtLeast(0)
        if (auto) autoCat = CATS[i]
        cat.setSelection(i)
    }

    private fun pickDate() {
        val c = Calendar.getInstance()
        if (date.isNotEmpty()) c.set(date.substring(0, 4).toInt(), date.substring(5, 7).toInt() - 1, date.substring(8, 10).toInt())
        DatePickerDialog(this, { _, y, m, d -> setDate(Fmt.validDate(y, m + 1, d)); refresh() },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    /** Ricalcola la categoria da scontrino + posizione + SMS + ora, se non l'hai scelta tu. */
    private fun recompute() {
        if (userCat) return
        val a = Fmt.num(tip.text.toString()) ?: 0.0
        val h = Categorizer.decide(receiptText, sms?.merchant, tags, time.text.toString(), a > 0)
        catReason = h.reason
        setCat(h.category, auto = true)
        catInfo.text = "Categoria da: " + h.reason
        refresh()
    }

    private fun renderChips() {
        val sel = Fmt.parseGuests(guests.text.toString()).map { it.lowercase() }
        val near = Table.recentNames().map { it.lowercase() }.toSet()
        chips.removeAllViews()
        for (n in Store.knownGuests()) {
            val on = sel.contains(n.lowercase())
            chips.addView(Chip(this).apply {
                text = if (near.contains(n.lowercase())) "$n · al tavolo" else n
                isCheckable = true; isChecked = on
                setOnClickListener {
                    val list = Fmt.parseGuests(guests.text.toString()).toMutableList()
                    val i = list.indexOfFirst { it.equals(n, true) }
                    if (i >= 0) list.removeAt(i) else list.add(n)
                    guests.setText(list.joinToString(", "))
                    guests.setSelection(guests.text.length)
                }
            })
        }
    }

    private fun renderTableBtn() {
        if (!::tableBtn.isInitialized) return
        tableBtn.text = if (Table.active) "Sono a tavola: Bluetooth attivo · mostra il mio QR" else "Sono a tavola: mostra il mio QR"
    }

    private fun addGuest(n: String) {
        val list = Fmt.parseGuests(guests.text.toString()).toMutableList()
        if (list.none { it.equals(n, true) }) list.add(n)
        guests.setText(list.joinToString(", "))
        guests.setSelection(guests.text.length)
        toast("Aggiunto: $n")
    }

    private fun toggleTable(on: Boolean) {
        if (!on) { Table.stop(); renderTableBtn(); return }
        if (Table.active) return
        val miss = Table.missing(this)
        if (miss.isNotEmpty()) { tablePerms.launch(miss.toTypedArray()); return }
        Table.start(this)?.let { toast(it) }
        renderTableBtn()
    }

    private fun current(withPdf: Boolean): Expense {
        val e = editing
        val a = Fmt.num(amount.text.toString()) ?: 0.0
        val t = Fmt.num(tip.text.toString())?.takeIf { it > 0 } ?: 0.0
        val tc = Fmt.num(tipCash.text.toString())?.takeIf { it > 0 } ?: 0.0
        val category = CATS[cat.selectedItemPosition.coerceIn(0, CATS.size - 1)]
        val x = Expense(
            id = expId, trip = e?.trip ?: Store.trip, date = date, place = place.text.toString().trim(),
            amount = Fmt.round2(a), tip = Fmt.round2(t), currency = cur.text.toString().trim().uppercase().take(3),
            card = card.text.toString().trim(), category = category,
            guests = Fmt.parseGuests(guests.text.toString()), pdf = e?.pdf, photo = photo?.absolutePath ?: e?.photo,
            bankId = e?.bankId ?: sms?.id, created = e?.created ?: System.currentTimeMillis(),
            time = Fmt.time(time.text.toString()), tipCash = if (category in MEALS) Fmt.round2(tc) else 0.0,
            lat = lat, lon = lon, country = country, poi = poi,
            catReason = if (userCat) "scelta manuale" else catReason
        )
        if (withPdf) x.pdf = x.pdfName()
        return x
    }

    private fun refresh() {
        val e = current(false)
        fname.text = e.pdfName()
        val meal = e.isMeal
        guestBox.visibility = if (meal || e.guests.isNotEmpty()) View.VISIBLE else View.GONE
        tipCashBox.visibility = if (meal) View.VISIBLE else View.GONE
        if (e.tipTotal > 0 && e.amount > 0) {
            tipInfo.visibility = View.VISIBLE
            tipInfo.text = "Mancia totale " + Fmt.money(e.tipTotal) + " " + e.currency + " · " +
                String.format(java.util.Locale.ITALY, "%.1f", e.tipPct()) + "% del conto" +
                (if (e.people > 1) " · " + Fmt.money(e.tipTotal / e.people) + " a persona" else "")
        } else tipInfo.visibility = View.GONE
        photoBtns.visibility = if (photo == null) View.VISIBLE else View.GONE
        val s = sms
        if (s == null || photo == null) {
            smsInfo.visibility = View.GONE
        } else {
            val d = Fmt.round2(s.amount - e.charged)
            smsInfo.visibility = View.VISIBLE
            when {
                e.amount <= 0 -> { smsInfo.text = "Addebito in banca: " + Fmt.money(s.amount) + " " + s.currency; smsInfo.setTextColor(col(R.color.ink2)) }
                Fmt.same(d, 0.0) -> { smsInfo.text = "Scontrino e addebito in banca coincidono."; smsInfo.setTextColor(col(R.color.ok)) }
                d > 0 -> { smsInfo.text = "L'addebito supera lo scontrino di " + Fmt.money(d) + ": forse una mancia aggiunta al POS non segnata."; smsInfo.setTextColor(col(R.color.warn)) }
                else -> { smsInfo.text = "L'addebito è inferiore allo scontrino di " + Fmt.money(-d) + ". Controlla l'importo."; smsInfo.setTextColor(col(R.color.warn)) }
            }
        }
        pdfBtn.isEnabled = !busy; saveBtn.isEnabled = !busy
    }

    // ---------- foto, lettura, posizione ----------
    private fun camera() {
        val f = File(cacheDir, "cam/$expId.jpg").apply { parentFile?.mkdirs() }
        val u = FileProvider.getUriForFile(this, "$packageName.files", f)
        camUri = u
        try { takePic.launch(u) } catch (e: ActivityNotFoundException) { toast("Nessuna app fotocamera trovata. Usa la galleria.") }
    }

    private fun gallery() {
        try { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (e: ActivityNotFoundException) { toast("Nessuna galleria disponibile.") }
    }

    private fun process(uri: Uri, fromCamera: Boolean) {
        status.text = "Preparo la foto…"; status.setTextColor(col(R.color.ink))
        Thread {
            val bmp = try { Images.load(this, uri) } catch (e: Exception) { null }
            if (bmp == null) {
                runOnUiThread { status.text = "Non riesco ad aprire questa foto. Provane un'altra o compila a mano."; status.setTextColor(col(R.color.warn)) }
                return@Thread
            }
            val exifPos = Where.fromPhoto(this, uri)
            val out = File(Store.photoDir(this), "$expId.jpg")
            try { Images.saveJpeg(bmp, out) } catch (_: Exception) {}
            runOnUiThread { showPhoto(out, bmp); read(bmp) }
            if (Store.useLocation) locate(exifPos ?: if (fromCamera) Where.now(this) else null)
        }.start()
    }

    /** Fuori dal thread principale: paese e luogo più vicino (distributore, albergo, ristorante…). */
    private fun locate(pos: Pair<Double, Double>?) {
        if (pos == null) return
        val (la, lo) = pos
        val cc = Where.country(this, la, lo)
        val t = Where.nearestPlace(la, lo)
        runOnUiThread {
            lat = la; lon = lo
            if (cc.isNotEmpty()) country = cc
            if (t != null) {
                tags = t
                poi = t["name"] ?: t["brand"] ?: ""
            }
            recompute()
        }
    }

    private fun showPhoto(f: File, bmp: Bitmap? = null) {
        photo = f
        preview.setImageBitmap(bmp ?: BitmapFactory.decodeFile(f.absolutePath))
        preview.visibility = View.VISIBLE
        preview.setOnClickListener { AlertDialog.Builder(this).setItems(arrayOf("Scatta di nuovo", "Scegli un'altra foto")) { _, w -> if (w == 0) camera() else gallery() }.show() }
        refresh()
    }

    private fun read(bmp: Bitmap) {
        status.text = "Leggo lo scontrino…"; status.setTextColor(col(R.color.ink))
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t -> apply(ReceiptParser.parse(t.text, Store.currency, sms?.amount)) }
            .addOnFailureListener {
                status.text = "Non sono riuscito a leggere lo scontrino. Compila i campi a mano."
                status.setTextColor(col(R.color.warn))
            }
    }

    private fun apply(r: ReceiptData) {
        receiptText = r.text
        if (editing == null || date.isEmpty()) { if (r.date.isNotEmpty()) setDate(r.date) }
        if (r.time.isNotEmpty() && time.text.isBlank()) time.setText(r.time)
        if (r.place.isNotEmpty() && (place.text.isBlank() || sms != null)) place.setText(r.place)
        r.amount?.let { amount.setText(Fmt.plain(it)) }
        r.tip?.let { tip.setText(Fmt.plain(it)) }
        if (r.currency.isNotEmpty() && (cur.text.isBlank() || sms == null)) cur.setText(r.currency)
        if (r.card.isNotEmpty() && (card.text.isBlank() || r.card == "Contanti")) card.setText(r.card)
        val names = mapOf("date" to "data", "place" to "luogo", "amount" to "importo")
        val unsure = r.uncertain.mapNotNull { names[it] }
        status.text = if (unsure.isEmpty()) "Letto. Controlla i dati e salva." else "Letto. Controlla bene: " + unsure.joinToString(", ") + "."
        status.setTextColor(col(if (unsure.isEmpty()) R.color.ink2 else R.color.warn))
        recompute()
    }

    // ---------- salvataggio ----------
    private fun validate(e: Expense): String? {
        val m = mutableListOf<String>()
        if (e.date.isEmpty()) m.add("la data")
        if (e.place.isEmpty()) m.add("il luogo")
        if (e.amount <= 0) m.add("l'importo")
        if (!Regex("^[A-Z]{3}$").matches(e.currency)) m.add("la valuta (3 lettere, es. MXN)")
        return if (m.isEmpty()) null else "Controlla " + m.joinToString(", ") + "."
    }

    private fun save(withPdf: Boolean) {
        if (busy) return
        val e = current(withPdf)
        validate(e)?.let { status.text = it; status.setTextColor(col(R.color.warn)); return }
        if (editing == null && !dupAck && Store.expenses.any {
                it.date == e.date && it.currency == e.currency && Fmt.same(it.amount, e.amount) &&
                    Fmt.slug(it.place, 30).equals(Fmt.slug(e.place, 30), true)
            }) {
            dupAck = true
            status.text = "Esiste già una spesa con stessa data, importo e luogo. Tocca di nuovo per salvarla comunque."
            status.setTextColor(col(R.color.warn)); return
        }
        busy = true; refresh()
        status.text = if (withPdf) (if (e.isMeal) "Creo il PDF con la pagina sull'inflazione…" else "Creo il PDF…") else "Salvo…"
        Thread {
            try {
                var pdf: File? = null
                if (withPdf) {
                    pdf = Exporter.pdfFor(this, e, rebuild = true)
                    pdf?.let { Exporter.toDownloads(this, it, "application/pdf") }
                }
                synchronized(Store) {
                    val i = Store.expenses.indexOfFirst { it.id == e.id }
                    if (i >= 0) Store.expenses[i] = e else Store.expenses.add(e)
                    Store.save(this)
                }
                runOnUiThread {
                    busy = false
                    toast("Registrato: " + e.place + ", " + Fmt.money(e.total) + " " + e.currency)
                    if (pdf != null) afterShare.launch(Exporter.shareIntent(this, pdf, "application/pdf", "Invia il PDF a OneDrive"))
                    else next()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    busy = false; refresh()
                    status.text = "Non sono riuscito a salvare: " + (ex.message ?: "errore"); status.setTextColor(col(R.color.warn))
                }
            }
        }.start()
    }

    /** Dopo il salvataggio propone il prossimo addebito ancora senza scontrino. */
    private fun next() {
        val pend = Store.pending()
        if (editing != null || pend.isEmpty()) { finish(); return }
        val s = pend.first()
        AlertDialog.Builder(this)
            .setTitle("Ancora ${pend.size} " + (if (pend.size == 1) "addebito" else "addebiti") + " senza scontrino")
            .setMessage("Prossimo: " + Fmt.money(s.amount) + " " + s.currency + listOf(s.merchant, Fmt.dmy(s.date)).filter { it.isNotEmpty() }.joinToString(" · ", " · "))
            .setPositiveButton("Fotografa") { _, _ ->
                startActivity(Intent(this, ExpenseActivity::class.java).putExtra(EXTRA_SMS, s.id).putExtra(EXTRA_CAMERA, true))
                finish()
            }
            .setNegativeButton("Più tardi") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
