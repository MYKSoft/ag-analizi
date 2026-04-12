package com.myksoft.aganalizi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.cell.*
import cz.mroczis.netmonster.core.model.signal.*
import cz.mroczis.netmonster.core.model.band.*
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import cz.mroczis.netmonster.core.model.connection.SecondaryConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class SystemState(
    val permissionsGranted: Boolean = false,
    val gpsEnabled: Boolean = false,
    val internetConnected: Boolean = false,
    val isWifiActive: Boolean = false,
    val isMobileDataActive: Boolean = false,
    val isAllReady: Boolean = false
)

data class NetworkState(
    val isWifi: Boolean = false,
    val operatorName: String = "",
    val networkType: String = "",
    val dbm: Int = -1,
    val cellId: String = "N/A",
    val wifiSsid: String = "N/A",
    val wifiRssi: Int = 0,
    val wifiLinkSpeed: Int = 0,
    val signalQuality: String = "Bilinmiyor",
    val technicalDetails: Map<String, String> = emptyMap(),
    val nrDetails: Map<String, String> = emptyMap(),
    val wifiDetails: Map<String, String> = emptyMap(),
    val lastUpdateTime: String = ""
)

data class SpeedTestState(
    val isRunning: Boolean = false,
    val ping: String = "Bekleniyor...",
    val download: String = "Bekleniyor...",
    val upload: String = "Bekleniyor...",
    val progress: Float = 0f,
    val statusText: String = ""
)

class NetworkViewModel : ViewModel() {

    private val _networkState = MutableStateFlow(NetworkState())
    val networkState = _networkState.asStateFlow()

    private val _systemState = MutableStateFlow(SystemState())
    val systemState = _systemState.asStateFlow()

