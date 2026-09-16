package com.totof.mywifiscan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.totof.mywifiscan.ui.theme.MyWifiScanTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyWifiScanTheme {
                var selectedNetwork by remember { mutableStateOf<WifiNetwork?>(null) }
                var selectedDevice by remember { mutableStateOf<Device?>(null) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (selectedNetwork == null && selectedDevice == null) {
                            TimeHeader()
                        }
                    },
                    containerColor = if (selectedNetwork != null || selectedDevice != null) Color.Black else MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    WifiScannerScreen(
                        modifier = Modifier.padding(innerPadding),
                        selectedNetwork = selectedNetwork,
                        onNetworkSelected = { selectedNetwork = it },
                        selectedDevice = selectedDevice,
                        onDeviceSelected = { selectedDevice = it }
                    )
                }
            }
        }
    }
}

@Composable
fun WifiScannerScreen(
    modifier: Modifier = Modifier,
    selectedNetwork: WifiNetwork?,
    onNetworkSelected: (WifiNetwork?) -> Unit,
    selectedDevice: Device?,
    onDeviceSelected: (Device?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { NetworkScanner(context) }

    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var wifiNetworks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var scanMode by remember { mutableIntStateOf(0) } // 0: Appareils, 1: Bornes WIFI

    var isScanning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isLocationEnabled by remember { mutableStateOf(true) }
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isLocationEnabled = locationManager.isLocationEnabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (selectedNetwork != null) {
        SignalMeterScreen(
            network = selectedNetwork,
            scanner = scanner,
            onBack = { onNetworkSelected(null) }
        )
    } else if (selectedDevice != null) {
        DeviceLatencyMeterScreen(
            device = selectedDevice,
            scanner = scanner,
            onBack = { onDeviceSelected(null) }
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SecondaryTabRow(selectedTabIndex = scanMode) {
                Tab(selected = scanMode == 0, onClick = { scanMode = 0 }) {
                    Text("Appareils", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = scanMode == 1, onClick = { scanMode = 1 }) {
                    Text("Bornes WIFI", modifier = Modifier.padding(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (scanMode == 1 && !isLocationEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Android n'autorise le scan WIFI que si la localisation est activée",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Activer la localisation")
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (!hasPermission) {
                        Toast.makeText(context, "Permissions requises", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (scanMode == 1 && !locationManager.isLocationEnabled) {
                        errorMessage = "Impossible de scanner les réseaux sans localisation"
                        wifiNetworks = emptyList()
                        return@Button
                    }
                    if (!scanner.isWifiConnected()) {
                        errorMessage = "Vous n'êtes pas connecté au WIFI"
                        devices = emptyList()
                        wifiNetworks = emptyList()
                        return@Button
                    }

                    errorMessage = null
                    isScanning = true
                    scope.launch {
                        if (scanMode == 0) {
                            devices = scanner.scanNetwork()
                        } else {
                            wifiNetworks = scanner.scanWifiNetworks()
                        }
                        isScanning = false
                        if ((scanMode == 0 && devices.isEmpty()) || (scanMode == 1 && wifiNetworks.isEmpty())) {
                            errorMessage = "Aucun résultat trouvé ou erreur lors du scan."
                        }
                    }
                },
                enabled = !isScanning
            ) {
                Text(if (isScanning) "Scan en cours..." else "Lancer le scan")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isScanning) {
                CircularProgressIndicator()
            }

            errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (scanMode == 0) {
                    items(devices) { device ->
                        DeviceItem(device, onClick = { onDeviceSelected(device) })
                    }
                } else {
                    items(wifiNetworks) { network ->
                        WifiNetworkItem(network, onClick = { onNetworkSelected(network) })
                    }
                }
            }
        }
    }
}

@Composable
fun WifiNetworkItem(network: WifiNetwork, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2D2A33) // Dark background similar to image
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = "SSID: ${network.ssid.ifEmpty { "fontanilibus" }}", // Using image example if empty
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "BSSID: ${network.bssid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray
                    )
                    Text(
                        text = "Canal: ${frequencyToChannel(network.frequency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                    Text(
                        text = "RSSI: ${network.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Fréquence: ${network.frequency} MHz",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = parseSecurity(network.capabilities),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = Color(0xFFA699E6) // Purple/lavender color for WPA2
                    )
                }
            }
        }
    }
}

fun frequencyToChannel(freq: Int): Int {
    return when(freq) {
        2484 -> 14
        in 2412..2472 -> (freq - 2412) / 5 + 1
        in 5170..5825 -> (freq - 5170) / 5 + 34
        else -> 0
    }
}

fun parseSecurity(capabilities: String): String {
    return when {
        capabilities.contains("WPA3", ignoreCase = true) -> "WPA3"
        capabilities.contains("WPA2", ignoreCase = true) -> "WPA2"
        capabilities.contains("WPA", ignoreCase = true) -> "WPA"
        capabilities.contains("WEP", ignoreCase = true) -> "WEP"
        else -> "Ouvert"
    }
}

