# 🍏⇄🤖 PixelMacSync

### PixelSync (macOS) ⇄ MacSync (Android)
(🇬🇧 Scroll down for the English readme, READ IT!)

PixelMacSync è un progetto open-source che fornisce un’integrazione nativa tra macOS e Android tramite **Bluetooth Low Energy (BLE)**.
L’obiettivo è offrire sincronizzazione di notifiche, telemetria e controllo remoto senza dipendere da Wi‑Fi, cloud o connessioni TCP persistenti.

>[!WARNING]
> ⚠️ **NOTA BENE PRIMA DI COMPILARE (Leggere Attentamente!)**
>
> Questo progetto è rilasciato esclusivamente sotto forma di **codice sorgente**. Prima di procedere con la build, ci sono due configurazioni da sistemare per evitare problemi funzionali o comportamenti anomali:
>
> **1. UUID Bluetooth (consigliato usarne di propri)**
> Il collegamento è cifrato e richiede l’abbinamento Bluetooth tra Mac e telefono, quindi nessun altro dispositivo può leggere le tue notifiche o inviare comandi, anche se usa gli stessi UUID. Usare UUID propri evita comunque che, prima dell’abbinamento, due installazioni vicine si trovino a vicenda.
> Non serve modificare il codice: genera 4 UUID (uno per il Service principale e tre per i canali dati) e mettili in due file locali, **esclusi da git**, assicurandoti che i valori **combacino esattamente** tra le due app:
> * Android: `Android/app/src/main/assets/ble_uuids.properties`
> * macOS: `MacOS/PixelSync/PixelSync/LocalUUIDs.plist`
>
> Il formato è descritto nella sezione "Protocollo BLE & UUIDs". Se i file non ci sono, le app usano gli UUID pubblici elencati in quella sezione.
>
> **2. Dipendenza per il controllo dell'Hotspot Remoto**
> Il toggle dell'Hotspot dal Mac invia un Intent broadcast ad Android per aggirare le restrizioni di sistema. Questo richiede l'app di terze parti **MacroDroid**.
> * Devi creare due macro che si attivino alla ricezione degli intent `it.luigi.macsync.HOTSPOT_ON` e `it.luigi.macsync.HOTSPOT_OFF`.
> * Se non usi MacroDroid, ignora semplicemente il toggle o commenta la relativa sezione in `ContentView.swift` su macOS.
>
> ---
> ℹ️ *Nota sulla Privacy Android*: l’app non usa la posizione. Al posto del nome della rete Wi‑Fi il Mac mostra semplicemente `"Wi-Fi"`, così l’indicatore della posizione sul telefono non si accende mai.

## 🎯 Obiettivi di Progetto

* Solo Bluetooth Low Energy (BLE)
* Sincronizzazione stateless
* Architettura incentrata sul risparmio energetico (Battery-first)
* Client nativi per Android e macOS
* Nessun servizio cloud o server esterno
* Nessuna dipendenza dalla rete Wi-Fi locale
* Comunicazione event-driven

> 💡 **Filosofia di Design**
>
> PixelMacSync è intenzionalmente focalizzato su una sincronizzazione a bassa larghezza di banda e guidata dagli eventi esclusivamente tramite Bluetooth Low Energy.
> 
> Funzionalità che richiederebbero grandi trasferimenti di dati, connessioni di rete persistenti, servizi cloud o un impatto significativo sulla batteria sono considerate rigorosamente al di fuori dello scopo di questo progetto.

---

## 📋 Requisiti

| Componente     | Requisito minimo                                              |
| -------------- | ------------------------------------------------------------- |
| **Android**    | Android 16+ (target Android 17, ottimizzato per Google Pixel) |
| **macOS**      | macOS 26.4+                                                   |
| **MacroDroid** | Richiesto per il controllo remoto dell'Hotspot                |
| **Bluetooth**  | BLE 4.2+ su entrambi i dispositivi                            |

---

## ✨ Features Principali

