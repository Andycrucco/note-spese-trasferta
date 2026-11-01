package it.scontrini.trasferta

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

val MESI = listOf("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")

/** Numeri, date e nomi di file: nessuna dipendenza da Android, così si può provare sul computer. */
object Fmt {
    private val IT = Locale.ITALY

    fun money(d: Double): String = String.format(IT, "%,.2f", d)
    fun plain(d: Double): String = String.format(IT, "%.2f", d)
    fun dot(d: Double): String = String.format(Locale.US, "%.2f", d)
    fun round2(d: Double): Double = Math.round(d * 100) / 100.0
    fun same(a: Double, b: Double): Boolean = abs(a - b) < 0.02

    /** "1.234,50", "1,234.50", "45,5", "450.00" → numero. */
    fun num(v: String?): Double? {
        var s = (v ?: "").trim().replace(Regex("\\s"), "").trimEnd('.', ',')
        s = s.replace(Regex("[^0-9.,\\-]"), "")
        if (s.isEmpty()) return null
        val hasC = s.contains(','); val hasD = s.contains('.')
        s = when {
            hasC && hasD -> if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            hasC -> {
                val after = s.substringAfterLast(',')
                if (after.length == 3 && s.count { it == ',' } >= 1 && s.substringBefore(',').length <= 3) s.replace(",", "") else s.replace(',', '.')
            }
            s.count { it == '.' } > 1 -> s.replace(".", "")
            else -> s
        }
        val d = s.toDoubleOrNull() ?: return null
        return if (d.isFinite()) d else null
    }

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun isoOf(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    fun validDate(y: Int, m: Int, d: Int): String {
        if (y < 2000 || y > 2100 || m !in 1..12 || d !in 1..31) return ""
        return try {
            val c = Calendar.getInstance()
            c.isLenient = false
            c.clear()
            c.set(y, m - 1, d)
            c.time
            String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
        } catch (e: Exception) {
            ""
        }
    }

    fun isoValid(s: String?): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(s ?: "") ?: return ""
        return validDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    fun dmy(iso: String): String =
        if (iso.length == 10) iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4) else ""

    fun dayLabel(iso: String): String {
        if (isoValid(iso).isEmpty()) return "Senza data"
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
            SimpleDateFormat("EEE d MMM yyyy", IT).format(d!!)
        } catch (e: Exception) {
            dmy(iso)
        }
    }

    fun daysBetween(a: String, b: String): Long? {
        if (isoValid(a).isEmpty() || isoValid(b).isEmpty()) return null
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val da = f.parse(a)!!.time; val db = f.parse(b)!!.time
        return Math.round(abs(da - db) / 86_400_000.0)
    }

    fun slug(s: String?, max: Int): String {
        val n = Normalizer.normalize(s ?: "", Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        var r = n.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        if (r.length > max) r = r.substring(0, max).trimEnd('-')
        return r
    }

    fun last4(s: String?): String = Regex("(\\d{4})(?!.*\\d)").find(s ?: "")?.groupValues?.get(1) ?: ""

    fun hash(s: String): String {
        var h = 5381
        for (ch in s) h = (h shl 5) + h + ch.code
        return (h.toLong() and 0xffffffffL).toString(36)
    }

    private fun up(s: String?, max: Int): String = slug(s, max).replace('-', '_').uppercase()

    /**
     * Stesso schema dell'organizzatore note spese:
     * 2026-03-22_CENA_700.00_MXN_CAFE_LAUREL_ARMIDA_MANCIA_70.00_MXN_VISA_1234.pdf
     */
    fun pdfName(date: String, category: String, amount: Double, currency: String, place: String, tipTotal: Double, card: String): String {
        val cur = currency.ifEmpty { "XXX" }
        val parts = mutableListOf(
            date.ifEmpty { "SENZA-DATA" },
            up(category, 20).ifEmpty { "ALTRO" },
            dot(amount), cur,
            up(place, 40).ifEmpty { "ESERCENTE" }
        )
        if (tipTotal > 0) { parts.add("MANCIA"); parts.add(dot(tipTotal)); parts.add(cur) }
        val c = up(card, 24)
        if (c.isNotEmpty()) parts.add(c)
        return parts.joinToString("_") + ".pdf"
    }

    /** "21:23" se trovato nel testo, altrimenti "". */
    fun time(text: String?): String {
        val m = Regex("""\b([01]?\d|2[0-3])[:h]([0-5]\d)(?::[0-5]\d)?\b""").find(text ?: "") ?: return ""
        return m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
    }

    fun parseGuests(s: String?): List<String> {
        val seen = HashSet<String>(); val out = mutableListOf<String>()
        for (raw in (s ?: "").split(Regex("[,;\\n]+"))) {
            val n = raw.replace(Regex("\\s+"), " ").trim().take(60)
            if (n.isEmpty()) continue
            if (seen.add(n.lowercase())) out.add(n)
        }
        return out.take(30)
    }
}
