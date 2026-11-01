package it.scontrini.trasferta

import kotlin.math.abs
import kotlin.math.max

data class Expense(
    val id: String,
    var trip: String,
    var date: String,
    var place: String,
    var amount: Double,
    var tip: Double,
    var currency: String,
    var card: String,
    var category: String,
    var guests: List<String>,
    var pdf: String?,
    var photo: String?,
    var bankId: String?,
    val created: Long,
    var time: String = "",
    /** mancia lasciata in contanti sul tavolo: non compare né sullo scontrino né nell'SMS */
    var tipCash: Double = 0.0,
    var lat: Double? = null,
    var lon: Double? = null,
    var country: String = "",
    var poi: String = "",
    var catReason: String = "",
    /** cambio BCE verso l'euro alla data della spesa (1 EUR se già in euro) */
    var eurRate: Double? = null,
    var eurDate: String = "",
    /** scontrino non disponibile: il PDF è una dichiarazione sostitutiva */
    var declaration: Boolean = false,
    /** ultima modifica: decide chi vince se la stessa spesa cambia su due telefoni */
    var updated: Long = 0L
) {
    val totalEur: Double? get() = eurRate?.let { Fmt.round2(total * it) }
    /** quanto addebitato sulla carta: è quello che si confronta con l'SMS */
    val charged: Double get() = amount + tip
    val tipTotal: Double get() = tip + tipCash
    val total: Double get() = amount + tip + tipCash
    val people: Int get() = guests.size + 1
    val isMeal: Boolean get() = category in MEALS
    fun tipPct(): Double = if (amount > 0) tipTotal / amount * 100 else 0.0
    fun pdfName(): String = Fmt.pdfName(date, category, amount, currency, place, tipTotal, card)
        .let { if (declaration) it.removeSuffix(".pdf") + "_DICHIARAZIONE.pdf" else it }
}

data class SmsCharge(
    val id: String,
    val date: String,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val card: String,
    val network: String,
    val created: Long,
    /** ora dell'addebito (dal testo dell'SMS o dall'ora di arrivo), "" se non nota */
    val time: String = "",
    /** "" o "sms" = SMS della banca; "estratto" = riga dell'estratto conto */
    val source: String = "",
    /** completata su un altro dispositivo (per esempio nella versione web) */
    var done: Boolean = false,
    /** SMS confermato da un estratto conto caricato dopo */
    var inStmt: Boolean = false
) {
    val isStmt: Boolean get() = source == "estratto"

    fun cardLabel(): String = when {
        network.isNotEmpty() && card.isNotEmpty() -> "$network $card"
        network.isNotEmpty() -> network
        card.isNotEmpty() -> "carta $card"
        else -> ""
    }
}

/** Abbinamento tra scontrini e addebiti della banca. Funzioni pure sulle liste. */
/**
 * Doppioni tra SMS, estratti conto e transazioni ricevute dalla versione web.
 * Stesso importo e valuta, stessa carta (se nota) e date vicine: l'estratto conto registra
 * spesso l'addebito 1-3 giorni dopo l'SMS. Uno a uno: ogni movimento già presente
 * può assorbire una sola riga per importazione, così due taxi uguali nello stesso giorno restano due.
 */
object Dedupe {
    fun same(a: SmsCharge, b: SmsCharge, days: Long): Boolean {
        if (abs(a.amount - b.amount) > 0.015) return false
        if (a.currency.isNotEmpty() && b.currency.isNotEmpty() && a.currency != b.currency) return false
        if (a.card.isNotEmpty() && b.card.isNotEmpty() && a.card != b.card) return false
        return (Fmt.daysBetween(a.date, b.date) ?: 0L) <= days
    }

    fun tolerance(a: SmsCharge, b: SmsCharge): Long = when {
        a.isStmt && b.isStmt -> 0
        a.isStmt || b.isStmt -> 3
        else -> 1
    }

    /** strictSms: tra due SMS conta solo l'identico (due spese uguali in giorni vicini sono vere) */
    fun find(rec: SmsCharge, pool: List<SmsCharge>, used: Set<String>, strictSms: Boolean): SmsCharge? {
        pool.firstOrNull { it.id == rec.id }?.let { return it }
        return pool.filter { it.id !in used }
            .filter { !(strictSms && !it.isStmt && !rec.isStmt) }
            .filter { same(rec, it, tolerance(rec, it)) }
            .minByOrNull { Fmt.daysBetween(rec.date, it.date) ?: 0L }
    }
}

