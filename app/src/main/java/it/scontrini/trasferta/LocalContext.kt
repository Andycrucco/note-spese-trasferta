package it.scontrini.trasferta

/**
 * Contesto locale: le serie della Banca Mondiale sono nazionali, ma alcune aree a forte crescita
 * (Monterrey e San Pedro Garza García, Katowice, Milano) hanno prezzi e redditi diversi dalla media.
 * Qui solo dati pubblicati da fonti ufficiali o universitarie, con la fonte accanto a ogni numero.
 * Dove l'istituto di statistica non pubblica l'inflazione per città, lo diciamo e usiamo un indicatore di reddito.
 */
object LocalContext {
    class Bar(val label: String, val value: Double, val highlight: Boolean)
    /** indici ufficiali di dicembre (l'ultimo anno può essere l'ultimo mese pubblicato): nazionale e dell'area */
    class Series(val name: String, val short: String, val last: String, val inflArea: Double, val inflCountry: Double,
                 val country: Map<Int, Double>, val area: Map<Int, Double>) {
        /** indice dell'area diviso indice nazionale, 2010 = 1: serve a disegnare la riga locale sulla base della Banca Mondiale */
        fun ratio(year: Int): Double? {
            if (year < 2010) return null
            val k = country.keys.filter { it <= year }.maxOrNull() ?: return null
            return (area.getValue(k) / area.getValue(2010)) / (country.getValue(k) / country.getValue(2010))
        }
        val lastYear: Int get() = country.keys.max()
        fun growthArea() = (area.getValue(lastYear) / area.getValue(2010) - 1) * 100
        fun growthCountry() = (country.getValue(lastYear) / country.getValue(2010) - 1) * 100
    }
    class Area(
        val key: String, val name: String, val title: String,
        val bars: List<Bar>, val unit: String, val notes: List<String>, val source: String,
        val keywords: List<String>, val box: DoubleArray?, // latMin, latMax, lonMin, lonMax
        val country: String = "", val series: Series? = null
    )

    private val MTY = Series("Area di Monterrey (INEGI)", "Monterrey", "settembre 2026", 2.91, 3.45,
        mapOf(2010 to 74.93, 2011 to 77.79, 2012 to 80.57, 2013 to 83.77, 2014 to 87.19, 2015 to 89.05, 2016 to 92.04, 2017 to 98.27, 2018 to 103.02,
            2019 to 105.93, 2020 to 109.27, 2021 to 117.31, 2022 to 126.48, 2023 to 132.37, 2024 to 137.95, 2025 to 143.04, 2026 to 146.08),
        mapOf(2010 to 78.01, 2011 to 80.70, 2012 to 83.24, 2013 to 85.90, 2014 to 89.07, 2015 to 90.59, 2016 to 93.46, 2017 to 98.68, 2018 to 103.31,
            2019 to 106.30, 2020 to 108.92, 2021 to 117.25, 2022 to 126.30, 2023 to 131.56, 2024 to 137.09, 2025 to 141.91, 2026 to 143.40))

    val AREAS = listOf(
        Area("monterrey", "Area metropolitana di Monterrey (N.L.)",
            "Aumento dei prezzi da dicembre 2010 a settembre 2026 (INEGI)",
            listOf(Bar("Area di Monterrey (INPC città)", 83.8, true), Bar("Messico (INPC nazionale)", 95.0, false)), "%",
            listOf(
                "Secondo l'INEGI dal 2010 i prezzi nell'area di Monterrey sono saliti meno della media del Messico; a settembre 2026 l'inflazione annua è 2,91% a Monterrey contro 3,45% nazionale.",
                "La forte crescita economica dell'area non si traduce in un'inflazione più alta: l'indice misura quanto cambiano i prezzi, non quanto costano. L'indice INEGI copre tutta l'area metropolitana; per San Pedro Garza García da solo non esiste.",
                "Il CIE-UANL calcola un indice proprio (paniere del 1996) che a dicembre 2024 dava 6,25% contro il 4,21% dell'INEGI: qui si usa il dato ufficiale INEGI."
            ),
            "INEGI, Banco de Información Económica: INPC Monterrey e nazionale (base 2a quincena luglio 2018 = 100), pubblicati da DATA Nuevo León; CIE-UANL, dicembre 2024",
            listOf("monterrey", "san pedro", "garza garc", "escobedo", "apodaca", "santa catarina", "nuevo leon", "nuevo león"),
            doubleArrayOf(25.40, 26.05, -100.70, -99.90), "MX", MTY),
        Area("slaskie", "Slesia (Katowice e Czestochowa)",
            "Ricchezza prodotta per abitante, 2023 (media del voivodato di Slesia = 100)",
            listOf(Bar("Subregione di Katowice", 135.2, true), Bar("Media voivodato di Slesia", 100.0, false)), "",
            listOf(
                "Il GUS pubblica l'inflazione solo per voivodato: Katowice e Czestochowa hanno lo stesso indice (Slesia +3,0% contro +2,8% della Polonia nel 1° trimestre 2024).",
                "La differenza tra le due città si vede nel reddito: nella subregione di Katowice il PIL per abitante è il 135% della media regionale, e i prezzi dei servizi (ristoranti, hotel) lo seguono."
            ),
            "GUS, Informacja o sytuacji społeczno-gospodarczej województw 1/2024; Raport o stanie województwa śląskiego za 2025 rok",
            listOf("katowice", "gliwice", "tychy", "sosnowiec", "chorzow", "chorzów", "bytom", "zabrze", "myslowice", "mysłowice", "ruda slaska", "ruda śląska", "siemianowice", "dabrowa gornicza", "dąbrowa górnicza", "czestochowa", "częstochowa"),
            doubleArrayOf(50.05, 50.90, 18.60, 19.50)),
        Area("italia", "Città italiane",
            "Indice dei prezzi NIC 2023 (2015 = 100): Milano e città del Sud",
            listOf(Bar("Palermo", 122.5, false), Bar("Napoli", 120.7, false), Bar("Bari", 120.2, false), Bar("Italia", 119.6, false), Bar("Milano", 119.3, true)), "",
            listOf(
                "Dal 2015 al 2023 i prezzi a Milano sono cresciuti un po' meno che a Napoli o Palermo: l'inflazione misura come cambiano i prezzi, non quanto costano.",
                "A Milano il livello dei prezzi resta più alto (ristoranti, alberghi): per questo il massimale va confrontato con il costo locale, non solo con l'inflazione."
            ),
            "ISTAT, indici NIC per capoluogo (Annuario statistico del Comune di Modena 2023); serie annuale di Milano: Comune di Milano, open data DS1821",
            listOf("milano", "milan", "sesto san giovanni", "assago", "rho ", "napoli", "palermo", "bari"),
            doubleArrayOf(45.35, 45.65, 9.00, 9.40))
    )

    fun areaFor(e: Expense): Area? {
        val lat = e.lat; val lon = e.lon
        if (lat != null && lon != null) AREAS.firstOrNull { a -> a.box?.let { lat in it[0]..it[1] && lon in it[2]..it[3] } == true }?.let { return it }
        val t = (e.place + " " + e.poi + " " + e.trip).lowercase()
        return AREAS.firstOrNull { a -> a.keywords.any { t.contains(it) } }
    }
}
