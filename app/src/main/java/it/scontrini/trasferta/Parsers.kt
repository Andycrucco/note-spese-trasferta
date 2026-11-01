package it.scontrini.trasferta

import java.util.Calendar

data class ParsedCharge(
    val date: String,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val card: String,
    val network: String
)

/** Legge gli SMS di addebito della banca (italiano, spagnolo, inglese). */
object SmsParser {
    private val CUR = mapOf(
        "€" to "EUR", "EURO" to "EUR", "EUROS" to "EUR", "US$" to "USD", "MX$" to "MXN", "PESOS" to "MXN", "$" to ""
    )
    private val AMOUNT = Regex(
        """(?:\b(EUR|USD|MXN|GBP|CHF|CAD)\b|(US\$|MX\$|€|\$))\s?([0-9][0-9.,]*)|([0-9][0-9.,]*)\s?(?:\b(EUR|USD|MXN|GBP|CHF|CAD|euros?|pesos)\b|(€))""",
        RegexOption.IGNORE_CASE
    )
    private val CODE = Regex("""\b(EUR|USD|MXN|GBP|CHF|CAD)\b""")
    val CARD = Regex(
        """(?:\*+|x{2,}|carta|card|tarjeta|terminaci[oó]n|terminada en|ending(?: in)?|finale)\D{0,12}(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )
    private val DATE = Regex("""\b(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?\b""")
    val NET = Regex("""\b(VISA|MASTERCARD|AMEX)\b""", RegexOption.IGNORE_CASE)
    private val MERCH = Regex("""\b(?:presso|c/o|en|at|in|da)\s+([A-Z0-9][A-Z0-9 &'.\-]{2,40})""")
    private val OTP = Regex("""\b(otp|codice|code|clave|c[oó]digo|password|token)\b""", RegexOption.IGNORE_CASE)
    private val CHARGE = Regex(
        """(addebit|cargo|compra|purchase|pago|pagamento|spesa|speso|acquisto|transac|consumo|autoriz|aprobad|approved|charged|spent|prelievo|retiro)""",
        RegexOption.IGNORE_CASE
    )

    fun looksLikeCharge(text: String): Boolean {
        if (!AMOUNT.containsMatchIn(text)) return false
        if (OTP.containsMatchIn(text) && !CHARGE.containsMatchIn(text)) return false
        return CHARGE.containsMatchIn(text) || CARD.containsMatchIn(text)
    }

    fun parse(text: String): List<ParsedCharge> {
        val out = mutableListOf<ParsedCharge>()
        for (chunk in text.split(Regex("""\r?\n\s*\r?\n"""))) {
            if (chunk.isBlank()) continue
            if (OTP.containsMatchIn(chunk) && !CHARGE.containsMatchIn(chunk)) continue
            val m = AMOUNT.find(chunk) ?: continue
            val g = m.groupValues
            val raw = g[3].ifEmpty { g[4] }.trimEnd('.', ',')
            val amt = Fmt.num(raw) ?: continue
            if (amt <= 0 || amt > 1e8) continue
            var cur = listOf(g[1], g[2], g[5], g[6]).firstOrNull { it.isNotEmpty() }?.uppercase() ?: ""
            cur = CUR[cur] ?: cur
            if (cur.isEmpty()) cur = CODE.find(chunk)?.groupValues?.get(1) ?: ""
            val card = CARD.find(chunk)?.groupValues?.get(1) ?: ""
            val rest = chunk.replace(m.value, " ")
            var date = ""
            DATE.find(rest)?.let { d ->
                val yy = d.groupValues[3]
                val y = when {
                    yy.isEmpty() -> Calendar.getInstance().get(Calendar.YEAR)
                    yy.length == 2 -> 2000 + yy.toInt()
                    else -> yy.toInt()
                }
                date = Fmt.validDate(y, d.groupValues[2].toInt(), d.groupValues[1].toInt())
                if (date.isNotEmpty() && yy.isEmpty() && date > Fmt.today()) {
                    date = Fmt.validDate(y - 1, d.groupValues[2].toInt(), d.groupValues[1].toInt())
                }
            }
            val net = NET.find(chunk)?.groupValues?.get(1)?.uppercase() ?: ""
            val mer = MERCH.find(chunk)?.groupValues?.get(1)
                ?.replace(Regex("""\s+\d[\d/.\-]*\s*$"""), "")?.trim() ?: ""
            out.add(ParsedCharge(date, Fmt.round2(amt), cur, mer.take(80), card, net))
        }
        return out
    }
}

data class ReceiptData(
    val date: String,
    val place: String,
    val amount: Double?,
    val tip: Double?,
    val currency: String,
    val card: String,
    val category: String?,
    val uncertain: Set<String>,
    val time: String = "",
    val text: String = ""
)

/** Ricava i dati dal testo letto sulla foto dello scontrino. L'SMS, se c'è, aiuta a scegliere il totale. */
object ReceiptParser {
    private val AMT = Regex("""(?<![\d.,])(\d{1,3}(?:[.,]\d{3})+[.,]\d{2}|\d+[.,]\d{2})(?![\d])""")
    private val TOTAL = Regex("""\b(total|totale|importe|a pagar|tot\.?|amount due|grand total|da pagare)\b""", RegexOption.IGNORE_CASE)
    private val NOT_TOTAL = Regex(
        """(sub ?-?total|subtotale|imponibile|iva\b|tax|impuesto|cambio|change|recibido|pagado con|contanti ricevuti|resto|descuento|sconto|ahorro)""",
        RegexOption.IGNORE_CASE
    )
    private val TIP = Regex("""(propina|mancia|\btip\b|gratuity|servicio|service charge)""", RegexOption.IGNORE_CASE)
    private val CASH = Regex("""\b(efectivo|contanti|cash|contante)\b""", RegexOption.IGNORE_CASE)
    private val DMY = Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})\b""")
    private val YMD = Regex("""\b(20\d{2})[/.\-](\d{1,2})[/.\-](\d{1,2})\b""")
    private val MONTHS = mapOf(
        "ene" to 1, "gen" to 1, "jan" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "apr" to 4, "may" to 5, "mag" to 5,
        "jun" to 6, "giu" to 6, "jul" to 7, "lug" to 7, "ago" to 8, "aug" to 8, "sep" to 9, "set" to 9,
        "oct" to 10, "ott" to 10, "nov" to 11, "dic" to 12, "dec" to 12
    )
    private val DMONTH = Regex("""\b(\d{1,2})[\s\-/.]+([A-Za-z]{3})[a-z]*\.?[\s\-/.,]+(\d{2,4})\b""")
    private val SKIP_PLACE = Regex(
        """(ticket|factura|rfc|tel[:.\s]|www\.|http|fecha|hora|folio|caja|cajero|mesa|comensal|cliente|scontrino|documento|p\.?\s?iva|c\.?f\.|via |calle|av\.|col\.|c\.p\.)""",
        RegexOption.IGNORE_CASE
    )

    private fun amounts(line: String): List<Double> =
        AMT.findAll(line).mapNotNull { Fmt.num(it.value) }.filter { it > 0 && it < 1e7 }.toList()

    fun parse(text: String, defaultCurrency: String, smsAmount: Double?): ReceiptData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val unsure = mutableSetOf<String>()
        val all = lines.flatMap { amounts(it) }

        // totale e mancia vicino alle loro etichette (riga stessa o le due successive)
        val totals = mutableListOf<Double>(); val tips = mutableListOf<Double>()
        for (i in lines.indices) {
            val l = lines[i]
            val isTip = TIP.containsMatchIn(l)
            val isTot = TOTAL.containsMatchIn(l) && !NOT_TOTAL.containsMatchIn(l)
            if (!isTip && !isTot) continue
            var vals = amounts(l)
            var j = i + 1
            while (vals.isEmpty() && j < lines.size && j <= i + 2) { vals = amounts(lines[j]); j++ }
            if (vals.isEmpty()) continue
            if (isTip && !isTot) tips.add(vals.last())
            else if (isTip && isTot) totals.add(vals.max())
            else totals.add(vals.last())
        }
        var amount: Double? = null
        var tip: Double? = tips.firstOrNull()

        if (smsAmount != null && smsAmount > 0) {
            val t = tip
            when {
                t != null && all.any { Fmt.same(it + t, smsAmount) } -> amount = all.first { Fmt.same(it + t, smsAmount) }
                all.any { Fmt.same(it, smsAmount) } -> {
                    // l'addebito coincide con un importo stampato: se c'è anche un totale più piccolo, la differenza è la mancia
                    val smaller = totals.filter { it < smsAmount - 0.02 && it >= smsAmount * 0.7 }.maxOrNull()
                    if (smaller != null && (t == null || Fmt.same(smaller + t, smsAmount))) { amount = smaller; tip = Fmt.round2(smsAmount - smaller) }
                    else { amount = smsAmount; if (t != null && !Fmt.same(t, 0.0)) { amount = Fmt.round2(smsAmount - t) } }
                }
            }
        }
        if (amount == null) {
            if (totals.isNotEmpty()) {
                val t = tip
                amount = if (t != null) {
                    totals.firstOrNull { a -> totals.any { b -> Fmt.same(a + t, b) } } ?: totals.min()
                } else totals.first()
            } else if (all.isNotEmpty()) {
                amount = all.max(); unsure.add("amount")
            } else unsure.add("amount")
        }
        if (tip != null && amount != null && Fmt.same(tip, amount)) tip = null

        // data
        var date = ""
        val joined = lines.joinToString("\n")
        YMD.find(joined)?.let { m -> date = Fmt.validDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        if (date.isEmpty()) DMY.findAll(joined).forEach { m ->
            if (date.isNotEmpty()) return@forEach
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            val yy = m.groupValues[3]; val y = if (yy.length == 2) 2000 + yy.toInt() else yy.toInt()
            var d = Fmt.validDate(y, b, a)
            if (d.isEmpty() && a <= 12) d = Fmt.validDate(y, a, b)
            if (d.isNotEmpty() && d <= Fmt.today()) date = d
        }
        if (date.isEmpty()) DMONTH.find(joined)?.let { m ->
            val mon = MONTHS[m.groupValues[2].lowercase().take(3)]
            if (mon != null) {
                val yy = m.groupValues[3]; val y = if (yy.length == 2) 2000 + yy.toInt() else yy.toInt()
                date = Fmt.validDate(y, mon, m.groupValues[1].toInt())
            }
        }
        if (date.isEmpty()) unsure.add("date")

        // nome dell'esercizio: una delle prime righe con lettere, che non sia indirizzo o intestazione fiscale
        val place = lines.take(6).firstOrNull { l ->
            l.count { it.isLetter() } >= 3 && amounts(l).isEmpty() && !SKIP_PLACE.containsMatchIn(l) &&
                !DMY.containsMatchIn(l) && !TOTAL.containsMatchIn(l)
        }?.take(60) ?: ""
        if (place.isEmpty()) unsure.add("place")

        // carta o contanti
        val card = when {
            CASH.containsMatchIn(joined) && !SmsParser.CARD.containsMatchIn(joined) -> "Contanti"
            else -> {
                val l4 = SmsParser.CARD.find(joined)?.groupValues?.get(1) ?: ""
                val net = SmsParser.NET.find(joined)?.groupValues?.get(1)?.uppercase() ?: ""
                listOf(net, l4).filter { it.isNotEmpty() }.joinToString(" ")
            }
        }

        val cur = when {
            Regex("""\bMXN\b|M\.N\.|pesos""", RegexOption.IGNORE_CASE).containsMatchIn(joined) -> "MXN"
            Regex("""\bUSD\b|US\$""").containsMatchIn(joined) -> "USD"
            Regex("""€|\bEUR\b""").containsMatchIn(joined) -> "EUR"
            else -> defaultCurrency
        }

        val cat = Categorizer.fromText(joined)?.category ?: if (tip != null) "Pasto" else null
        return ReceiptData(date, place, amount?.let { Fmt.round2(it) }, tip?.let { Fmt.round2(it) }, cur, card, cat, unsure, Fmt.time(joined), joined)
    }
}
