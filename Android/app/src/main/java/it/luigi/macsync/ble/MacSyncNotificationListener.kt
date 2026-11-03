package it.luigi.macsync.ble

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import it.luigi.macsync.ChannelInfo
import it.luigi.macsync.ChannelSettings

class MacSyncNotificationListener : NotificationListenerService() {

    companion object {
        private const val SEPARATOR = "\u001F"
        // Dentro il campo pulsanti: \u001E separa i pulsanti, \u001D i dati di un pulsante (indice, tipo, nome)
        private const val ACTION_SEPARATOR = "\u001E"
        private const val ACTION_FIELD_SEPARATOR = "\u001D"
        private const val MAX_ACTIONS = 3

        // ID del Mac -> key della notifica Android, per il comando KILL
        private val macIdToKey = mutableMapOf<String, String>()
        // Notifiche già inoltrate al Mac: servono a riconoscere gli aggiornamenti e a rispecchiare le rimozioni
        private val forwardedKeys = mutableSetOf<String>()

        // ID breve e stabile per ogni notifica Android: se l'app la aggiorna, sul Mac viene sostituita invece di duplicata
        private fun macIdFor(key: String): String = Integer.toHexString(key.hashCode())

        // Il listener collegato al sistema: serve alla schermata dei canali per leggerne l'elenco
        @Volatile
        private var connected: MacSyncNotificationListener? = null

        // Tutti i canali di un'app, divisi per gruppo. Android lo concede solo con il Mac associato come
        // dispositivo compagno: senza associazione, o con il listener non collegato, restituisce null.
        fun allChannels(packageName: String): List<ChannelInfo>? {
            val listener = connected ?: return null
            return try {
                val user = Process.myUserHandle()
                val groups = listener.getNotificationChannelGroups(packageName, user).associateBy { it.id }
                listener.getNotificationChannels(packageName, user).map { channel ->
                    val group = channel.group?.let { groups[it] }
                    ChannelInfo(
                        id = channel.id,
                        name = channel.name?.toString() ?: channel.id,
                        description = channel.description,
                        groupName = group?.name?.toString(),
                        blockedOnPhone = channel.importance == NotificationManager.IMPORTANCE_NONE || group?.isBlocked == true
                    )
                }
            } catch (e: SecurityException) {
                Log.w("MacSync", "Elenco canali non disponibile (Mac non associato?): ${e.message}")
                null
            }
        }

        private fun seenKey(packageName: String) = "seen_channels_$packageName"

        // Ripiego senza associazione: i canali da cui è già arrivata almeno una notifica ("id␟nome")
        fun seenChannels(context: Context, packageName: String): List<ChannelInfo>? {
            val prefs = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
            val seen = prefs.getStringSet(seenKey(packageName), emptySet()).orEmpty()
            if (seen.isEmpty()) return null
            return seen.map { entry ->
                val id = entry.substringBefore(SEPARATOR)
                ChannelInfo(id, entry.substringAfter(SEPARATOR, id), null, null, blockedOnPhone = false)
            }.sortedBy { it.name.lowercase() }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        super.onListenerDisconnected()
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
        if (connected === this) connected = null
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
            // Il Mac ha appena svuotato il suo centro notifiche: reinviamo tutto, sempre con banner
            val payload = buildNotificationPayload(sbn, enabledApps, isSnapshot = true) ?: continue
            sendToGattServer(payload)
        }
        Log.d("MacSync", "Snapshot inviato con successo al Mac.")
    }

