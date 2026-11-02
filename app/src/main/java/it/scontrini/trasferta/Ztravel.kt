package it.scontrini.trasferta

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Regole Ztravel e delle linee guida aziendali:
 * voci di spesa, massimali di vitto e soggiorno (Addendum 1 PG.W.DRUO-09 rev.02, 28/08/2015),
 * righe della nota spese (Mance separate con lo stesso numero di allegato), quota a persona,
 * allegati di una pagina sotto i 200 KB (conservazione sostitutiva).
 */
object Ztravel {
    val VOCI = listOf(
        "Bar", "Pranzo", "Cena", "Pernotto", "Pernotto + Colazione", "Pernotto + Mezza Pensione", "Pernotto + Pensione Completa",
        "Pernotto + Colaz. per collega", "Carb. Auto a Noleggio", "Carb. Auto Assegnata", "Noleggio Auto", "Pedaggio Autostradale",
        "Parcheggio", "Taxi", "Treno", "Bus", "Metropolitana", "Biglietto Maceba", "Spese di Rappresentanza", "Spese di Vendita", "Spese Varie"
    )
    private val DEF = mapOf(
        "Colazione" to "Bar", "Pranzo" to "Pranzo", "Cena" to "Cena", "Bar" to "Bar", "Albergo" to "Pernotto + Colazione",
        "Carburante" to "Carb. Auto a Noleggio", "Pedaggio" to "Pedaggio Autostradale", "Parcheggio" to "Parcheggio", "Taxi" to "Taxi",
        "Treno" to "Treno", "Aereo" to "Biglietto Maceba", "Noleggio auto" to "Noleggio Auto", "Altro" to "Spese Varie"
    )

    fun defaultVoce(category: String) = DEF[category] ?: "Spese Varie"
    fun voce(e: Expense) = e.zvoce.takeIf { it in VOCI } ?: defaultVoce(e.category)
    fun isPernotto(v: String) = v.startsWith("Pernotto")

    class Zone(val name: String, val cur: String, val vitto: Double, val soggiorno: Double, val countries: List<String>)

    val ZONES = listOf(
        Zone("Italia", "EUR", 37.0, 171.0, listOf("IT", "SM", "VA")),
        Zone("Europa Occidentale (eccetto UK)", "EUR", 50.0, 180.0, listOf("FR", "DE", "ES", "PT", "NL", "BE", "LU", "AT", "CH", "IE", "DK", "SE", "NO", "FI", "IS", "MC", "LI", "GR", "MT", "CY", "AD")),
        Zone("United Kingdom", "GBP", 45.0, 150.0, listOf("GB")),
        Zone("Europa Orientale", "EUR", 37.0, 148.0, listOf("SK", "HU", "RO", "BG", "HR", "SI", "RS", "BA", "ME", "MK", "AL", "EE", "LV", "LT", "UA", "BY", "MD", "TR")),
        Zone("Polonia", "PLN", 125.0, 500.0, listOf("PL")),
        Zone("Repubblica Ceca", "CZK", 800.0, 2750.0, listOf("CZ")),
        Zone("Russia", "RUB", 3000.0, 8250.0, listOf("RU")),
        Zone("Asia (eccetto Cina, Giappone e India)", "EUR", 35.0, 110.0, listOf("KR", "TH", "VN", "SG", "MY", "ID", "PH", "TW", "HK", "AE", "SA", "IL", "QA", "KW", "BH", "OM", "PK", "BD", "LK")),
        Zone("Cina", "CNY", 500.0, 1200.0, listOf("CN")),
        Zone("Giappone", "JPY", 9300.0, 25800.0, listOf("JP")),
        Zone("India", "INR", 2400.0, 15000.0, listOf("IN")),
        Zone("America del Nord", "USD", 60.0, 350.0, listOf("US", "CA")),
        Zone("Messico", "MXN", 600.0, 2350.0, listOf("MX")),
        Zone("Sud America (eccetto Brasile e Argentina)", "USD", 35.0, 175.0, listOf("CL", "PE", "CO", "UY", "PY", "BO", "EC", "VE")),
        Zone("Argentina", "ARS", 350.0, 1050.0, listOf("AR")),
        Zone("Brasile", "BRL", 100.0, 550.0, listOf("BR")),
        Zone("Australia", "AUD", 50.0, 200.0, listOf("AU", "NZ")),
        Zone("Africa", "EUR", 45.0, 250.0, listOf("ZA", "MA", "TN", "EG", "DZ", "NG", "KE"))
    )
    const val CAP_YEAR = 2015
    private val CUR_ZONE = mapOf("MXN" to "Messico", "PLN" to "Polonia", "CZK" to "Repubblica Ceca", "RUB" to "Russia", "CNY" to "Cina",
        "JPY" to "Giappone", "INR" to "India", "USD" to "America del Nord", "CAD" to "America del Nord", "ARS" to "Argentina",
        "BRL" to "Brasile", "AUD" to "Australia", "GBP" to "United Kingdom", "EUR" to "Italia")

