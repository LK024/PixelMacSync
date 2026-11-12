package it.luigi.macsync

import android.content.Context
import android.content.SharedPreferences
import it.luigi.macsync.ble.MacSyncNotificationListener

// Un canale di notifica di un'altra app, come lo mostra la schermata dei canali
data class ChannelInfo(
    val id: String,
    val name: String,
    val description: String?,
    val groupName: String?,
    val blockedOnPhone: Boolean
)

// Scelte per app e per canale. Si salvano i canali DISATTIVATI, non quelli attivi:
// così un'app mai toccata ha tutti i canali attivi e i canali nuovi creati in futuro partono attivi.
object ChannelSettings {

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)

    private fun disabledKey(packageName: String) = "disabled_channels_$packageName"

    fun disabledChannels(context: Context, packageName: String): Set<String> =
        prefs(context).getStringSet(disabledKey(packageName), emptySet())?.toSet() ?: emptySet()

    fun isChannelEnabled(context: Context, packageName: String, channelId: String?): Boolean =
        channelId == null || channelId !in disabledChannels(context, packageName)

    fun isAppEnabled(context: Context, packageName: String): Boolean =
        prefs(context).getStringSet("enabled_apps", emptySet())?.contains(packageName) == true

    private fun saveAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        val apps = prefs(context).getStringSet("enabled_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (enabled) apps.add(packageName) else apps.remove(packageName)
        prefs(context).edit().putStringSet("enabled_apps", apps).apply()
    }

    private fun saveDisabled(context: Context, packageName: String, disabled: Set<String>) {
        prefs(context).edit().putStringSet(disabledKey(packageName), disabled).apply()
    }

    // Interruttore dell'app nell'elenco: le scelte sui canali restano salvate quando la spegni.
    // Se la accendi mentre tutti i suoi canali risultano disattivati, li riattiva tutti (altrimenti non arriverebbe niente).
    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        if (enabled) {
            val controllable = channelsFor(context, packageName)?.filterNot { it.blockedOnPhone }?.map { it.id }.orEmpty()
            val disabled = disabledChannels(context, packageName)
            if (controllable.isNotEmpty() && controllable.all { it in disabled }) {
                saveDisabled(context, packageName, disabled - controllable.toSet())
            }
        }
        saveAppEnabled(context, packageName, enabled)
    }

    // Interruttore di un canale (o di "Tutti i canali"): tiene coerente l'interruttore dell'app.
    // Nessun canale attivo → app spenta; almeno un canale attivo → app accesa. Restituisce lo stato dell'app.
    fun setChannelsEnabled(
        context: Context,
        packageName: String,
        channelIds: Collection<String>,
        enabled: Boolean,
        controllableIds: Collection<String>
    ): Boolean {
        val disabled = disabledChannels(context, packageName).toMutableSet()
        if (enabled) disabled.removeAll(channelIds.toSet()) else disabled.addAll(channelIds)
        saveDisabled(context, packageName, disabled)

        val anyActive = controllableIds.any { it !in disabled }
        saveAppEnabled(context, packageName, anyActive)
        return anyActive
    }

    // Elenco completo dei canali (richiede il Mac associato come dispositivo compagno);
    // altrimenti i canali già visti arrivare, oppure null se non ce ne sono
    fun channelsFor(context: Context, packageName: String): List<ChannelInfo>? =
        MacSyncNotificationListener.allChannels(packageName) ?: MacSyncNotificationListener.seenChannels(context, packageName)

    // true se l'elenco è completo, false se mostra solo i canali già visti
    fun hasFullChannelList(packageName: String): Boolean =
        MacSyncNotificationListener.allChannels(packageName) != null
}
