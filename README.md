# 🍏⇄🤖 PixelMacSync

### PixelSync (macOS) ⇄ MacSync (Android)
(🇬🇧 Scroll down for the English readme, READ IT!)

PixelMacSync è un progetto open-source che fornisce un’integrazione nativa tra macOS e Android tramite **Bluetooth Low Energy (BLE)**.
L’obiettivo è offrire sincronizzazione di notifiche, telemetria e controllo remoto senza dipendere da Wi‑Fi, cloud o connessioni TCP persistenti.

>[!WARNING]
> ⚠️ **NOTA BENE PRIMA DI COMPILARE (Leggere Attentamente!)**
>
> Questo progetto è rilasciato esclusivamente sotto forma di **codice sorgente**. Prima di procedere con la build, ci sono due configurazioni fondamentali da sistemare per evitare problemi funzionali o comportamenti anomali:
>
> **1. Sicurezza e Conflitto tra Dispositivi (Cambia gli UUID!)**
> Attualmente, il pairing Bluetooth si basa su UUID codificati nel sorgente (elencati nella sezione "Protocollo BLE & UUIDs" più in basso). Se due persone nella stessa stanza utilizzano questa app con il codice originale, i loro Mac potrebbero incrociarsi e intercettare le notifiche a vicenda. 
> **Prima di compilare**, genera 4 nuovi UUID univoci (uno per il Service principale e tre per i canali dati) e sostituiscili nei file indicati qui sotto, **assicurandoti che i codici inseriti combacino esattamente** tra i due sistemi operativi:
> * Android: `GattServerManager.kt` e `BLEAdvertiser.kt`
> * macOS: `BLEManager.swift`
> 
> **2. Dipendenza per il controllo dell'Hotspot Remoto**
> Il toggle dell'Hotspot dal Mac invia un Intent broadcast ad Android per aggirare le restrizioni di sistema. Questo richiede l'app di terze parti **MacroDroid**.
> * Devi creare due macro che si attivino alla ricezione degli intent `it.luigi.macsync.HOTSPOT_ON` e `it.luigi.macsync.HOTSPOT_OFF`.
> * Se non usi MacroDroid, ignora semplicemente il toggle o commenta la relativa sezione in `ContentView.swift` su macOS.
>
> ---
> ℹ️ *Nota sulla Privacy Android (Pallino della localizzazione)*: Per inviare al Mac il nome reale della rete Wi-Fi (SSID), l'app Android interroga API di rete che richiedono il permesso di localizzazione. Questo accenderà l'indicatore blu della privacy sul telefono. Se preferisci nasconderlo nativamente, revoca il permesso `ACCESS_FINE_LOCATION` (consenti posizione esatta) dalle impostazioni di Android: il server farà un fallback automatico inviando la stringa fissa `"Wi-Fi"`, spegnendo l'indicatore per sempre senza causare crash.

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

| Componente     | Requisito minimo                               |
| -------------- | ---------------------------------------------- |
| **Android** | Android 16+ (ottimizzato per Google Pixel)     |
| **macOS** | macOS 13 Ventura+                              |
| **MacroDroid** | Richiesto per il controllo remoto dell'Hotspot |
| **Bluetooth** | BLE 4.2+ su entrambi i dispositivi             |

---

## ✨ Features Principali

* 📊 **Telemetria in tempo reale**: visualizza dalla Menu Bar del Mac batteria, stato di ricarica, tipo di connessione, segnale e altri indicatori del telefono.
* 🔔 **Inoltro notifiche**: le notifiche Android selezionate vengono inoltrate a macOS come notifiche native, mantenendo le icone originali quando disponibili.
* 🔄 **Reconnection sync**: alla riconnessione Bluetooth, macOS richiede uno snapshot completo dello stato attuale per riallineare notifiche e telemetria.
* 🛡️ **Filtro anti-doppioni**: le notifiche “summary” o raggruppate di alcune app vengono filtrate per ridurre rumore e duplicati.
* 🧹 **Dismiss bidirezionale**: una notifica aperta o rimossa su un lato viene sincronizzata anche sull’altro lato, nei limiti delle API disponibili.
* 🖱️ **Apertura app dinamica**: il mapping tra package name Android e app/PWA su macOS è configurabile e salvato in un file JSON locale.
* 🚀 **Controllo hotspot remoto**: accendi e spegni l’hotspot del telefono dal Mac tramite integrazione con MacroDroid.

