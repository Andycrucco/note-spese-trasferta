package it.scontrini.trasferta

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

object Images {
    /** Apre la foto (fotocamera, galleria o Google Foto), la raddrizza e la riduce a 2000 px. */
    fun load(ctx: Context, uri: Uri, maxSide: Int = 2000): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        }
        val s = min(1f, maxSide.toFloat() / max(bmp.width, bmp.height))
        if (s < 1f) m.postScale(s, s)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return bmp
    }

    fun saveJpeg(bmp: Bitmap, f: File) {
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }
}

object PdfMaker {
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private const val INK = 0xFF0A0A0A.toInt()
    private const val INK2 = 0xFF5E6570.toInt()
    private const val RED = 0xFFE10A1E.toInt()
    private const val LINE = 0xFFD5DAE0.toInt()

    private fun paint(size: Float, bold: Boolean, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun wrap(text: String, p: Paint, width: Float, maxLines: Int): List<String> {
        val out = mutableListOf<String>(); var line = ""
        for (w in text.split(" ")) {
            val t = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(t) <= width) line = t else { if (line.isNotEmpty()) out.add(line); line = w }
            if (out.size >= maxLines) break
        }
        if (line.isNotEmpty() && out.size < maxLines) out.add(line)
        return out
    }

    private fun header(ctx: Context, c: Canvas, title: String): Float {
        c.drawRect(0f, 0f, W.toFloat(), 6f, Paint().apply { color = RED })
        var titleW = W - 2 * M
        val logoF = Store.logoFile(ctx)
        if (logoF.exists()) {
            BitmapFactory.decodeFile(logoF.absolutePath)?.let { logo ->
                val lw = min(150f, logo.width * 34f / logo.height)
                val lh = logo.height * lw / logo.width
                c.drawBitmap(logo, null, RectF(W - M - lw, M - 8f, W - M, M - 8f + lh), Paint(Paint.FILTER_BITMAP_FLAG))
                titleW -= lw + 16f
            }
        }
        val tp = paint(19f, true, INK)
        var y = M + 14f
        for (l in wrap(title, tp, titleW, 2)) { c.drawText(l, M, y, tp); y += 24f }
        return y + 8f
    }