* 📊 **Telemetria in tempo reale**: visualizza dalla Menu Bar del Mac batteria, stato di ricarica, tipo di connessione, segnale e altri indicatori del telefono.
* 🔔 **Inoltro notifiche**: le notifiche Android selezionate vengono inoltrate a macOS come notifiche native, mantenendo le icone originali quando disponibili.
* 🔄 **Reconnection sync**: alla riconnessione Bluetooth, macOS richiede uno snapshot completo dello stato attuale per riallineare notifiche e telemetria.
* 🛡️ **Filtro anti-doppioni**: le notifiche “summary” o raggruppate vengono filtrate; quando un’app aggiorna una notifica (es. nuovi messaggi nella stessa chat) quella sul Mac viene sostituita, non duplicata. Le notifiche permanenti (download, navigazione, musica) non vengono inoltrate, le chiamate in arrivo sì.
* 🧹 **Dismiss bidirezionale**: una notifica aperta, chiusa con la X o rimossa su un lato (anche dall’app stessa, es. messaggio letto da un altro dispositivo) sparisce anche dall’altro.
* 💬 **Pulsanti delle notifiche**: i pulsanti Android utilizzabili da remoto (Rispondi, Segna come letto, Archivia…) compaiono anche sulla notifica del Mac; le risposte si scrivono direttamente dalla notifica. I pulsanti che aprirebbero un’app sul telefono non vengono mostrati, quelli che eliminano qualcosa sono in rosso.
* 🔐 **Collegamento cifrato**: Mac e telefono comunicano solo dopo l’abbinamento Bluetooth; PixelSync si collega sempre e solo al telefono abbinato.
* 🖱️ **Apertura app dinamica**: il mapping tra package name Android e app/PWA su macOS è configurabile e salvato in un file JSON locale.
* 🚀 **Controllo hotspot remoto**: accendi e spegni l’hotspot del telefono dal Mac tramite integrazione con MacroDroid.

---

## 🔍 How It Works

PixelMacSync è diviso in due client che dialogano tramite un server GATT personalizzato.

### 📱 Android Client — MacSync

L’app Android agisce come **Peripheral / GATT Server** ed è ottimizzata per la serie Google Pixel.

* **Motore BLE in background**: usa un *Foreground Service* per restare attivo anche durante i cicli di Doze; mentre il Mac è collegato smette di farsi cercare, per risparmiare batteria.
* **Notification interceptor & snapshot**: intercetta le notifiche e genera uno snapshot dello stato attivo quando richiesto dal Mac.
* **GattServerManager & broadcasts**: riceve comandi dal Mac e li inoltra ai componenti interni dell’app; le notifiche verso il Mac partono in coda, una alla volta.
* **App compagna**: il Mac può essere associato come dispositivo compagno di Android (nome del Mac nel Bluetooth, app protetta dalla chiusura).
* **MacroDroid bridge**: delega l’attivazione/disattivazione dell’hotspot a MacroDroid tramite `Intent Broadcast`.

<p align="center">
  <img src="https://github.com/user-attachments/assets/a55d6e95-bf33-423b-8b50-254c783abc28" width="300" />
  <img src="https://github.com/user-attachments/assets/10e7dae0-ad07-421b-b3c2-7bff0fce7c6e" width="300" />
</p>


### 💻 macOS Client — PixelSync

L’app macOS agisce come **Central / GATT Client** e vive nella Menu Bar.

* **CoreBluetooth & Combine**: gestisce connessione, riconnessione e aggiornamenti di stato; il motore Bluetooth parte all’avvio dell’app, anche senza aprire il menu.
* **UNUserNotificationCenterDelegate**: intercetta le interazioni dell’utente con le notifiche (clic, X, pulsanti, risposte) e le sincronizza con Android.
* **Configurazione disaccoppiata**: usa un file JSON locale per le regole di mapping delle app.

<p align="center">
  <img width="368" height="143" alt="PixelSync con hotspot remoto acceso" src="https://github.com/user-attachments/assets/3f0dd50c-9473-4a33-99d9-c407546afedb" />
</p>

<p align="center">
  <img width="368" height="143" alt="PixelSync con il telefono su Wi-Fi" src="https://github.com/user-attachments/assets/575f7340-615e-4d43-b23a-65e0d9b7d379" />
</p>

---

## 📡 Protocollo BLE & UUIDs

UUID pubblici predefiniti (**⚠️ consigliato usarne di propri**, vedi sotto):

* **Service Principale:** `58DF214B-9942-45A5-BAF9-7B24F5D0232C`
* **Canale Telemetria (Read/Notify):** `CEFB6548-6C8A-4D25-A086-C8A69D3F6625`
* **Canale Notifiche (Notify):** `6E6C9609-9FFA-42E2-A882-B0C4398D58DE`
* **Canale Comandi (Write):** `586B06E6-CCC5-44B8-BFD9-5D2514A67842`