---

## 🔍 How It Works

PixelMacSync è diviso in due client che dialogano tramite un server GATT personalizzato.

### 📱 Android Client — MacSync

L’app Android agisce come **Peripheral / GATT Server** ed è ottimizzata per la serie Google Pixel.

* **Motore BLE in background**: usa un *Foreground Service* per restare attivo anche durante i cicli di Doze.
* **Notification interceptor & snapshot**: intercetta le notifiche e genera uno snapshot dello stato attivo quando richiesto dal Mac.
* **GattServerManager & broadcasts**: riceve comandi dal Mac e li inoltra ai componenti interni dell’app.
* **MacroDroid bridge**: delega l’attivazione/disattivazione dell’hotspot a MacroDroid tramite `Intent Broadcast`.

<p align="center">
  <img src="https://github.com/user-attachments/assets/a55d6e95-bf33-423b-8b50-254c783abc28" width="300" />
  <img src="https://github.com/user-attachments/assets/10e7dae0-ad07-421b-b3c2-7bff0fce7c6e" width="300" />
</p>


### 💻 macOS Client — PixelSync

L’app macOS agisce come **Central / GATT Client** e vive nella Menu Bar.

* **CoreBluetooth & Combine**: gestisce connessione, riconnessione e aggiornamenti di stato.
* **UNUserNotificationCenterDelegate**: intercetta le interazioni dell’utente con le notifiche e le sincronizza con Android.
* **Configurazione disaccoppiata**: usa un file JSON locale per le regole di mapping delle app.

<p align="center">
  <img src="https://github.com/user-attachments/assets/4a4723fe-4591-4464-9a53-d1822d0aa46b" />
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/8387a26b-e0d5-48ef-9cc7-ea965ef9079e" />
</p>

---

## 📡 Protocollo BLE & UUIDs

Per garantire una comunicazione sicura e univoca, il progetto utilizza i seguenti UUID immutabili (**⚠️ da modificare obbligatoriamente prima della compilazione su entrambi i dispositivi**):

* **Service Principale:** `58DF214B-9942-45A5-BAF9-7B24F5D0232C`
* **Canale Telemetria (Read/Notify):** `CEFB6548-6C8A-4D25-A086-C8A69D3F6625`
* **Canale Notifiche (Notify):** `6E6C9609-9FFA-42E2-A882-B0C4398D58DE`
* **Canale Comandi (Write):** `586B06E6-CCC5-44B8-BFD9-5D2514A67842`

Il pacchetto telemetrico è formattato in questo ordine:
`Batteria | In carica | Rete/SSID | Segnale | isWifi | isHotspotActive`

Le notifiche vengono inoltrate con prefisso `POST\u001F` o `REMOVE\u001F`.
Il canale comandi supporta `HOTSPOT_ON`, `HOTSPOT_OFF`, `SYNC_REQ` e `KILL\u001F[ID_Notifica]`.

---

## 🛠 Setup e Configurazione

### Lato Android

1. Compila l’APK in modalità **Release** tramite Android Studio.
2. Vai in *Impostazioni → App → MacSync → Batteria* e imposta **"Senza restrizioni"**.
3. Concedi i permessi richiesti al primo avvio: Bluetooth, Posizione, Dispositivi Vicini.
4. Abilita **MacSync Notifiche** nell’Accesso alle Notifiche di sistema e seleziona le app desiderate dalla UI.
5. **Configurazione MacroDroid (solo hotspot):**
   * Installa MacroDroid dal Play Store.
   * Macro 1: attivatore `it.luigi.macsync.HOTSPOT_ON` → azione: *Abilita Hotspot*.
   * Macro 2: attivatore `it.luigi.macsync.HOTSPOT_OFF` → azione: *Disabilita Hotspot*.

### Lato macOS

1. Apri il progetto in Xcode e compila in modalità **Release**.
2. Per consentire il salvataggio del file `app_mappings.json` nella cartella utente, disabilita l’**App Sandbox** in *Signing & Capabilities* prima della build.
3. Al primo avvio, concedi il permesso per le Notifiche di macOS.
4. Il sistema scansionerà automaticamente il BLE e richiederà il primo *Reconnection Sync*.