    /** zona dei massimali: scelta in Impostazioni, altrimenti dal paese della foto, altrimenti dalla valuta */
    fun zoneFor(e: Expense): Zone? {
        if (Store.capZone.isNotEmpty()) ZONES.firstOrNull { it.name == Store.capZone }?.let { return it }
        val cc = e.country.uppercase()
        if (cc.isNotEmpty()) ZONES.firstOrNull { cc in it.countries }?.let { return it }
        val z = CUR_ZONE[e.currency.uppercase()] ?: return null
        return ZONES.firstOrNull { it.name == z }
    }

    class Cap(val kind: String, val cap: Double, val cur: String, val zone: String, val value: Double, val over: Boolean?)

    fun people(e: Expense) = e.guests.size + 1

    /** come il "Controllo massimali" di Ztravel: pranzo/cena a persona senza mancia, pernotto a notte */
    fun capCheck(e: Expense): Cap? {
        val v = voce(e); val z = zoneFor(e) ?: return null
        val (kind, cap, value) = when {
            v == "Pranzo" || v == "Cena" -> Triple("vitto", z.vitto, e.amount / people(e))
            isPernotto(v) -> Triple("soggiorno", z.soggiorno, e.amount / e.nights.coerceAtLeast(1))
            else -> return null
        }
        if (!e.currency.equals(z.cur, true)) return Cap(kind, cap, z.cur, z.name, value, null)
        return Cap(kind, cap, z.cur, z.name, Fmt.round2(value), value > cap + 0.005)
    }

    fun capNote(e: Expense): String {
        val c = capCheck(e) ?: return ""
        if (c.over != true) return ""
        val y = e.date.take(4).ifEmpty { Fmt.today().take(4) }
        return e.place.uppercase() + "_Sforamento massimale " + (if (c.kind == "vitto") "pasto " else "pernotto ") + Fmt.money(c.cap) + " " + c.cur +
            " (massimali 2015, " + c.zone + "): " + Fmt.money(c.value) + " " + c.cur + (if (c.kind == "vitto") " a persona" else " a notte") +
            "; inflazione del 2015-$y non considerata"
    }

    fun personal(e: Expense): Boolean = when (e.pay) {
        "pers" -> true
        "az" -> false
        else -> Regex("contant|cash|efectivo", RegexOption.IGNORE_CASE).containsMatchIn(e.card)
    }

    /** quota registrata in Ztravel: tutto, o la mia parte se il conto è diviso */
    fun share(e: Expense, v: Double): Double = if (e.split && people(e) > 1) Fmt.round2(v / people(e)) else v

    class Line(val voce: String, val amount: Double, val personal: Boolean)

    /** righe della nota spese: la voce, poi le Mance (carta) e le Mance in contanti, con lo stesso numero di allegato */
    fun lines(e: Expense): List<Line> {
        val out = mutableListOf(Line(voce(e), share(e, e.amount), personal(e)))
        share(e, e.tip).takeIf { it > 0 }?.let { out.add(Line("Mance", it, personal(e))) }
        share(e, e.tipCash).takeIf { it > 0 }?.let { out.add(Line("Mance", it, true)) }
        return out
    }

    fun linesCsv(list: List<Expense>): String {
        fun cell(v: String) = if (v.contains(';') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
        val sb = StringBuilder("﻿")
        sb.append(listOf("N. Alleg.", "Data", "Voce spesa", "Importo in divisa", "Div.", "Tipo documento", "Modalita di pagamento", "Carta",
            "Commensali", "Fornitore / percorso", "Note", "Massimale").joinToString(";")).append("\r\n")
        Numbering.ordered(list).forEachIndexed { i, e ->
            val c = capCheck(e)
            lines(e).forEachIndexed { j, l ->
                sb.append(listOf(
                    (i + 1).toString(), Fmt.dmy(e.date), l.voce, Fmt.plain(l.amount), e.currency,
                    if (isPernotto(voce(e))) "Fattura" else "Ricevuta/Scontrino",
                    if (l.personal) "Personale" else "Carta aziendale", if (l.personal) "" else e.card,
                    if (j == 0) e.guests.joinToString(", ") else "",
                    if (j == 0 && voce(e) == "Taxi") listOf(e.provider, e.route).filter { it.isNotBlank() }.joinToString(" - ") else "",
                    if (j == 0) e.note else "",
                    if (j == 0 && c?.over != null) (if (c.over == true) "OLTRE " + Fmt.plain(c.cap) + " " + c.cur else "ok") else ""
                ).joinToString(";") { cell(it) }).append("\r\n")
            }
        }
        return sb.toString()
    }

    fun jpgName(n: Int, e: Expense): String =
        n.toString().padStart(2, '0') + "_" + Fmt.slug(voce(e), 24).replace('-', '_').uppercase() + "_" + e.date + "_" +
            Fmt.dot(share(e, e.amount)) + "_" + e.currency + "_" + Fmt.slug(e.place, 30).replace('-', '_').uppercase() + ".jpg"

    /** Giustificativo "tipo scansione": scala di grigi, contrasto, JPEG sotto maxBytes. Restituisce i byte e le dimensioni. */
    fun scanJpeg(src: Bitmap, maxBytes: Int = 180 * 1024): Triple<ByteArray, Int, Int> {
        var side = 1700
        var best: Triple<ByteArray, Int, Int>? = null
        repeat(5) {
            val k = minOf(1f, side.toFloat() / maxOf(src.width, src.height))
            val w = (src.width * k).toInt().coerceAtLeast(1); val h = (src.height * k).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cm = ColorMatrix().apply { setSaturation(0f) }
            // contrasto un po' più alto, come una scansione
            cm.postConcat(ColorMatrix(floatArrayOf(1.35f, 0f, 0f, 0f, -40f, 0f, 1.35f, 0f, 0f, -40f, 0f, 0f, 1.35f, 0f, -40f, 0f, 0f, 0f, 1f, 0f)))
            Canvas(bmp).drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
            var q = 72
            while (q >= 34) {
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bo)
                val b = bo.toByteArray()
                best = Triple(b, w, h)
                if (b.size <= maxBytes) { bmp.recycle(); return best!! }
                q -= 8
            }
            bmp.recycle()
            side = (side * 0.8).toInt()
        }
        return best!!
    }
}

