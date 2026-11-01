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
    var catReason: String = ""
) {
    /** quanto addebitato sulla carta: è quello che si confronta con l'SMS */
    val charged: Double get() = amount + tip
    val tipTotal: Double get() = tip + tipCash
    val total: Double get() = amount + tip + tipCash
    val people: Int get() = guests.size + 1
    val isMeal: Boolean get() = category in MEALS
    fun tipPct(): Double = if (amount > 0) tipTotal / amount * 100 else 0.0
    fun pdfName(): String = Fmt.pdfName(date, category, amount, currency, place, tipTotal, card)
}

data class SmsCharge(
    val id: String,
    val date: String,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val card: String,
    val network: String,
    val created: Long
) {
    fun cardLabel(): String = when {
        network.isNotEmpty() && card.isNotEmpty() -> "$network $card"
        network.isNotEmpty() -> network
        card.isNotEmpty() -> "carta $card"
        else -> ""
    }
}

/** Abbinamento tra scontrini e addebiti della banca. Funzioni pure sulle liste. */
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
        return sms.filter { !r.s2e.containsKey(it.id) }.sortedBy { it.date }
    }
}
