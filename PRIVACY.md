# Informativa privacy – Scontrini Trasferta (Note spese trasferta)

Ultimo aggiornamento: 3 novembre 2026

Scontrini Trasferta è un'app per raccogliere gli scontrini delle trasferte di lavoro e preparare la nota spese. Non richiede un account, non ha un server dello sviluppatore e non contiene pubblicità né strumenti di analisi o tracciamento. I dati restano sul telefono, salvo quando scegli tu di inviarli altrove.

## Dati che l'app tratta
- **Spese e scontrini:** data, importo, valuta, esercente, categoria, commensali, note e foto degli scontrini che scatti o scegli. Il testo degli scontrini viene letto direttamente sul telefono con il riconoscimento del testo di Google ML Kit, senza invio a server.
- **SMS della banca (solo versione con lettura SMS):** l'app legge gli SMS in arrivo e, su tua richiesta, quelli degli ultimi 30 giorni, solo per riconoscere gli addebiti con carta. Conserva soltanto data, ora, importo, valuta, esercente e ultime 4 cifre della carta. Il testo degli SMS non viene salvato e non viene inviato a nessuno. La versione senza lettura SMS riceve gli addebiti solo se li condividi tu (Condividi dall'app Messaggi o un'automazione come MacroDroid).
- **Estratto conto:** se carichi un file della banca (CSV, Excel o PDF), viene letto sul telefono per trovare gli addebiti; il file non viene conservato né inviato.
- **Posizione:** solo quando registri una spesa, se l'hai consentita. Le coordinate vengono inviate al servizio pubblico OpenStreetMap Overpass (overpass-api.de) per trovare il locale più vicino e suggerire la categoria; il paese viene ricavato con il servizio di geocodifica di Android. Puoi disattivare l'uso della posizione nelle Impostazioni dell'app.
- **Profilo:** nome, email facoltativa e ultime 4 cifre della carta aziendale, che restano sul telefono e servono per i PDF e per riconoscere i tuoi addebiti.
- **Nome per i commensali:** il nome che scrivi viene mostrato in un QR, trasmesso via NFC o via Bluetooth/Wi-Fi (Google Nearby) ai telefoni vicini solo mentre la finestra "Sono a tavola" è aperta.

## Servizi esterni usati dall'app
- **Dati economici pubblici:** indice dei prezzi della Banca Mondiale (api.worldbank.org), previsioni d'inflazione del Fondo Monetario Internazionale (imf.org) e cambi di riferimento BCE (api.frankfurter.dev). Le richieste contengono solo paese, valuta e data, nessun dato personale.
- **Repository condiviso (facoltativo):** se lo colleghi, spese, foto e addebiti vengono sincronizzati nel repository GitHub privato che indichi tu, con il token che crei tu. I dati vanno solo in quel repository, sotto il tuo controllo; puoi scollegarlo in qualsiasi momento.
- **Aggiornamenti:** le versioni scaricate da Google Play si aggiornano tramite Google Play. Solo le versioni installate da GitHub controllano le nuove versioni su api.github.com, senza inviare dati personali.

## Sicurezza
- I dati dell'app sono cifrati con una chiave AES-256 custodita nel Keystore di Android, che non lascia mai il telefono.
- Tutte le connessioni ai servizi esterni usano HTTPS.
- L'app può essere bloccata con impronta, volto o PIN del telefono (Profilo e sicurezza).
- I dati dell'app sono esclusi dal backup automatico di Android. Il "Backup su file" lo crei tu: è un file ZIP non cifrato, salvato dove scegli; il token del repository non vi viene incluso.

## Condivisione
L'app non vende né condivide dati con terzi per pubblicità o altri scopi. PDF, foto, file CSV, cartelle per la nota spese e backup escono dal telefono solo quando li condividi o li salvi tu (per esempio su OneDrive o via email).

## Conservazione e cancellazione
I dati restano nella memoria dell'app finché non li elimini dall'app o disinstalli l'app. Puoi eliminare ogni spesa o addebito singolarmente. I file esportati in Download, i backup e i dati nel tuo repository restano finché non li cancelli tu.

## Permessi
Fotocamera (tramite l'app fotocamera), SMS (solo versione con lettura SMS), notifiche, posizione, Bluetooth e dispositivi nelle vicinanze, NFC, Internet, avvio dopo il riavvio (per il promemoria serale). Ogni permesso viene chiesto solo per la funzione descritta sopra e può essere revocato dalle impostazioni di Android.

## Minori
L'app è pensata per adulti che lavorano e non è rivolta a minori di 18 anni.

## Contatti
Per domande o richieste sulla privacy apri una segnalazione sulla pagina del progetto: https://github.com/Andycrucco/note-spese-trasferta/issues

---

# Privacy policy – Scontrini Trasferta (English)

Last updated: 3 November 2026

Scontrini Trasferta is an app for collecting business-trip receipts and preparing expense reports. It needs no account, has no developer server, and contains no ads, analytics or tracking. Data stays on the phone unless you choose to send it elsewhere.

- **Expenses and receipts:** date, amount, currency, merchant, category, diners, notes and receipt photos. Receipt text is read on the device with Google ML Kit; nothing is uploaded.
- **Bank SMS (SMS-reading edition only):** incoming bank SMS (and, on request, those of the last 30 days) are read only to detect card charges. Only date, time, amount, currency, merchant and last 4 card digits are kept. Message text is neither stored nor sent anywhere. The edition without SMS reading only receives charges you share with it.
- **Bank statements:** files you load (CSV, Excel, PDF) are parsed on the device and neither kept nor sent.
- **Location:** only when you record an expense, if allowed. Coordinates are sent to the public OpenStreetMap Overpass service to find the nearest venue; the country comes from Android's geocoder. It can be turned off in Settings.
- **Profile:** name, optional email and last 4 digits of the company card stay on the phone.
- **Diner name:** shown as a QR code, sent via NFC or Bluetooth/Wi-Fi (Google Nearby) to nearby phones only while "Sono a tavola" is open.
- **External services:** World Bank, IMF and ECB reference data (country, currency and date only); an optional private GitHub repository you configure with your own token for syncing; update checks on api.github.com only for versions installed from GitHub.
- **Security:** app data is encrypted with an AES-256 key kept in the Android Keystore; all connections use HTTPS; optional biometric/PIN lock; app data is excluded from Android backup. File backups are created by you, unencrypted, wherever you choose, without the repository token.
- **Sharing:** no data is sold or shared with third parties. Files leave the phone only when you share or save them.
- **Retention:** data is kept until you delete it or uninstall the app.
- **Children:** the app is intended for working adults, not for anyone under 18.
- **Contact:** https://github.com/Andycrucco/note-spese-trasferta/issues
