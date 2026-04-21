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
    val networkTypeRes: Int? = null,
    val dbm: Int = -1,
    val cellId: String = "N/A",
    val wifiSsid: String = "N/A",
    val wifiRssi: Int = 0,
    val wifiLinkSpeed: Int = 0,
    val signalQuality: String = "",
    val signalQualityRes: Int? = null,
    val technicalDetails: Map<Int, String> = emptyMap(),
    val nrDetails: Map<Int, String> = emptyMap(),
    val wifiDetails: Map<Int, String> = emptyMap(),
    val lastUpdateTime: String = "",
    val lteBands: String = "",
    val nrBands: String = ""
)

data class SpeedTestState(
    val isRunning: Boolean = false,
    val ping: String = "",
    val pingUnit: String = "ms",
    val download: String = "",
    val downloadUnit: String = "Mbps",
    val upload: String = "",
    val uploadUnit: String = "Mbps",
    val progress: Float = 0f,
    val statusTextRes: Int? = null
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

    fun addLog(resId: Int, vararg args: Any) {
        val msg = try {
            // we need context for resource strings, but for now let's just log names or handle it in activity
            "Log: $resId" 
        } catch (e: Exception) { "Log Error" }
        addLog(msg)
    }

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
        
        @Suppress("DEPRECATION")
        val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val fromCaps = capabilities?.transportInfo as? android.net.wifi.WifiInfo
            if (fromCaps == null || fromCaps.ssid == WifiManager.UNKNOWN_SSID) {
                wifiManager.connectionInfo
            } else {
                fromCaps
            }
        } else {
            wifiManager.connectionInfo
        }
        
        var ssid = wifiInfo?.ssid?.replace("\"", "") ?: context.getString(R.string.unknown)
        if (ssid == WifiManager.UNKNOWN_SSID) {
            ssid = context.getString(R.string.hidden_ssid)
        }
        
        val details = mutableMapOf<Int, String>()
        
        linkProperties?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.let {
            details[R.string.local_ip] = it.address.hostAddress ?: context.getString(R.string.unknown)
        }
        
        linkProperties?.dnsServers?.filterIsInstance<java.net.Inet4Address>()?.forEachIndexed { index, inetAddress ->
            val key = if (index == 0) R.string.dns1 else R.string.dns2
            details[key] = inetAddress.hostAddress?.replace("/", "") ?: ""
        }
        
        linkProperties?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.let {
            details[R.string.gateway] = it.hostAddress ?: ""
        }
        
        wifiInfo?.let { info ->
            val freq = info.frequency
            val bandStr = when (freq) {
                in 2412..2484 -> "2.4 GHz"
                in 5170..5825 -> "5 GHz"
                in 5945..7125 -> "6 GHz"
                else -> context.getString(R.string.unknown)
            }
            details[R.string.label_band] = bandStr
            details[R.string.frequency] = "$freq MHz"
            
            val channel = when (freq) {
                in 2412..2484 -> (freq - 2412) / 5 + 1
                in 5170..5825 -> (freq - 5170) / 5 + 34
                else -> 0
            }
            if (channel > 0) details[R.string.channel] = channel.toString()
            details[R.string.bssid] = info.bssid ?: "N/A"
        }
        
        val rssi = wifiInfo?.rssi ?: -127
        val qualityRes = when {
            rssi >= -50 -> R.string.signal_excellent
            rssi >= -60 -> R.string.signal_very_good
            rssi >= -70 -> R.string.signal_good
            rssi >= -80 -> R.string.signal_moderate
            else -> R.string.signal_very_poor
        }

        _networkState.value = NetworkState(
            isWifi = true,
            wifiSsid = ssid,
            wifiRssi = rssi,
            wifiLinkSpeed = wifiInfo?.linkSpeed ?: 0,
            signalQualityRes = qualityRes,
            wifiDetails = details,
            lastUpdateTime = time
        )
    }

    private fun updateMobileState(context: Context, primaryCell: ICell, allCells: List<ICell>, time: String) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val netMonster = NetMonsterFactory.get(context)
        
        // Step 1: Verify Network Type via NetMonster
        val networkTypeStr = netMonster.getNetworkType(-1).toString()
        val isNsaAccordingToNetMonster = networkTypeStr.contains("Nsa", ignoreCase = true)
        
        val operatorName = primaryCell.network?.let { getOperatorName(it.mcc, it.mnc) } ?: tm.networkOperatorName
        
        val techDetails = mutableMapOf<Int, String>()
        val nrDetails = mutableMapOf<Int, String>()
        
        var dbm = primaryCell.signal?.dbm ?: -1
        var cellId = "N/A"
        var lteBandsSummary = ""
        var nrBandsSummary = ""

        // Step 2: Search for Active NR Cell
        var nrCell = allCells.asSequence().filterIsInstance<CellNr>().firstOrNull { 
            it.connectionStatus is PrimaryConnection || it.connectionStatus is SecondaryConnection 
        }

        // Step 3: Cell Recovery (If active NR not found but system says NSA)
        if (nrCell == null && isNsaAccordingToNetMonster) {
            nrCell = allCells.asSequence().filterIsInstance<CellNr>().firstOrNull()
        }

        when (primaryCell) {
            is CellLte -> {
                val eci = primaryCell.eci
                cellId = eci?.toString() ?: "N/A"
                if (eci != null) {
                    techDetails[R.string.label_ci] = eci.toString()
                    techDetails[R.string.label_enb] = (eci / 256).toString()
                    techDetails[R.string.label_cid] = (eci % 256).toString()
                }
                
                techDetails[R.string.label_pci] = primaryCell.pci?.toString() ?: "N/A"
                techDetails[R.string.label_tac] = primaryCell.tac?.toString() ?: "N/A"
                techDetails[R.string.label_bandwidth] = primaryCell.bandwidth?.let { "${it / 1000} MHz" } ?: "N/A"
                
                primaryCell.band?.let { band ->
                    val bandInfo = CellUtils.getLteBandInfo(band.channelNumber)
                    techDetails[R.string.label_earfcn] = band.channelNumber.toString()
                    techDetails[R.string.label_band] = bandInfo.band
                    lteBandsSummary = "4G • LTE ${bandInfo.frequency.replace(" MHz", "")}"
                }

                val signal = primaryCell.signal as? SignalLte
                if (signal != null) {
                    techDetails[R.string.signal_strength] = "${signal.rsrp ?: "N/A"} dBm"
                    techDetails[R.string.label_rsrq] = "${signal.rsrq ?: "N/A"} dB"
                    techDetails[R.string.label_rssi] = "${signal.rssi ?: "N/A"} dBm"
                    techDetails[R.string.label_snr] = "${signal.snr ?: "N/A"} dB"
                    signal.timingAdvance?.let {
                        val meters = it * 78 // Approx 78 meters per TA unit
                        techDetails[R.string.label_ta] = "$it ($meters m)"
                    }
                }

                if (nrCell != null) {
                    // We found an NR cell (either active or recovered)
                    nrDetails[R.string.label_pci] = nrCell.pci?.toString() ?: "N/A"
                    nrDetails[R.string.label_tac] = nrCell.tac?.toString() ?: "N/A"
                    nrDetails[R.string.label_nrarfcn] = nrCell.band?.channelNumber?.toString() ?: "N/A"
                    
                    val bandInfo = CellUtils.getNrBandInfo(nrCell.band?.channelNumber)
                    nrDetails[R.string.label_band] = bandInfo.band
                    
                    if (nrCell.band?.number == 0 || bandInfo.band == "!" || bandInfo.band == "n??") {
                        nrBandsSummary = context.getString(R.string.nsa_5g_uncertain)
                    } else {
                        nrBandsSummary = "5G • NSA ${bandInfo.band} ${bandInfo.frequency}"
                    }
                    
                    val nrSignal = nrCell.signal as? SignalNr
                    if (nrSignal != null) {
                        nrDetails[R.string.label_ssrsrp] = "${nrSignal.ssRsrp ?: "N/A"} dBm"
                        nrDetails[R.string.label_ssrsrq] = "${nrSignal.ssRsrq ?: "N/A"} dB"
                        nrDetails[R.string.label_sssnr] = "${nrSignal.ssSinr ?: "N/A"} dB"
                    }
                } else if (isNsaAccordingToNetMonster) {
                    // Step 4: Full Fallback (No NR cell object but system says NSA)
                    nrBandsSummary = context.getString(R.string.nsa_5g_uncertain)
                    nrDetails[R.string.label_band] = "0"
                }
            }
            is CellNr -> {
                cellId = primaryCell.nci?.toString() ?: "N/A"
                val signal = primaryCell.signal as? SignalNr
                dbm = signal?.ssRsrp ?: dbm
                
                techDetails[R.string.label_pci] = primaryCell.pci?.toString() ?: "N/A"
                techDetails[R.string.label_tac] = primaryCell.tac?.toString() ?: "N/A"
                primaryCell.band?.let { 
                    val bandInfo = CellUtils.getNrBandInfo(it.channelNumber)
                    techDetails[R.string.label_nrarfcn] = it.channelNumber.toString()
                    techDetails[R.string.label_band] = bandInfo.band
                    if (it.number == 0) {
                        nrBandsSummary = context.getString(R.string.sa_5g_uncertain)
                    } else {
                        nrBandsSummary = "5G • SA ${bandInfo.band} ${bandInfo.frequency}"
                    }
                } ?: run {
                    nrBandsSummary = context.getString(R.string.sa_5g_uncertain)
                }
                
                if (signal != null) {
                    techDetails[R.string.label_ssrsrp] = "${signal.ssRsrp ?: "N/A"} dBm"
                    techDetails[R.string.label_ssrsrq] = "${signal.ssRsrq ?: "N/A"} dB"
                    techDetails[R.string.label_sssnr] = "${signal.ssSinr ?: "N/A"} dB"
                }
            }
        }

        val qualityRes = when {
            dbm >= -70 -> R.string.signal_excellent
            dbm >= -85 -> R.string.signal_very_good
            dbm >= -100 -> R.string.signal_good
            dbm >= -110 -> R.string.signal_moderate
            else -> R.string.signal_very_poor
        }

        _networkState.value = NetworkState(
            isWifi = false,
            operatorName = operatorName,
            networkTypeRes = getNetworkTypeRes(primaryCell, allCells),
            dbm = dbm,
            cellId = cellId,
            signalQualityRes = qualityRes,
            technicalDetails = techDetails,
            nrDetails = nrDetails,
            lastUpdateTime = time,
            lteBands = lteBandsSummary,
            nrBands = nrBandsSummary
        )
    }

    private fun getNetworkTypeRes(primaryCell: ICell, allCells: List<ICell>): Int {
        val isNr = allCells.asSequence().any { it is CellNr }
        return when (primaryCell) {
            is CellNr -> R.string.sa_5g
            is CellLte -> if (isNr) R.string.nsa_5g else R.string.lte_4g
            is CellWcdma -> R.string.type_3g
            is CellGsm -> R.string.type_2g
            else -> if (isNr) R.string.nsa_5g else R.string.unknown
        }
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
            statusTextRes = R.string.failed_label,
            progress = 0f
        )
    }

    fun runSpeedTest() {
        if (_speedTestState.value.isRunning) return
        
        speedTestJob = viewModelScope.launch(Dispatchers.IO) {
            _speedTestState.value = SpeedTestState(isRunning = true, statusTextRes = R.string.start)
            
            try {
                // Ping
                val ping = measurePing()
                _speedTestState.value = _speedTestState.value.copy(ping = ping, statusTextRes = R.string.measuring)
                
                // Download
                val download = runDownloadTest { speed, prog ->
                    _speedTestState.value = _speedTestState.value.copy(download = speed, progress = prog * 0.5f)
                }
                
                _speedTestState.value = _speedTestState.value.copy(statusTextRes = R.string.measuring)
                
                // Upload
                val upload = runUploadTest { speed, prog ->
                    _speedTestState.value = _speedTestState.value.copy(upload = speed, progress = 0.5f + prog * 0.5f)
                }
                
                _speedTestState.value = _speedTestState.value.copy(
                    isRunning = false,
                    download = download,
                    upload = upload,
                    statusTextRes = R.string.speed_test_completed,
                    progress = 1f
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
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