    // --- 3. QUANDO ARRIVA (O SI AGGIORNA) UNA NOTIFICA ---
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val payload = buildNotificationPayload(sbn, enabledApps(), isSnapshot = false) ?: return
        sendToGattServer(payload)
    }

    // --- 4. QUANDO LA NOTIFICA VIENE RIMOSSA DAL TELEFONO ---
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // Qualunque motivo conta (letta da un altro dispositivo, annullata dall'app, scaduta, cliccata...),
        // ma solo per le notifiche che abbiamo effettivamente inviato al Mac
        val key = sbn.key
        if (!forwardedKeys.remove(key)) return

        val macId = macIdFor(key)
        // Log diagnostico (senza contenuti): perché Android ha tolto la notifica
        Log.d("MacSync", "Rimossa $macId (${sbn.packageName}): motivo=${removalReasonName(reason)} -> REMOVE")
        macIdToKey.remove(macId)
        sendToGattServer("REMOVE$SEPARATOR$macId")
    }

    private fun removalReasonName(reason: Int): String = when (reason) {
        REASON_CLICK -> "clic"
        REASON_CANCEL -> "chiusa dall'utente"
        REASON_CANCEL_ALL -> "cancella tutte"
        REASON_APP_CANCEL -> "chiusa dall'app"
        REASON_APP_CANCEL_ALL -> "chiuse tutte dall'app"
        REASON_LISTENER_CANCEL -> "chiusa da MacSync"
        REASON_GROUP_SUMMARY_CANCELED -> "riepilogo di gruppo chiuso"
        REASON_SNOOZED -> "posticipata"
        REASON_TIMEOUT -> "scaduta"
        else -> "codice $reason"
    }

    // Restituisce il pacchetto da inoltrare (POST con banner, UPDATE passivo), oppure null se la notifica va ignorata.
    // Ogni notifica nuova e ogni aggiornamento vengono inoltrati; banner o silenzio li decidono Android e l'app.
    private fun buildNotificationPayload(sbn: StatusBarNotification, enabledApps: Set<String>, isSnapshot: Boolean): String? {
        val packageName = sbn.packageName
        if (packageName == "android" || packageName == "com.android.systemui") return null
        if (!enabledApps.contains(packageName)) return null

        val notification = sbn.notification

        // Canali scelti nella schermata dell'app: quelli disattivati non arrivano al Mac
        rememberSeenChannel(sbn.key, packageName)
        if (!ChannelSettings.isChannelEnabled(this, packageName, notification.channelId)) {
            Log.d("MacSync", "Notifica ${macIdFor(sbn.key)} ($packageName): canale disattivato in MacSync -> non inoltrata")
            return null
        }

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
        val isUpdate = !forwardedKeys.add(key)
        val macId = macIdFor(key)
        macIdToKey[macId] = key

        // Come sul telefono, ignorando il Non disturbare (non si usa il filtro delle interruzioni):
        // - canale a importanza Bassa/Minima → silenziosa anche sul Mac
        // - aggiornamento che l'app marca "avvisa solo la prima volta" → silenzioso anche sul Mac
        // In ogni altro caso (importanza Predefinita/Alta o sconosciuta) banner. La riconnessione resta sempre con banner.
        val importance = channelImportance(key)
        val onlyAlertOnce = (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        val isLowImportance = importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW
        val isSilent = !isSnapshot && (isLowImportance || (isUpdate && onlyAlertOnce))
        val packetType = if (isSilent) "UPDATE" else "POST"

        // Log diagnostico (senza contenuti): per il collaudo
        Log.d("MacSync", "Notifica $macId ($packageName): ${if (isUpdate) "aggiornamento" else "nuova"} " +
            "importanza=$importance soloPrimaVolta=$onlyAlertOnce snapshot=$isSnapshot -> $packetType")

        // I pulsanti stanno prima di titolo e testo: se il pacchetto va troncato si perde la coda del testo, non i pulsanti
        val actions = encodeActions(notification)
        return "$packetType$SEPARATOR$macId$SEPARATOR$packageName$SEPARATOR$actions$SEPARATOR${clean(title)}$SEPARATOR${clean(text)}"
    }

    // Annota il canale di una notifica arrivata, per mostrarlo anche senza l'elenco completo dei canali
    private fun rememberSeenChannel(key: String, packageName: String) {
        val ranking = Ranking()
        if (currentRanking?.getRanking(key, ranking) != true) return
        val channel = ranking.channel ?: return
        val prefs = applicationContext.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(seenKey(packageName), emptySet()).orEmpty()
        if (seen.any { it.substringBefore(SEPARATOR) == channel.id }) return
        prefs.edit().putStringSet(seenKey(packageName), seen + "${channel.id}$SEPARATOR${channel.name ?: channel.id}").apply()
    }

    // Importanza del canale della notifica (impostata dall'app o da te), oppure null se non disponibile
    private fun channelImportance(key: String): Int? {
        val ranking = Ranking()
        return if (currentRanking?.getRanking(key, ranking) == true) ranking.importance else null
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
