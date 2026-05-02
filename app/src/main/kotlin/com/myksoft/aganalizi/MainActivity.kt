package com.myksoft.aganalizi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.myksoft.aganalizi.ui.theme.NetworkAnalyzerTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val viewModel: NetworkViewModel by viewModels()

    private fun applyLanguage(langCode: String) {
        val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(langCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            viewModel.addLog("Tüm izinler kullanıcı tarafından onaylandı.")
        }
    }

    companion object {
        val logBuffer = StringBuilder()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // İlk açılışta dil kontrolü
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.contains("lang")) {
            val systemLang = Locale.getDefault().language
            val supported = listOf("tr", "en", "hi", "zh", "ar")
            val target = if (supported.contains(systemLang)) systemLang else "en"
            prefs.edit { putString("lang", target) }
            applyLanguage(target)
        } else {
            prefs.getString("lang", "en")?.let { applyLanguage(it) }
        }

        viewModel.startMonitoring(this)
        
        setContent {
            NetworkAnalyzerTheme {
                val systemState by viewModel.systemState.collectAsState()
                var autoRequestAttempted by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val permissions = arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    )
                    val allGranted = permissions.all {
                        checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (!allGranted) {
                        requestPermissionLauncher.launch(permissions)
                    }
                    autoRequestAttempted = true
                }
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (systemState.isAllReady) {
                        NetworkAnalyzerScreen(viewModel)
                    } else if (autoRequestAttempted) {
                        OnboardingScreen(
                            systemState = systemState,
                            onRequestPermissions = {
                                requestPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.READ_PHONE_STATE
                                    )
                                )
                            },
                            onOpenGpsSettings = {
                                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            }
                        ) {
                            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(
    systemState: SystemState,
    onRequestPermissions: () -> Unit,
    onOpenGpsSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(R.string.setup_required),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            stringResource(R.string.setup_description),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        RequirementItem(
            title = stringResource(R.string.permission_location_phone),
            description = stringResource(R.string.permission_location_phone_desc),
            isMet = systemState.permissionsGranted,
            onClick = onRequestPermissions
        )

        RequirementItem(
            title = stringResource(R.string.gps_location_service),
            description = stringResource(R.string.gps_location_service_desc),
            isMet = systemState.gpsEnabled,
            onClick = onOpenGpsSettings
        )

        RequirementItem(
            title = stringResource(R.string.internet_connection),
            description = stringResource(R.string.internet_connection_desc),
            isMet = systemState.internetConnected,
            onClick = onOpenNetworkSettings
        )
        
        Spacer(modifier = Modifier.height(40.dp))
        
        if (systemState.permissionsGranted && systemState.gpsEnabled && !systemState.internetConnected) {
            Text(
                stringResource(R.string.no_internet_warning),
                color = Color(0xFFE60000),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun RequirementItem(title: String, description: String, isMet: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMet) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = if (isMet) Color(0xFF2E7D32) else Color(0xFFE65100))
                Text(description, fontSize = 12.sp, color = Color.Gray)
            }
            if (isMet) {
                Text("✅", fontSize = 24.sp)
            } else {
                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(stringResource(R.string.enable), fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkAnalyzerScreen(viewModel: NetworkViewModel) {
    val networkState by viewModel.networkState.collectAsState()
    val speedTestState by viewModel.speedTestState.collectAsState()
    val logs by viewModel.logs.collectAsState()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    var showLanguagePicker by remember { mutableStateOf(value = false) }

    val languages = listOf(
        "tr" to "🇹🇷 Türkçe",
        "en" to "🇺🇸 English",
        "hi" to "🇮🇳 हिन्दी",
        "zh" to "🇨🇳 中文",
        "ar" to "🇸🇦 العربية"
    )

    if (showLanguagePicker) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showLanguagePicker = false },
            title = { Text(stringResource(R.string.select_language)) },
            text = {
                Column {
                    languages.forEach { (code, name) ->
                        TextButton(
                            onClick = {
                                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                                prefs.edit { putString("lang", code) }
                                
                                val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(code)
                                AppCompatDelegate.setApplicationLocales(appLocale)
                                
                                showLanguagePicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(name, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(
                                stringResource(R.string.live_update, networkState.lastUpdateTime),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showLanguagePicker = true }) {
                            Text("🌐", fontSize = 24.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("📡", fontSize = 20.sp) },
                    label = { Text(stringResource(R.string.tab_analysis), fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🚀", fontSize = 20.sp) },
                    label = { Text(stringResource(R.string.tab_speed), fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("ℹ️", fontSize = 20.sp) },
                    label = { Text(stringResource(R.string.tab_about), fontSize = 12.sp) }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> AnalysisTab(networkState)
                1 -> SpeedTestTab(
                    state = speedTestState,
                    onStart = viewModel::runSpeedTest,
                    onStop = viewModel::stopSpeedTest
                )
                2 -> AboutTab(logs)
            }
        }
    }
}

@Composable
fun AnalysisTab(state: NetworkState) {
    var showNsaInfo by remember { mutableStateOf(false) }

    if (showNsaInfo) {
        AlertDialog(
            onDismissRequest = { showNsaInfo = false },
            title = { Text(stringResource(R.string.nsa_uncertain_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.nsa_uncertain_desc))
                    Text(stringResource(R.string.nsa_uncertain_reasons), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = {
                TextButton(onClick = { showNsaInfo = false }) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp
    val scaleFactor = (screenHeight / 800f).coerceIn(0.85f, 1.3f)
    
    val scrollState = rememberScrollState()
    
    // Scroll hint logic
    val showScrollHint = scrollState.value < 50 && scrollState.maxValue > 0
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Üst bilgi kartı - Artık sadece içeriği kadar yer kaplıyor
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        state.isWifi -> Color(0xFF05BEC8)
                        state.operatorName.lowercase().contains("turkcell") -> Color(0xFF0055A5)
                        state.operatorName.lowercase().contains("vodafone") -> Color(0xFFE60000)
                        state.operatorName.lowercase().contains("türk telekom") -> Color(0xFF003366)
                        state.operatorName.lowercase().contains("verizon") -> Color(0xFFCD040B)
                        state.operatorName.lowercase().contains("t-mobile") -> Color(0xFFE20074)
                        state.operatorName.lowercase().contains("at&t") -> Color(0xFF00A8E0)
                        state.operatorName.lowercase().contains("airtel") -> Color(0xFFED1C24)
                        state.operatorName.lowercase().contains("jio") -> Color(0xFF0066CC)
                        state.operatorName.lowercase().contains("china mobile") -> Color(0xFF0061B2)
                        else -> Color(0xFFE60000) // Default Red
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (state.isWifi) stringResource(R.string.wifi_network) else stringResource(R.string.cellular_network),
                                fontSize = (11 * scaleFactor).sp,
                                color = Color.White.copy(alpha = 0.8f),
                                lineHeight = (12 * scaleFactor).sp
                            )
                            if (!state.isWifi) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = Color.White.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = state.networkTypeRes?.let { stringResource(it) } ?: state.networkType,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = (10 * scaleFactor).sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (state.isWifi) state.wifiSsid else state.operatorName,
                            fontSize = (26 * scaleFactor).sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            lineHeight = (30 * scaleFactor).sp
                        )
                        if (!state.isWifi) {
                            if (state.lteBands.isNotEmpty()) {
                                Text(
                                    text = state.lteBands,
                                    fontSize = (14 * scaleFactor).sp,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                            if (state.nrBands.isNotEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = state.nrBands,
                                        fontSize = (14 * scaleFactor).sp,
                                        color = Color.White.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        }
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.signal_strength), fontSize = (10 * scaleFactor).sp, color = Color.White.copy(alpha = 0.7f))
                            Text(
                                text = "${if (state.isWifi) state.wifiRssi else state.dbm} dBm",
                                fontSize = (20 * scaleFactor).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(state.signalQualityRes?.let { stringResource(it) } ?: state.signalQuality, fontSize = (12 * scaleFactor).sp, color = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (state.isWifi) stringResource(R.string.link_speed_label) else stringResource(R.string.cell_id),
                                fontSize = (10 * scaleFactor).sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                text = if (state.isWifi) "${state.wifiLinkSpeed} Mbps" else state.cellId,
                                fontSize = (20 * scaleFactor).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Teknik detaylar bölümü
            DetailSection(
                title = stringResource(R.string.label_technical_details),
                details = (if (state.isWifi) state.wifiDetails else state.technicalDetails).mapKeys { stringResource(it.key) },
                scaleFactor = scaleFactor,
                bandTag = if (!state.isWifi) state.technicalDetails[R.string.label_band]?.replace("B", "") else null
            )

            // Alt detay bölümü (Sadece 5G NSA varsa)
            if (state.nrDetails.isNotEmpty()) {
                DetailSection(
                    title = stringResource(R.string.label_5g_nsa), 
                    details = state.nrDetails.mapKeys { stringResource(it.key) }, 
                    scaleFactor = scaleFactor,
                    bandTag = state.nrDetails[R.string.label_band]?.replace("n", ""),
                    uncertainDescription = if (state.isNrUncertain) {
                        stringResource(R.string.nsa_uncertain_desc) + "\n\n" + stringResource(R.string.nsa_uncertain_reasons)
                    } else null
                )
            }
            
            // Extra padding at the bottom to ensure last card is readable above hint
            Spacer(modifier = Modifier.height(32.dp))
        }
        
        // Pulsating Scroll Hint
        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollHint,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "hint")
            val translateY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "y"
            )
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Daha Fazla Bilgi İçin Kaydırın",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.graphicsLayer(translationY = translateY)
                )
                Text(
                    text = "▼",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.graphicsLayer(translationY = translateY)
                )
            }
        }
    }
}

@Composable
fun DetailSection(
    title: String, 
    details: Map<String, String>, 
    modifier: Modifier = Modifier, 
    scaleFactor: Float, 
    bandTag: String? = null,
    uncertainDescription: String? = null
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = (11 * scaleFactor).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (bandTag != null && bandTag != "0") {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(4.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E7D32))
                ) {
                    Text(
                        text = "Band: $bandTag",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = (10 * scaleFactor).sp,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF0F0F0))
        ) {
            val items = details.toList()
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uncertainDescription != null) {
                    Text(
                        text = uncertainDescription,
                        fontSize = (14 * scaleFactor).sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        lineHeight = (18 * scaleFactor).sp
                    )
                }
                for (i in items.indices step 2) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DetailItem(items[i].first, items[i].second, scaleFactor, Modifier.weight(1f))
                        if ((i + 1 < items.size)) {
                            DetailItem(items[i+1].first, items[i+1].second, scaleFactor, Modifier.weight(1f))
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailItem(label: String, value: String, scaleFactor: Float, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = (10 * scaleFactor).sp, color = Color.Gray, maxLines = 1)
        Text(value, fontSize = (14 * scaleFactor).sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1)
    }
}

@Composable
fun SpeedTestTab(state: SpeedTestState, onStart: () -> Unit, onStop: () -> Unit) {
    var showWarning by remember { mutableStateOf(value = false) }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.data_usage_warning_title)) },
            text = { Text(stringResource(R.string.data_usage_warning_desc)) },
            confirmButton = {
                Button(onClick = {
                    showWarning = false
                    onStart()
                }) { Text(stringResource(R.string.start)) }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFE8F5E9), Color.White)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.network_performance_test),
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF2E7D32)
        )
        Text(
            stringResource(R.string.measure_speed_desc),
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.weight(0.1f))

        // --- SPEED GAUGE ---
        val currentSpeedValue = when {
            !state.isRunning -> 0f
            state.progress < 0.11f -> state.ping.toFloatOrNull() ?: 0f
            state.progress in 0.11f..0.15f -> 0f // Reset period
            state.progress < 0.61f -> state.download.toFloatOrNull() ?: 0f
            state.progress in 0.61f..0.65f -> 0f // Reset period
            else -> state.upload.toFloatOrNull() ?: 0f
        }
        val animatedSpeed by animateFloatAsState(
            targetValue = currentSpeedValue,
            animationSpec = tween(if (currentSpeedValue == 0f) 200 else 500, easing = LinearOutSlowInEasing),
            label = "speed"
        )
        
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
            SpeedGauge(speed = animatedSpeed, progress = state.progress)
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.isRunning) {
                    val displayValue = when {
                        state.progress < 0.11f -> state.ping
                        state.progress in 0.11f..0.15f -> ""
                        state.progress < 0.61f -> state.download
                        state.progress in 0.61f..0.65f -> ""
                        else -> state.upload
                    }
                    
                    if (displayValue.isNotEmpty()) {
                        Text(
                            text = displayValue,
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF333333),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (state.progress < 0.11f) "ms" else "Mb/s",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                    }
                    state.statusTextRes?.let {
                        val statusText = if (it == R.string.speed_test_completed) stringResource(R.string.measuring) else stringResource(it)
                        Text(
                            statusText,
                            fontSize = 11.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else if (state.statusTextRes != null) {
                    // Test finished, show Modern Checkmark
                    ModernCheckmark()
                } else {
                    Text("0.00", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color.LightGray.copy(alpha = 0.5f))
                    Text("Mb/s", fontSize = 16.sp, color = Color.LightGray.copy(alpha = 0.5f))
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // --- STAT CARDS ---
        Row(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatCard(
                label = "Ping",
                value = state.ping,
                max = "-",
                avg = "-",
                graphData = emptyList(),
                color = Color(0xFFFF9800),
                modifier = Modifier.weight(1f),
                isActive = state.isRunning && state.progress < 0.11f,
                unit = "ms"
            )
            StatCard(
                label = stringResource(R.string.rx_speed),
                value = state.download,
                max = state.maxDownload,
                avg = state.avgDownload,
                graphData = state.downloadGraphData,
                color = Color(0xFF2196F3),
                modifier = Modifier.weight(1f),
                isActive = state.isRunning && state.progress in 0.15f..0.61f,
                unit = "Mb/s"
            )
            StatCard(
                label = stringResource(R.string.tx_speed),
                value = state.upload,
                max = state.maxUpload,
                avg = state.avgUpload,
                graphData = state.uploadGraphData,
                color = Color(0xFF4CAF50),
                modifier = Modifier.weight(1f),
                isActive = state.isRunning && state.progress > 0.65f,
                unit = "Mb/s"
            )
        }
        
        Spacer(modifier = Modifier.weight(0.1f))

        if (state.isRunning) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60000))
            ) {
                Text(stringResource(R.string.stop_test), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } else {
            Button(
                onClick = { showWarning = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF795548))
            ) {
                Text(stringResource(R.string.start_test), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun ModernCheckmark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(100.dp)
            .background(Color(0xFFE8F5E9), CircleShape)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = Path().apply {
                moveTo(size.width * 0.2f, size.height * 0.5f)
                lineTo(size.width * 0.45f, size.height * 0.75f)
                lineTo(size.width * 0.8f, size.height * 0.25f)
            }
            drawPath(
                path = path,
                color = Color(0xFF2E7D32),
                style = Stroke(width = 12f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            )
        }
    }
}

@Composable
fun SpeedGauge(speed: Float, progress: Float) {
    val sweepAngle = 240f
    val startAngle = 150f
    
    // Scale points to match labels exactly
    val scalePoints = listOf(0f, 5f, 10f, 20f, 30f, 50f, 100f, 200f, 500f, 1000f, 5000f, 10000f)
    
    val targetLevel = if (speed <= 0f) 0f else {
        var foundIndex = 0
        for (i in 0 until scalePoints.size - 1) {
            if (speed >= scalePoints[i] && speed < scalePoints[i+1]) {
                foundIndex = i
                break
            }
        }
        if (speed >= scalePoints.last()) 1f
        else {
            val baseLevel = foundIndex.toFloat() / (scalePoints.size - 1)
            val nextLevel = (foundIndex + 1).toFloat() / (scalePoints.size - 1)
            val ratio = (speed - scalePoints[foundIndex]) / (scalePoints[foundIndex+1] - scalePoints[foundIndex])
            baseLevel + (nextLevel - baseLevel) * ratio
        }
    }.coerceIn(0f, 1f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerOffset = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2.5f
        
        // Background track
        drawArc(
            color = Color.LightGray.copy(alpha = 0.3f),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(width = size.minDimension * 0.06f, cap = StrokeCap.Round)
        )
        
        // Active progress track
        drawArc(
            brush = Brush.sweepGradient(
                0f to Color(0xFF4CAF50),
                0.5f to Color(0xFF8BC34A),
                1f to Color(0xFFCDDC39)
            ),
            startAngle = startAngle,
            sweepAngle = sweepAngle * targetLevel,
            useCenter = false,
            style = Stroke(width = size.minDimension * 0.06f, cap = StrokeCap.Round)
        )
        
        // Ticks and Labels
        val tickCount = 12
        val labels = listOf("0", "5", "10", "20", "30", "50", "100", "200", "500", "1Gb", "5Gb", "10Gb")
        
        for (i in 0 until tickCount) {
            val angle = startAngle + (i.toFloat() / (tickCount - 1)) * sweepAngle
            val angleRad = angle * (PI / 180f).toFloat()
            
            val innerTick = radius - (size.minDimension * 0.04f)
            val outerTick = radius + (size.minDimension * 0.015f)
            
            drawLine(
                color = Color.Gray,
                start = androidx.compose.ui.geometry.Offset(
                    centerOffset.x + cos(angleRad) * innerTick,
                    centerOffset.y + sin(angleRad) * innerTick
                ),
                end = androidx.compose.ui.geometry.Offset(
                    centerOffset.x + cos(angleRad) * outerTick,
                    centerOffset.y + sin(angleRad) * outerTick
                ),
                strokeWidth = 2f
            )
            
            // Draw labels
            drawContext.canvas.nativeCanvas.apply {
                val x = centerOffset.x + cos(angleRad) * (radius + (size.minDimension * 0.08f))
                val y = centerOffset.y + sin(angleRad) * (radius + (size.minDimension * 0.08f))
                
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = size.minDimension * 0.04f
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                drawText(labels[i], x, y + (size.minDimension * 0.01f), paint)
            }
        }
    }
}

@Composable
fun StatCard(
    label: String, 
    value: String, 
    max: String, 
    avg: String, 
    graphData: List<Float>, 
    color: Color, 
    modifier: Modifier,
    isActive: Boolean,
    unit: String
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(if (isActive) 4.dp else 1.dp),
        border = if (isActive) androidx.compose.foundation.BorderStroke(2.dp, color.copy(alpha = 0.5f)) else null
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
                Spacer(modifier = Modifier.width(4.dp))
                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray, maxLines = 1)
            }
            
            Text(text = "$value", fontSize = 14.sp, fontWeight = FontWeight.Black, color = color, maxLines = 1)
            Text(unit, fontSize = 8.sp, color = Color.Gray)
            
            if (max != "-") {
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Maks", fontSize = 7.sp, color = Color.Gray)
                        Text(max, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Ort", fontSize = 7.sp, color = Color.Gray)
                        Text(avg, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Sparkline Graph
            if (graphData.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxWidth().height(35.dp)) {
                    if (graphData.size > 1) {
                        val maxValue = (graphData.maxOrNull() ?: 1f).coerceAtLeast(1f)
                        val path = Path()
                        graphData.forEachIndexed { index, valF ->
                            val x = (index.toFloat() / (graphData.size - 1)) * size.width
                            val y = size.height - (valF / maxValue) * size.height
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path = path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
                        
                        // Add transparent fill
                        val fillPath = Path().apply {
                            addPath(path)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(fillPath, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.2f), Color.Transparent)))
                    }
                }
            } else if (label == "Ping") {
                Spacer(modifier = Modifier.height(10.dp))
                Text("Anlık Ölçüm", fontSize = 8.sp, color = Color.Gray, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
fun AboutTab(logs: List<String>) {
    val context = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }

    if (showLogs) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showLogs = false }) {
                    Text("⬅️")
                }
                Text(stringResource(R.string.system_logs), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            LogsTab(logs)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(32.dp))
                    Text("🚀", fontSize = 48.sp)
                    Text(
                        stringResource(R.string.app_name),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    val versionName = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
                    } catch (_: Exception) { "1.0.0" }
                    
                    Text(stringResource(R.string.version, versionName), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Text(
                            stringResource(R.string.about_description),
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.developer), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("MYK Soft", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Mustafa Yaşar KAR", fontSize = 13.sp)
                    Text("mustafa.yasar.kar@gmail.com", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showLogs = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(stringResource(R.string.logs_button), fontSize = 12.sp)
                        }
                        Button(
                            onClick = { sendFeedback(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(stringResource(R.string.support_button), fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/Myasarkar/ag-analizi/blob/main/PRIVACY_POLICY.md".toUri())
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.privacy_policy_button), fontSize = 12.sp)
                    }
                    
                    Text(stringResource(R.string.copyright), fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

private fun sendFeedback(context: Context) {
    try {
        val logs = MainActivity.logBuffer.toString()
        val file = File(context.cacheDir, "network_logs_feedback.txt")
        FileOutputStream(file).use {
            it.write(logs.toByteArray())
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf("mustafa.yasar.kar@gmail.com"))
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedback_subject))
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.feedback_body, android.os.Build.MODEL, android.os.Build.VERSION.RELEASE))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(emailIntent, context.getString(R.string.choose_feedback_app)))
    } catch (_: Exception) {
    }
}

@Composable
fun LogsTab(logs: List<String>) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        items(logs) { log ->
            Text(
                text = log,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
        }
    }
}
