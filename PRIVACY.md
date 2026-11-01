# Informativa privacy – Note spese trasferta

Ultimo aggiornamento: 31 ottobre 2026

Note spese trasferta è un'app personale per raccogliere gli scontrini delle trasferte di lavoro. Non richiede account e non ha un server: i dati restano sul telefono.

## Dati che l'app usa
- **SMS:** l'app legge gli SMS in arrivo e, su tua richiesta, quelli degli ultimi 30 giorni, solo per riconoscere gli addebiti con carta della banca. Conserva soltanto data, importo, valuta, esercente e ultime 4 cifre della carta. Il testo degli SMS non viene salvato e non viene inviato a nessuno.
- **Foto degli scontrini:** scattate o scelte da te, lette direttamente sul telefono (riconoscimento del testo di Google ML Kit, senza invio a server).
- **Posizione:** quando fotografi uno scontrino, la posizione viene inviata al servizio pubblico OpenStreetMap Overpass (overpass-api.de) per trovare il locale più vicino e suggerire la categoria. Il paese viene ricavato con il servizio di geocodifica di Android. Puoi disattivare l'uso della posizione nelle Impostazioni dell'app.
- **Nome per i commensali:** il nome che scrivi nelle Impostazioni viene mostrato in un QR, trasmesso via NFC o via Bluetooth/Wi-Fi (Google Nearby) ai telefoni vicini solo mentre la finestra "Sono a tavola" è attiva.
- **Dati economici pubblici:** l'app scarica l'indice dei prezzi della Banca Mondiale (api.worldbank.org) e i cambi di riferimento BCE (api.frankfurter.dev). Queste richieste contengono solo il paese, la valuta e la data, nessun dato personale.

## Condivisione
L'app non vende né condivide dati con terzi. PDF, foto e file CSV escono dal telefono solo quando li condividi tu (per esempio su OneDrive o via email).

## Conservazione e cancellazione
I dati restano nella memoria dell'app finché non li elimini dall'app o disinstalli l'app. I PDF e le cartelle esportate in Download restano finché non li cancelli.

## Permessi
SMS (ricezione e lettura), notifiche, posizione, Bluetooth e dispositivi nelle vicinanze, NFC, Internet. Ogni permesso viene chiesto solo per la funzione descritta sopra e può essere revocato dalle impostazioni di Android.

## Contatti
Per domande scrivi all'autore tramite la pagina del progetto: https://github.com/Andycrucco/note-spese-trasferta/issues

---

# Privacy policy – Note spese trasferta (English)

Note spese trasferta is a personal app for collecting business-trip receipts. It has no account and no server: data stays on the phone.

- **SMS:** incoming bank SMS (and, on request, those of the last 30 days) are read only to detect card charges. Only date, amount, currency, merchant and last 4 card digits are kept. Message text is neither stored nor sent anywhere.
- **Receipt photos:** read on the device with Google ML Kit text recognition, never uploaded.
- **Location:** when you photograph a receipt, the location is sent to the public OpenStreetMap Overpass service to find the nearest venue and suggest a category; the country comes from Android's geocoder. It can be turned off in Settings.
- **Diner name:** shown as a QR code, sent via NFC or Bluetooth/Wi-Fi (Google Nearby) to nearby phones only while "Sono a tavola" is open.
- **Public economic data:** World Bank CPI and ECB reference rates are downloaded with country, currency and date only.
- **Sharing:** no data is sold or shared. Files leave the phone only when you share them.
- **Retention:** data is kept until you delete it or uninstall the app.
- **Contact:** https://github.com/Andycrucco/note-spese-trasferta/issues