---

## ⚠️ Limitazioni Note

* **Hotspot — MacroDroid richiesto:** il controllo remoto dell’hotspot richiede MacroDroid installato e configurato.
* **Dismiss notifiche — solo al click:** la sincronizzazione del dismiss da Mac ad Android avviene solo quando si clicca sulla notifica macOS, non quando la si swipe via.
* **Notifiche di gruppo:** alcune app inviano notifiche summary raggruppate; il filtro riduce il rumore ma il comportamento finale dipende dall’app stessa.

---

## 📂 Assets Mapping

Il client macOS mappa i `PackageName` ricevuti su file locali in formato **.icns** situati in `~/Pictures/icone/` tramite un dizionario a tempo di ricerca O(1).
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
> This project is released strictly as **source code**. Before proceeding with the build, there are two crucial configurations to address to avoid functional issues or unexpected behaviors:
>
> **1. Security and Device Conflict (Change the UUIDs!)**
> Currently, Bluetooth pairing relies on hardcoded UUIDs (listed in the "BLE Protocol & UUIDs" section below). If two people in the same room use this app with the original code, their Macs might cross-connect and intercept each other's notifications. 
> **Before compiling**, generate 4 new unique UUIDs (one for the main Service and three for the data channels) and replace them in the following files, **ensuring that the inserted codes match exactly** across both operating systems:
> * Android: `GattServerManager.kt` and `BLEAdvertiser.kt`
> * macOS: `BLEManager.swift`
> 
> **2. Dependency for Remote Hotspot Control**
> The Hotspot toggle from the Mac sends a broadcast Intent to Android to bypass system tethering restrictions. This requires the third-party app **MacroDroid**.
> * You must create two macros that trigger upon receiving the intents `it.luigi.macsync.HOTSPOT_ON` and `it.luigi.macsync.HOTSPOT_OFF`.
> * If you don't use MacroDroid, simply ignore the toggle or comment out the relevant section in `ContentView.swift` on macOS.
>
> ---
> ℹ️ *Android Privacy Note (Location dot)*: To display the real name of your Wi-Fi network (SSID) on the Mac, the Android app queries network APIs that require location permissions. This will turn on the blue privacy indicator on the phone. If you prefer to hide it natively, revoke the `ACCESS_FINE_LOCATION` permission from Android settings: the server will automatically fallback to sending the static string `"Wi-Fi"`, turning off the indicator permanently without causing crashes.

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

| Component      | Minimum Requirement                           |
| -------------- | --------------------------------------------- |
| **Android** | Android 16+ (optimized for Google Pixel)      |
| **macOS** | macOS 13 Ventura+                             |
| **MacroDroid** | Required for remote Hotspot control           |
| **Bluetooth** | BLE 4.2+ on both devices                      |

---

## ✨ Main Features

* 📊 **Real-time Telemetry**: View your phone's battery, charging status, connection type, signal strength, and other indicators directly from the Mac Menu Bar.
* 🔔 **Notification Forwarding**: Selected Android notifications are forwarded to macOS as native notifications, keeping their original icons when available.
* 🔄 **Reconnection Sync**: Upon Bluetooth reconnection, macOS requests a complete state snapshot to realign notifications and telemetry.
* 🛡️ **Anti-Duplicate Filter**: "Summary" or grouped notifications from certain apps are filtered out to reduce noise and duplicates.
* 🧹 **Bidirectional Dismiss**: A notification opened or dismissed on one side is synced to the other side, within the limits of the available APIs.
* 🖱️ **Dynamic App Launching**: The mapping between Android package names and macOS apps/PWAs is configurable and saved in a local JSON file.
* 🚀 **Remote Hotspot Control**: Turn the phone's hotspot on and off from the Mac via MacroDroid integration.

---

## 🔍 How It Works

PixelMacSync is divided into two clients that communicate via a custom GATT server.

### 📱 Android Client — MacSync

The Android app acts as the **Peripheral / GATT Server** and is optimized for the Google Pixel series.