@Composable
fun SignalMeterScreen(network: WifiNetwork, scanner: NetworkScanner, onBack: () -> Unit) {
    var currentRssi by remember { mutableIntStateOf(network.rssi) }

    LaunchedEffect(Unit) {
        while (true) {
            currentRssi = scanner.getLatestRssi(network.bssid)
            delay(1.seconds)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Retour",
                    tint = Color.White
                )
            }
            Text(
                text = "Détail Signal",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(48.dp)) // To balance the back button
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = network.ssid.ifEmpty { "fontanilibus" },
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Text(
            text = network.bssid,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
        
        Spacer(modifier = Modifier.height(48.dp))

        SignalGauge(rssi = currentRssi)

        Spacer(modifier = Modifier.weight(1f))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2A33)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                InfoRow(label = "Type de Box", value = "Inconnu")
                InfoRow(label = "Protection", value = parseSecurity(network.capabilities))
                InfoRow(label = "Canal", value = frequencyToChannel(network.frequency).toString())
                InfoRow(label = "Fréquence", value = "${network.frequency} MHz")
                InfoRow(label = "Signal", value = "$currentRssi dBm")
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
fun SignalGauge(rssi: Int) {
    val animatedRssi by animateFloatAsState(targetValue = rssi.toFloat(), label = "rssi")
    
    // RSSI range: -100 to -30 (70 dBm range)
    val progress = ((animatedRssi + 100).coerceIn(0f, 70f) / 70f)
    
    // Sweep 270 degrees, from 135 to 405 (which is 135 + 270)
    val startAngle = 135f
    val sweepAngle = 270f
    
    Box(
        modifier = Modifier.size(280.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 20.dp.toPx()
            val innerRadius = size.width / 2 - strokeWidth
            val center = Offset(size.width / 2, size.height / 2)
            
            // Background arc (gray)
            drawArc(
                color = Color.DarkGray.copy(alpha = 0.5f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            
            // Progress arc (yellow)
            drawArc(
                color = Color.Yellow,
                startAngle = startAngle,
                sweepAngle = progress * sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            
            // Ticks
            val tickCount = 12
            for (i in 0..tickCount) {
                val angleDeg = startAngle + (i.toFloat() / tickCount) * sweepAngle
                val angleRad = angleDeg * PI.toFloat() / 180f
                val tickStart = innerRadius - 10.dp.toPx()
                val tickEnd = innerRadius - 25.dp.toPx()
                
                drawLine(
                    color = Color.White.copy(alpha = 0.5f),
                    start = Offset(
                        center.x + tickStart * cos(angleRad),
                        center.y + tickStart * sin(angleRad)
                    ),
                    end = Offset(
                        center.x + tickEnd * cos(angleRad),
                        center.y + tickEnd * sin(angleRad)
                    ),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$rssi",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 64.sp
                ),
                color = Color.White
            )
            Text(
                text = "dBm",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
        }
    }
}

@Composable
fun DeviceLatencyMeterScreen(device: Device, scanner: NetworkScanner, onBack: () -> Unit) {
    var latency by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(Unit) {
        while (true) {
            latency = scanner.measureLatency(device.ip)
            delay(1.seconds)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = device.name,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(text = device.ip, style = MaterialTheme.typography.bodyMedium)
        
        Spacer(modifier = Modifier.height(32.dp))

        LatencyGauge(latency = latency)

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = if (latency >= 0) "$latency ms" else "Inatteignable",
            style = MaterialTheme.typography.displayMedium,
            color = getLatencyColor(latency)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(onClick = onBack) {
            Text("Retour à la liste")
        }
    }
}

@Composable
fun LatencyGauge(latency: Long) {
    val displayLatency = if (latency < 0) 1000f else latency.toFloat()
    val animatedLatency by animateFloatAsState(targetValue = displayLatency, label = "latency")
    
    // Map Latency (0 to 500ms) to angle (0 to 180)
    // 500ms+ is Left (Weak), 0ms is Right (Strong)
    val progress = (1f - (animatedLatency.coerceIn(0f, 500f) / 500f))
    val targetAngle = progress * 180f

    GaugeCanvas(targetAngle = targetAngle)
}

@Composable
fun GaugeCanvas(targetAngle: Float) {
    Box(
        modifier = Modifier
            .size(280.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 15.dp.toPx()
            val center = Offset(size.width / 2, size.height * 0.8f)
            val radius = size.width / 2

            // Arc background
            drawArc(
                color = Color.LightGray.copy(alpha = 0.3f),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(0f, size.height * 0.8f - radius),
                size = Size(size.width, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Needle
            val needleAngleRad = (180f + targetAngle) * PI.toFloat() / 180f
            val needleLen = radius - 20.dp.toPx()
            drawLine(
                color = Color.DarkGray,
                start = center,
                end = Offset(
                    center.x + needleLen * cos(needleAngleRad),
                    center.y + needleLen * sin(needleAngleRad)
                ),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
            
            drawCircle(Color.DarkGray, radius = 10.dp.toPx(), center = center)
        }
    }
}

fun getLatencyColor(latency: Long): Color {
    if (latency < 0) return Color.Gray
    return when {
        latency < 50 -> Color(0xFF4CAF50) // Green
        latency < 150 -> Color(0xFFFFC107) // Yellow
        else -> Color(0xFFF44336) // Red
    }
}

@Composable
fun TimeHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.bandeau),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        Text(
            text = "scanner mon réseau",
            style = MaterialTheme.typography.titleLarge,
            color = Color.Black
        )
    }
}

@Composable
fun DeviceItem(device: Device, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Nom: ${device.name}", style = MaterialTheme.typography.bodyLarge)
            Text(text = "IP: ${device.ip}", style = MaterialTheme.typography.bodyMedium)
            
            device.model?.let {
                if (it.isNotBlank()) {
                    Text(text = "Modèle: $it", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (device.os != "Inconnu") {
                Text(
                    text = "OS: ${device.os}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (!device.mac.contains("Inconnu") && device.mac.isNotBlank()) {
                Text(text = "MAC: ${device.mac}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
