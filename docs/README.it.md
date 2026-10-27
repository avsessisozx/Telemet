<p align="center"><img src="icon.png" width="100" alt="Telemet"></p>

<h1 align="center">Telemet</h1>

<p align="center"><b>Telemetria GPS gratuita per fotocamere DJI Osmo Action. Usa il telefono Android al posto del telecomando GPS Bluetooth DJI.</b></p>

<p align="center">[English](../README.md) · [Türkçe](README.tr.md) · [Español](README.es.md) · [Français](README.fr.md) · **Italiano** · [Deutsch](README.de.md) · [Português](README.pt-BR.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Русский](README.ru.md) · [العربية](README.ar.md)</p>

---

Le fotocamere DJI Osmo Action registrano velocità, percorso e altitudine nel video solo quando è collegato il **telecomando GPS Bluetooth** di DJI. Telemet trasforma il telefono in quel telecomando: si collega via Bluetooth, invia il GPS del telefono 10 volte al secondo e la fotocamera lo salva direttamente nel video. Apri il video in **DJI Mimo** o **DJI Studio** e aggiungi la dashboard di velocità, percorso e altitudine. Nessuna conversione FIT, nessun hardware aggiuntivo. Pensato per i motociclisti, funziona allo stesso modo in bici, in auto o a piedi.

## Funzioni

- 📍 **Emula il telecomando GPS DJI**: i dati vengono incorporati nel video
- 🎬 **Controllo della fotocamera**: avvia/ferma la registrazione dall'app o dalla notifica; modalità, stato e batteria
- 🗂️ **Ogni giro separato**: "I miei giri" raggruppati per giorno con distanza, durata, velocità max/media e dislivello
- 💾 **Backup FIT + GPX di ogni giro**: importa il FIT in DJI Studio se la fotocamera ha perso i dati
- 🏍️ **Angolo di piega e forza G** da giroscopio e GPS, indipendenti da come è montato il telefono
- 🎞️ **Abbinamento ai video**: la videocamera divide le registrazioni lunghe in parti; sceglile (o condividile con Telemet dalla galleria) e ottieni un FIT + GPX per parte o un unico file unito
- 🟩 **Video indicatori**: velocità, piega, G, altitudine, distanza e minimappa su sfondo verde, con dimensioni e durata di ogni parte; sovrapponilo al video con il chroma key
- 🎛️ **Piega e G in DJI Mimo** (opzionale): scritti anche nei campi cadenza e potenza
- 🤖 **Registrazione automatica** (opzionale): la videocamera parte quando ti muovi e si ferma dopo 3 minuti da fermo
- 📈 **Altitudine barometrica** sui telefoni con barometro
- 🔋 **Funziona come un navigatore**: continua a registrare a schermo spento o con un'altra app in primo piano
- ⚡ **Primo segnale rapido**: il GPS si prepara all'apertura dell'app; i vuoti prima del segnale e nelle gallerie vengono riempiti
- 🌍 **12 lingue** · 🔓 **Gratuito e open source**: niente pubblicità, account o internet

## Download e installazione

1. Sul telefono apri l'**[ultima versione](https://github.com/avsessisozx/Telemet/releases/latest)**.
2. Tocca **Telemet-x.y.z.apk** per scaricarlo e aprilo.
3. Se Android avvisa di origini sconosciute, consenti l'installazione dal browser o dal file manager.
4. Le nuove versioni si installano sopra la precedente e mantengono i tuoi giri.

> **Scarica solo da questa pagina.** La chiave di firma è pubblica (open source); un APK da altre fonti potrebbe essere una copia manomessa che si installa sopra l'app originale.

## Avvio rapido

1. Sulla **fotocamera** attiva la connessione wireless. **Chiudi DJI Mimo**: la fotocamera parla con un solo telecomando alla volta.
2. Apri Telemet. La scheda **Configurazione** chiede i permessi necessari (posizione "Consenti sempre", Bluetooth, notifiche, batteria; su Xiaomi anche avvio automatico e risparmio batteria).
3. Tocca **Avvia**. La prima volta conferma la connessione sullo schermo della fotocamera (il codice deve coincidere).
4. Quando il punto GPS è verde, avvia la registrazione dalla fotocamera o da Telemet.
5. Alla fine tocca **Ferma**. Il giro compare in **I miei giri** e i file FIT/GPX sono in `Download/Telemet`.

## Usare il backup FIT in DJI Studio

Condividi il giro da **I miei giri** sul computer → apri il video in DJI Studio → **Dashboard** → **Origine dati** → **Importa file FIT** → se serve, **Allineamento temporale**.

---

Fotocamere supportate, sensori, FAQ e dettagli tecnici: [pagina principale in inglese](../README.md).

Licenza MIT. Questo progetto non è affiliato a DJI. DJI, Osmo e Osmo Action sono marchi di SZ DJI Technology Co., Ltd.