/** Scambio con la versione web: una riga "NS1|fonte|data|ora|importo|valuta|carta|circuito|esercente" per transazione. */
object Ns {
    fun smsId(date: String, amount: Double, currency: String, card: String, merchant: String) =
        "s" + Fmt.hash(listOf(date, Fmt.dot(amount), currency, card, Fmt.slug(merchant, 20)).joinToString("|"))

    fun stmtId(date: String, amount: Double, currency: String, merchant: String, k: Int) =
        "e" + Fmt.hash(listOf(date, Fmt.dot(amount), currency, Fmt.slug(merchant, 20), k.toString()).joinToString("|"))

    fun line(s: SmsCharge): String {
        fun c(v: String) = v.replace(Regex("[|\\r\\n]"), " ").trim()
        return listOf("NS1", if (s.isStmt) "estratto" else "sms", c(s.date), c(s.time), Fmt.dot(s.amount), c(s.currency), c(s.card), c(s.network), c(s.merchant)).joinToString("|")
    }

    fun text(list: List<SmsCharge>): String =
        "Note spese: ${list.size} " + (if (list.size == 1) "transazione" else "transazioni") + " da completare\n" + list.joinToString("\n") { line(it) }

    fun isOwnLine(l: String) = l.trim().startsWith("NS1|") || Regex("^Note spese: \\d+ transazion", RegexOption.IGNORE_CASE).containsMatchIn(l.trim())

    fun parse(text: String): List<SmsCharge> {
        val out = mutableListOf<SmsCharge>()
        val seen = HashMap<String, Int>()
        for (raw in text.lines()) {
            val p = raw.trim().split("|")
            if (p.size < 9 || p[0] != "NS1") continue
            val amount = Fmt.num(p[4]) ?: continue
            if (amount <= 0) continue
            val stmt = p[1] == "estratto"
            val date = Fmt.isoValid(p[2]); val cur = p[5].takeIf { Regex("^[A-Z]{3}$").matches(it) } ?: ""
            val card = p[6].takeIf { Regex("^\\d{4}$").matches(it) } ?: ""
            val merchant = p.drop(8).joinToString(" ").take(80)
            val key = listOf(date, Fmt.dot(amount), cur, Fmt.slug(merchant, 20)).joinToString("|")
            val k = seen.getOrDefault(key, 0); seen[key] = k + 1
            out.add(SmsCharge(
                if (stmt) stmtId(date, amount, cur, merchant, k) else smsId(date, amount, cur, card, merchant),
                date, Fmt.round2(amount), cur, merchant, card,
                p[7].takeIf { it in setOf("VISA", "MASTERCARD", "AMEX") } ?: "", System.currentTimeMillis(),
                p[3].takeIf { Regex("^\\d{2}:\\d{2}$").matches(it) } ?: "", if (stmt) "estratto" else "sms"))
        }
        return out
    }
}

/**
 * Numerazione degli scontrini nell'ordine delle transazioni in banca.
 * Non viene salvata: si ricalcola a ogni apertura ed esportazione, così un SMS aggiunto dopo
 * (anche giorni dopo) rimette tutto in ordine da solo.
 * Ordine: data dell'addebito in banca (o dello scontrino se non abbinato), poi ora dell'addebito
 * (o dello scontrino), poi momento di registrazione.
 */
object Numbering {
    fun ordered(list: List<Expense>, m: Match.Result = Store.match(), sms: List<SmsCharge> = Store.sms): List<Expense> {
        val byId = sms.associateBy { it.id }
        fun s(e: Expense) = m.e2s[e.id]?.let { byId[it] }
        return list.sortedWith(compareBy<Expense>(
            { s(it)?.date?.ifEmpty { null } ?: it.date },
            { s(it)?.time?.ifEmpty { null } ?: it.time.ifEmpty { "99:99" } },
            { s(it)?.created ?: Long.MAX_VALUE },
            { it.created }
        ))
    }

    fun numbers(list: List<Expense>): Map<String, Int> =
        ordered(list).mapIndexed { i, e -> e.id to i + 1 }.toMap()