**UUID propri, senza modificare il codice.** Crea i due file seguenti (sono già esclusi da git), con gli stessi valori:

`Android/app/src/main/assets/ble_uuids.properties`
```properties
service=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
telemetry=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
notifications=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
command=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
```

`MacOS/PixelSync/PixelSync/LocalUUIDs.plist`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>service</key><string>XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX</string>
	<key>telemetry</key><string>XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX</string>
	<key>notifications</key><string>XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX</string>
	<key>command</key><string>XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX</string>
</dict>
</plist>
```

I campi di ogni pacchetto sono separati da `\u001F`. Il pacchetto telemetrico è formattato in questo ordine:
`Batteria | In carica | Rete (tipo di rete cellulare o "Wi-Fi") | Segnale | isWifi | isHotspotActive | Modello del telefono`

Le notifiche vengono inoltrate come `POST | ID | Package | Pulsanti | Titolo | Testo` oppure `REMOVE | ID`; l'ID è stabile per tutta la vita della notifica Android.
Il campo *Pulsanti* contiene fino a 3 pulsanti separati da `\u001E`, ognuno come `indice \u001D tipo \u001D nome` (tipo `R` risposta con testo, `D` distruttivo, `A` azione).

Il canale comandi supporta `HOTSPOT_ON`, `HOTSPOT_OFF`, `SYNC_REQ`, `MAC_NAME | nome del computer`, `KILL | ID` e `ACTION | ID | indice pulsante [| testo risposta]`.
I comandi possono arrivare a 512 byte: oltre l'MTU vengono inviati come scrittura lunga BLE e ricomposti dal telefono.

Tutte le caratteristiche richiedono un **link cifrato**: solo un Mac abbinato al telefono può leggere telemetria e notifiche o inviare comandi.
I pacchetti vengono inviati uno alla volta e, se superano l'MTU negoziato (massimo 512 byte), troncati senza spezzare caratteri UTF-8.

---

## 🛠 Setup e Configurazione

### Lato Android

1. Compila l’APK in modalità **Release** tramite Android Studio.
2. Vai in *Impostazioni → App → MacSync → Batteria* e imposta **"Senza restrizioni"** (l'associazione come app compagna, sotto, non lo fa in automatico).
3. Concedi i permessi richiesti al primo avvio: Dispositivi nelle vicinanze (Bluetooth) e Telefono (per il tipo di rete). La posizione non serve.
4. Abilita **MacSync Notifiche** nell’Accesso alle Notifiche di sistema e seleziona le app desiderate dalla UI.
5. **Configurazione MacroDroid (solo hotspot):**
   * Installa MacroDroid dal Play Store.
   * Macro 1: attivatore `it.luigi.macsync.HOTSPOT_ON` → azione: *Abilita Hotspot*.
   * Macro 2: attivatore `it.luigi.macsync.HOTSPOT_OFF` → azione: *Disabilita Hotspot*.

### Lato macOS

1. Apri il progetto in Xcode e compila in modalità **Release**.
2. L’app gira in **App Sandbox**: il file di mapping delle app si trova in `~/Library/Containers/it.luigi.pixelsyncmac/Data/Documents/MacSync/app_mappings.json`.
3. Al primo avvio, concedi il permesso per le Notifiche di macOS.
4. Il sistema scansionerà automaticamente il BLE e richiederà il primo *Reconnection Sync*.
5. **Abbinamento Bluetooth** (una volta sola). Consigliato: abbina il Mac dalle *impostazioni Bluetooth del telefono* (abbinamento classico), così Android lo vede con il nome vero e l’icona di computer. In alternativa, al primo collegamento PixelSync fa partire da solo una richiesta di abbinamento su Mac e telefono: funziona allo stesso modo, ma Android mostra il Mac come “Mac” generico. Da quel momento PixelSync si collega solo a quel telefono (per cambiarlo: clic sull'icona del telefono o dell'antenna → *Dimentica telefono associato*).
6. Facoltativo: nell’app Android tocca **"Associa il Mac"** e conferma il dialogo di sistema. MacSync diventa l’app compagna del Mac: il Mac compare nel Bluetooth con il suo nome vero e Android protegge l’app dalla chiusura forzata (in *Info app* il pulsante *Termina* risulta disattivato finché l’associazione esiste).

---

## ⚠️ Limitazioni Note

* **Hotspot — MacroDroid richiesto:** il controllo remoto dell’hotspot richiede MacroDroid installato e configurato.
* **Dismiss notifiche dal Mac:** la rimozione su Android avviene cliccando la notifica o chiudendola con la X; altri gesti (es. swipe) dipendono da cosa macOS segnala all’app.
* **Risposte dal Mac:** massimo circa 450 caratteri (meno con emoji o lettere accentate). Se una risposta non può essere inviata, il testo viene copiato negli appunti.
* **Pulsanti che aprono un’app:** Android non permette di lanciarli da remoto (es. il “Rispondi” di Gmail), quindi non compaiono sul Mac.
* **Notifiche di gruppo:** alcune app inviano notifiche summary raggruppate; il filtro riduce il rumore ma il comportamento finale dipende dall’app stessa.

---

## 📂 Assets Mapping

Il client macOS allega a ogni notifica l’icona `~/Pictures/MacSyncIcons/<PackageName>.png`, se presente (es. `com.instagram.android.png`).
<p align="center">
<img width="928" height="515" alt="Screenshot 2026-06-05 alle 11 47 53" src="https://github.com/user-attachments/assets/c8b705e1-d121-4662-9f16-ce8afe234c3d" />
</p>

<br><br><br>

---

## 🇬🇧 English Version

### PixelSync (macOS) ⇄ MacSync (Android)

PixelMacSync is an open-source project that provides native integration between macOS and Android via **Bluetooth Low Energy (BLE)**.
The goal is to offer notification synchronization, telemetry, and remote control without relying on Wi-Fi, cloud services, or persistent TCP connections.

>[!WARNING]
> ⚠️ **IMPORTANT BEFORE COMPILING (Read Carefully!)**
>
> This project is released strictly as **source code**. Before proceeding with the build, there are two configurations to address to avoid functional issues or unexpected behaviors:
>
> **1. Bluetooth UUIDs (using your own is recommended)**
> The link is encrypted and requires Bluetooth pairing between the Mac and the phone, so no other device can read your notifications or send commands, even with the same UUIDs. Using your own UUIDs still prevents two nearby installations from finding each other before pairing.
> No code changes are needed: generate 4 UUIDs (one for the main Service and three for the data channels) and put them in two local files, **excluded from git**, making sure the values **match exactly** across both apps:
> * Android: `Android/app/src/main/assets/ble_uuids.properties`
> * macOS: `MacOS/PixelSync/PixelSync/LocalUUIDs.plist`
>
> The format is described in the "BLE Protocol & UUIDs" section. If the files are missing, the apps use the public UUIDs listed there.
>
> **2. Dependency for Remote Hotspot Control**
> The Hotspot toggle from the Mac sends a broadcast Intent to Android to bypass system tethering restrictions. This requires the third-party app **MacroDroid**.
> * You must create two macros that trigger upon receiving the intents `it.luigi.macsync.HOTSPOT_ON` and `it.luigi.macsync.HOTSPOT_OFF`.
> * If you don't use MacroDroid, simply ignore the toggle or comment out the relevant section in `ContentView.swift` on macOS.
>
> ---
> ℹ️ *Android Privacy Note*: the app does not use location. Instead of the Wi‑Fi network name the Mac simply shows `"Wi-Fi"`, so the location indicator on the phone never turns on.

## 🎯 Design Goals

* Bluetooth Low Energy only
* Stateless synchronization
* Battery-first architecture
* Native Android and macOS clients
* No cloud services
* No Wi‑Fi dependency
* Event-driven communication

> 💡 **Design Philosophy**
>
> PixelMacSync is intentionally focused on low-bandwidth,
> event-driven synchronization over Bluetooth Low Energy.
>
> Features that would require large payload transfers,
> persistent networking, cloud services or significant
> battery impact are considered outside the scope of the project.

---

## 📋 Requirements

| Component      | Minimum Requirement                                          |
| -------------- | ------------------------------------------------------------ |
| **Android**    | Android 16+ (targets Android 17, optimized for Google Pixel) |
| **macOS**      | macOS 26.4+                                                  |
| **MacroDroid** | Required for remote Hotspot control                          |
| **Bluetooth**  | BLE 4.2+ on both devices                                     |

---

## ✨ Main Features

* 📊 **Real-time Telemetry**: View your phone's battery, charging status, connection type, signal strength, and other indicators directly from the Mac Menu Bar.
* 🔔 **Notification Forwarding**: Selected Android notifications are forwarded to macOS as native notifications, keeping their original icons when available.
* 🔄 **Reconnection Sync**: Upon Bluetooth reconnection, macOS requests a complete state snapshot to realign notifications and telemetry.
* 🛡️ **Anti-Duplicate Filter**: "Summary" or grouped notifications are filtered out; when an app updates a notification (e.g. new messages in the same chat) the Mac notification is replaced, not duplicated. Ongoing notifications (downloads, navigation, music) are not forwarded, incoming calls are.
* 🧹 **Bidirectional Dismiss**: A notification opened, closed with the X or removed on one side (even by the app itself, e.g. a message read on another device) disappears on the other side too.
* 💬 **Notification Actions**: Android actions that can be used remotely (Reply, Mark as read, Archive…) also appear on the Mac notification; replies are typed directly in the notification. Actions that would open an app on the phone are not shown, delete-type actions are red.
* 🔐 **Encrypted Link**: Mac and phone only communicate after Bluetooth pairing; PixelSync always connects to the paired phone only.
* 🖱️ **Dynamic App Launching**: The mapping between Android package names and macOS apps/PWAs is configurable and saved in a local JSON file.
* 🚀 **Remote Hotspot Control**: Turn the phone's hotspot on and off from the Mac via MacroDroid integration.

---

## 🔍 How It Works

PixelMacSync is divided into two clients that communicate via a custom GATT server.

### 📱 Android Client — MacSync

The Android app acts as the **Peripheral / GATT Server** and is optimized for the Google Pixel series.

* **Background BLE Engine**: Uses a *Foreground Service* to stay active even during Doze cycles; while the Mac is connected it stops advertising to save battery.
* **Notification Interceptor & Snapshot**: Intercepts notifications and generates a snapshot of the active state when requested by the Mac.
* **GattServerManager & Broadcasts**: Receives commands from the Mac and forwards them to internal app components; notifications to the Mac are queued and sent one at a time.
* **Companion App**: The Mac can be associated as an Android companion device (Mac name in the Bluetooth list, app protected from force stop).
* **MacroDroid Bridge**: Delegates hotspot activation/deactivation to MacroDroid via `Intent Broadcast`.

<p align="center">
  <img src="https://github.com/user-attachments/assets/a55d6e95-bf33-423b-8b50-254c783abc28" width="300" />
  <img src="https://github.com/user-attachments/assets/10e7dae0-ad07-421b-b3c2-7bff0fce7c6e" width="300" />
</p>


### 💻 macOS Client — PixelSync

The macOS app acts as the **Central / GATT Client** and lives in the Menu Bar.

* **CoreBluetooth & Combine**: Manages connection, reconnection, and state updates; the Bluetooth engine starts at launch, even without opening the menu.
* **UNUserNotificationCenterDelegate**: Intercepts user interactions with notifications (click, X, actions, replies) and syncs them with Android.
* **Decoupled Configuration**: Uses a local JSON file for app mapping rules.

<p align="center">
  <img width="368" height="143" alt="PixelSync with remote hotspot on" src="https://github.com/user-attachments/assets/3f0dd50c-9473-4a33-99d9-c407546afedb" />
</p>

<p align="center">
  <img width="368" height="143" alt="PixelSync with the phone on Wi-Fi" src="https://github.com/user-attachments/assets/575f7340-615e-4d43-b23a-65e0d9b7d379" />
</p>

---

## 📡 BLE Protocol & UUIDs

Default public UUIDs (**⚠️ using your own is recommended**, see below):

* **Main Service:** `58DF214B-9942-45A5-BAF9-7B24F5D0232C`
* **Telemetry Channel (Read/Notify):** `CEFB6548-6C8A-4D25-A086-C8A69D3F6625`
* **Notification Channel (Notify):** `6E6C9609-9FFA-42E2-A882-B0C4398D58DE`
* **Command Channel (Write):** `586B06E6-CCC5-44B8-BFD9-5D2514A67842`

**Your own UUIDs, without changing the code.** Create these two files (already excluded from git) with the same values:

`Android/app/src/main/assets/ble_uuids.properties`
```properties
service=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
telemetry=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
notifications=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
command=XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
```

`MacOS/PixelSync/PixelSync/LocalUUIDs.plist`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>service</key><string>XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX</string>
	<key>telemetry</key><string>XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX</string>
	<key>notifications</key><string>XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX</string>
	<key>command</key><string>XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX</string>
</dict>
</plist>
```

