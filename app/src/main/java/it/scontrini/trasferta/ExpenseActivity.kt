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
        const val EXTRA_DECL = "declaration"
    }

    /** scontrino non disponibile: si compila una dichiarazione invece della foto */
    private var declaration = false

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

    private lateinit var preview: AmountPicker
    private lateinit var photoCard: View
    private lateinit var modeChips: ChipGroup
    private lateinit var cashChip: Chip
    /** cosa imposta il tocco su un numero della foto: importo, mancia o mancia in contanti */
    private var pickMode = "amount"
    private lateinit var cashPctRow: View
    private lateinit var zvoce: Spinner
    private lateinit var payS: Spinner
    private lateinit var nights: EditText
    private lateinit var nightsBox: View
    private lateinit var taxiBox: View
    private lateinit var provider: EditText
    private lateinit var route: EditText
    private lateinit var splitBox: android.widget.CheckBox
    private lateinit var noteIn: EditText
    private lateinit var capInfo: TextView
    private var zvoceTouched = false
    private var payTouched = false
    private var settingZ = false
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
        declaration = editing?.declaration ?: intent.getBooleanExtra(EXTRA_DECL, false)
        expId = editing?.id ?: (b?.getString("expId") ?: Store.newId("x"))
        camUri = b?.getString("camUri")?.let { Uri.parse(it) }
        title = when {
            declaration -> "Dichiarazione scontrino mancante"
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
        if (b == null && editing == null && !declaration) {
            when {
                intent.getBooleanExtra(EXTRA_CAMERA, false) -> preview.post { camera() }
                intent.getBooleanExtra(EXTRA_GALLERY, false) -> preview.post { gallery() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Lock.guard(this)
        Table.onChange = { runOnUiThread { renderChips(); renderTableBtn() } }
        renderTableBtn()
    }

    override fun onPause() {
        super.onPause()
        Lock.paused()
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
        root.padForSystemBars()

        sms?.let { s ->
            val (c, inner) = card()
            c.strokeColor = col(R.color.red)
            inner.addView(cap("Addebito in banca"))
            inner.addView(mono(Fmt.money(s.amount) + " " + s.currency, 22f, bold = true))
            inner.addView(txt(listOf(s.merchant, Fmt.dayLabel(s.date), s.cardLabel()).filter { it.isNotEmpty() }.joinToString(" · "), 13f, R.color.ink2))
            root.addView(c, lp())
        }

        // foto grande con i numeri letti: rosso = importo, giallo = mancia; tocca un numero per correggere
        val (pc, pin) = card()
        photoCard = pc; pc.visibility = View.GONE
        preview = AmountPicker(this).apply { onPick = { v -> picked(v) } }
        pin.addView(preview, lp(h = dp(380)))
        val modeRow = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        modeRow.addView(txt("Tocca un numero per:", 13f, R.color.ink2), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        modeChips = ChipGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        fun modeChip(label: String, mode: String) = Chip(this).apply {
            text = label; isCheckable = true; isChecked = mode == pickMode
            setOnClickListener { pickMode = mode }
        }.also { modeChips.addView(it) }
        modeChip("Importo", "amount"); modeChip("Mancia", "tip")
        cashChip = modeChip("Contanti", "cash")
        modeRow.addView(modeChips, LinearLayout.LayoutParams(0, -2, 1f))
        pin.addView(modeRow, lp(top = dp(6)))
        pin.addView(txt("Rosso = importo, giallo = mancia. Due dita per ingrandire.", 12f, R.color.ink2))
        pin.addView(txt("Cambia foto", 13.5f, R.color.red, true).apply {
            setPadding(0, dp(8), 0, dp(2))
            setOnClickListener { AlertDialog.Builder(this@ExpenseActivity).setItems(arrayOf("Scatta di nuovo", "Scegli un'altra foto")) { _, w -> if (w == 0) camera() else gallery() }.show() }
        })
        root.addView(pc, lp(top = dp(12)))
        status = txt("", 14f, R.color.ink2)
        root.addView(status, lp(top = dp(10)))

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
        root.addView(pctRow("Mancia:", tip), lp(top = dp(4)))
        tipCashBox = field("Mancia lasciata in contanti a tavola", tipCash)
        root.addView(tipCashBox, lp(top = dp(12)))
        cashPctRow = pctRow("Contanti:", tipCash)
        root.addView(cashPctRow, lp(top = dp(4)))
        tipInfo = txt("", 13f, R.color.ink2)
        root.addView(tipInfo, lp(top = dp(4)))
        root.addView(field("Carta", card), lp(top = dp(12)))
        // campi per Ztravel
        zvoce = Spinner(this).apply { adapter = ArrayAdapter(this@ExpenseActivity, android.R.layout.simple_spinner_dropdown_item, Ztravel.VOCI) }
        payS = Spinner(this).apply { adapter = ArrayAdapter(this@ExpenseActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Carta aziendale", "Personale / contanti")) }
        root.addView(two(field("Voce Ztravel", zvoce), field("Pagamento", payS)), lp(top = dp(12)))
        nights = input("1", InputType.TYPE_CLASS_NUMBER)
        nightsBox = field("Notti", nights)
        root.addView(nightsBox, lp(top = dp(12)))
        provider = input("Nome del tassista o della compagnia")
        route = input("Percorso, es. Hotel → Brembo Escobedo")
        taxiBox = vbox().apply {
            addView(cap("Taxi: prestatore e percorso (obbligatori, Uber escluso)"))
            addView(provider, lp(top = dp(4))); addView(route, lp(top = dp(6)))
        }
        root.addView(taxiBox, lp(top = dp(12)))

        tableBtn = btn("Sono a tavola: mostra il mio QR", false) { Tavolo.showMine(this) { toggleTable(true) } }
        val addBtn = btn("Aggiungi commensale (QR o NFC)", false) { Tavolo.addFrom(this) { n -> addGuest(n) } }
        guestBox = vbox().apply {
            addView(cap("Commensali (con chi eri a tavola)"))
            addView(guests, lp())
            addView(chips, lp(top = dp(4)))
            splitBox = android.widget.CheckBox(this@ExpenseActivity).apply {
                text = "Conto diviso: in Ztravel metto solo la mia quota"; setTextColor(col(R.color.ink))
                setOnCheckedChangeListener { _, _ -> refresh() }
            }
            addView(splitBox, lp(top = dp(4)))
            addView(addBtn, lp(top = dp(6)))
            addView(tableBtn, lp(top = dp(6)))
            addView(txt("Tre modi, solo tra colleghi con l'app: inquadri il loro QR, avvicini i telefoni (NFC) o li trovi via Bluetooth con \"Sono a tavola\" (10 minuti). Nessun server.", 11.5f, R.color.ink2), lp(top = dp(4)))
        }
        root.addView(guestBox, lp(top = dp(12)))

        noteIn = input("Facoltativa: compare nel CSV e nel PDF", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply { setSingleLine(false); maxLines = 4 }
        root.addView(field("Note per Ztravel", noteIn), lp(top = dp(12)))
        capInfo = txt("", 13f, R.color.ink2).apply {
            visibility = View.GONE
            setOnClickListener {
                val n = Ztravel.capNote(current(false))
                if (n.isNotEmpty() && !noteIn.text.contains(n)) noteIn.setText((noteIn.text.toString() + " " + n).trim())
            }
        }
        root.addView(capInfo, lp(top = dp(4)))
        smsInfo = txt("", 13.5f, R.color.ink2).apply { visibility = View.GONE }
        root.addView(smsInfo, lp(top = dp(12)))

        root.addView(cap("Nome del file"), lp(top = dp(16)))
        fname = mono("", 12f, R.color.ink2).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(col(R.color.surface)) }
        root.addView(fname, lp(top = dp(4)))

        pdfBtn = btn(if (declaration) "Crea dichiarazione PDF e salva" else "Crea PDF e salva") { save(true) }
        saveBtn = btn("Salva senza PDF", false) { save(false) }
        root.addView(pdfBtn, lp(top = dp(18)))
        root.addView(saveBtn, lp(top = dp(8)))

        listOf(place, amount, tip, tipCash, cur, card, nights, provider, route, noteIn).forEach { it.doAfterTextChanged { dupAck = false; refresh() } }
        zvoce.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (!settingZ) zvoceTouched = true; refresh() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        payS.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (!settingZ) payTouched = true; refresh() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
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
            settingZ = true
            if (e.zvoce in Ztravel.VOCI) { zvoce.setSelection(Ztravel.VOCI.indexOf(e.zvoce)); zvoceTouched = true }
            if (e.pay.isNotEmpty()) { payS.setSelection(if (e.pay == "pers") 1 else 0); payTouched = true }
            settingZ = false
            if (e.nights > 1) nights.setText(e.nights.toString())
            provider.setText(e.provider); route.setText(e.route); noteIn.setText(e.note); splitBox.isChecked = e.split
            status.text = "Modifica i dati e salva."
        } else if (s != null) {
            if (s.date.isNotEmpty()) setDate(s.date)
            if (s.time.isNotEmpty()) time.setText(s.time)
            place.setText(s.merchant); amount.setText(Fmt.plain(s.amount))
            if (s.currency.isNotEmpty()) cur.setText(s.currency)
            card.setText(s.cardLabel().removePrefix("carta "))
            status.text = if (declaration) "Scontrino non disponibile: controlla i dati dell'addebito e scegli la categoria. Il PDF sarà una dichiarazione da firmare."
            else "Fotografa lo scontrino: lo leggo e controllo che coincida con l'addebito."
            recompute()
        } else {
            status.text = "Fotografa lo scontrino o scegli la foto: leggo io data, importo, mancia e carta."
            if (Store.myCard.isNotEmpty()) card.setText("Carta " + Store.myCard)
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
            catReason = if (userCat) "scelta manuale" else catReason,
            eurRate = e?.eurRate, eurDate = e?.eurDate ?: "", declaration = declaration,
            updated = e?.updated ?: 0L,
            zvoce = if (zvoceTouched) Ztravel.VOCI.getOrElse(zvoce.selectedItemPosition) { "" } else "",
            pay = if (payTouched) (if (payS.selectedItemPosition == 1) "pers" else "az") else "",
            nights = nights.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 1,
            split = splitBox.isChecked, provider = provider.text.toString().trim(), route = route.text.toString().trim(),
            note = noteIn.text.toString().trim()
        )
        if (e != null && (e.date != x.date || e.currency != x.currency)) { x.eurRate = null; x.eurDate = "" }
        if (withPdf) x.pdf = x.pdfName()
        return x
    }

    private fun refresh() {
        val e = current(false)
        fname.text = e.pdfName()
        val meal = e.isMeal
        guestBox.visibility = if (meal || e.guests.isNotEmpty()) View.VISIBLE else View.GONE
        // Ztravel: voce e pagamento automatici finché non li scegli tu
        settingZ = true
        if (!zvoceTouched) zvoce.setSelection(Ztravel.VOCI.indexOf(Ztravel.defaultVoce(e.category)).coerceAtLeast(0))
        if (!payTouched) payS.setSelection(if (Ztravel.personal(e)) 1 else 0)
        settingZ = false
        val v = Ztravel.voce(e)
        nightsBox.visibility = if (Ztravel.isPernotto(v)) View.VISIBLE else View.GONE
        taxiBox.visibility = if (v == "Taxi") View.VISIBLE else View.GONE
        splitBox.visibility = if (meal && e.guests.isNotEmpty()) View.VISIBLE else View.GONE
        val cc = Ztravel.capCheck(e)
        if (cc?.over != null && e.amount > 0) {
            capInfo.visibility = View.VISIBLE
            capInfo.text = (if (cc.over == true) "● Oltre il massimale " else "● Entro il massimale ") + (if (cc.kind == "vitto") "pasto " else "pernotto ") +
                Fmt.money(cc.cap) + " " + cc.cur + " (" + cc.zone + "): " + Fmt.money(cc.value) + " " + cc.cur + (if (cc.kind == "vitto") " a persona." else " a notte.") +
                (if (cc.over == true) "  Tocca qui per aggiungere la nota per Ztravel." else "") +
                (if (e.split && e.people > 1) "  In Ztravel: " + Ztravel.lines(e).joinToString(" + ") { it.voce + " " + Fmt.money(it.amount) } + " " + e.currency + "." else "")
            capInfo.setTextColor(col(if (cc.over == true) R.color.warn else R.color.ok))
        } else if (e.split && e.people > 1 && e.amount > 0) {
            capInfo.visibility = View.VISIBLE
            capInfo.text = "In Ztravel: " + Ztravel.lines(e).joinToString(" + ") { it.voce + " " + Fmt.money(it.amount) } + " " + e.currency + "."
            capInfo.setTextColor(col(R.color.ink2))
        } else capInfo.visibility = View.GONE
        tipCashBox.visibility = if (meal) View.VISIBLE else View.GONE
        cashPctRow.visibility = tipCashBox.visibility
        cashChip.visibility = tipCashBox.visibility
        preview.amount = Fmt.num(amount.text.toString()); preview.tip = Fmt.num(tip.text.toString()); preview.cash = Fmt.num(tipCash.text.toString())
        if (e.tipTotal > 0 && e.amount > 0) {
            tipInfo.visibility = View.VISIBLE
            tipInfo.text = "Mancia totale " + Fmt.money(e.tipTotal) + " " + e.currency + " · " +
                String.format(java.util.Locale.ITALY, "%.1f", e.tipPct()) + "% del conto" +
                (if (e.people > 1) " · " + Fmt.money(e.tipTotal / e.people) + " a persona" else "")
        } else tipInfo.visibility = View.GONE
        photoBtns.visibility = if (photo == null && !declaration) View.VISIBLE else View.GONE
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
        preview.bitmap = bmp ?: BitmapFactory.decodeFile(f.absolutePath)
        preview.boxes = emptyList()
        photoCard.visibility = View.VISIBLE
        refresh()
    }

    /** Righe con 10% 15% 20% del conto: per la mancia che sullo scontrino non c'è. */
    private fun pctRow(label: String, target: EditText): View {
        val row = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(txt(label, 13f, R.color.ink2), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        for (p in listOf(10, 15, 20)) row.addView(Chip(this).apply {
            text = "$p%"
            setOnClickListener {
                val a = Fmt.num(amount.text.toString())
                if (a == null || a <= 0) { toast("Prima inserisci l'importo dello scontrino."); return@setOnClickListener }
                target.setText(Fmt.plain(Fmt.round2(a * p / 100.0)))
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        row.addView(Chip(this).apply { text = "0"; setOnClickListener { target.setText("") } })
        return row
    }

    private fun picked(v: Double) {
        val f = when (pickMode) { "tip" -> tip; "cash" -> tipCash; else -> amount }
        f.setText(Fmt.plain(v))
        status.text = (when (pickMode) { "tip" -> "Mancia"; "cash" -> "Mancia in contanti"; else -> "Importo" }) + " preso dalla foto: " + Fmt.money(v) + ". Controlla e salva."
        status.setTextColor(col(R.color.ink2))
        if (pickMode != "amount") { pickMode = "amount"; (modeChips.getChildAt(0) as? Chip)?.isChecked = true }
        refresh()
    }

    /**
     * OCR con la foto nel verso giusto: se le righe risultano verticali o non trovo il totale,
     * provo la foto ruotata e tengo la lettura migliore. Da chiamare fuori dal thread principale.
     */
    private fun ocrBest(bmp: Bitmap): Pair<Bitmap, com.google.mlkit.vision.text.Text> {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        fun run(b: Bitmap) = com.google.android.gms.tasks.Tasks.await(rec.process(InputImage.fromBitmap(b, 0)))
        val totalRe = Regex("total|totale|importe|propina|mancia", RegexOption.IGNORE_CASE)
        fun score(t: com.google.mlkit.vision.text.Text): Int {
            val lines = t.textBlocks.flatMap { it.lines }.filter { it.text.length >= 4 }
            val upright = lines.count { l -> l.boundingBox?.let { it.width() > it.height() * 1.5 } == true }
            val chars = lines.sumOf { l -> l.text.count { it.isLetterOrDigit() } }
            return upright * 4 + chars / 8 + (if (totalRe.containsMatchIn(t.text)) 60 else 0)
        }
        fun rotate(b: Bitmap, deg: Float): Bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
        val t0 = run(bmp)
        val lines0 = t0.textBlocks.flatMap { it.lines }.filter { it.text.length >= 4 }
        val vertical = lines0.count { l -> l.boundingBox?.let { it.height() > it.width() } == true }
        if (lines0.size >= 6 && vertical * 3 < lines0.size && totalRe.containsMatchIn(t0.text)) return bmp to t0
        var best = bmp to t0; var bs = score(t0)
        for (deg in listOf(90f, 270f, 180f)) {
            if (deg == 180f && bs > 120) break
            val r = try { rotate(bmp, deg) } catch (e: OutOfMemoryError) { continue }
            val t = try { run(r) } catch (e: Exception) { continue }
            val sc = score(t)
            if (sc > bs) { best = r to t; bs = sc }
        }
        return best
    }

    private fun read(bmp: Bitmap) {
        status.text = "Leggo lo scontrino…"; status.setTextColor(col(R.color.ink)); Disc.spinText(status, true)
        Thread {
            val res = try { ocrBest(bmp) } catch (e: Exception) { null }
            if (res != null && res.first !== bmp) photo?.let { f -> try { Images.saveJpeg(res.first, f) } catch (_: Exception) {} }
            runOnUiThread {
                Disc.spinText(status, false)
                if (res == null) {
                    status.text = "Non sono riuscito a leggere lo scontrino. Compila i campi a mano o tocca i numeri sulla foto."
                    status.setTextColor(col(R.color.warn)); return@runOnUiThread
                }
                val (b, t) = res
                if (b !== bmp) preview.bitmap = b
                preview.boxes = AmountPicker.boxesFrom(t)
                apply(ReceiptParser.parse(Ocr.rows(t), Store.currency, sms?.amount))
            }
        }.start()
    }

    private fun apply(r: ReceiptData) {
        receiptText = r.text
        if (editing == null || date.isEmpty()) { if (r.date.isNotEmpty()) setDate(r.date) }
        if (r.time.isNotEmpty() && time.text.isBlank()) time.setText(r.time)
        if (r.place.isNotEmpty() && (place.text.isBlank() || sms != null)) place.setText(r.place)
        r.amount?.let { amount.setText(Fmt.plain(it)) }
        r.tip?.let { tip.setText(Fmt.plain(it)) }
        if (r.currency.isNotEmpty() && (cur.text.isBlank() || sms == null)) cur.setText(r.currency)
        if (r.card.isNotEmpty() && (card.text.isBlank() || r.card == "Contanti" || (Store.myCard.isNotEmpty() && card.text.toString() == "Carta " + Store.myCard && Fmt.last4(r.card) == Store.myCard))) card.setText(r.card)
        val names = mapOf("date" to "data", "place" to "luogo", "amount" to "importo")
        val unsure = r.uncertain.mapNotNull { names[it] }
        status.text = (if (unsure.isEmpty()) "Letto. Controlla i dati e salva." else "Letto. Controlla bene: " + unsure.joinToString(", ") + ".") +
            " Se un valore è sbagliato, tocca il numero giusto sulla foto."
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
                try { Fx.rate(this, e.date, e.currency)?.let { (r, d) -> e.eurRate = r; e.eurDate = d } } catch (_: Exception) {}
                if (withPdf) {
                    pdf = Exporter.pdfFor(this, e, rebuild = true)
                    pdf?.let { Exporter.toDownloads(this, it, "application/pdf") }
                }
                synchronized(Store) {
                    val i = Store.expenses.indexOfFirst { it.id == e.id }
                    e.updated = System.currentTimeMillis()
                    if (i >= 0) Store.expenses[i] = e else Store.expenses.add(e)
                    Store.save(this)
                }
                Sync.later(this)
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
