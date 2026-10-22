package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.util.Log

// Associa il Mac a MacSync come "dispositivo compagno" di Android.
// L'associazione dà a MacSync il permesso di avviare il servizio da background, il diritto di rinominare
// il Mac nell'elenco Bluetooth e la protezione dalla chiusura forzata. Su Android 17 NON esenta
// dalle ottimizzazioni della batteria: "Senza restrizioni" va impostato a mano.
object MacCompanion {

    private fun companionManager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    fun isAssociated(context: Context, address: String): Boolean =
        companionManager(context)?.myAssociations.orEmpty()
            .any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) }

    // Il Mac è già abbinato: con un filtro sull'indirizzo e setSingleDevice il sistema lo cerca
    // anche tra i dispositivi abbinati, senza bisogno che sia in giro a trasmettere
    fun associate(
        context: Context,
        address: String,
        onPending: (IntentSender) -> Unit,
        onCreated: () -> Unit,
        onFailed: (String) -> Unit
    ) {
        val manager = companionManager(context) ?: return onFailed("Dispositivi compagni non supportati")

        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()

        manager.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) = onCreated()

            override fun onFailure(error: CharSequence?) {
                Log.e("MacSync", "Associazione non riuscita: $error")
                onFailed(error?.toString() ?: "Associazione non riuscita")
            }
        })
    }

    // Rinomina il Mac nell'elenco Bluetooth del telefono (consentito solo ai dispositivi associati)
    @SuppressLint("MissingPermission")
    fun applyAlias(context: Context, address: String, name: String) {
        if (!isAssociated(context, address)) return
        val device = context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) ?: return
        if (device.alias == name) return

        try {
            val status = device.setAlias(name)
            Log.d("MacSync", "Nome Bluetooth del Mac impostato (status $status)")
        } catch (e: SecurityException) {
            Log.e("MacSync", "Impossibile rinominare il Mac: ${e.message}")
        }
    }
}
