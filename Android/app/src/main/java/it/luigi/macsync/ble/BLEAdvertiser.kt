package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

class BLEAdvertiser(context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // Usiamo il manager moderno per recuperare l'antenna fresca ad ogni chiamata senza warning!
    private val advertiser: BluetoothLeAdvertiser?
        get() = bluetoothManager.adapter?.bluetoothLeAdvertiser

    // Variabile di sicurezza per non avviare due volte l'antenna
    private var isAdvertising = false

    private val serviceUUID = UUID.fromString("E20A39F4-73F5-4BC4-A12F-17D1AD07A961")

    // BALANCED (~250 ms) basta al Mac per trovarci in un attimo e consuma molto meno di LOW_LATENCY (~100 ms).
    // L'advertising resta acceso solo finché il Mac non è collegato (vedi MacSyncBleService).
    private val advertiseSettings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .build()

    private val advertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .addServiceUuid(ParcelUuid(serviceUUID))
        .build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("PixelSync", "Advertising avviato con successo! Il Mac dovrebbe vedermi.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("PixelSync", "Errore nell'avvio dell'advertising: $errorCode")
            // Se l'avvio è fallito davvero, la prossima chiamata deve poter riprovare
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) isAdvertising = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (isAdvertising) return // Impedisce i doppi avvii

        val currentAdvertiser = advertiser
        if (currentAdvertiser == null) {
            Log.e("PixelSync", "Bluetooth LE non supportato o spento su questo dispositivo.")
            return
        }

        Log.d("PixelSync", "Avvio advertising...")
        currentAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        isAdvertising = true
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!isAdvertising) return // Impedisce crash per spegnimenti di cose già spente
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
    }
}