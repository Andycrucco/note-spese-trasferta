package it.scontrini.trasferta

/**
 * Controllo prima dell'invio: le cose che di solito fanno tornare indietro una nota spese
 * (scontrino mancante, taxi senza percorso, addebiti della banca senza spesa, importi a zero).
 * Le voci "da sistemare" bloccano poco: l'esportazione chiede solo conferma.
 */
object Checks {
    class Issue(val text: String, val severe: Boolean, val expense: Expense? = null, val sms: SmsCharge? = null)

    fun forTrip(list: List<Expense>, pending: List<SmsCharge>): List<Issue> {
        val out = mutableListOf<Issue>()
        fun who(e: Expense) = e.place.ifBlank { e.category.ifBlank { "Spesa" } } + " · " + Fmt.dmy(e.date) + " · " + Fmt.money(e.total) + " " + e.currency
        for (e in list.sortedBy { it.date }) {
            val voce = Ztravel.voce(e)
            if (e.amount <= 0.0) out.add(Issue("Importo a zero: " + who(e), true, e))
            if (e.photo == null && !e.declaration) out.add(Issue("Senza foto dello scontrino: fotografalo o fai la dichiarazione · " + who(e), true, e))
            if (voce == "Taxi" && (e.provider.isBlank() || e.route.isBlank())) out.add(Issue("Taxi senza prestatore o percorso (richiesti per la parte fiscale) · " + who(e), true, e))
            if (Ztravel.capCheck(e)?.over == true) out.add(Issue("Oltre il massimale: la nota con la motivazione è già pronta, controllala · " + who(e), false, e))
            if (e.currency != "EUR" && e.eurRate == null) out.add(Issue("Manca il cambio BCE (si completa con internet) · " + who(e), false, e))
            if (voce == "Spese Varie") out.add(Issue("Voce generica \"Spese Varie\": scegli la voce giusta se c'è · " + who(e), false, e))
        }
        // addebiti della banca ancora senza scontrino nelle date della trasferta
        if (list.isNotEmpty()) {
            val from = list.minOf { it.date }; val to = list.maxOf { it.date }
            pending.filter { it.date in from..to }.forEach { s ->
                out.add(Issue("Addebito senza scontrino: " + s.merchant.ifBlank { "esercente non indicato" } + " · " + Fmt.dmy(s.date) + " · " + Fmt.money(s.amount) + " " + s.currency, true, sms = s))
            }
        }
        return out.sortedByDescending { it.severe }
    }
}
