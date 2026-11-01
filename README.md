# Note spese trasferta (Android)

App personale per le note spese di viaggio: legge gli SMS di addebito della banca, chiede la foto dello scontrino, la abbina, crea il PDF rinominato e lo condivide su OneDrive. Tutto resta sul telefono, funziona offline e non serve alcun account.

## Cosa fa
- **SMS della banca**: quando arriva un addebito con carta compare la notifica "Addebito 495,00 MXN · CAFE TACUBA — fotografa lo scontrino". Tocca **Fotografa**.
- **Foto**: dalla fotocamera, dalla galleria o da Google Foto.
- **Lettura dello scontrino sul telefono** (senza internet): data, ora, luogo, importo, mancia, carta o contanti. L'importo dell'SMS aiuta a distinguere totale e mancia.
- **Categoria automatica** (Colazione, Pranzo, Cena, Bar, Albergo, Carburante, Pedaggio, Parcheggio, Taxi, Treno, Aereo, Noleggio auto, Altro): incrocia testo dello scontrino, esercente dell'SMS, luogo della foto (distributore, albergo, noleggio auto, ristorante secondo OpenStreetMap) e ora del pasto. L'app mostra sempre da dove viene la categoria e puoi cambiarla.
- **Mancia**: quella sullo scontrino e quella lasciata in contanti a tavola, con percentuale sul conto e quota a persona.
- **Pagina inflazione** nei PDF dei pasti: indice dei prezzi al consumo del paese (Banca Mondiale, FP.CPI.TOTL, 2010 = 100), inflazione annua e, se imposti un massimale pasto, il massimale rivalutato confrontato con la spesa a persona.
- **Commensali da QR, NFC o Bluetooth**: "Sono a tavola" mostra il tuo QR, ti rende leggibile via NFC finché la finestra è aperta (telefoni retro contro retro) e visibile via Bluetooth per 10 minuti. Nella spesa, "Aggiungi commensale" inquadra il QR del collega o legge il suo telefono via NFC. Tutto senza server, solo tra colleghi con l'app. Gli altri suggerimenti combinano commensali recenti e storicamente più frequenti.
- **Verifica**: dice se scontrino e addebito coincidono, oppure di quanto differiscono.
- **Commensali**: nomi delle persone a tavola, con suggerimenti dai nomi già usati.
- **PDF A4** con dati e foto, chiamato come nell'organizzatore note spese: `2026-03-22_CENA_700.00_MXN_CAFE_LAUREL_ARMIDA_MANCIA_70.00_MXN_VISA_1234.pdf`, salvato in *Download/Scontrini* e inviabile a OneDrive.
- **Più addebiti in fila**: dopo uno scontrino propone il prossimo addebito senza foto.
- **Totali** per valuta, carta e categoria, più il **controvalore in euro** al cambio BCE del giorno di ogni spesa (servizio Frankfurter; nel PDF e nel CSV).
- **Scontrino mancante**: da un addebito senza foto (tieni premuto) crei una dichiarazione sostitutiva in PDF, chiaramente etichettata, da firmare.
- **Promemoria alle 21** se ci sono addebiti ancora senza scontrino.
- **Cartella per Ztravel**: `Download/NoteSpese/NoteSpese_<trasferta>/` con, in ordine cronologico, `001_<nome>.pdf` e `001_<nome>.jpg` per ogni scontrino e `Riepilogo_Ztravel.csv` (N., data, ora, tipo spesa, importi, mance, carta, commensali, persone, paese, allegati, verifica banca). La stessa cartella viene impacchettata in ZIP da inviare a OneDrive.
- **Logo aziendale**: dal menu ⋮ → Impostazioni → *Scegli logo aziendale* puoi caricare il file ufficiale del logo; compare in testata e sui PDF.

## Privacy
- Dati, foto e PDF restano sul telefono.
- La posizione viene inviata solo al servizio pubblico OpenStreetMap (Overpass) per trovare il locale più vicino; si può disattivare in Impostazioni.
- I dati sull'inflazione vengono scaricati dalla Banca Mondiale (api.worldbank.org) e conservati sul telefono per 30 giorni.
- "Sono a tavola" annuncia solo il nome che scrivi in Impostazioni, solo mentre è attivo, solo ai telefoni vicini con l'app.

## Installazione
1. Sul telefono apri la pagina **Releases** di questo repository e scarica `NoteSpeseTrasferta.apk`.
2. Aprilo e consenti l'installazione da questa fonte (Chrome o File) quando Android lo chiede. Play Protect può avvisare che l'app non viene dallo Store: scegli *Installa comunque*.
3. Installa l'app nel **profilo personale**, quello che riceve gli SMS della banca.
4. Al primo avvio consenti **SMS** e **notifiche**.
5. Se Android dice che l'impostazione è "con restrizioni": Impostazioni → App → Note spese trasferta → menu ⋮ in alto → *Consenti impostazioni con restrizioni*, poi riapri l'app e concedi gli SMS.

Gli aggiornamenti si installano sopra la versione precedente senza perdere i dati.

## Compilazione
Ogni modifica su `main` viene compilata da GitHub Actions (`.github/workflows/build.yml`) e pubblicata come release.
