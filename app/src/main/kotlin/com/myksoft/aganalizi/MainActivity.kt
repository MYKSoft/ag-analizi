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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
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
        ActivityResultContracts.RequestMultiplePermissions()
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
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
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
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (systemState.isAllReady) {
                        NetworkAnalyzerScreen(viewModel)
                    } else {
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
                            },
                            onOpenNetworkSettings = {
                                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                            }
                        )
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
    var showLanguagePicker by remember { mutableStateOf(false) }

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
    // Ekran yüksekliğine göre dinamik font ölçeklendirme - alt sınır yükseltildi
    val scaleFactor = (screenHeight / 800f).coerceIn(0.85f, 1.3f)
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Üst bilgi kartı - Artık sadece içeriği kadar yer kaplıyor
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (state.isWifi) Color(0xFF05BEC8) else Color(0xFFE60000)
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
                                if (state.nrBands.contains(stringResource(R.string.nsa_5g_uncertain).split(" ")[0]) || 
                                    state.nrBands.contains("Belirsiz") || state.nrBands.contains("Uncertain")) {
                                    IconButton(
                                        onClick = { showNsaInfo = true },
                                        modifier = Modifier.size((18 * scaleFactor).dp)
                                    ) {
                                        Text("ⓘ", color = Color.White, fontSize = (14 * scaleFactor).sp)
                                    }
                                }
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
                bandTag = state.nrDetails[R.string.label_band]?.replace("n", "")
            )
        }
    }
}

@Composable
fun DetailSection(title: String, details: Map<String, String>, modifier: Modifier = Modifier, scaleFactor: Float, bandTag: String? = null) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = (11 * scaleFactor).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (bandTag != null) {
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
                for (i in items.indices step 2) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DetailItem(items[i].first, items[i].second, scaleFactor, Modifier.weight(1f))
                        if (i + 1 < items.size) {
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
    var showWarning by remember { mutableStateOf(false) }

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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.network_performance_test), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.measure_speed_desc),
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }

        // Hız Gösterge Paneli
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(Color(0xFFF5F5F5)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val valueText = if (state.isRunning) {
                    if (state.progress < 0.5f) state.download.split(" ")[0].ifEmpty { "0.0" }
                    else state.upload.split(" ")[0].ifEmpty { "0.0" }
                } else "0.0"
                
                Text(
                    text = valueText,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Mbps",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
            }
            
            CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 8.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.LightGray.copy(alpha = 0.3f)
            )
        }

        // Detaylı Bilgi Kartları
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SpeedCard("PING", state.ping.ifEmpty { "0" }, "ms", Modifier.weight(1f))
            SpeedCard(stringResource(R.string.rx_speed), state.download.split(" ")[0].ifEmpty { "0.0" }, "Mbps", Modifier.weight(1f))
            SpeedCard(stringResource(R.string.tx_speed), state.upload.split(" ")[0].ifEmpty { "0.0" }, "Mbps", Modifier.weight(1f))
        }

        if (state.isRunning) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = state.statusTextRes?.let { stringResource(it) } ?: "",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60000))
                ) {
                    Text(stringResource(R.string.stop_test), fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Button(
                onClick = { showWarning = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.start_test), fontWeight = FontWeight.Bold)
            }
        }

        // Bilgi Notu
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9C4)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("ℹ️", fontSize = 16.sp)
                Text(
                    stringResource(R.string.speed_test_info_note),
                    fontSize = 11.sp,
                    color = Color(0xFF5D4037)
                )
            }
        }
    }
}

@Composable
fun SpeedCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Text(unit, fontSize = 10.sp, color = Color.Gray)
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
