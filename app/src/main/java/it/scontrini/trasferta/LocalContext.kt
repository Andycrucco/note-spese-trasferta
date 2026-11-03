package it.scontrini.trasferta

/**
 * Contesto locale: le serie della Banca Mondiale sono nazionali, ma alcune aree a forte crescita
 * (Monterrey e San Pedro Garza García, Katowice, Milano) hanno prezzi e redditi diversi dalla media.
 * Qui solo dati pubblicati da fonti ufficiali o universitarie, con la fonte accanto a ogni numero.
 * Dove l'istituto di statistica non pubblica l'inflazione per città, lo diciamo e usiamo un indicatore di reddito.
 */
object LocalContext {
    class Bar(val label: String, val value: Double, val highlight: Boolean)
    class Area(
        val key: String, val name: String, val title: String,
        val bars: List<Bar>, val unit: String, val notes: List<String>, val source: String,
        val keywords: List<String>, val box: DoubleArray? // latMin, latMax, lonMin, lonMax
    )

    val AREAS = listOf(
        Area("monterrey", "Area metropolitana di Monterrey (N.L.)",
            "Inflazione annua a dicembre 2024: area di Monterrey contro media nazionale",
            listOf(Bar("Monterrey (indice CIE-UANL)", 6.25, true), Bar("Messico (INPC, INEGI)", 4.21, false)), "%",
            listOf(
                "A fine 2024 i prezzi nell'area di Monterrey sono saliti circa 2 punti più della media del Messico.",
                "San Pedro Garza García fa parte dell'area metropolitana: nessun istituto pubblica un indice dei prezzi solo per il comune."
            ),
            "Centro de Investigaciones Económicas, UANL (dicembre 2024), riportato da ABC Noticias il 13/01/2025",
            listOf("monterrey", "san pedro", "garza garc", "escobedo", "apodaca", "santa catarina", "nuevo leon", "nuevo león"),
            doubleArrayOf(25.40, 26.05, -100.70, -99.90)),
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
