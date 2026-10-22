package it.luigi.macsync.ble

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import java.util.Properties
import java.util.UUID

// UUID del protocollo BLE. Quelli qui sotto sono i valori pubblici del repository: per usarne di propri
// senza modificare il codice, crea app/src/main/assets/ble_uuids.properties (escluso da git) con le chiavi
// service, telemetry, notifications e command. Devono coincidere con LocalUUIDs.plist dell'app Mac.
class BleUuids private constructor(
    val service: UUID,
    val telemetry: UUID,
    val notifications: UUID,
    val command: UUID
) {
    companion object {
        private const val DEFAULT_SERVICE = "58DF214B-9942-45A5-BAF9-7B24F5D0232C"
        private const val DEFAULT_TELEMETRY = "CEFB6548-6C8A-4D25-A086-C8A69D3F6625"
        private const val DEFAULT_NOTIFICATIONS = "6E6C9609-9FFA-42E2-A882-B0C4398D58DE"
        private const val DEFAULT_COMMAND = "586B06E6-CCC5-44B8-BFD9-5D2514A67842"

        @Volatile
        private var instance: BleUuids? = null

        fun get(context: Context): BleUuids = instance ?: synchronized(this) {
            instance ?: load(context).also { instance = it }
        }

        private fun load(context: Context): BleUuids {
            val local = Properties()
            try {
                context.assets.open("ble_uuids.properties").use { local.load(it) }
                Log.d("MacSync", "UUID BLE personalizzati caricati da ble_uuids.properties")
            } catch (_: FileNotFoundException) {
                // Nessun file locale: si usano gli UUID pubblici
            }

            fun uuid(key: String, default: String) = UUID.fromString(local.getProperty(key)?.trim() ?: default)

            return BleUuids(
                service = uuid("service", DEFAULT_SERVICE),
                telemetry = uuid("telemetry", DEFAULT_TELEMETRY),
                notifications = uuid("notifications", DEFAULT_NOTIFICATIONS),
                command = uuid("command", DEFAULT_COMMAND)
            )
        }
    }
}
