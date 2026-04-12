package com.myksoft.aganalizi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.myksoft.aganalizi.ui.theme.NetworkAnalyzerTheme
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private val viewModel: NetworkViewModel by viewModels()

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
            "Kurulum Gerekiyor",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Uygulamanın çalışabilmesi için aşağıdaki izinler ve bağlantılar gereklidir.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.outline
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        // İzin Durumu
        RequirementItem(
            title = "Konum ve Telefon İzni",
            description = "Şebeke sinyal bilgilerini okumak için gereklidir.",
            isMet = systemState.permissionsGranted,
            onClick = onRequestPermissions
        )

        // GPS Durumu
        RequirementItem(
            title = "GPS / Konum Servisi",
            description = "Baz istasyonu konum analizi için GPS açık olmalıdır.",
            isMet = systemState.gpsEnabled,
            onClick = onOpenGpsSettings
        )

        // İnternet Durumu
        RequirementItem(
            title = "İnternet Bağlantısı",
            description = "Wi-Fi veya Mobil Veri aktif olmalıdır.",
            isMet = systemState.internetConnected,
            onClick = onOpenNetworkSettings
        )
        
        Spacer(modifier = Modifier.height(40.dp))
        
        if (systemState.permissionsGranted && systemState.gpsEnabled && !systemState.internetConnected) {
            Text(
                "İnternete bağlı değilsiniz. Lütfen Wi-Fi veya Mobil Veriyi açın.",
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
                    Text("Etkinleştir", fontSize = 12.sp)
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

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Ağ Analizi", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(
                                "CANLI • ${networkState.lastUpdateTime}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
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
                    label = { Text("Analiz", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🚀", fontSize = 20.sp) },
                    label = { Text("Hız", fontSize = 12.sp) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("ℹ️", fontSize = 20.sp) },
                    label = { Text("Hakkında", fontSize = 12.sp) }
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        // Ekran yüksekliğine göre dinamik font ölçeklendirme - alt sınır yükseltildi
        val scaleFactor = (screenHeight.value / 800f).coerceIn(0.85f, 1.3f)
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Üst Bilgi Kartı - Artık sadece içeriği kadar yer kaplıyor
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
                                text = if (state.isWifi) "Wi-Fi Şebeke" else "Hücresel Şebeke",
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
                                        text = state.networkType,
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
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SİNYAL GÜCÜ", fontSize = (10 * scaleFactor).sp, color = Color.White.copy(alpha = 0.7f))
                            Text(
                                text = "${if (state.isWifi) state.wifiRssi else state.dbm} dBm",
                                fontSize = (20 * scaleFactor).sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(state.signalQuality, fontSize = (12 * scaleFactor).sp, color = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (state.isWifi) "BAĞLANTI HIZI" else "HÜCRE ID",
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

            // Teknik Detaylar Bölümü
            DetailSection(
                title = "TEKNİK DETAYLAR",
                details = if (state.isWifi) state.wifiDetails else state.technicalDetails,
                scaleFactor = scaleFactor
            )

            // Alt Detay Bölümü (Sadece 5G NSA varsa)
            if (state.nrDetails.isNotEmpty()) {
                DetailSection(title = "5G NSA", details = state.nrDetails, scaleFactor = scaleFactor)
            }
        }
    }
}

@Composable
fun DetailSection(title: String, details: Map<String, String>, modifier: Modifier = Modifier, scaleFactor: Float) {
    Column(modifier = modifier) {
        Text(
            text = title,
            fontSize = (11 * scaleFactor).sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 4.dp)
        )
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
            title = { Text("Veri Kullanımı Uyarısı") },
            text = { Text("Hız testi yüksek miktarda mobil veri tüketebilir. Devam etmek istiyor musunuz?") },
            confirmButton = {
                Button(onClick = {
                    showWarning = false
                    onStart()
                }) { Text("Başlat") }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) { Text("İptal") }
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
            Text("Ağ Performans Testi", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Bağlantı hızınızı ve gecikme sürenizi ölçün",
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
                    if (state.progress < 0.5f) state.download.split(" ")[0]
                    else state.upload.split(" ")[0]
                } else "0.0"
                
                Text(
                    text = valueText,
                    fontSize = if (valueText.contains("Bekleniyor")) 22.sp else 42.sp,
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
            SpeedCard("PING", state.ping, "ms", Modifier.weight(1f))
            SpeedCard("İNDİRME", state.download.split(" ")[0], "Mbps", Modifier.weight(1f))
            SpeedCard("YÜKLEME", state.upload.split(" ")[0], "Mbps", Modifier.weight(1f))
        }

        if (state.isRunning) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = state.statusText,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE60000))
                ) {
                    Text("Testi Durdur", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Button(
                onClick = { showWarning = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Testi Başlat", fontWeight = FontWeight.Bold)
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
                    "Doğru sonuçlar için test sırasında aktif indirme yapmadığınızdan emin olun.",
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
                Text("Sistem Logları", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            LogsTab(logs)
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("🚀", fontSize = 48.sp)
                Text(
                    "Ağ Analizi",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                val versionName = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (e: Exception) { "1.0.0" }
                
                Text("Versiyon $versionName", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        "Mobil ve Wi-Fi ağlarını teknik düzeyde analiz etmek, sinyal kalitesi ve performans ölçümü için geliştirilmiştir.",
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
                Text("Geliştirici", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
                        Text("📜 Loglar", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { sendFeedback(context) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("📧 Destek", fontSize = 12.sp)
                    }
                }

                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Myasarkar/ag-analizi/blob/main/PRIVACY_POLICY.md"))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("🛡️ Gizlilik Politikası", fontSize = 12.sp)
                }
                
                Text("MYK Soft © 2024", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
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

        val selectorIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
        }

        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf("mustafa.yasar.kar@gmail.com"))
            putExtra(Intent.EXTRA_SUBJECT, "Ağ Analizi Uygulaması Geri Bildirim")
            putExtra(Intent.EXTRA_TEXT, "Merhaba,\n\nUygulama hakkındaki geri bildirimim aşağıdadır:\n\n[Buraya mesajınızı yazın]\n\n--- Sistem Logları Ektedir ---\n\nCihaz: ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selector = selectorIntent
        }
        
        context.startActivity(Intent.createChooser(emailIntent, "Geri Bildirim Gönder"))
    } catch (e: Exception) {
        e.printStackTrace()
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

@Composable
fun SpeedItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 16.sp
    )
}
