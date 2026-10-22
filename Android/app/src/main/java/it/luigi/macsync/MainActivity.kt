package it.luigi.macsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.luigi.macsync.ble.GattServerManager
import it.luigi.macsync.ble.MacCompanion
import it.luigi.macsync.ui.theme.MacSyncTheme

class MainActivity : ComponentActivity() {

    private lateinit var gattServerManager: GattServerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        gattServerManager = GattServerManager.getInstance(this)

        setContent {
            MacSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(gattServerManager)
                }
            }
        }
    }
}

private fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(pkgName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(gattServerManager: GattServerManager) {
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    MainScreen(gattServerManager = gattServerManager) {
        showBottomSheet = true
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = { BottomSheetDefaults.DragHandle() },
            contentWindowInsets = { WindowInsets.statusBars }
        ) {
            AppSelectionScreen()
        }
    }
}

@Composable
fun MainScreen(gattServerManager: GattServerManager, onOpenNotificationsClick: () -> Unit) {
    var permissionsGranted by remember { mutableStateOf(false) }

    val statusText by gattServerManager.connectionState.collectAsState()
    val macAddress by gattServerManager.macAddress.collectAsState()
    val macName by gattServerManager.macName.collectAsState()

    val context = LocalContext.current
    val isListenerGranted = isNotificationServiceEnabled(context)

    var isMacAssociated by remember(macAddress) {
        mutableStateOf(macAddress?.let { MacCompanion.isAssociated(context, it) } ?: false)
    }
    val onMacAssociated = {
        isMacAssociated = true
        gattServerManager.applyMacAlias()
        Toast.makeText(context, "Mac associato", Toast.LENGTH_SHORT).show()
    }
    // Il dialogo di sistema "Consentire a MacSync di accedere a..." arriva come IntentSender
    val associationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->

        // 1. Controlliamo SOLO i permessi vitali per far funzionare l'app
        val isBluetoothConnectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true ||
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == null // Fallback per versioni vecchie

        val isBluetoothAdvertiseGranted = permissions[Manifest.permission.BLUETOOTH_ADVERTISE] == true ||
                permissions[Manifest.permission.BLUETOOTH_ADVERTISE] == null

        // 2. Se il Bluetooth c'è, la UI è autorizzata a sbloccarsi
        val essentialGranted = isBluetoothConnectGranted && isBluetoothAdvertiseGranted

        permissionsGranted = essentialGranted

        // 3. Avviamo il servizio in background
        if (essentialGranted) {
            context.startForegroundService(Intent(context, MacSyncBleService::class.java))
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.READ_PHONE_STATE
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(54.dp))

        Text(
            text = "MacSync",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(36.dp))

        if (!permissionsGranted) {
            Text("Richiesta permessi Bluetooth...", modifier = Modifier.padding(top = 32.dp))
            return@Column
        }

        Image(
            painter = painterResource(id = R.drawable.macbookpro),
            contentDescription = "MacBook Pro",
            modifier = Modifier.size(165.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = macName ?: "Mac",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        // --- RIGA STATO PULITA E COERENTE ---
        val isConnected = !statusText.contains("Disconnesso", ignoreCase = true)

        val cleanStatus = when {
            statusText.contains("Disconnesso", ignoreCase = true) -> "Disconnesso"
            statusText.contains("Connesso", ignoreCase = true) -> "Connesso"
            else -> statusText
        }


        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = cleanStatus,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (isConnected) {
                Icon(
                    imageVector = Icons.Rounded.Link, // <-- Icona Catena Intera
                    contentDescription = "Connesso",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary // Stesso colore Monet della scritta "Connesso"
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.LinkOff, // <-- Icona Catena Spezzata
                    contentDescription = "Disconnesso",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant // Stesso grigio della scritta "Disconnesso"
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // --- SEZIONE IMPOSTAZIONI ---
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Impostazioni",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            SettingsCardItem(
                icon = Icons.Rounded.Notifications,
                title = "Notifiche",
                subtitle = "Sincronizza notifiche con il Mac",
                onClick = onOpenNotificationsClick
            )

            val address = macAddress
            if (address != null && !isMacAssociated) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsCardItem(
                    icon = Icons.Rounded.LaptopMac,
                    title = "Associa il Mac",
                    subtitle = "Nome del Mac nel Bluetooth e app protetta dalla chiusura",
                    onClick = {
                        MacCompanion.associate(
                            context = context,
                            address = address,
                            onPending = { sender -> associationLauncher.launch(IntentSenderRequest.Builder(sender).build()) },
                            onCreated = onMacAssociated,
                            onFailed = { error -> Toast.makeText(context, error, Toast.LENGTH_LONG).show() }
                        )
                    }
                )
            }

            if (!isListenerGranted) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsCardItem(
                    icon = Icons.Rounded.Warning,
                    title = "Accesso alle Notifiche",
                    subtitle = "Tocca per concedere il permesso di sistema",
                    isWarning = true,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsCardItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isWarning: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (isWarning) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val txtColor = if (isWarning) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = txtColor)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = if (isWarning) txtColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
