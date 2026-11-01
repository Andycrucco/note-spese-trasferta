package it.scontrini.trasferta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Estratto conto scaricato dalla banca: CSV/TXT, Excel (.xlsx) o PDF (anche scansionato, letto con l'OCR del telefono).
 * Ogni riga con una data e un importo diventa un movimento; i doppioni con SMS ed estratti già caricati vengono saltati.
 */
object Statement {
    class Row(val charge: SmsCharge, val dup: SmsCharge?, val expense: Expense?)

    class Unsupported(msg: String) : Exception(msg)

    fun name(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        } ?: ""
    } catch (_: Exception) { "" }

    /** Testo dell'estratto conto, una riga per movimento quando possibile. Da chiamare fuori dal thread principale. */
    fun readText(ctx: Context, uri: Uri): String {
        val n = name(ctx, uri).lowercase()
        val mime = ctx.contentResolver.getType(uri) ?: ""
        return when {
            n.endsWith(".pdf") || mime == "application/pdf" -> pdf(ctx, uri)
            n.endsWith(".xlsx") || mime.contains("openxmlformats") -> xlsx(ctx, uri)
            n.endsWith(".xls") || mime == "application/vnd.ms-excel" ->
                throw Unsupported("Il vecchio formato Excel .xls non è supportato: salvalo come .xlsx o CSV, oppure scarica il PDF.")
            else -> {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val utf = String(bytes, Charsets.UTF_8)
                if (utf.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else utf
            }
        }
    }

    // ---------- PDF: pagine renderizzate e lette con ML Kit, righe ricostruite per altezza ----------
    private fun pdf(ctx: Context, uri: Uri): String {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return ""
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val out = StringBuilder()
        pfd.use {
            PdfRenderer(it).use { r ->
                for (i in 0 until minOf(r.pageCount, 15)) {
                    r.openPage(i).use { page ->
                        val scale = minOf(2.5f, 1800f / page.width)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val t = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))
                        out.append(Ocr.rows(t)).append('\n')
                        bmp.recycle()
                    }
                }
            }
        }
        return out.toString()
    }

    // ---------- XLSX: stringhe condivise + fogli, date seriali di Excel convertite ----------
    private fun xlsx(ctx: Context, uri: Uri): String {
        val files = HashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val nm = e.name
                    if (nm == "xl/sharedStrings.xml" || nm == "xl/styles.xml" || (nm.startsWith("xl/worksheets/sheet") && nm.endsWith(".xml"))) files[nm] = z.readBytes()
                    e = z.nextEntry
                }
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val dateStyles = files["xl/styles.xml"]?.let { dateStyles(it) } ?: emptySet()
        val sb = StringBuilder()
        files.keys.filter { it.startsWith("xl/worksheets/") }.sorted().forEach { k -> sb.append(sheet(files[k]!!, shared, dateStyles)) }
        return sb.toString()
    }

    private fun parser(b: ByteArray): XmlPullParser = Xml.newPullParser().apply { setInput(b.inputStream(), "UTF-8") }

    private fun sharedStrings(b: ByteArray): List<String> {
        val p = parser(b); val out = mutableListOf<String>(); var cur: StringBuilder? = null; var inT = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "si" -> cur = StringBuilder(); "t" -> inT = true }
                XmlPullParser.TEXT -> if (inT) cur?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> { out.add(cur?.toString() ?: ""); cur = null } }
            }
        }
        return out
    }

    /** indici di stile delle celle che sono date (formati 14-22, 45-47 o personalizzati con g/m/a) */
    private fun dateStyles(b: ByteArray): Set<Int> {
        val p = parser(b); val custom = HashMap<Int, String>(); val xf = mutableListOf<Int>(); var inXfs = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                "numFmt" -> custom[p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: -1] = p.getAttributeValue(null, "formatCode") ?: ""
                "cellXfs" -> inXfs = true
                "xf" -> if (inXfs) xf.add(p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0)
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "cellXfs") inXfs = false
        }
        return xf.withIndex().filter { (_, id) ->
            id in 14..22 || id in 45..47 || custom[id]?.lowercase()?.replace(Regex("\\[[^]]*]|\"[^\"]*\""), "")?.let { f -> (f.contains('d') || f.contains('y')) && f.contains('m') && !f.contains('0') } == true
        }.map { it.index }.toSet()
    }

    private fun serialDate(n: Double): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.set(1899, 11, 30, 0, 0, 0); c.add(java.util.Calendar.DAY_OF_MONTH, n.toInt())
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    private fun sheet(b: ByteArray, shared: List<String>, dates: Set<Int>): String {
        val p = parser(b); val sb = StringBuilder(); val row = mutableListOf<String>()
        var t = ""; var s = -1; var v: StringBuilder? = null; var inV = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row.clear()
                    "c" -> { t = p.getAttributeValue(null, "t") ?: ""; s = p.getAttributeValue(null, "s")?.toIntOrNull() ?: -1; v = StringBuilder() }
                    "v", "t" -> inV = true
                }
                XmlPullParser.TEXT -> if (inV) v?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val raw = v?.toString() ?: ""
                        row.add(when {
                            t == "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                            t == "inlineStr" || t == "str" -> raw
                            t == "b" -> ""
                            else -> raw.toDoubleOrNull()?.let { d ->
                                if (s in dates && d in 20000.0..80000.0) serialDate(d)
                                else if (d == Math.floor(d) && abs(d) >= 1e5) raw else Fmt.dot(d)
                            } ?: raw
                        }.replace(Regex("[;\\r\\n]+"), " "))
                    }
                    "row" -> sb.append(row.joinToString(";")).append('\n')
                }
            }
        }
        return sb.toString()
    }

    fun parse(text: String, defaultCurrency: String): List<SmsCharge> =
        StatementLines.parse(text, defaultCurrency).map { r ->
            SmsCharge(Ns.stmtId(r.date, r.amount, r.currency, r.merchant, r.k), r.date, r.amount, r.currency, r.merchant, r.card, "",
                System.currentTimeMillis(), r.time, "estratto")
        }

    fun prepare(charges: List<SmsCharge>): List<Row> {
        val used = HashSet<String>()
        return charges.map { c ->
            val d = Dedupe.find(c, Store.sms, used, strictSms = false)
            if (d != null) used.add(d.id)
            Row(c, d, if (d == null) Match.expenseFor(c, Store.expenses, Store.sms + c) else null)
        }
    }
}

/** Righe di testo ricostruite dall'OCR: le parole alla stessa altezza diventano una riga, da sinistra a destra. */
object Ocr {
    fun rows(t: com.google.mlkit.vision.text.Text): String {
        val lines = t.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        if (lines.isEmpty()) return t.text
        val sorted = lines.sortedBy { it.boundingBox!!.centerY() }
        val groups = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Line>>()
        for (l in sorted) {
            val b = l.boundingBox!!
            val g = groups.lastOrNull()
            val gb = g?.last()?.boundingBox
            if (g != null && gb != null && abs(b.centerY() - gb.centerY()) < maxOf(b.height(), gb.height()) * 0.6) g.add(l) else groups.add(mutableListOf(l))
        }
        return groups.joinToString("\n") { g -> g.sortedBy { it.boundingBox!!.left }.joinToString("  ") { it.text } }
    }
}