Fields in every packet are separated by `\u001F`. The telemetry packet is formatted in this order:
`Battery | Charging | Network (cellular network type or "Wi-Fi") | Signal | isWifi | isHotspotActive | Phone model`

Notifications are forwarded as `POST | ID | Package | Actions | Title | Text` or `REMOVE | ID`; the ID is stable for the whole life of the Android notification.
The *Actions* field holds up to 3 actions separated by `\u001E`, each as `index \u001D type \u001D name` (type `R` text reply, `D` destructive, `A` action).

The command channel supports `HOTSPOT_ON`, `HOTSPOT_OFF`, `SYNC_REQ`, `MAC_NAME | computer name`, `KILL | ID` and `ACTION | ID | action index [| reply text]`.
Commands can be up to 512 bytes: beyond the MTU they are sent as a BLE long write and reassembled by the phone.

All characteristics require an **encrypted link**: only a Mac paired with the phone can read telemetry and notifications or send commands.
Packets are sent one at a time and, if they exceed the negotiated MTU (512 bytes at most), truncated without splitting UTF-8 characters.

---

## 🛠 Setup and Configuration

### Android Side

1. Compile the APK in **Release** mode via Android Studio.
2. Go to *Settings → Apps → MacSync → Battery* and set it to **"Unrestricted"** (the companion association below does not do it automatically).
3. Grant required permissions on first launch: Nearby devices (Bluetooth) and Phone (for the network type). Location is not needed.
4. Enable **MacSync Notifications** in the system's Notification Access and select the desired apps from the UI.
5. **MacroDroid Configuration (Hotspot only):**
   * Install MacroDroid from the Play Store.
   * Macro 1: Trigger `it.luigi.macsync.HOTSPOT_ON` → Action: *Enable Hotspot*.
   * Macro 2: Trigger `it.luigi.macsync.HOTSPOT_OFF` → Action: *Disable Hotspot*.