    private val _speedTestState = MutableStateFlow(SpeedTestState())
    val speedTestState = _speedTestState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private var isMonitoring = false

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val newLog = "[$time] $msg"
        MainActivity.logBuffer.append(newLog).append("\n")
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, newLog)
        if (currentLogs.size > 500) {
            _logs.value = currentLogs.take(400)
        } else {
            _logs.value = currentLogs
        }
    }

    fun startMonitoring(context: Context) {
        if (isMonitoring) return
        isMonitoring = true
        viewModelScope.launch {
            while (isMonitoring) {
                checkSystemState(context)
                if (_systemState.value.isAllReady) {
                    updateState(context)
                }
                delay(1000)
            }
        }
    }

    private fun checkSystemState(context: Context) {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        val permissionsGranted = permissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isMobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val internetConnected = isWifi || isMobile

        _systemState.value = SystemState(
            permissionsGranted = permissionsGranted,
            gpsEnabled = gpsEnabled,
            internetConnected = internetConnected,
            isWifiActive = isWifi,
            isMobileDataActive = isMobile,
            isAllReady = permissionsGranted && gpsEnabled && internetConnected
        )
    }

    @SuppressLint("MissingPermission")
    private fun updateState(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val netMonster = NetMonsterFactory.get(context)
        val cells = netMonster.getCells()
        val primaryCell = cells.firstOrNull { it.connectionStatus is PrimaryConnection } ?: cells.firstOrNull()

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        if (isWifi) {
            updateWifiState(context, time)
        } else if (primaryCell != null) {
            updateMobileState(context, primaryCell, cells, time)
        } else {
            _networkState.value = NetworkState(
                lastUpdateTime = time
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateWifiState(context: Context, time: String) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
        
        // WifiInfo'yu alma - Modern ve yedekli yöntem
        @Suppress("DEPRECATION")
        val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ConnectivityManager üzerinden deneme
            val fromCaps = capabilities?.transportInfo as? android.net.wifi.WifiInfo
            if (fromCaps == null || fromCaps.ssid == android.net.wifi.WifiManager.UNKNOWN_SSID) {
                // Eğer modern yöntem sonuç vermezse eski yöntemle (deprecated) tekrar dene
                wifiManager.connectionInfo
            } else {
                fromCaps
            }
        } else {
            wifiManager.connectionInfo
        }
        
        var ssid = wifiInfo?.ssid?.replace("\"", "") ?: "Bilinmiyor"
        if (ssid == android.net.wifi.WifiManager.UNKNOWN_SSID || ssid == "<unknown ssid>") {
            ssid = "Erişim Yok (Konum Kapalı olabilir)"
        }
        
        val details = mutableMapOf<String, String>()
        
        // IP Bilgileri (LinkProperties üzerinden - Modern Yol)
        linkProperties?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.let {
            details["Yerel IP"] = it.address.hostAddress ?: "Bilinmiyor"
        }
        
        linkProperties?.dnsServers?.forEachIndexed { index, inetAddress ->
            details["DNS ${index + 1}"] = inetAddress.hostAddress ?: ""
        }
        
        linkProperties?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.let {
            details["Ağ Geçidi"] = it.hostAddress ?: ""
        }
        
        // Diğer Wi-Fi detayları (wifiInfo üzerinden)
        wifiInfo?.let { info ->
            val freq = info.frequency
            val band = when {
                freq in 2412..2484 -> "2.4 GHz"
                freq in 5170..5825 -> "5 GHz"
                freq in 5945..7125 -> "6 GHz"
                else -> "Bilinmiyor"
            }
            details["Bant"] = band
            details["Frekans"] = "$freq MHz"
            
            val channel = if (freq in 2412..2484) (freq - 2412) / 5 + 1 
                         else if (freq in 5170..5825) (freq - 5170) / 5 + 34 
                         else 0
            if (channel > 0) details["Kanal"] = "$channel"
            details["BSSID"] = info.bssid ?: "N/A"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val standard = when (info.wifiStandard) {
                    ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
                    ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
                    ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
                    else -> "Legacy"
                }
                details["Standart"] = standard
            }
        }
        
        val rssi = wifiInfo?.rssi ?: -127
        val quality = when {
            rssi >= -50 -> "Mükemmel"
            rssi >= -60 -> "Çok İyi"
            rssi >= -70 -> "İyi"
            rssi >= -80 -> "Kötü"
            else -> "Çok Kötü"
        }

        _networkState.value = NetworkState(
            isWifi = true,
            wifiSsid = ssid,
            wifiRssi = rssi,
            wifiLinkSpeed = wifiInfo?.linkSpeed ?: 0,
            signalQuality = quality,
            wifiDetails = details,
            lastUpdateTime = time
        )
    }

    private fun updateMobileState(context: Context, primaryCell: ICell, allCells: List<ICell>, time: String) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val operatorName = primaryCell.network?.let { getOperatorName(it.mcc, it.mnc) } ?: tm.networkOperatorName
        
        val techDetails = mutableMapOf<String, String>()
        val nrDetails = mutableMapOf<String, String>()
        
        var dbm = primaryCell.signal?.dbm ?: -1
        var cellId = "Bilinmiyor"
        
        val quality = when {
            dbm >= -70 -> "Mükemmel"
            dbm >= -85 -> "Çok İyi"
            dbm >= -100 -> "İyi"
            dbm >= -110 -> "Kötü"
            dbm != -1 -> "Çok Kötü"
            else -> "Bilinmiyor"
        }

        when (primaryCell) {
            is CellLte -> {
                cellId = primaryCell.eci?.toString() ?: "N/A"
                techDetails["CI"] = primaryCell.eci?.toString() ?: "N/A"
                techDetails["eNb"] = primaryCell.enb?.toString() ?: "N/A"
                techDetails["CID"] = primaryCell.cid?.toString() ?: "N/A"
                techDetails["TAC"] = primaryCell.tac?.toString() ?: "N/A"
                techDetails["PCI"] = primaryCell.pci?.toString() ?: "N/A"
                
                primaryCell.band?.let { band ->
                    techDetails["EARFCN"] = band.channelNumber.toString()
                    techDetails["Bant"] = "B${band.number} (${band.name})"
                }

                val signal = primaryCell.signal as? SignalLte
                if (signal != null) {
                    dbm = signal.rsrp?.toInt() ?: dbm
                    techDetails["RSSI"] = "${signal.rssi ?: "N/A"} dBm"
                    techDetails["RSRP"] = "${signal.rsrp ?: "N/A"} dBm"
                    techDetails["RSRQ"] = "${signal.rsrq ?: "N/A"} dB"
                    techDetails["SNR"] = "${signal.snr ?: "N/A"} dB"
                    signal.timingAdvance?.let { 
                        val distance = it * 78 // Her TA birimi yaklaşık 78m
                        techDetails["TA"] = "$it (~$distance m)"
                    }
                }
                
                // Bant Genişliği (BW) - NetMonster core modelinde bazen birden fazla bant bilgisi olabilir
                val bandwidths = allCells.filterIsInstance<CellLte>()
                    .mapNotNull { it.band?.name?.substringAfterLast(" ") } // Basit bir yaklaşım
                if (bandwidths.isNotEmpty()) {
                    techDetails["BW"] = bandwidths.distinct().joinToString(" + ") + " MHz"
                }

                // 5G NSA Kontrolü
                val nrCell = allCells.filterIsInstance<CellNr>().firstOrNull { 
                    it.connectionStatus is PrimaryConnection || it.connectionStatus is SecondaryConnection 
                } ?: allCells.filterIsInstance<CellNr>().firstOrNull()
                
                if (nrCell != null) {
                    nrDetails["TAC"] = nrCell.tac?.toString() ?: "N/A"
                    nrDetails["PCI"] = nrCell.pci?.toString() ?: "N/A"
                    nrDetails["ARFCN"] = nrCell.band?.channelNumber?.toString() ?: "N/A"
                    nrCell.band?.let { nrDetails["Bant"] = "n${it.number}" }
                    
                    val nrSignal = nrCell.signal as? SignalNr
                    if (nrSignal != null) {
                        nrDetails["SS-RSRP"] = "${nrSignal.ssRsrp ?: "N/A"} dBm"
                        nrDetails["SS-RSRQ"] = "${nrSignal.ssRsrq ?: "N/A"} dB"
                        nrDetails["SS-SNR"] = "${nrSignal.ssSinr ?: "N/A"} dB"
                    }
                }
            }
            is CellNr -> {
                cellId = primaryCell.nci?.toString() ?: "N/A"
                val signal = primaryCell.signal as? SignalNr
                dbm = signal?.ssRsrp ?: dbm
                
                techDetails["NCI"] = primaryCell.nci?.toString() ?: "N/A"
                techDetails["PCI"] = primaryCell.pci?.toString() ?: "N/A"
                techDetails["TAC"] = primaryCell.tac?.toString() ?: "N/A"
                primaryCell.band?.let { 
                    techDetails["ARFCN"] = it.channelNumber.toString()
                    techDetails["Bant"] = "n${it.number}" 
                }
                
                if (signal != null) {
                    techDetails["SS-RSRP"] = "${signal.ssRsrp ?: "N/A"} dBm"
                    techDetails["SS-RSRQ"] = "${signal.ssRsrq ?: "N/A"} dB"
                    techDetails["SS-SINR"] = "${signal.ssSinr ?: "N/A"} dB"
                }
            }
        }

        _networkState.value = NetworkState(
            isWifi = false,
            operatorName = operatorName,
            networkType = getNetworkTypeString(primaryCell, allCells),
            dbm = dbm,
            cellId = cellId,
            signalQuality = quality,
            technicalDetails = techDetails,
            nrDetails = nrDetails,
            lastUpdateTime = time
        )
    }

    private fun getNetworkTypeString(primaryCell: ICell, allCells: List<ICell>): String {
        val isNr = allCells.any { it is CellNr }
        return when (primaryCell) {
            is CellNr -> "5G (SA)"
            is CellLte -> if (isNr) "5G (NSA)" else "4G (LTE)"
            is CellWcdma -> "3G"
            is CellGsm -> "2G"
            else -> "Bilinmiyor"
        }
    }

    private fun getOperatorName(mcc: String, mnc: String): String = when ("$mcc$mnc") {
        "28601" -> "Turkcell"
        "28602" -> "Vodafone TR"
        "28603" -> "Türk Telekom"
        else -> "Operatör ($mcc$mnc)"
    }

    private fun formatIp(ip: Int): String = if (ip == 0) "0.0.0.0" else
        "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"

    private var speedTestJob: kotlinx.coroutines.Job? = null

    fun stopSpeedTest() {
        speedTestJob?.cancel()
        _speedTestState.value = _speedTestState.value.copy(
            isRunning = false,
            statusText = "Durduruldu",
            progress = 0f
        )
        addLog("Hız testi kullanıcı tarafından durduruldu.")
    }

    fun runSpeedTest() {
        if (_speedTestState.value.isRunning) return
        
        speedTestJob = viewModelScope.launch(Dispatchers.IO) {
            _speedTestState.value = SpeedTestState(isRunning = true, statusText = "Başlatılıyor...")
            
            try {
                // Ping
                val ping = measurePing()
                _speedTestState.value = _speedTestState.value.copy(ping = ping, statusText = "İndirme Ölçülüyor...")
                
                // Download
                val download = runDownloadTest { speed, prog ->
                    _speedTestState.value = _speedTestState.value.copy(download = speed, progress = prog * 0.5f)
                }
                
                _speedTestState.value = _speedTestState.value.copy(statusText = "Yükleme Ölçülüyor...")
                
                // Upload
                val upload = runUploadTest { speed, prog ->
                    _speedTestState.value = _speedTestState.value.copy(upload = speed, progress = 0.5f + prog * 0.5f)
                }
                
                _speedTestState.value = _speedTestState.value.copy(
                    isRunning = false,
                    download = download,
                    upload = upload,
                    statusText = "Tamamlandı",
                    progress = 1f
                )
                addLog("Hız Testi: Ping $ping, İndirme $download, Yükleme $upload")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job iptal edildiğinde burası çalışır
            }
        }
    }

    private fun measurePing(): String {
        return try {
            val process = Runtime.getRuntime().exec("ping -c 3 8.8.8.8")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var avgPing = "Hata"
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("min/avg/max")) {
                        avgPing = line.split("=")[1].trim().split("/")[1].substringBefore(".") + " ms"
                    }
                }
            }
            avgPing
        } catch (e: Exception) { "Hata" }
    }

    private suspend fun runDownloadTest(onUpdate: (String, Float) -> Unit): String {
        val testUrl = "https://speed.cloudflare.com/__down?bytes=50000000"
        val startTime = System.currentTimeMillis()
        var totalBytes = 0L
        val duration = 10000L
        
        return try {
            val url = URL(testUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            connection.inputStream.use { input ->
                val buffer = ByteArray(128 * 1024) // 128KB buffer for better stability
                var read: Int
                while (System.currentTimeMillis() - startTime < duration) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    read = input.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 0) {
                        val speedMbps = (totalBytes * 8 / 1_000_000.0) / (elapsed / 1000.0)
                        onUpdate(String.format(Locale.US, "%.1f Mbps", speedMbps), elapsed.toFloat() / duration)
                    }
                }
            }
            val totalElapsed = System.currentTimeMillis() - startTime
            String.format(Locale.US, "%.1f Mbps", (totalBytes * 8 / 1_000_000.0) / (totalElapsed / 1000.0))
        } catch (e: Exception) { "Hata" }
    }

    private suspend fun runUploadTest(onUpdate: (String, Float) -> Unit): String {
        val testUrl = "https://speed.cloudflare.com/__up"
        val startTime = System.currentTimeMillis()
        var totalBytes = 0L
        val duration = 10000L
        
        return try {
            val url = URL(testUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setChunkedStreamingMode(64 * 1024)
            
            connection.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                while (System.currentTimeMillis() - startTime < duration) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    output.write(buffer)
                    totalBytes += buffer.size
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 0) {
                        val speedMbps = (totalBytes * 8 / 1_000_000.0) / (elapsed / 1000.0)
                        onUpdate(String.format(Locale.US, "%.1f Mbps", speedMbps), elapsed.toFloat() / duration)
                    }
                }
            }
            val totalElapsed = System.currentTimeMillis() - startTime
            String.format(Locale.US, "%.1f Mbps", (totalBytes * 8 / 1_000_000.0) / (totalElapsed / 1000.0))
        } catch (e: Exception) { "Hata" }
    }
}
