package it.luigi.macsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import it.luigi.macsync.ble.BLEAdvertiser
import it.luigi.macsync.ble.GattServerManager
import android.os.Handler
import android.os.Looper

class MacSyncBleService : Service() {

    private lateinit var gattServerManager: GattServerManager
    private lateinit var bleAdvertiser: BLEAdvertiser

    // NOVITÀ: Ricevitore per ascoltare quando accendi/spegni il Bluetooth da Android
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d("MacSync", "Bluetooth riacceso da Android! Attendo 2 secondi...")
                        // Il Bluetooth ha bisogno di 1-2 secondi per risvegliare l'hardware GATT
                        Handler(Looper.getMainLooper()).postDelayed({
                            gattServerManager.startServer()
                            bleAdvertiser.startAdvertising()
                        }, 2000)
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d("MacSync", "Bluetooth spento da Android! Fermo i motori BLE...")
                        gattServerManager.stopServer()
                        bleAdvertiser.stopAdvertising()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val channelId = "macsync_bg_channel"
        val channel = NotificationChannel(channelId, "Sincronizzazione Mac", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Mantiene attiva la connessione BLE con il Mac"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MacSync Attivo")
            .setContentText("In ascolto in background...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        gattServerManager = GattServerManager.getInstance(this)
        bleAdvertiser = BLEAdvertiser(this)

        // Da collegati il Mac non ha bisogno di trovarci: niente advertising finché non si scollega
        gattServerManager.onMacLinkChanged = { isLinked ->
            if (isLinked) bleAdvertiser.stopAdvertising() else bleAdvertiser.startAdvertising()
        }

        // Registriamo l'ascoltatore del Bluetooth
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)

        // Facciamo partire i servizi se il Bluetooth è già acceso al lancio dell'app
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter?.isEnabled == true) {
            gattServerManager.startServer()
            bleAdvertiser.startAdvertising()
        }
    }

    override fun onDestroy() {
        gattServerManager.onMacLinkChanged = null
        unregisterReceiver(bluetoothStateReceiver)
        gattServerManager.stopServer()
        bleAdvertiser.stopAdvertising()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}