/**
 * PDF minimale scritto a mano: le immagini JPEG entrano così come sono (DCTDecode), senza ricompressione,
 * così ogni pagina resta sotto i 200 KB. Testo in Helvetica.
 */
class TinyPdf {
    private class Page(val texts: List<Triple<Float, Float, Pair<Float, String>>>, val img: Triple<ByteArray, Int, Int>?, val imgBox: FloatArray?)
    private val pages = mutableListOf<Page>()

    /** pagina A4 (595x842 pt); y dall'alto */
    fun page(texts: List<Triple<Float, Float, Pair<Float, String>>>, jpeg: Triple<ByteArray, Int, Int>? = null, box: FloatArray? = null) {
        pages.add(Page(texts, jpeg, box))
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch == '(' || ch == ')' || ch == '\\' -> sb.append('\\').append(ch)
                ch.code in 32..126 -> sb.append(ch)
                ch.code in 160..255 -> sb.append("\\").append(Integer.toOctalString(ch.code).padStart(3, '0'))
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }

    fun write(f: File) {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun w(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
        w("%PDF-1.4\n%âãÏÓ\n")
        // 1 catalog, 2 pages, 3 font; poi per pagina: page, content, (image)
        val objs = mutableListOf<ByteArray>()
        val pageIds = mutableListOf<Int>()
        var next = 4
        val bodies = mutableListOf<Pair<Int, ByteArray>>()
        for (p in pages) {
            val pageId = next++; val contId = next++; val imgId = if (p.img != null) next++ else -1
            pageIds.add(pageId)
            val cs = StringBuilder()
            if (p.img != null && p.imgBox != null) {
                val b = p.imgBox
                cs.append("q ${b[2]} 0 0 ${b[3]} ${b[0]} ${842 - b[1] - b[3]} cm /Im0 Do Q\n")
            }
            for ((x, y, t) in p.texts) cs.append("BT /F1 ${t.first} Tf $x ${842 - y} Td (${esc(t.second)}) Tj ET\n")
            val content = cs.toString().toByteArray(Charsets.ISO_8859_1)
            val res = if (imgId > 0) "/Resources << /Font << /F1 3 0 R >> /XObject << /Im0 $imgId 0 R >> >>" else "/Resources << /Font << /F1 3 0 R >> >>"
            bodies.add(pageId to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] $res /Contents $contId 0 R >>".toByteArray())
            bodies.add(contId to ("<< /Length ${content.size} >>\nstream\n").toByteArray() + content + "\nendstream".toByteArray())
            if (p.img != null && imgId > 0) {
                val (bytes, iw, ih) = p.img
                bodies.add(imgId to ("<< /Type /XObject /Subtype /Image /Width $iw /Height $ih /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n").toByteArray() + bytes + "\nendstream".toByteArray())
            }
        }
        val all = mutableListOf<Pair<Int, ByteArray>>()
        all.add(1 to "<< /Type /Catalog /Pages 2 0 R >>".toByteArray())
        all.add(2 to ("<< /Type /Pages /Kids [" + pageIds.joinToString(" ") { "$it 0 R" } + "] /Count ${pageIds.size} >>").toByteArray())
        all.add(3 to "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".toByteArray())
        all.addAll(bodies)
        all.sortBy { it.first }
        val pos = IntArray(all.size + 1)
        for ((id, body) in all) {
            pos[id] = out.size()
            w("$id 0 obj\n"); out.write(body); w("\nendobj\n")
        }
        val xref = out.size()
        w("xref\n0 ${all.size + 1}\n0000000000 65535 f \n")
        for (i in 1..all.size) w(String.format(java.util.Locale.US, "%010d 00000 n \n", pos[i]))
        w("trailer\n<< /Size ${all.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        f.writeBytes(out.toByteArray())
        objs.clear()
    }
}
