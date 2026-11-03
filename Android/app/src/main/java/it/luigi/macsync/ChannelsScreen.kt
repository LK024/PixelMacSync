package it.luigi.macsync

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Lo scorrimento di una lista dentro il pannello non lo trascina mai: arrivati in cima, il gesto che avanza
// (trascinamento o lancio verso il basso) viene assorbito qui invece di chiudere il pannello.
// Il pannello si chiude ancora trascinando dalla parte alta (maniglia, titolo), toccando fuori o con "indietro".
@Composable
fun rememberSheetSafeScroll(): NestedScrollConnection = remember {
    object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            Offset(0f, available.y.coerceAtLeast(0f))

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            Velocity(0f, available.y.coerceAtLeast(0f))
    }
}

// Interruttore con spunta/croce, lo stesso stile dell'elenco delle app
@Composable
fun MacSyncSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedIconColor = MaterialTheme.colorScheme.primary,
            uncheckedIconColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize)
            )
        }
    )
}

// Canali di notifica di un'app: quali inoltrare al Mac
@Composable
fun ChannelsScreen(app: AppItem, onBack: () -> Unit, onAppEnabledChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var channels by remember { mutableStateOf<List<ChannelInfo>?>(null) }
    var isFullList by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var disabled by remember { mutableStateOf(ChannelSettings.disabledChannels(context, app.packageName)) }

    LaunchedEffect(app.packageName) {
        val (list, full) = withContext(Dispatchers.IO) {
            ChannelSettings.channelsFor(context, app.packageName) to ChannelSettings.hasFullChannelList(app.packageName)
        }
        channels = list
        isFullList = full
        isLoading = false
    }

    // I canali disattivati sul telefono non contano: da lì non arrivano notifiche comunque
    val controllableIds = channels.orEmpty().filterNot { it.blockedOnPhone }.map { it.id }

    fun setEnabled(ids: Collection<String>, enabled: Boolean) {
        val appEnabled = ChannelSettings.setChannelsEnabled(context, app.packageName, ids, enabled, controllableIds)
        disabled = ChannelSettings.disabledChannels(context, app.packageName)
        onAppEnabledChanged(appEnabled)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = app.name, style = MaterialTheme.typography.titleLarge)
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MorphingLoadingIndicator() }

            channels.isNullOrEmpty() -> Text(
                text = "Nessun canale disponibile: compariranno quando arriverà la prima notifica di quest'app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp)
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize().nestedScroll(rememberSheetSafeScroll())) {
                if (!isFullList) {
                    item {
                        Text(
                            text = "Mostro solo i canali da cui è già arrivata una notifica. Per l'elenco completo associa il Mac dalla schermata principale.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }

                item {
                    val allOn = controllableIds.isNotEmpty() && controllableIds.none { it in disabled }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = allOn, enabled = controllableIds.isNotEmpty()) { setEnabled(controllableIds, it) }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tutti i canali", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        MacSyncSwitch(checked = allOn, onCheckedChange = null)
                    }
                    HorizontalDivider()
                }

                // Gruppi come nella schermata di sistema; i canali senza gruppo stanno in fondo, in "Altri"
                val groups = channels.orEmpty()
                    .groupBy { it.groupName }
                    .toList()
                    .sortedWith(compareBy<Pair<String?, List<ChannelInfo>>> { it.first == null }.thenBy { it.first?.lowercase() })
                val showHeaders = groups.size > 1 || groups.firstOrNull()?.first != null

                groups.forEach { (groupName, groupChannels) ->
                    if (showHeaders) {
                        item(key = "group-${groupName ?: ""}") {
                            Text(
                                text = groupName ?: "Altri",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)
                            )
                        }
                    }
                    items(groupChannels, key = { "channel-${it.id}" }) { channel ->
                        ChannelRow(
                            channel = channel,
                            enabled = channel.id !in disabled,
                            onToggle = { setEnabled(listOf(channel.id), it) },
                            onOpenSystemSettings = {
                                context.startActivity(
                                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                                        .putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)
                                )
                            }
                        )
                    }
                }

                item {
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                            )
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                    ) {
                        Text("Impostazioni di sistema delle notifiche dell'app")
                    }
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelInfo,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit
) {
    if (channel.blockedOnPhone) {
        // Disattivato sul telefono: niente interruttore, solo il collegamento alla pagina di sistema del canale
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSystemSettings)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Text("Disattivato sul telefono", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
            TextButton(onClick = onOpenSystemSettings) { Text("Impostazioni") }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, onValueChange = onToggle)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.bodyLarge)
                channel.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            MacSyncSwitch(checked = enabled, onCheckedChange = null)
        }
    }
}
