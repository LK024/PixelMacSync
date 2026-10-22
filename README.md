# 🍏⇄🤖 PixelMacSync

### PixelSync (macOS) ⇄ MacSync (Android)

PixelMacSync è un progetto open-source che fornisce un’integrazione nativa tra macOS e Android tramite **Bluetooth Low Energy (BLE)**.
L’obiettivo è offrire sincronizzazione di notifiche, telemetria e controllo remoto senza dipendere da Wi‑Fi, cloud o connessioni TCP persistenti.

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

## 📋 Requisiti

| Componente     | Requisito minimo                               |
| -------------- | ---------------------------------------------- |
| **Android**    | Android 16+ (ottimizzato per Google Pixel)     |
| **macOS**      | macOS 26.4+                                    |
| **MacroDroid** | Richiesto per il controllo remoto dell'Hotspot |
| **Bluetooth**  | BLE 4.2+ su entrambi i dispositivi             |

---

## ✨ Features Principali

* 📊 **Telemetria in tempo reale**: visualizza dalla Menu Bar del Mac batteria, stato di ricarica, tipo di connessione, segnale e altri indicatori del telefono.
* 🔔 **Inoltro notifiche**: le notifiche Android selezionate vengono inoltrate a macOS come notifiche native, mantenendo le icone originali quando disponibili.
* 🔄 **Reconnection sync**: alla riconnessione Bluetooth, macOS richiede uno snapshot completo dello stato attuale per riallineare notifiche e telemetria.
* 🛡️ **Filtro anti-doppioni**: le notifiche “summary” o raggruppate vengono filtrate; quando un’app aggiorna una notifica (es. nuovi messaggi nella stessa chat) quella sul Mac viene sostituita, non duplicata. Le notifiche permanenti (download, navigazione, musica) non vengono inoltrate, le chiamate in arrivo sì.
* 🧹 **Dismiss bidirezionale**: una notifica aperta, chiusa con la X o rimossa su un lato (anche dall’app stessa, es. messaggio letto da un altro dispositivo) sparisce anche dall’altro.
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
  <img src="https://github.com/user-attachments/assets/f269ba38-e4fa-4da9-8fee-4704925e5ffa" width="300" />
  <img src="https://github.com/user-attachments/assets/3eb51e38-2b7c-4977-a411-fce849127947" width="300" />
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

Per garantire una comunicazione sicura e univoca, il progetto utilizza i seguenti UUID immutabili:

* **Service Principale:** `E20A39F4-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Telemetria (Read/Notify):** `33333333-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Notifiche (Notify):** `22222222-73F5-4BC4-A12F-17D1AD07A961`
* **Canale Comandi (Write):** `44444444-73F5-4BC4-A12F-17D1AD07A961`

I campi di ogni pacchetto sono separati da `\u001F`. Il pacchetto telemetrico è formattato in questo ordine:
`Batteria | In carica | Rete (tipo di rete cellulare o "Wi-Fi") | Segnale | isWifi | isHotspotActive | Modello del telefono`

Le notifiche vengono inoltrate come `POST | ID | Package | Titolo | Testo` oppure `REMOVE | ID`; l'ID è stabile per tutta la vita della notifica Android.
Il canale comandi supporta `HOTSPOT_ON`, `HOTSPOT_OFF`, `SYNC_REQ` e `KILL\u001F[ID_Notifica]`.

Tutte le caratteristiche richiedono un **link cifrato**: solo un Mac abbinato al telefono può leggere telemetria e notifiche o inviare comandi.
I pacchetti vengono inviati uno alla volta e, se superano l'MTU negoziato, troncati senza spezzare caratteri UTF-8.

---

## 🛠 Setup e Configurazione

### Lato Android

1. Compila l’APK in modalità **Release** tramite Android Studio.
2. Vai in *Impostazioni → App → MacSync → Batteria* e imposta **"Senza restrizioni"**.
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
5. Al primo collegamento compare una **richiesta di abbinamento Bluetooth** su Mac e telefono: accettala su entrambi. Da quel momento PixelSync si collega solo a quel telefono (per cambiarlo: clic sull'icona del telefono o dell'antenna → *Dimentica telefono associato*).

---

## ⚠️ Limitazioni Note

* **Hotspot — MacroDroid richiesto:** il controllo remoto dell’hotspot richiede MacroDroid installato e configurato.
* **Dismiss notifiche dal Mac:** la rimozione su Android avviene cliccando la notifica o chiudendola con la X; altri gesti (es. swipe) dipendono da cosa macOS segnala all’app.
* **Notifiche di gruppo:** alcune app inviano notifiche summary raggruppate; il filtro riduce il rumore ma il comportamento finale dipende dall’app stessa.

---

## 📂 Assets Mapping

Il client macOS allega a ogni notifica l’icona `~/Pictures/MacSyncIcons/<PackageName>.png`, se presente (es. `com.instagram.android.png`).
<p align="center">
<img width="928" height="515" alt="Screenshot 2026-06-05 alle 11 47 53" src="https://github.com/user-attachments/assets/c8b705e1-d121-4662-9f16-ce8afe234c3d" />
</p>