* **Background BLE Engine**: Uses a *Foreground Service* to stay active even during Doze cycles.
* **Notification Interceptor & Snapshot**: Intercepts notifications and generates a snapshot of the active state when requested by the Mac.
* **GattServerManager & Broadcasts**: Receives commands from the Mac and forwards them to internal app components.
* **MacroDroid Bridge**: Delegates hotspot activation/deactivation to MacroDroid via `Intent Broadcast`.

<p align="center">
  <img src="https://github.com/user-attachments/assets/a55d6e95-bf33-423b-8b50-254c783abc28" width="300" />
  <img src="https://github.com/user-attachments/assets/10e7dae0-ad07-421b-b3c2-7bff0fce7c6e" width="300" />
</p>


### 💻 macOS Client — PixelSync

The macOS app acts as the **Central / GATT Client** and lives in the Menu Bar.

* **CoreBluetooth & Combine**: Manages connection, reconnection, and state updates.
* **UNUserNotificationCenterDelegate**: Intercepts user interactions with notifications and syncs them with Android.
* **Decoupled Configuration**: Uses a local JSON file for app mapping rules.

<p align="center">
  <img src="https://github.com/user-attachments/assets/4a4723fe-4591-4464-9a53-d1822d0aa46b" />
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/8387a26b-e0d5-48ef-9cc7-ea965ef9079e" />
</p>

---

## 📡 BLE Protocol & UUIDs

To ensure secure and unique communication, the project uses the following immutable UUIDs (**⚠️ these must be changed before compiling on both devices**):

* **Main Service:** `58DF214B-9942-45A5-BAF9-7B24F5D0232C`
* **Telemetry Channel (Read/Notify):** `CEFB6548-6C8A-4D25-A086-C8A69D3F6625`
* **Notification Channel (Notify):** `6E6C9609-9FFA-42E2-A882-B0C4398D58DE`
* **Command Channel (Write):** `586B06E6-CCC5-44B8-BFD9-5D2514A67842`

The telemetry packet is formatted in this order:
`Battery | Charging | Network/SSID | Signal | isWifi | isHotspotActive`

Notifications are forwarded with the prefix `POST\u001F` or `REMOVE\u001F`.
The command channel supports `HOTSPOT_ON`, `HOTSPOT_OFF`, `SYNC_REQ`, and `KILL\u001F[Notification_ID]`.

---

## 🛠 Setup and Configuration

### Android Side

1. Compile the APK in **Release** mode via Android Studio.
2. Go to *Settings → Apps → MacSync → Battery* and set it to **"Unrestricted"**.
3. Grant required permissions on first launch: Bluetooth, Location, Nearby Devices.
4. Enable **MacSync Notifications** in the system's Notification Access and select the desired apps from the UI.
5. **MacroDroid Configuration (Hotspot only):**
   * Install MacroDroid from the Play Store.
   * Macro 1: Trigger `it.luigi.macsync.HOTSPOT_ON` → Action: *Enable Hotspot*.
   * Macro 2: Trigger `it.luigi.macsync.HOTSPOT_OFF` → Action: *Disable Hotspot*.

### macOS Side

1. Open the project in Xcode and compile in **Release** mode.
2. To allow saving the `app_mappings.json` file in the user folder, disable **App Sandbox** in *Signing & Capabilities* before building.
3. On first launch, grant permission for macOS Notifications.
4. The system will automatically scan for BLE and request the first *Reconnection Sync*.

---

## ⚠️ Known Limitations

* **Hotspot — MacroDroid Required:** Remote control of the hotspot requires MacroDroid to be installed and configured.
* **Notification Dismiss — Click Only:** The dismiss synchronization from Mac to Android only occurs when the macOS notification is clicked, not when swiped away.
* **Group Notifications:** Some apps send grouped summary notifications; the filter reduces noise, but the final behavior depends on the app itself.

---

## 📂 Assets Mapping

The macOS client maps received `PackageNames` to local files in **.icns** format located in `~/Pictures/icone/` using an O(1) lookup dictionary.
<p align="center">
<img width="928" height="515" alt="Screenshot 2026-06-05 alle 11 47 53" src="https://github.com/user-attachments/assets/c8b705e1-d121-4662-9f16-ce8afe234c3d" />
</p>