    /** impronta dell'ordine attuale: cambia quando cambia la numerazione */
    fun signature(list: List<Expense>): String =
        ordered(list).joinToString(",") { it.id }

    fun bankDate(e: Expense, m: Match.Result = Store.match()): String =
        m.e2s[e.id]?.let { id -> Store.sms.firstOrNull { it.id == id }?.date } ?: ""
}

object Match {
    class Result(
        val e2s: Map<String, String>,
        val s2e: Map<String, String>,
        val diff: Map<String, Double>,
        val manual: Set<String>
    )

    /** Punteggio di somiglianza, oppure null se sicuramente non sono la stessa spesa. */
    fun score(e: Expense, s: SmsCharge): Pair<Int, Double>? {
        if (e.currency.isNotEmpty() && s.currency.isNotEmpty() && e.currency != s.currency) return null
        val l = Fmt.last4(e.card)
        var cardMatch = false
        if (l.isNotEmpty() && s.card.isNotEmpty()) {
            if (l != s.card) return null
            cardMatch = true
        }
        val dd = Fmt.daysBetween(e.date, s.date)
        if (dd != null && dd > 4) return null
        val sa = s.amount
        var sc: Int
        val diff: Double
        if (abs(sa - e.charged) < 0.02 || abs(sa - e.amount) < 0.02) {
            sc = 60; diff = 0.0
        } else if (sa > e.charged + 0.02 && sa <= e.total * 1.3 + 0.02 && (cardMatch || (dd != null && dd <= 1))) {
            sc = 30; diff = sa - e.charged
        } else return null
        if (cardMatch) sc += 25
        if (dd != null) sc += max(0, 10 - (dd * 3).toInt())
        val ms = Fmt.slug(s.merchant, 12).lowercase()
        if (ms.isNotEmpty() && Fmt.slug(e.place, 80).lowercase().contains(ms)) sc += 10
        return sc to Fmt.round2(diff)
    }

    fun all(expenses: List<Expense>, sms: List<SmsCharge>): Result {
        val e2s = HashMap<String, String>(); val s2e = HashMap<String, String>()
        val diff = HashMap<String, Double>(); val manual = HashSet<String>()
        for (e in expenses) {
            val b = e.bankId ?: continue
            val s = sms.find { it.id == b } ?: continue
            if (s2e.containsKey(s.id)) continue
            e2s[e.id] = s.id; s2e[s.id] = e.id; manual.add(e.id)
            diff[e.id] = Fmt.round2(s.amount - e.charged)
        }
        data class Cand(val e: Expense, val s: SmsCharge, val score: Int, val diff: Double)
        val cands = mutableListOf<Cand>()
        for (e in expenses) {
            if (e2s.containsKey(e.id)) continue
            for (s in sms) {
                if (s2e.containsKey(s.id)) continue
                val r = score(e, s) ?: continue
                cands.add(Cand(e, s, r.first, r.second))
            }
        }
        cands.sortByDescending { it.score }
        for (c in cands) {
            if (e2s.containsKey(c.e.id) || s2e.containsKey(c.s.id)) continue
            e2s[c.e.id] = c.s.id; s2e[c.s.id] = c.e.id; diff[c.e.id] = c.diff
        }
        return Result(e2s, s2e, diff, manual)
    }

    /** Lo scontrino già registrato che corrisponde a questo addebito, se c'è. */
    fun expenseFor(s: SmsCharge, expenses: List<Expense>, sms: List<SmsCharge>): Expense? {
        expenses.find { it.bankId == s.id }?.let { return it }
        val r = all(expenses, sms)
        r.s2e[s.id]?.let { id -> return expenses.find { it.id == id } }
        var best: Expense? = null; var bestScore = 0
        for (e in expenses) {
            if (e.bankId != null && e.bankId != s.id) continue
            if (r.e2s.containsKey(e.id) && r.e2s[e.id] != s.id) continue
            val sc = score(e, s)?.first ?: continue
            if (sc >= 70 && sc > bestScore) { best = e; bestScore = sc }
        }
        return best
    }

    /** Addebiti ancora senza scontrino, dal più vecchio. */
    fun pending(expenses: List<Expense>, sms: List<SmsCharge>): List<SmsCharge> {
        val r = all(expenses, sms)
        return sms.filter { !r.s2e.containsKey(it.id) && !it.done }.sortedBy { it.date }
    }
}
