package it.luigi.macsync.ble

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MacSyncNotificationListener : NotificationListenerService() {

    companion object {
        private const val SEPARATOR = "\u001F"
        // Dentro il campo pulsanti: \u001E separa i pulsanti, \u001D i dati di un pulsante (indice, tipo, nome)
        private const val ACTION_SEPARATOR = "\u001E"
        private const val ACTION_FIELD_SEPARATOR = "\u001D"
        private const val MAX_ACTIONS = 3
        // Dopo un pulsante premuto dal Mac, l'app aggiorna la notifica (es. "Tu: ..."): non la rimandiamo al Mac
        private const val SUPPRESS_AFTER_ACTION_MS = 5_000L

        // ID del Mac -> key della notifica Android, per il comando KILL
        private val macIdToKey = mutableMapOf<String, String>()
        // Ultimo "titolo + testo" inviato per ogni key: gli aggiornamenti identici non rimbalzano sul Mac
        private val lastSentContent = mutableMapOf<String, String>()
        // Key -> istante (elapsedRealtime) fino a cui ignorare gli aggiornamenti dopo un pulsante premuto dal Mac
        private val suppressedUntil = mutableMapOf<String, Long>()

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

    // --- 0b. RICEVITORE DEI PULSANTI PREMUTI SUL MAC ---
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "it.luigi.macsync.RUN_ACTION") {
                val macId = intent.getStringExtra("macNotifId") ?: return
                val actionIndex = intent.getIntExtra("actionIndex", -1)
                runAction(macId, actionIndex, intent.getStringExtra("replyText"))
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

        val actionFilter = IntentFilter("it.luigi.macsync.RUN_ACTION")
        registerReceiver(actionReceiver, actionFilter, Context.RECEIVER_NOT_EXPORTED)

        Log.d("MacSync", "MacSyncNotificationListener avviato: Ricevitori armati.")
    }

    override fun onDestroy() {
        unregisterReceiver(killReceiver)
        unregisterReceiver(syncReceiver)
        unregisterReceiver(actionReceiver)
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
        suppressedUntil.remove(key)
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

        // Subito dopo un pulsante premuto dal Mac l'aggiornamento resta solo sul telefono: il Mac ha già chiuso la notifica
        if (!force && SystemClock.elapsedRealtime() < (suppressedUntil[key] ?: 0L)) return null

        val macId = macIdFor(key)
        macIdToKey[macId] = key

        // I pulsanti stanno prima di titolo e testo: se il pacchetto va troncato si perde la coda del testo, non i pulsanti
        val actions = encodeActions(notification)
        return "POST$SEPARATOR$macId$SEPARATOR$packageName$SEPARATOR$actions$SEPARATOR${clean(title)}$SEPARATOR${clean(text)}"
    }

    // Solo i pulsanti utilizzabili da remoto: niente pulsanti che aprono un'app (Android li blocca da background),
    // niente risposte rapide suggerite dal sistema e niente risposte a scelta fissa senza testo libero
    private fun encodeActions(notification: Notification): String {
        val actions = notification.actions ?: return ""
        return actions.withIndex()
            .filter { (_, action) ->
                val intent = action.actionIntent
                val inputs = action.remoteInputs
                intent != null && !intent.isActivity && !action.isContextual && !action.title.isNullOrBlank() &&
                    (inputs.isNullOrEmpty() || inputs.any { it.allowFreeFormInput })
            }
            .take(MAX_ACTIONS)
            .joinToString(ACTION_SEPARATOR) { (index, action) ->
                val type = when {
                    action.remoteInputs?.any { it.allowFreeFormInput } == true -> "R" // Risposta con testo
                    action.semanticAction == Notification.Action.SEMANTIC_ACTION_DELETE -> "D" // Distruttivo
                    else -> "A"
                }
                listOf(index.toString(), type, clean(action.title.toString())).joinToString(ACTION_FIELD_SEPARATOR)
            }
    }

    // Preme sul telefono il pulsante scelto sul Mac, inserendo la risposta se è un pulsante "Rispondi"
    private fun runAction(macId: String, actionIndex: Int, replyText: String?) {
        val key = macIdToKey[macId] ?: return
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return
        val action = sbn.notification.actions?.getOrNull(actionIndex) ?: return
        val freeFormInputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
        if (freeFormInputs.isNotEmpty() && replyText == null) return

        suppressedUntil[key] = SystemClock.elapsedRealtime() + SUPPRESS_AFTER_ACTION_MS
        try {
            if (replyText != null && freeFormInputs.isNotEmpty()) {
                // Come una risposta scritta dalla tendina: il testo viaggia nell'intent come risultato del RemoteInput
                val results = Bundle().apply { freeFormInputs.forEach { putCharSequence(it.resultKey, replyText) } }
                val fillIn = Intent()
                RemoteInput.addResultsToIntent(freeFormInputs.toTypedArray(), fillIn, results)
                RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
                action.actionIntent.send(this, 0, fillIn)
            } else {
                action.actionIntent.send()
            }
            Log.d("MacSync", "Pulsante \"${action.title}\" eseguito dal Mac")
        } catch (e: PendingIntent.CanceledException) {
            Log.e("MacSync", "Pulsante non più valido: ${e.message}")
        }
    }

    // Un separatore dentro il testo spezzerebbe il pacchetto sul Mac
    private fun clean(field: String): String =
        field.replace(SEPARATOR, " ").replace(ACTION_SEPARATOR, " ").replace(ACTION_FIELD_SEPARATOR, " ")

    private fun enabledApps(): Set<String> {
        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("enabled_apps", setOf()) ?: setOf()
    }

    // Stesso processo: chiamata diretta al server GATT, senza passare da un broadcast
    private fun sendToGattServer(payload: String) {
        GattServerManager.getInstance(applicationContext).sendNotificationToMac(payload)
    }
}