    /** Pagina 1: dati e foto. Per i pasti, pagina 2: inflazione del paese (Banca Mondiale). */
    fun make(ctx: Context, e: Expense, photo: Bitmap?, cpi: Cpi.Series? = null, cpiCountry: String = ""): File {
        val doc = PdfDocument()
        page1(ctx, doc, e, photo)
        if (e.isMeal) page2(ctx, doc, e, cpi, cpiCountry)
        val f = File(Store.pdfDir(ctx), e.pdfName())
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun page1(ctx: Context, doc: PdfDocument, e: Expense, photo: Bitmap?) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val cw = W - 2 * M
        var y = header(ctx, c, if (e.declaration) "Dichiarazione sostitutiva: scontrino non disponibile" else e.place.ifBlank { "Scontrino" })
        val label = paint(8f, false, INK2).apply { letterSpacing = 0.08f }
        val value = paint(11.5f, true, INK)
        val cu = " " + e.currency
        val rows = mutableListOf(
            "DATA" to (Fmt.dmy(e.date).ifEmpty { "-" } + if (e.time.isNotEmpty()) "  " + e.time else ""),
            "VOCE ZTRAVEL" to Ztravel.voce(e),
            "IMPORTO SCONTRINO" to Fmt.money(e.amount) + cu,
            "CARTA" to e.card.ifBlank { "-" } + (if (Ztravel.personal(e)) " (personale)" else "")
        )
        if (e.tip > 0) rows.add("MANCIA SU SCONTRINO / CARTA" to Fmt.money(e.tip) + cu)
        if (e.tipCash > 0) rows.add("MANCIA IN CONTANTI A TAVOLA" to Fmt.money(e.tipCash) + cu)
        if (e.tipTotal > 0) {
            rows.add("MANCIA TOTALE" to Fmt.money(e.tipTotal) + cu + "  (" + String.format(java.util.Locale.ITALY, "%.1f", e.tipPct()) + "% del conto)")
            if (e.people > 1) rows.add("MANCIA A PERSONA" to Fmt.money(e.tipTotal / e.people) + cu + "  (" + e.people + " persone)")
        }
        rows.add("TOTALE SPESO" to Fmt.money(e.total) + cu)
        if (e.currency != "EUR") e.totalEur?.let { rows.add("CONTROVALORE IN EURO" to Fmt.money(it) + " EUR (BCE " + Fmt.dmy(e.eurDate) + ")") }
        if (e.people > 1) rows.add("TOTALE A PERSONA" to Fmt.money(e.total / e.people) + cu)
        rows.add("TRASFERTA" to e.trip.ifBlank { "-" })
        if (e.split && e.people > 1) rows.add("IN ZTRAVEL (MIA QUOTA)" to Ztravel.lines(e).joinToString(" + ") { it.voce + " " + Fmt.money(it.amount) } + cu)
        if (Ztravel.isPernotto(Ztravel.voce(e)) && e.nights > 1) rows.add("NOTTI" to e.nights.toString())
        if (Ztravel.voce(e) == "Taxi") { rows.add("PRESTATORE" to e.provider.ifBlank { "-" }); rows.add("PERCORSO" to e.route.ifBlank { "-" }) }
        Ztravel.capCheck(e)?.takeIf { it.over != null }?.let { cc -> rows.add("MASSIMALE " + cc.zone.uppercase().take(24) to (if (cc.over == true) "OLTRE: " else "entro: ") + Fmt.money(cc.value) + " / " + Fmt.money(cc.cap) + " " + cc.cur) }
        if (e.poi.isNotEmpty() || e.country.isNotEmpty()) rows.add("LUOGO DELLA FOTO" to listOf(e.poi, e.country).filter { it.isNotEmpty() }.joinToString(" · "))
        val colW = cw / 2
        rows.forEachIndexed { i, (k, v) ->
            val x = M + (i % 2) * colW; val yy = y + (i / 2) * 30f
            c.drawText(k, x, yy, label)
            c.drawText(wrap(v, value, colW - 10f, 1).firstOrNull() ?: v, x, yy + 14f, value)
        }
        y += ((rows.size + 1) / 2) * 30f + 4f
        if (e.note.isNotEmpty()) {
            c.drawText("NOTE", M, y, label); y += 14f
            for (l in wrap(e.note, value, cw, 3)) { c.drawText(l, M, y, value); y += 14f }
            y += 4f
        }
        if (e.guests.isNotEmpty()) {
            c.drawText("COMMENSALI (" + e.people + " PERSONE, ME COMPRESO)", M, y, label)
            y += 14f
            for (l in wrap(e.guests.joinToString(", "), value, cw, 3)) { c.drawText(l, M, y, value); y += 14f }
            y += 4f
        }
        if (e.catReason.isNotEmpty()) {
            for (l in wrap("Categoria assegnata da: " + e.catReason, paint(8.5f, false, INK2), cw, 2)) { c.drawText(l, M, y, paint(8.5f, false, INK2)); y += 11f }
        }
        c.drawLine(M, y, W - M, y, Paint().apply { color = LINE; strokeWidth = 1f })
        y += 10f
        if (e.declaration) {
            val body = paint(11f, false, INK); val bold = paint(11f, true, INK)
            val me = Store.myName.ifBlank { "________________________" } + (if (Store.myEmail.isNotBlank()) " (" + Store.myEmail + ")" else "")
            val text = listOf(
                "Io sottoscritto/a $me dichiaro che il documento di spesa relativo alla spesa sopra indicata non è disponibile.",
                "Esercente: " + e.place.ifBlank { "-" } + ". Data: " + Fmt.dmy(e.date) + ". Importo: " + Fmt.money(e.charged) + " " + e.currency + (if (e.card.isNotBlank()) ", pagato con " + e.card else "") + ".",
                if (e.bankId != null) "L'addebito risulta dall'SMS di notifica della banca, abbinato a questa spesa." else "",
                "Motivo: [ ] smarrito   [ ] non rilasciato dall'esercente   [ ] illeggibile   [ ] altro: ______________________",
                "Dichiaro inoltre che la spesa è stata sostenuta per motivi di servizio durante la trasferta \"" + e.trip + "\" e che non è stata rimborsata in altro modo."
            ).filter { it.isNotEmpty() }
            y += 6f
            for (t in text) { for (l in wrap(t, body, cw, 4)) { c.drawText(l, M, y, body); y += 15f }; y += 6f }
            y += 30f
            c.drawText("Luogo e data: ______________________________", M, y, bold)
            y += 40f
            c.drawText("Firma: _____________________________________", M, y, bold)
            y += 30f
            for (l in wrap("Documento generato dall'app Note spese trasferta come dichiarazione: non è uno scontrino.", paint(8.5f, false, INK2), cw, 2)) { c.drawText(l, M, y, paint(8.5f, false, INK2)); y += 11f }
        } else if (photo != null) {
            val availH = H - M - y
            val s = min(cw / photo.width, availH / photo.height)
            val iw = photo.width * s; val ih = photo.height * s
            val x = M + (cw - iw) / 2
            c.drawBitmap(photo, null, RectF(x, y, x + iw, y + ih), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawText("Foto dello scontrino non disponibile", M, y + 14f, paint(10f, false, INK2))
        }
        doc.finishPage(page)
    }

    private fun page2(ctx: Context, doc: PdfDocument, e: Expense, cpi: Cpi.Series?, iso: String) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 2).create())
        val c = page.canvas
        val cw = W - 2 * M
        var y = header(ctx, c, "Inflazione: " + (cpi?.name ?: iso.ifEmpty { "paese non determinato" }))
        val small = paint(9f, false, INK2); val body = paint(11f, false, INK); val bold = paint(11f, true, INK)
        c.drawText("Indice dei prezzi al consumo (2010 = 100) · Banca Mondiale, indicatore FP.CPI.TOTL (fonte FMI)", M, y, small)
        y += 18f
        if (cpi == null || cpi.points.size < 3) {
            for (l in wrap("Dati della Banca Mondiale non disponibili per questo paese o senza connessione. Apri la spesa quando sei online e ricrea il PDF.", body, cw, 4)) { c.drawText(l, M, y, body); y += 15f }
            doc.finishPage(page); return
        }
        val pts = cpi.points.filter { it.year >= cpi.points.last().year - 15 }
        val last = pts.last()
        val yearSpent = e.date.take(4).toIntOrNull() ?: last.year

        // grafico a linea dell'indice
        y = lineChart(c, pts, M, y + 6f, cw, 210f) + 26f
        // inflazione annua
        val yy = CpiMath.yoy(pts)
        c.drawText("INFLAZIONE ANNUA (%)", M, y, paint(8f, false, INK2).apply { letterSpacing = 0.08f })
        y = barChart(c, yy, M, y + 8f, cw, 150f) + 26f

        // sintesi
        val lines = mutableListOf<Pair<String, Boolean>>()
        lines.add(("Ultimo dato disponibile: " + last.year + ", indice " + String.format(java.util.Locale.ITALY, "%.1f", last.value) + ".") to true)
        yy.lastOrNull()?.let { lines.add(("Inflazione " + it.first + ": " + String.format(java.util.Locale.ITALY, "%.1f", it.second) + "%.") to false) }
        for (from in listOf(last.year - 5, 2015, 2010).distinct().filter { it < last.year }) {
            CpiMath.cumulative(pts + cpi.points, from, last.year)?.let {
                lines.add(("Dal $from al ${last.year} i prezzi sono saliti del " + String.format(java.util.Locale.ITALY, "%.1f", it) + "%.") to false)
            }
        }
        if (yearSpent > last.year) lines.add(("La spesa è del $yearSpent: la Banca Mondiale non ha ancora pubblicato il dato di quell'anno.") to false)
        val zone = Ztravel.zoneFor(e)
        val manual = Store.capAmount > 0 && Store.capYear > 0 && Store.capCurrency.equals(e.currency, true)
        val capBase = if (manual) Store.capAmount else zone?.takeIf { it.cur.equals(e.currency, true) }?.vitto ?: 0.0
        val capYear = if (manual) Store.capYear else Ztravel.CAP_YEAR
        if (capBase > 0) {
            CpiMath.revalue(capBase, capYear, cpi.points)?.let { (cap, yr) ->
                val perHead = e.amount / e.people
                val within = perHead <= cap + 0.005
                lines.add(("Massimale pasto: " + Fmt.money(capBase) + " " + e.currency + " (" + capYear + (if (!manual && zone != null) ", " + zone.name else "") + "), rivalutato al $yr: " + Fmt.money(cap) + " " + e.currency + " a persona.") to true)
                lines.add(("Questo pasto: " + Fmt.money(perHead) + " " + e.currency + " a persona (" + e.people + " persone) → " + (if (within) "entro il massimale." else "oltre il massimale di " + Fmt.money(perHead - cap) + ".")) to true)
            }
        }
        for ((t, b) in lines) for (l in wrap(t, if (b) bold else body, cw, 3)) { c.drawText(l, M, y, if (b) bold else body); y += 15f }
        y += 8f
        val upd = if (cpi.lastUpdated.isNotEmpty()) "aggiornamento Banca Mondiale " + cpi.lastUpdated + ", " else ""
        c.drawText("Fonte: data.worldbank.org/indicator/FP.CPI.TOTL · " + upd + "scaricato il " + Fmt.isoOf(cpi.fetchedAt), M, y, small)
        doc.finishPage(page)
    }

    private fun lineChart(c: Canvas, pts: List<CpiPoint>, x: Float, y: Float, w: Float, h: Float): Float {
        val left = x + 34f; val bottom = y + h - 16f; val top = y + 6f; val right = x + w - 6f
        val minV = pts.minOf { it.value }; val maxV = pts.maxOf { it.value }
        val lo = Math.floor(minV / 10) * 10; val hi = Math.ceil(maxV / 10) * 10
        val span = (hi - lo).coerceAtLeast(1.0)
        val grid = Paint().apply { color = LINE; strokeWidth = 0.6f }
        val lab = paint(7.5f, false, INK2)
        val steps = 4
        for (i in 0..steps) {
            val v = lo + span * i / steps
            val yy = (bottom - (v - lo) / span * (bottom - top)).toFloat()
            c.drawLine(left, yy, right, yy, grid)
            c.drawText(String.format(java.util.Locale.ITALY, "%.0f", v), x, yy + 3f, lab)
        }
        val n = pts.size
        fun px(i: Int) = left + (right - left) * i / (n - 1).coerceAtLeast(1)
        fun py(v: Double) = (bottom - (v - lo) / span * (bottom - top)).toFloat()
        val path = android.graphics.Path()
        val fill = android.graphics.Path()
        pts.forEachIndexed { i, p -> if (i == 0) { path.moveTo(px(i), py(p.value)); fill.moveTo(px(i), bottom); fill.lineTo(px(i), py(p.value)) } else { path.lineTo(px(i), py(p.value)); fill.lineTo(px(i), py(p.value)) } }
        fill.lineTo(px(n - 1), bottom); fill.close()
        c.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FE10A1E })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED; strokeWidth = 2f; style = Paint.Style.STROKE })
        val endP = pts.last()
        c.drawCircle(px(n - 1), py(endP.value), 3.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED })
        val endLabel = String.format(java.util.Locale.ITALY, "%.1f", endP.value)
        val ep = paint(8.5f, true, INK)
        c.drawText(endLabel, (px(n - 1) - ep.measureText(endLabel)).coerceAtLeast(left), py(endP.value) - 7f, ep)
        pts.forEachIndexed { i, p -> if (i % 3 == 0 || i == n - 1) { val t = p.year.toString(); c.drawText(t, px(i) - lab.measureText(t) / 2, bottom + 11f, lab) } }
        return y + h
    }

    private fun barChart(c: Canvas, data: List<Pair<Int, Double>>, x: Float, y: Float, w: Float, h: Float): Float {
        if (data.isEmpty()) return y
        val left = x + 34f; val right = x + w - 6f; val top = y + 12f; val bottom = y + h - 16f
        val hi = Math.ceil(data.maxOf { it.second }.coerceAtLeast(0.0) / 2) * 2
        val lo = Math.floor(data.minOf { it.second }.coerceAtMost(0.0) / 2) * 2
        val span = (hi - lo).coerceAtLeast(1.0)
        fun py(v: Double) = (bottom - (v - lo) / span * (bottom - top)).toFloat()
        val lab = paint(7.5f, false, INK2)
        c.drawLine(left, py(0.0), right, py(0.0), Paint().apply { color = INK2; strokeWidth = 0.6f })
        c.drawText(String.format(java.util.Locale.ITALY, "%.0f", hi), x, py(hi) + 3f, lab)
        c.drawText("0", x, py(0.0) + 3f, lab)
        val n = data.size; val slot = (right - left) / n; val bw = slot * 0.62f
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A9099.toInt() }
        val barLast = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
        val val8 = paint(7f, false, INK)
        data.forEachIndexed { i, (yr, v) ->
            val cx = left + slot * i + slot / 2
            val y0 = py(0.0); val y1 = py(v)
            c.drawRect(cx - bw / 2, min(y0, y1), cx + bw / 2, max(y0, y1), if (i == n - 1) barLast else bar)
            val t = String.format(java.util.Locale.ITALY, "%.1f", v)
            c.drawText(t, cx - val8.measureText(t) / 2, if (v >= 0) y1 - 3f else y1 + 9f, val8)
            if (i % 2 == (n - 1) % 2) { val ys = yr.toString(); c.drawText(ys, cx - lab.measureText(ys) / 2, bottom + 11f, lab) }
        }
        return y + h
    }
}

