package it.scontrini.trasferta

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Registrarsi come commensale in tre modi, tutti senza server e solo su tua azione:
 * QR code (il collega inquadra il tuo), NFC (telefoni retro contro retro), Bluetooth ("Sono a tavola", 10 minuti).
 */
object Tavolo {
    private const val PREFIX = "NOTESPESE-COMMENSALE:1:"
    /** AID privato usato per lo scambio NFC tra due telefoni con l'app. */
    val AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x4E, 0x53, 0x54, 0x52, 0x41, 0x56, 0x4C)

    /** Finché è vero (finestra "Sono a tavola" aperta) il telefono risponde via NFC con il tuo nome. */
    @Volatile var sharing = false
    @Volatile private var sharingUntil = 0L

    fun isSharing(): Boolean = sharing && System.currentTimeMillis() < sharingUntil

    /**
     * Il QR e l'NFC contengono il link per scaricare l'app con il nome in coda:
     * chi non ha l'app apre la pagina e la installa, chi ce l'ha riceve il nome.
     */
    fun payload(name: String): String =
        Store.downloadUrl.trim().substringBefore('#') + "#commensale=" + URLEncoder.encode(name.trim().take(60), "UTF-8").replace("+", "%20")

    fun nameFrom(raw: String?): String? {
        val s = raw?.trim() ?: return null
        val enc = when {
            s.startsWith(PREFIX) -> s.removePrefix(PREFIX)
            s.contains("commensale=") -> s.substringAfter("commensale=").substringBefore('&')
            else -> return null
        }
        return try { URLDecoder.decode(enc, "UTF-8").trim().take(60).ifEmpty { null } } catch (e: Exception) { null }
    }

    fun qr(text: String, size: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun remember(act: Activity, name: String) {
        Store.tableSeen[name] = System.currentTimeMillis()
        try { Store.save(act) } catch (_: Exception) {}
    }

    /** "Sono a tavola": mostra il tuo QR, attiva l'NFC finché la finestra è aperta e il Bluetooth per 10 minuti. */
    fun showMine(act: Activity, startBluetooth: () -> Unit) {
        val me = Store.myName.trim()
        if (me.isEmpty()) {
            Toast.makeText(act, "Prima scrivi il tuo nome in Impostazioni: è quello che riceveranno i colleghi.", Toast.LENGTH_LONG).show()
            return
        }
        val box = act.vbox().apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0); gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ImageView(act).apply {
            setImageBitmap(qr(payload(me))); adjustViewBounds = true
            setBackgroundColor(Color.WHITE); setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(10))
        }, lp(w = act.dp(240), h = act.dp(240)))
        box.addView(act.txt(me, 18f, bold = true).apply { gravity = Gravity.CENTER }, lp(top = act.dp(10)))
        val nfc = NfcAdapter.getDefaultAdapter(act)
        val nfcLine = when {
            nfc == null -> "Questo telefono non ha l'NFC: usa il QR o il Bluetooth."
            !nfc.isEnabled -> "NFC spento: accendilo dalle impostazioni rapide per farti leggere avvicinando i telefoni."
            else -> "Oppure avvicina il retro del tuo telefono a quello del collega mentre lui tocca \"Aggiungi commensale\"."
        }
        box.addView(act.txt("Fai inquadrare questo codice al collega.\n$nfcLine\nSei visibile anche via Bluetooth ai colleghi con l'app per 10 minuti.\nChi non ha l'app: inquadrando il QR con la fotocamera o avvicinando il telefono si apre la pagina per installarla (solo Android).", 13f, R.color.ink2).apply { gravity = Gravity.CENTER }, lp(top = act.dp(8)))
        sharing = true; sharingUntil = System.currentTimeMillis() + 15 * 60_000L
        startBluetooth()
        AlertDialog.Builder(act).setTitle("Sono a tavola").setView(box)
            .setPositiveButton("Chiudi", null)
            .setOnDismissListener { sharing = false }
            .show()
    }

    /** "Aggiungi commensale": QR o NFC. */
    fun addFrom(act: Activity, onName: (String) -> Unit) {
        AlertDialog.Builder(act).setTitle("Aggiungi commensale")
            .setItems(arrayOf("Inquadra il QR del collega", "Avvicina il telefono del collega (NFC)")) { _, w ->
                if (w == 0) scanQr(act, onName) else readNfc(act, onName)
            }.setNegativeButton("Annulla", null).show()
    }

    private fun scanQr(act: Activity, onName: (String) -> Unit) {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(act, opts).startScan()
            .addOnSuccessListener { b ->
                val n = nameFrom(b.rawValue)
                if (n == null) Toast.makeText(act, "Questo QR non è un commensale di Note spese trasferta.", Toast.LENGTH_LONG).show()
                else { remember(act, n); onName(n) }
            }
            .addOnFailureListener { Toast.makeText(act, "Lettore QR non disponibile. Serve internet la prima volta per scaricarlo.", Toast.LENGTH_LONG).show() }
    }

    private fun readNfc(act: Activity, onName: (String) -> Unit) {
        val nfc = NfcAdapter.getDefaultAdapter(act)
        if (nfc == null) { Toast.makeText(act, "Questo telefono non ha l'NFC: usa il QR.", Toast.LENGTH_LONG).show(); return }
        if (!nfc.isEnabled) { Toast.makeText(act, "Accendi l'NFC dalle impostazioni rapide e riprova.", Toast.LENGTH_LONG).show(); return }
        val dlg = AlertDialog.Builder(act).setTitle("Avvicina i telefoni")
            .setMessage("Il collega apre \"Sono a tavola\". Appoggia il retro del tuo telefono al suo.")
            .setNegativeButton("Annulla", null).create()
        dlg.setOnDismissListener { try { nfc.disableReaderMode(act) } catch (_: Exception) {} }
        dlg.show()
        val select = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)
        nfc.enableReaderMode(act, { tag ->
            val iso = IsoDep.get(tag) ?: return@enableReaderMode
            try {
                iso.connect(); iso.timeout = 3000
                val r = iso.transceive(select)
                iso.close()
                val ok = r.size >= 2 && r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()
                val n = if (ok) String(r, 0, r.size - 2, Charsets.UTF_8).trim().take(60) else ""
                act.runOnUiThread {
                    if (n.isEmpty()) Toast.makeText(act, "Il collega deve tenere aperta la finestra \"Sono a tavola\".", Toast.LENGTH_LONG).show()
                    else { remember(act, n); onName(n); dlg.dismiss() }
                }
            } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "Lettura NFC interrotta: tieni i telefoni fermi e riprova.", Toast.LENGTH_SHORT).show() }
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, Bundle())
    }
}