### macOS Side

1. Open the project in Xcode and compile in **Release** mode.
2. The app runs in the **App Sandbox**: the app mapping file is at `~/Library/Containers/it.luigi.pixelsyncmac/Data/Documents/MacSync/app_mappings.json`.
3. On first launch, grant permission for macOS Notifications.
4. The system will automatically scan for BLE and request the first *Reconnection Sync*.
5. **Bluetooth pairing** (once). Recommended: pair the Mac from the *phone's Bluetooth settings* (classic pairing), so Android shows it with its real name and a computer icon. Otherwise, on the first connection PixelSync starts a pairing request on both Mac and phone by itself: it works the same, but Android shows the Mac as a generic “Mac”. From then on PixelSync only connects to that phone (to change it: click the phone or antenna icon → *Dimentica telefono associato*).
6. Optional: in the Android app tap **"Associa il Mac"** and confirm the system dialog. MacSync becomes the Mac's companion app: the Mac appears in the Bluetooth list with its real name and Android protects the app from force stop (in *App info* the *Force stop* button is disabled while the association exists).

---

## ⚠️ Known Limitations

* **Hotspot — MacroDroid Required:** Remote control of the hotspot requires MacroDroid to be installed and configured.
* **Notification Dismiss from the Mac:** Removal on Android happens when the notification is clicked or closed with the X; other gestures (e.g. swipe) depend on what macOS reports to the app.
* **Replies from the Mac:** About 450 characters at most (fewer with emoji or accented letters). If a reply cannot be sent, its text is copied to the clipboard.
* **Actions that open an app:** Android does not allow launching them remotely (e.g. Gmail's “Reply”), so they don't appear on the Mac.
* **Group Notifications:** Some apps send grouped summary notifications; the filter reduces noise, but the final behavior depends on the app itself.

---

## 📂 Assets Mapping

The macOS client attaches the icon `~/Pictures/MacSyncIcons/<PackageName>.png` to each notification, if present (e.g. `com.instagram.android.png`).
<p align="center">
<img width="928" height="515" alt="Screenshot 2026-06-05 alle 11 47 53" src="https://github.com/user-attachments/assets/c8b705e1-d121-4662-9f16-ce8afe234c3d" />
</p>