object Exporter {
    private fun cell(v: String): String = if (v.contains(';') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
    private fun n(d: Double): String = Fmt.plain(d)

    /** ordine delle transazioni in banca (vedi Numbering): si ricalcola a ogni esportazione */
    fun ordered(list: List<Expense>): List<Expense> = Numbering.ordered(list)

    /** CSV per Ztravel: punto e virgola, decimali con la virgola, date GG/MM/AAAA, in ordine cronologico. */
    fun csv(list: List<Expense>, files: Map<String, Pair<String, String?>> = emptyMap()): String {
        val m = Store.match()
        val head = listOf("N.", "Data", "Ora", "Tipo spesa", "Descrizione", "Importo scontrino", "Mancia su scontrino",
            "Mancia contanti a tavola", "Mancia totale", "Mancia %", "Totale speso", "Addebitato su carta", "Valuta", "Carta",
            "Commensali", "N. persone", "Totale a persona", "Paese", "Trasferta", "Allegato PDF", "Foto", "Verificato banca", "Categoria da",
            "Cambio EUR (BCE)", "Data cambio", "Totale EUR", "Dichiarazione", "Data addebito banca")
        val sb = StringBuilder("﻿").append(head.joinToString(";")).append("\r\n")
        ordered(list).forEachIndexed { i, e ->
            val f = files[e.id]
            sb.append(listOf(
                (i + 1).toString(), Fmt.dmy(e.date), e.time, e.category, e.place, n(e.amount),
                if (e.tip > 0) n(e.tip) else "", if (e.tipCash > 0) n(e.tipCash) else "",
                if (e.tipTotal > 0) n(e.tipTotal) else "", if (e.tipTotal > 0) String.format(java.util.Locale.ITALY, "%.1f", e.tipPct()) else "",
                n(e.total), n(e.charged), e.currency, e.card,
                e.guests.joinToString(", "), e.people.toString(), n(e.total / e.people),
                Categorizer.countryFor(e.country, e.currency), e.trip,
                f?.first ?: e.pdf ?: "", f?.second ?: "", if (m.e2s.containsKey(e.id)) "Sì" else "No", e.catReason,
                e.eurRate?.let { String.format(java.util.Locale.ITALY, "%.6f", it) } ?: "", Fmt.dmy(e.eurDate),
                e.totalEur?.let { n(it) } ?: "", if (e.declaration) "Sì" else "",
                Fmt.dmy(Numbering.bankDate(e, m))
            ).joinToString(";") { cell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    fun exportDir(ctx: Context) = File(ctx.cacheDir, "export").apply { mkdirs() }

    /** PDF della spesa (lo crea se manca); per i pasti scarica i dati CPI se c'è internet. */
    fun pdfFor(ctx: Context, e: Expense, rebuild: Boolean = false): File? {
        if (!rebuild) e.pdf?.let { val f = File(Store.pdfDir(ctx), it); if (f.exists() && f.name == e.pdfName()) return f }
        val p = e.photo?.let { File(it) }?.takeIf { it.exists() }
        val iso = Categorizer.countryFor(e.country, e.currency)
        val cpi = if (e.isMeal) Cpi.get(ctx, iso) else null
        val f = PdfMaker.make(ctx, e, p?.let { BitmapFactory.decodeFile(it.absolutePath) }, cpi, iso)
        e.pdf = f.name
        return f
    }

    class Folder(val zip: File, val folder: String, val count: Int, val withPhoto: Int, val savedToDownloads: Boolean)

    /**
     * Cartella per Ztravel: per ogni scontrino, in ordine cronologico,
     * 001_<nome>.pdf e 001_<nome>.jpg, più Riepilogo_Ztravel.csv.
     * Viene scritta in Download/NoteSpese/<trasferta> e impacchettata in uno ZIP da condividere.
     */
    fun folder(ctx: Context, list: List<Expense>, tripLabel: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Folder {
        val dirName = "NoteSpese_" + Fmt.slug(tripLabel, 40).ifEmpty { "trasferta" }
        val rel = Environment.DIRECTORY_DOWNLOADS + "/NoteSpese/" + dirName
        val zipF = File(exportDir(ctx), "$dirName.zip")
        val files = HashMap<String, Pair<String, String?>>()
        val items = ordered(list)
        try { Fx.fill(ctx, items) } catch (_: Exception) {}
        var ok = true; var photos = 0
        // la numerazione può essere cambiata (SMS aggiunti dopo): tolgo i file della volta scorsa
        clearDownloads(ctx, rel)
        ZipOutputStream(zipF.outputStream()).use { z ->
            items.forEachIndexed { i, e ->
                onProgress(i + 1, items.size)
                val prefix = String.format(java.util.Locale.US, "%03d_", i + 1)
                val base = prefix + e.pdfName().removeSuffix(".pdf")
                val pdf = try { pdfFor(ctx, e) } catch (ex: Exception) { null }
                var jpgName: String? = null
                if (pdf != null) {
                    z.putNextEntry(ZipEntry("$dirName/$base.pdf")); pdf.inputStream().use { it.copyTo(z) }; z.closeEntry()
                    ok = toDownloads(ctx, pdf, "application/pdf", rel, "$base.pdf") && ok
                }
                e.photo?.let { File(it) }?.takeIf { it.exists() }?.let { ph ->
                    jpgName = "$base.jpg"; photos++
                    z.putNextEntry(ZipEntry("$dirName/$jpgName")); ph.inputStream().use { it.copyTo(z) }; z.closeEntry()
                    ok = toDownloads(ctx, ph, "image/jpeg", rel, jpgName!!) && ok
                }
                files[e.id] = (if (pdf != null) "$base.pdf" else "") to jpgName
            }
            // ---- per Ztravel: allegati singoli <= 180 KB, PDF unico per la modalità massiva, PDF "nessun documento", righe con le Mance ----
            val massiva = TinyPdf(); var pages = 0
            items.forEachIndexed { i, e ->
                val ph = e.photo?.let { File(it) }?.takeIf { it.exists() } ?: return@forEachIndexed
                val bmp = try { android.graphics.BitmapFactory.decodeFile(ph.absolutePath) } catch (_: Exception) { null } ?: return@forEachIndexed
                val jpg = try { Ztravel.scanJpeg(bmp) } catch (_: Exception) { null }
                bmp.recycle()
                if (jpg == null) return@forEachIndexed
                val name = Ztravel.jpgName(i + 1, e)
                z.putNextEntry(ZipEntry("$dirName/Ztravel/Allegati_singoli/$name")); z.write(jpg.first); z.closeEntry()
                val tf = File(exportDir(ctx), name).apply { writeBytes(jpg.first) }
                ok = toDownloads(ctx, tf, "image/jpeg", "$rel/Ztravel/Allegati_singoli", name) && ok
                tf.delete()
                val head = "N. Alleg. ${i + 1}  -  " + Fmt.dmy(e.date) + "  -  " + Ztravel.lines(e).joinToString(" + ") { it.voce + " " + Fmt.money(it.amount) } + " " + e.currency
                val sc = minOf(527f / jpg.second, 770f / jpg.third)
                val iw = jpg.second * sc; val ih = jpg.third * sc
                massiva.page(listOf(Triple(34f, 30f, Pair(11f, head))), jpg, floatArrayOf(34f + (527f - iw) / 2, 44f, iw, ih))
                pages++
            }
            fun addFile(f: File, sub: String, mime: String) {
                z.putNextEntry(ZipEntry("$dirName/$sub/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                ok = toDownloads(ctx, f, mime, "$rel/$sub", f.name) && ok
            }
            if (pages > 0) {
                val mf = File(exportDir(ctx), "Giustificativi_modalita_massiva.pdf"); massiva.write(mf); addFile(mf, "Ztravel", "application/pdf")
            }
            val nd = File(exportDir(ctx), "Allegati_NESSUN_DOCUMENTO_DA_ALLEGARE.pdf")
            TinyPdf().apply {
                page(listOf(Triple(110f, 400f, Pair(26f, "NESSUN DOCUMENTO DA ALLEGARE")), Triple(110f, 440f, Pair(12f, "Nota spese: $tripLabel"))))
            }.write(nd)
            addFile(nd, "Ztravel", "application/pdf")
            val rf = File(exportDir(ctx), "Righe_nota_spese.csv").apply { writeText(Ztravel.linesCsv(items), Charsets.UTF_8) }
            addFile(rf, "Ztravel", "text/csv")
            val lf = File(exportDir(ctx), "LEGGIMI.txt").apply {
                writeText("Caricamento in Ztravel (Spese viaggiatore)\r\n\r\n" +
                    "Modalita singola: per ogni riga carica il JPG di Allegati_singoli con lo stesso numero (N. Alleg.). Ogni file e una sola pagina sotto i 200 KB.\r\n" +
                    "Modalita massiva: carica solo Giustificativi_modalita_massiva.pdf sulla prima spesa. Scegli una sola delle due modalita.\r\n" +
                    "Sezione Allegati: se non hai altri documenti carica Allegati_NESSUN_DOCUMENTO_DA_ALLEGARE.pdf.\r\n" +
                    "Le Mance vanno in una riga separata con lo stesso numero di allegato (vedi Righe_nota_spese.csv).\r\n" +
                    "Taxi: giustificativo con data, prestatore, percorso e importo (Uber escluso). Pernotto: fattura intestata al dipendente.\r\n" +
                    "I PDF numerati 001_... sono il tuo archivio completo (con la pagina sull'inflazione per i pasti): non caricarli in Ztravel.\r\n")
            }
            addFile(lf, "Ztravel", "text/plain")
            val csvF = File(exportDir(ctx), "Riepilogo_Ztravel.csv")
            csvF.writeText(csv(items, files), Charsets.UTF_8)
            z.putNextEntry(ZipEntry("$dirName/Riepilogo_Ztravel.csv")); csvF.inputStream().use { it.copyTo(z) }; z.closeEntry()
            ok = toDownloads(ctx, csvF, "text/csv", rel, "Riepilogo_Ztravel.csv") && ok
        }
        try { Store.save(ctx) } catch (_: Exception) {}
        markExported(ctx, tripLabel, list)
        return Folder(zipF, "Download/NoteSpese/$dirName", items.size, photos, ok && Build.VERSION.SDK_INT >= 29)
    }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    /** Apre il menu Condividi (OneDrive, mail, Teams…). */
    fun shareIntent(ctx: Context, f: File, mime: String, title: String): Intent {
        val u = uri(ctx, f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, u)
            putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
            clipData = ClipData.newRawUri(f.name, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    /** Copia in Download (sostituisce un file con lo stesso nome creato in precedenza dall'app). */
    /** Cancella i file che l'app aveva salvato in quella cartella di Download. */
    fun clearDownloads(ctx: Context, relPath: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val r = relPath.trimEnd('/') + "/"
            ctx.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Downloads.RELATIVE_PATH + "=? OR " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?", arrayOf(r, "$r%"))
        } catch (_: Exception) {}
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("export", Context.MODE_PRIVATE)
    private fun sigKey(label: String) = "sig_" + Fmt.slug(label, 60)

    fun markExported(ctx: Context, label: String, list: List<Expense>) {
        prefs(ctx).edit().putString(sigKey(label), Numbering.signature(list)).apply()
    }

    /**
     * true se la cartella di questa trasferta era già stata esportata e da allora la numerazione
     * è cambiata (nuovo SMS, scontrino aggiunto o modificato): va riesportata.
     */
    fun needsReexport(ctx: Context, label: String, list: List<Expense>): Boolean {
        val old = prefs(ctx).getString(sigKey(label), null) ?: return false
        return old != Numbering.signature(list)
    }

    fun toDownloads(ctx: Context, f: File, mime: String, relPath: String = Environment.DIRECTORY_DOWNLOADS + "/Scontrini", name: String = f.name): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val cr = ctx.contentResolver
            val rel = relPath.trimEnd('/') + "/"
            try {
                cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?", arrayOf(name, rel))
            } catch (_: Exception) {}
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, rel)
            }
            val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
            cr.openOutputStream(u)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            true
        } catch (e: Exception) {
            false
        }
    }
}
