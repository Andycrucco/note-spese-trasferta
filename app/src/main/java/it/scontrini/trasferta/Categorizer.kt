package it.scontrini.trasferta

/** Tipi di spesa allineati a Ztravel e all'organizzatore note spese. */
val CATS = listOf(
    "Colazione", "Pranzo", "Cena", "Bar", "Albergo", "Carburante", "Pedaggio",
    "Parcheggio", "Taxi", "Treno", "Aereo", "Noleggio auto", "Altro"
)
val MEALS = setOf("Colazione", "Pranzo", "Cena", "Bar")

data class CatHint(val category: String, val strength: Int, val reason: String)

/**
 * Decide la categoria incrociando: testo letto sullo scontrino, esercente dell'SMS,
 * luogo in cui è stata fatta la foto (OpenStreetMap) e ora del pasto.
 */
object Categorizer {
    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    private val TEXT_RULES = listOf(
        "Carburante" to rx("""(gasolin|pemex|benzin|carburant|gasolio|diesel|magna\b|premium\b|litros?\b|\blts?\b|\bfuel\b|petrol|\bbp\b|shell|orlen|eni\b|q8\b|tamoil|repsol|total ?energies|oxxo gas|g500|mobil\b|chevron|arco\b|stacja paliw)"""),
        "Pedaggio" to rx("""(peaje|caseta|capufe|pedaggi|autostrad|telepass|\btoll\b|autopista|i-?pass|viapass|myto)"""),
        "Parcheggio" to rx("""(estacionamiento|parcheggi|parking|parkimetro|parquimetro|valet|aparcamiento|parkowanie)"""),
        "Noleggio auto" to rx("""(\bbudget\b|hertz|\bavis\b|europcar|\bsixt\b|enterprise rent|national car|alamo|localiza|thrifty|dollar rent|noleggio auto|car rental|renta de autos|arrendadora|wypo[zż]yczalnia)"""),
        "Albergo" to rx("""(hotel|hostal|hostel|albergo|\bmotel\b|airbnb|\bposada\b|b&b|hospedaje|alojamiento|pernottament|tassa di soggiorno|room charge|folio|check.?out|hotel tax|ish\b)"""),
        "Aereo" to rx("""(aeromexico|volaris|viva ?aerobus|alitalia|\bita airways\b|lufthansa|ryanair|easyjet|\blot\b polish|wizz|united airlines|american airlines|delta air|iberia|air france|klm|boarding pass|tarjeta de embarque|carta d.imbarco|baggage|equipaje)"""),
        "Treno" to rx("""(trenitalia|italo\b|\bfrecciarossa|\bpkp\b|intercity|renfe|\bsncf\b|deutsche bahn|biglietto treno|tren suburbano|amtrak)"""),
        "Taxi" to rx("""(\buber\b|\bdidi\b|cabify|\btaxi\b|\bbolt\b|free ?now|radio ?taxi|sitio de taxi|itaxi)""")
    )
    private val BAR = rx("""(starbucks|caf[eé]\b|caff[eè]|coffee|cafeter|\bbar\b|pub\b|cerveceria|cantina|espresso|cappuccino|kawiarnia)""")
    private val FOOD = rx("""(restaurant|ristorante|trattoria|osteria|pizzer|taquer|tacos|bistro|cocina|comida|sushi|burger|grill|asador|mariscos|panader|pasteler|comensal|mesero|mesa\b|propina|coperto|antojitos|restauracja|bistr)""")

    fun fromText(text: String?): CatHint? {
        val t = text ?: return null
        if (t.isBlank()) return null
        for ((cat, re) in TEXT_RULES) {
            re.find(t)?.let { return CatHint(cat, 3, "scontrino: \"" + it.value.trim() + "\"") }
        }
        // segni di un pasto servito al tavolo (coperti, mesa, propina…) contano più della parola "café"
        FOOD.find(t)?.let { return CatHint("Pasto", 2, "scontrino: \"" + it.value.trim() + "\"") }
        BAR.find(t)?.let { return CatHint("Bar", 2, "scontrino: \"" + it.value.trim() + "\"") }
        return null
    }

    /** tag di OpenStreetMap del luogo più vicino alla foto. */
    fun fromTags(tags: Map<String, String>): CatHint? {
        val name = tags["name"] ?: tags["brand"] ?: ""
        val amenity = tags["amenity"] ?: ""; val tourism = tags["tourism"] ?: ""
        val label = fun(kind: String) = "posizione: " + (if (name.isNotEmpty()) "$name ($kind)" else kind)
        return when {
            amenity == "fuel" -> CatHint("Carburante", 3, label("distributore"))
            amenity == "car_rental" -> CatHint("Noleggio auto", 3, label("noleggio auto"))
            tourism in setOf("hotel", "motel", "hostel", "guest_house", "apartment") -> CatHint("Albergo", 3, label("albergo"))
            amenity == "parking" || amenity == "parking_entrance" -> CatHint("Parcheggio", 2, label("parcheggio"))
            tags["barrier"] == "toll_booth" || tags["highway"] == "toll_gantry" -> CatHint("Pedaggio", 3, label("casello"))
            tags["aeroway"] != null -> CatHint("Aereo", 2, label("aeroporto"))
            tags["railway"] == "station" -> CatHint("Treno", 2, label("stazione"))
            amenity == "taxi" -> CatHint("Taxi", 2, label("taxi"))
            amenity in setOf("cafe", "bar", "pub", "ice_cream") -> CatHint("Bar", 2, label("bar"))
            amenity in setOf("restaurant", "fast_food", "food_court", "biergarten") -> CatHint("Pasto", 2, label("ristorante"))
            else -> null
        }
    }

