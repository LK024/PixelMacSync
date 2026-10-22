package it.luigi.macsync.ble

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MacSyncNotificationListener : NotificationListenerService() {

    companion object {
        private const val SEPARATOR = "\u001F"

        // ID del Mac -> key della notifica Android, per il comando KILL
        private val macIdToKey = mutableMapOf<String, String>()
        // Ultimo "titolo + testo" inviato per ogni key: gli aggiornamenti identici non rimbalzano sul Mac
        private val lastSentContent = mutableMapOf<String, String>()

        // ID breve e stabile per ogni notifica Android: se l'app la aggiorna, sul Mac viene sostituita invece di duplicata
        private fun macIdFor(key: String): String = Integer.toHexString(key.hashCode())
    }

    // --- 0. RICEVITORE DEL COMANDO "KILL" DAL MAC ---
    private val killReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "it.luigi.macsync.KILL_NOTIFICATION") {
                val targetMacId = intent.getStringExtra("macNotifId") ?: return
                val targetKey = macIdToKey[targetMacId] ?: return
                // La pulizia delle mappe avviene in onNotificationRemoved
                cancelNotification(targetKey)
            }
        }
    }

    // --- 1. RICEVITORE DELLA RICHIESTA DI SINCRONIZZAZIONE A FREDDO ---
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "it.luigi.macsync.SYNC_REQUEST") {
                Log.d("MacSync", "Inizio generazione Snapshot delle notifiche...")
                sendSnapshotToMac()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val killFilter = IntentFilter("it.luigi.macsync.KILL_NOTIFICATION")
        registerReceiver(killReceiver, killFilter, Context.RECEIVER_NOT_EXPORTED)

        val syncFilter = IntentFilter("it.luigi.macsync.SYNC_REQUEST")
        registerReceiver(syncReceiver, syncFilter, Context.RECEIVER_NOT_EXPORTED)

        Log.d("MacSync", "MacSyncNotificationListener avviato: Ricevitori armati.")
    }

    override fun onDestroy() {
        unregisterReceiver(killReceiver)
        unregisterReceiver(syncReceiver)
        Log.d("MacSync", "MacSyncNotificationListener terminato: Ricevitori rimossi.")
        super.onDestroy()
    }

    // --- 2. LA FOTOGRAFIA E L'INVIO AL MAC ---
    private fun sendSnapshotToMac() {
        val currentNotifications = this.activeNotifications ?: return
        val enabledApps = enabledApps()

        for (sbn in currentNotifications) {
            // Il Mac ha appena svuotato il suo centro notifiche: reinviamo tutto, anche se già inviato prima
            val payload = buildPostPayload(sbn, enabledApps, force = true) ?: continue
            sendToGattServer(payload)
        }
        Log.d("MacSync", "Snapshot inviato con successo al Mac.")
    }

    // --- 3. QUANDO ARRIVA (O SI AGGIORNA) UNA NOTIFICA ---
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val payload = buildPostPayload(sbn, enabledApps(), force = false) ?: return
        sendToGattServer(payload)
    }

    // --- 4. QUANDO LA NOTIFICA VIENE RIMOSSA DAL TELEFONO ---
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // Qualunque motivo conta (letta da un altro dispositivo, annullata dall'app, scaduta, cliccata...),
        // ma solo per le notifiche che abbiamo effettivamente inviato al Mac
        val key = sbn.key
        if (lastSentContent.remove(key) == null) return

        val macId = macIdFor(key)
        macIdToKey.remove(macId)
        sendToGattServer("REMOVE$SEPARATOR$macId")
    }

    // Restituisce il pacchetto POST da inoltrare, oppure null se la notifica va ignorata
    private fun buildPostPayload(sbn: StatusBarNotification, enabledApps: Set<String>, force: Boolean): String? {
        val packageName = sbn.packageName
        if (packageName == "android" || packageName == "com.android.systemui") return null
        if (!enabledApps.contains(packageName)) return null

        val notification = sbn.notification

        // Filtro anti-doppioni
        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return null

        // Download, navigazione, musica: notifiche permanenti che si aggiornano di continuo e sul Mac sarebbero solo rumore.
        // Le chiamate in arrivo restano.
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing && notification.category != Notification.CATEGORY_CALL) return null

        val extras = notification.extras
        // Molte app (WhatsApp, Gmail...) usano titoli formattati: getString restituirebbe null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        // BIG_TEXT, se presente, contiene il testo completo (es. anteprima di una email)
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return null

        val key = sbn.key
        val content = title + SEPARATOR + text
        if (!force && lastSentContent[key] == content) return null
        lastSentContent[key] = content

        val macId = macIdFor(key)
        macIdToKey[macId] = key

        return "POST$SEPARATOR$macId$SEPARATOR$packageName$SEPARATOR${clean(title)}$SEPARATOR${clean(text)}"
    }

    // Un separatore dentro il testo spezzerebbe il pacchetto sul Mac
    private fun clean(field: String): String = field.replace(SEPARATOR, " ")

    private fun enabledApps(): Set<String> {
        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("enabled_apps", setOf()) ?: setOf()
    }

    // Stesso processo: chiamata diretta al server GATT, senza passare da un broadcast
    private fun sendToGattServer(payload: String) {
        GattServerManager.getInstance(applicationContext).sendNotificationToMac(payload)
    }
}
