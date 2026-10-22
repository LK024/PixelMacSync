package it.luigi.macsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class GattServerManager private constructor(private val context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: GattServerManager? = null

        fun getInstance(context: Context): GattServerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GattServerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private var connectedMac: BluetoothDevice? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null

    private val SERVICE_UUID = UUID.fromString("E20A39F4-73F5-4BC4-A12F-17D1AD07A961")
    private val TELEMETRY_UUID = UUID.fromString("33333333-73F5-4BC4-A12F-17D1AD07A961")
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val NOTIFICATIONS_UUID = UUID.fromString("22222222-73F5-4BC4-A12F-17D1AD07A961")
    private val COMMAND_UUID = UUID.fromString("44444444-73F5-4BC4-A12F-17D1AD07A961")
    private var notificationsCharacteristic: BluetoothGattCharacteristic? = null

    // --- CODA DI INVIO BLE ---
    // Android accetta una sola notify alla volta: la successiva parte solo dopo onNotificationSent.
    // Tutto lo stato della coda vive sul main thread (le callback GATT arrivano su thread binder).
    private data class PendingNotify(val characteristic: BluetoothGattCharacteristic, val payload: String, var attempts: Int = 0)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sendQueue = ArrayDeque<PendingNotify>()
    private var isSending = false
    // La telemetria non entra in coda: conta solo l'ultimo valore, e solo se è cambiato
    private var pendingTelemetry: String? = null
    private var lastTelemetryPayload: String? = null
    // MTU negoziato per dispositivo (23 è il minimo BLE finché il Mac non ne chiede uno più grande)
    private val mtuByAddress = mutableMapOf<String, Int>()
    private val sendTimeout = Runnable {
        Log.w("MacSync", "onNotificationSent non arrivato in tempo, sblocco la coda")
        isSending = false
        drainQueue()
    }

    private val _connectionState = MutableStateFlow("In attesa di connessione...")
    val connectionState: StateFlow<String> = _connectionState

    // --- VARIABILI DI STATO ---
    private var currentBatteryLevel = 0
    private var isCharging = false
    private var currentCellularNetwork = "--"
    private var currentSignal = 0
    private var isWifiConnected = false
    private var wifiSSID = "Wi-Fi"
    private var isHotspotActive = false

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


    // 1. Ascoltatore del Wi-Fi (Versione Caching Ottimizzata + Fix SSID)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            isWifiConnected = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

            if (isWifiConnected) {
                try {
                    // Usiamo il WifiManager interno all'evento per bypassare i blocchi del callback
                    if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val info = wifiManager.connectionInfo
                        val rawSsid = info?.ssid

                        wifiSSID = if (rawSsid != null && rawSsid != "<unknown ssid>") {
                            rawSsid.removeSurrounding("\"")
                        } else {
                            "Wi-Fi"
                        }
                    } else {
                        wifiSSID = "Wi-Fi"
                    }
                } catch (e: Exception) {
                    Log.e("MacSync", "Errore estrazione Wi-Fi: ${e.message}")
                    wifiSSID = "Wi-Fi"
                }
            }
            notifyMacTelemetry()
        }

        override fun onLost(network: Network) {
            isWifiConnected = false
            wifiSSID = "Wi-Fi" // Resettiamo la cache
            notifyMacTelemetry()
        }
    }
    private fun getNetworkString(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "E"
            else -> "--"
        }
    }

    // 2. Ascoltatore Segnale Cellulare MIGLIORATO
    private val telephonyCallback = object : TelephonyCallback(),
        TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.DisplayInfoListener,
        TelephonyCallback.DataConnectionStateListener {

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            currentSignal = signalStrength.level
            notifyMacTelemetry()
        }

        @SuppressLint("MissingPermission")
        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            currentCellularNetwork = when (telephonyDisplayInfo.overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
                else -> getNetworkString(telephonyDisplayInfo.networkType)
            }
            notifyMacTelemetry()
        }

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                currentCellularNetwork = getNetworkString(networkType)
                notifyMacTelemetry()
            }
        }
    }

    // 3. Ascoltatore Batteria
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

                if (level != -1 && scale != -1) {
                    currentBatteryLevel = (level * 100) / scale
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                    notifyMacTelemetry()
                }
            }
        }
    }

    // 4. Ascoltatore Hotspot (Tethering)
    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.net.wifi.WIFI_AP_STATE_CHANGED") {
                val state = intent.getIntExtra("wifi_state", 11)
                if (state == 12 || state == 13) {
                    if (!isHotspotActive) {
                        isHotspotActive = true
                        notifyMacTelemetry()
                    }
                } else if (state == 10 || state == 11) {
                    if (isHotspotActive) {
                        isHotspotActive = false
                        notifyMacTelemetry()
                    }
                }
            }
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "it.luigi.macsync.NEW_NOTIFICATION") {
                val payload = intent.getStringExtra("payload")
                if (payload != null) sendNotificationToMac(payload)
            }
        }
    }

    private fun buildTelemetryPayload(): String {
        val networkStringToUse = if (isWifiConnected) wifiSSID else currentCellularNetwork
        return "$currentBatteryLevel\u001F$isCharging\u001F$networkStringToUse\u001F$currentSignal\u001F$isWifiConnected\u001F$isHotspotActive\u001F${android.os.Build.MODEL}"
    }

    private fun notifyMacTelemetry() {
        // Chiamata anche dal thread di ConnectivityManager: passiamo sempre dal main thread
        mainHandler.post {
            if (connectedMac == null) return@post
            val payload = buildTelemetryPayload()
            // Batteria e segnale cambiano spesso senza che il valore inviato cambi: niente invii inutili
            if (payload == lastTelemetryPayload) return@post
            lastTelemetryPayload = payload
            pendingTelemetry = payload
            drainQueue()
        }
    }

    fun sendNotificationToMac(payload: String) {
        mainHandler.post {
            val characteristic = notificationsCharacteristic
            if (connectedMac == null || characteristic == null) return@post
            sendQueue.addLast(PendingNotify(characteristic, payload))
            drainQueue()
        }
    }

    private fun takePendingTelemetry(): PendingNotify? {
        val payload = pendingTelemetry ?: return null
        val characteristic = telemetryCharacteristic ?: return null
        pendingTelemetry = null
        return PendingNotify(characteristic, payload)
    }

    private fun drainQueue() {
        if (isSending) return
        val mac = connectedMac ?: return
        val server = gattServer ?: return
        val next = sendQueue.removeFirstOrNull() ?: takePendingTelemetry() ?: return

        val data = fitToMtu(next.payload, mtuByAddress[mac.address] ?: 23)
        val status = server.notifyCharacteristicChanged(mac, next.characteristic, false, data)

        if (status == BluetoothStatusCodes.SUCCESS) {
            isSending = true
            mainHandler.postDelayed(sendTimeout, 1000)
        } else {
            Log.w("MacSync", "Notify rifiutata (status $status), tentativo ${next.attempts + 1}")
            next.attempts++
            if (next.attempts < 3) sendQueue.addFirst(next)
            mainHandler.postDelayed({ drainQueue() }, 100)
        }
    }

    // Una notify può contenere al massimo MTU - 3 byte. Tagliamo sui confini UTF-8:
    // un'emoji spezzata a metà renderebbe il pacchetto illeggibile sul Mac.
    private fun fitToMtu(payload: String, mtu: Int): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val maxLength = mtu - 3
        if (bytes.size <= maxLength) return bytes

        val ellipsis = "…".toByteArray(Charsets.UTF_8)
        var cut = maxLength - ellipsis.size
        while (cut > 0 && (bytes[cut].toInt() and 0xC0) == 0x80) cut--
        return bytes.copyOf(cut) + ellipsis
    }

    private fun resetLinkState() {
        mainHandler.removeCallbacks(sendTimeout)
        connectedMac = null
        sendQueue.clear()
        pendingTelemetry = null
        lastTelemetryPayload = null
        isSending = false
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            // Il collegamento da solo non basta: il Mac diventa "connectedMac" solo quando si iscrive
            // alle notify, cosa che lo stack consente unicamente su un link cifrato (dispositivo associato)
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mainHandler.post {
                    mtuByAddress.remove(device.address)
                    if (device == connectedMac) {
                        resetLinkState()
                        _connectionState.value = "Disconnesso. In attesa..."
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            super.onMtuChanged(device, mtu)
            mainHandler.post { mtuByAddress[device.address] = mtu }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            mainHandler.post {
                mainHandler.removeCallbacks(sendTimeout)
                isSending = false
                drainQueue()
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            if (characteristic.uuid == TELEMETRY_UUID) {
                // Se il valore supera l'MTU il Mac lo legge a pezzi (long read): rispondiamo dall'offset richiesto
                val data = buildTelemetryPayload().toByteArray(Charsets.UTF_8)
                val chunk = if (offset < data.size) data.copyOfRange(offset, data.size) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor.uuid == CCC_DESCRIPTOR_UUID) {
                // Arriviamo qui solo su link cifrato: il descrittore \u00E8 *_ENCRYPTED
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    mainHandler.post {
                        if (device != connectedMac) {
                            resetLinkState()
                            connectedMac = device
                            _connectionState.value = "Connesso al Mac! \uD83C\uDF4F"
                        }
                        notifyMacTelemetry()
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        // --- ⚙️ GESTIONE COMANDI IN INGRESSO DAL MAC ---
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)

            if (characteristic.uuid == COMMAND_UUID && value != null) {
                val fullCommand = String(value, Charsets.UTF_8)
                Log.d("MacSync", "Ricevuto pacchetto comandi dal Mac: $fullCommand")

                val parts = fullCommand.split("\u001F")
                val commandType = parts[0]

                when (commandType) {
                    "HOTSPOT_ON" -> {
                        val intent = Intent("it.luigi.macsync.HOTSPOT_ON")
                        intent.setPackage("com.arlosoft.macrodroid")
                        context.sendBroadcast(intent)
                    }
                    "HOTSPOT_OFF" -> {
                        val intent = Intent("it.luigi.macsync.HOTSPOT_OFF")
                        intent.setPackage("com.arlosoft.macrodroid")
                        context.sendBroadcast(intent)
                    }
                    "KILL" -> {
                        if (parts.size >= 2) {
                            val notifIdToKill = parts[1]
                            Log.d("MacSync", "Comando KILL ricevuto. Chiedo l'eliminazione per ID: $notifIdToKill")
                            val intent = Intent("it.luigi.macsync.KILL_NOTIFICATION")
                            intent.putExtra("macNotifId", notifIdToKill)
                            intent.setPackage(context.applicationContext.packageName)
                            context.sendBroadcast(intent)
                        }
                    }
                    // --- NUOVO COMANDO: RICHIESTA DI SINCRONIZZAZIONE A FREDDO ---
                    "SYNC_REQ" -> {
                        Log.d("MacSync", "Il Mac ha richiesto la sincronizzazione a freddo!")
                        val intent = Intent("it.luigi.macsync.SYNC_REQUEST")
                        intent.setPackage(context.applicationContext.packageName)
                        context.sendBroadcast(intent)
                    }
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
            }
        }
    }

    private var isServerRunning = false

    fun startServer() {
        if (isServerRunning) return
        isServerRunning = true

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        setupService()

        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        context.registerReceiver(notificationReceiver, IntentFilter("it.luigi.macsync.NEW_NOTIFICATION"), Context.RECEIVER_NOT_EXPORTED)
        context.registerReceiver(hotspotReceiver, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"))

        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback)
            currentCellularNetwork = getNetworkString(telephonyManager.dataNetworkType)
        }
    }

    private fun setupService() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Tutti i permessi sono *_ENCRYPTED: un dispositivo non associato riceve "Insufficient Authentication",
        // il Mac reagisce avviando l'abbinamento (una volta sola) e chiunque altro resta fuori.
        val localTelemetryCharacteristic = BluetoothGattCharacteristic(
            TELEMETRY_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )

        val charNotifications = BluetoothGattCharacteristic(
            NOTIFICATIONS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )

        val notifDescriptor = BluetoothGattDescriptor(CCC_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED)
        charNotifications.addDescriptor(notifDescriptor)
        service.addCharacteristic(charNotifications)
        notificationsCharacteristic = charNotifications

        val clientConfigDescriptor = BluetoothGattDescriptor(
            CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
        )

        val commandCharacteristic = BluetoothGattCharacteristic(
            COMMAND_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        )
        service.addCharacteristic(commandCharacteristic)

        localTelemetryCharacteristic.addDescriptor(clientConfigDescriptor)
        service.addCharacteristic(localTelemetryCharacteristic)
        gattServer?.addService(service)

        telemetryCharacteristic = localTelemetryCharacteristic
    }

    fun stopServer() {
        if (!isServerRunning) return
        isServerRunning = false

        context.unregisterReceiver(batteryReceiver)
        context.unregisterReceiver(notificationReceiver)
        context.unregisterReceiver(hotspotReceiver)

        telephonyManager.unregisterTelephonyCallback(telephonyCallback)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        gattServer?.close()
        gattServer = null
        mainHandler.post {
            resetLinkState()
            mtuByAddress.clear()
        }
    }
}