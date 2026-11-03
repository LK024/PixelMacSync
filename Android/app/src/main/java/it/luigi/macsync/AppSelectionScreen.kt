package it.luigi.macsync

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape // <-- AGGIUNTO IMPORT MANCANTE
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.CornerRounding

class MorphShape(
    private val morph: Morph,
    private val progress: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val androidPath = morph.toPath(progress)
        val matrix = android.graphics.Matrix()
        matrix.setScale(size.width / 2f, size.height / 2f)
        matrix.postTranslate(size.width / 2f, size.height / 2f)
        androidPath.transform(matrix)
        return Outline.Generic(androidPath.asComposePath())
    }
}

@Composable
fun MorphingLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "morphing")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "rotation"
    )

    val morphProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 0
                // Corretto "with" in "using" per la nuova sintassi di Compose
                0f at 200 using FastOutSlowInEasing
                1f at 1000
                1f at 1200
            },
            repeatMode = RepeatMode.Reverse
        ), label = "morph"
    )

    val cookie = remember { RoundedPolygon.star(numVerticesPerRadius = 4, innerRadius = 0.5f, rounding = CornerRounding(radius = 0.2f)) }
    val sunny = remember { RoundedPolygon.star(numVerticesPerRadius = 8, innerRadius = 0.7f, rounding = CornerRounding(radius = 0.15f)) }
    val morph = remember { Morph(cookie, sunny) }

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                rotationZ = rotation
            }
            .clip(MorphShape(morph, morphProgress))
            .background(MaterialTheme.colorScheme.primary)
    )
}

data class AppItem(val name: String, val packageName: String, val isEnabled: Boolean, val icon: ImageBitmap)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("MacSync_Prefs", Context.MODE_PRIVATE)

    var appList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    // App di cui è aperta la schermata dei canali (null = elenco delle app)
    var openedApp by remember { mutableStateOf<AppItem?>(null) }
    val scope = rememberCoroutineScope()

    fun updateEnabled(packageName: String, enabled: Boolean) {
        appList = appList.map { if (it.packageName == packageName) it.copy(isEnabled = enabled) else it }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val savedApps = prefs.getStringSet("enabled_apps", emptySet()) ?: emptySet()

            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)

            val resolveInfos = pm.queryIntentActivities(intent, 0)

            val apps = resolveInfos.mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo.applicationInfo

                if (appInfo.packageName == context.packageName) return@mapNotNull null

                val iconBitmap = pm.getApplicationIcon(appInfo).toBitmap(128, 128).asImageBitmap()

                AppItem(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    isEnabled = savedApps.contains(appInfo.packageName),
                    icon = iconBitmap
                )
            }
                .distinctBy { it.packageName }
                .sortedWith(compareByDescending<AppItem> { it.isEnabled }.thenBy { it.name })

            withContext(Dispatchers.Main) {
                appList = apps
                isLoading = false
            }
        }
    }

    openedApp?.let { app ->
        ChannelsScreen(
            app = appList.firstOrNull { it.packageName == app.packageName } ?: app,
            onBack = { openedApp = null },
            onAppEnabledChanged = { enabled -> updateEnabled(app.packageName, enabled) }
        )
        return
    }

    val filteredList = if (searchQuery.isEmpty()) {
        appList
    } else {
        appList.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Titolo centrale in puro stile widget di Android Stock
                Text(
                    text = "App Notifiche",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // SearchBar "Pillola" pulita e senza parentesi graffe finali
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Cerca") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Cerca")
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Cancella")
                            }
                        }
                    },
                    shape = RoundedCornerShape(50),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp) // <-- Primo step: i lati
                        .padding(bottom = 8.dp)      // <-- Secondo step: la base
                )
            }
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                MorphingLoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .nestedScroll(rememberSheetSafeScroll()),
            ) {
                items(filteredList, key = { it.packageName }) { appItem ->
                    AppRow(
                        appItem = appItem,
                        onOpenChannels = { openedApp = appItem },
                        onCheckedChange = { isChecked ->
                            updateEnabled(appItem.packageName, isChecked)
                            // Le scelte sui canali restano salvate quando l'app viene spenta (vedi ChannelSettings)
                            scope.launch(Dispatchers.IO) {
                                ChannelSettings.setAppEnabled(context, appItem.packageName, isChecked)
                            }
                        }
                    )
                }
                item {
                    Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

// Tocco sulla riga: apre i canali dell'app. Interruttore: accende/spegne l'app.
@Composable
fun AppRow(appItem: AppItem, onOpenChannels: () -> Unit, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenChannels)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = appItem.icon,
            contentDescription = "Icona di ${appItem.name}",
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = appItem.name, style = MaterialTheme.typography.bodyLarge)
            Text(text = appItem.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Come nelle impostazioni di Android: freccetta = c'è un sottomenù, separatore = l'interruttore è un tocco a parte
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = "Canali",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VerticalDivider(modifier = Modifier.height(32.dp).padding(horizontal = 12.dp))

        MacSyncSwitch(checked = appItem.isEnabled, onCheckedChange = onCheckedChange)
    }
}