    /** Colazione, pranzo, cena o bar in base all'ora (HH:MM). */
    fun mealByTime(hhmm: String?): String {
        val m = Regex("""^(\d{1,2}):(\d{2})""").find(hhmm ?: "") ?: return "Pranzo"
        val min = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        return when {
            min in 240 until 630 -> "Colazione"      // 4:00 – 10:30
            min in 630 until 990 -> "Pranzo"         // 10:30 – 16:30
            min in 990 until 1110 -> "Bar"           // 16:30 – 18:30
            else -> "Cena"
        }
    }

    /**
     * Combina gli indizi. Vince quello più forte; a parità, testo dello scontrino > posizione > SMS.
     * "Pasto" generico diventa colazione/pranzo/cena secondo l'ora.
     */
    fun decide(receiptText: String?, smsMerchant: String?, tags: Map<String, String>?, time: String?, hasTip: Boolean): CatHint {
        val hints = listOfNotNull(
            fromText(receiptText),
            tags?.let { fromTags(it) },
            fromText(smsMerchant)?.let { it.copy(strength = it.strength - 1, reason = it.reason.replace("scontrino", "SMS")) }
        )
        var best = hints.maxByOrNull { it.strength }
        if (best == null && hasTip) best = CatHint("Pasto", 1, "mancia sullo scontrino")
        if (best == null) return CatHint("Altro", 0, "nessun indizio: scegli tu la categoria")
        val agree = hints.filter { it !== best && sameKind(it.category, best!!.category) }
        var reason = best.reason + agree.joinToString("") { " + " + it.reason }
        var cat = best.category
        if (cat == "Pasto") {
            cat = mealByTime(time)
            reason += if (!time.isNullOrBlank()) " · ora $time" else ""
        }
        return CatHint(cat, best.strength + agree.size, reason)
    }

    private fun sameKind(a: String, b: String): Boolean =
        a == b || (a == "Pasto" && b in MEALS) || (b == "Pasto" && a in MEALS) || (a in MEALS && b in MEALS)

    /** Converte le categorie della prima versione. */
    fun migrate(old: String, time: String?): String = when (old) {
        "Pasti" -> mealByTime(time)
        "Trasporti" -> "Taxi"
        "Alloggio" -> "Albergo"
        in CATS -> old
        else -> "Altro"
    }

    /** Paese della spesa (codice ISO a 2 lettere per la Banca Mondiale) dalla valuta, se manca la posizione. */
    private val CUR_COUNTRY = mapOf(
        "MXN" to "MX", "PLN" to "PL", "USD" to "US", "GBP" to "GB", "CHF" to "CH", "CAD" to "CA", "BRL" to "BR",
        "CNY" to "CN", "JPY" to "JP", "INR" to "IN", "CZK" to "CZ", "HUF" to "HU", "RON" to "RO", "TRY" to "TR",
        "SEK" to "SE", "NOK" to "NO", "DKK" to "DK", "ARS" to "AR", "CLP" to "CL", "COP" to "CO", "PEN" to "PE",
        "ZAR" to "ZA", "KRW" to "KR", "AUD" to "AU", "SGD" to "SG", "AED" to "AE", "SAR" to "SA", "RSD" to "RS",
        "EUR" to "XC"  // area euro, se non si sa il paese esatto
    )
    fun countryFor(country: String?, currency: String?): String =
        country?.uppercase()?.takeIf { it.length == 2 } ?: CUR_COUNTRY[currency?.uppercase() ?: ""] ?: ""
}

/** est = anno stimato con le previsioni FMI (non ancora pubblicato dalla Banca Mondiale) */
data class CpiPoint(val year: Int, val value: Double, val est: Boolean = false)

/** Calcoli sull'indice dei prezzi al consumo (FP.CPI.TOTL, 2010 = 100). */
object CpiMath {
    fun yoy(points: List<CpiPoint>): List<Pair<Int, Double>> {
        val s = points.sortedBy { it.year }
        return s.zipWithNext().filter { it.first.year + 1 == it.second.year && it.first.value > 0 }
            .map { (a, b) -> b.year to (b.value / a.value - 1) * 100 }
    }

    fun cumulative(points: List<CpiPoint>, from: Int, to: Int): Double? {
        val a = points.find { it.year == from }?.value ?: return null
        val b = points.find { it.year == to }?.value ?: return null
        return if (a > 0) (b / a - 1) * 100 else null
    }

    /**
     * Prolunga la serie ufficiale con le variazioni annue previste dall'FMI (World Economic Outlook)
     * fino all'anno indicato: i punti aggiunti sono marcati come stima.
     */
    fun project(points: List<CpiPoint>, imf: Map<Int, Double>?, upTo: Int): List<CpiPoint> {
        val s = points.sortedBy { it.year }.toMutableList()
        if (imf == null || s.isEmpty()) return s
        var last = s.last()
        while (last.year < upTo) {
            val r = imf[last.year + 1] ?: break
            last = CpiPoint(last.year + 1, last.value * (1 + r / 100.0), est = true)
            s.add(last)
        }
        return s
    }

    /** Massimale rivalutato all'ultimo anno disponibile. */
    fun revalue(cap: Double, baseYear: Int, points: List<CpiPoint>): Pair<Double, Int>? {
        val base = points.find { it.year == baseYear }?.value ?: return null
        val last = points.maxByOrNull { it.year } ?: return null
        if (base <= 0) return null
        return Fmt.round2(cap * last.value / base) to last.year
    }
}