/**
 * Risponde via NFC solo mentre la finestra "Sono a tavola" è aperta:
 * - a un telefono con l'app (AID privato) manda il tuo nome;
 * - a un telefono senza app si presenta come un tag NFC con il link di download + nome,
 *   così Android apre la pagina per installare l'app.
 */
class NameApduService : HostApduService() {
    private val ok = byteArrayOf(0x90.toByte(), 0x00)
    private val notFound = byteArrayOf(0x6A, 0x82.toByte())
    private val ndefAid = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
    private var file = 0   // 1 = CC, 2 = NDEF
    private var inNdefApp = false

    private fun ndefFile(): ByteArray {
        val uri = Tavolo.payload(Store.myName)
        val (code, rest) = when {
            uri.startsWith("https://") -> 0x04.toByte() to uri.removePrefix("https://")
            uri.startsWith("http://") -> 0x03.toByte() to uri.removePrefix("http://")
            else -> 0x00.toByte() to uri
        }
        val payload = byteArrayOf(code) + rest.toByteArray(Charsets.UTF_8)
        val rec = if (payload.size < 256) byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), 0x55) + payload
        else byteArrayOf(0xC1.toByte(), 0x01, 0, 0, (payload.size shr 8).toByte(), payload.size.toByte(), 0x55) + payload
        return byteArrayOf((rec.size shr 8).toByte(), rec.size.toByte()) + rec
    }

    private val cc = byteArrayOf(0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34, 0x04, 0x06, 0xE1.toByte(), 0x04, 0x04, 0x00, 0x00, 0xFF.toByte())

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (!Tavolo.isSharing() || apdu.size < 4) { inNdefApp = false; return notFound }
        Store.load(this)
        val name = Store.myName.trim().take(60)
        if (name.isEmpty()) return notFound
        val ins = apdu[1]; val p1 = apdu[2]
        // SELECT per AID
        if (ins == 0xA4.toByte() && p1 == 0x04.toByte() && apdu.size >= 5) {
            val len = apdu[4].toInt() and 0xFF
            if (apdu.size < 5 + len) return notFound
            val aid = apdu.copyOfRange(5, 5 + len)
            if (aid.contentEquals(Tavolo.AID)) { inNdefApp = false; return name.toByteArray(Charsets.UTF_8) + ok }
            if (aid.contentEquals(ndefAid)) { inNdefApp = true; file = 0; return ok }
            inNdefApp = false; return notFound
        }
        if (!inNdefApp) return notFound
        // SELECT file (E103 = CC, E104 = NDEF)
        if (ins == 0xA4.toByte() && p1 == 0x00.toByte() && apdu.size >= 7) {
            file = when { apdu[5] == 0xE1.toByte() && apdu[6] == 0x03.toByte() -> 1; apdu[5] == 0xE1.toByte() && apdu[6] == 0x04.toByte() -> 2; else -> 0 }
            return if (file == 0) notFound else ok
        }
        // READ BINARY
        if (ins == 0xB0.toByte() && apdu.size >= 5) {
            val data = when (file) { 1 -> cc; 2 -> ndefFile(); else -> return notFound }
            val off = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            var le = apdu[4].toInt() and 0xFF; if (le == 0) le = 256
            if (off > data.size) return byteArrayOf(0x6B, 0x00)
            return data.copyOfRange(off, minOf(data.size, off + le)) + ok
        }
        return notFound
    }

    override fun onDeactivated(reason: Int) { inNdefApp = false; file = 0 }
}
