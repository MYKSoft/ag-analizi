package com.myksoft.aganalizi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.cell.*
import cz.mroczis.netmonster.core.model.signal.*
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import cz.mroczis.netmonster.core.model.connection.SecondaryConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
    val isAllReady: Boolean = false,
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
    val nrBands: String = "",
    val isNrUncertain: Boolean = false,
)

data class SpeedTestState(
    val isRunning: Boolean = false,
    val ping: String = "",
    val download: String = "0.0",
    val upload: String = "0.0",
    val maxDownload: String = "0.0",
    val avgDownload: String = "0.0",
    val maxUpload: String = "0.0",
    val avgUpload: String = "0.0",
    val downloadGraphData: List<Float> = emptyList(),
    val uploadGraphData: List<Float> = emptyList(),
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

    private var currentSsid: String? = null
    private var cachedIsp: String? = null

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
            if (fromCaps == null || (fromCaps.ssid == WifiManager.UNKNOWN_SSID)) {
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
        
        if (ssid != currentSsid) {
            currentSsid = ssid
            cachedIsp = null // Reset cache on SSID change
            fetchWifiIsp()
        }
        
        val details = mutableMapOf<Int, String>()
        
        cachedIsp?.let {
            details[R.string.label_isp] = it
        }
        
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

    private fun fetchWifiIsp() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://ipapi.co/org/")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val isp = reader.readLine()
                reader.close()
                if (!isp.isNullOrBlank()) {
                    cachedIsp = isp
                }
            } catch (e: Exception) {
                // Fallback or ignore
            }
        }
    }

    private fun updateMobileState(context: Context, primaryCell: ICell, allCells: List<ICell>, time: String) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val netMonster = NetMonsterFactory.get(context)
        
        // Step 1: Verify Network Type via NetMonster
        val networkTypeStr = try {
            netMonster.getNetworkType(-1).toString()
        } catch (_: SecurityException) {
            "Unknown"
        }
        val isNsaAccordingToNetMonster = networkTypeStr.contains("Nsa", ignoreCase = true)
        
        val operatorNameFromMap = primaryCell.network?.let { getOperatorName(it.mcc, it.mnc) } ?: ""
        val operatorName = if (operatorNameFromMap.isNotEmpty()) operatorNameFromMap else tm.networkOperatorName.ifEmpty { "Operatör" }
        
        val techDetails = mutableMapOf<Int, String>()
        val nrDetails = mutableMapOf<Int, String>()
        
        var dbm = primaryCell.signal?.dbm ?: -1
        var cellId = "N/A"
        var lteBandsSummary = ""
        var nrBandsSummary = ""
        var isNrUncertain = false

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

                val signal = primaryCell.signal
                techDetails[R.string.signal_strength] = "${signal.rsrp ?: "N/A"} dBm"
                techDetails[R.string.label_rsrq] = "${signal.rsrq ?: "N/A"} dB"
                techDetails[R.string.label_rssi] = "${signal.rssi ?: "N/A"} dBm"
                techDetails[R.string.label_snr] = "${signal.snr ?: "N/A"} dB"
                signal.timingAdvance?.let {
                    val meters = it * 78 // Approx 78 meters per TA unit
                    techDetails[R.string.label_ta] = "$it ($meters m)"
                }

                if (nrCell != null) {
                    // We found an NR cell (either active or recovered)
                    nrDetails[R.string.label_pci] = nrCell.pci?.toString() ?: "N/A"
                    nrDetails[R.string.label_tac] = nrCell.tac?.toString() ?: "N/A"
                    nrDetails[R.string.label_nrarfcn] = nrCell.band?.channelNumber?.toString() ?: "N/A"
                    
                    val bandInfo = CellUtils.getNrBandInfo(nrCell.band?.channelNumber)
                    nrDetails[R.string.label_band] = bandInfo.band
                    
                    nrBandsSummary = if (nrCell.band?.number == 0 || bandInfo.band == "!" || bandInfo.band == "n??") {
                        isNrUncertain = true
                        context.getString(R.string.nsa_5g_uncertain)
                    } else {
                        "5G • NSA ${bandInfo.band} ${bandInfo.frequency}"
                    }
                    
                    val nrSignal = nrCell.signal
                    nrDetails[R.string.label_ssrsrp] = "${nrSignal.ssRsrp ?: "N/A"} dBm"
                    nrDetails[R.string.label_ssrsrq] = "${nrSignal.ssRsrq ?: "N/A"} dB"
                    nrDetails[R.string.label_sssnr] = "${nrSignal.ssSinr ?: "N/A"} dB"
                } else if (isNsaAccordingToNetMonster) {
                    // Step 4: Full Fallback (No NR cell object but system says NSA)
                    nrBandsSummary = context.getString(R.string.nsa_5g_uncertain)
                    nrDetails[R.string.label_band] = "0"
                    isNrUncertain = true
                }
            }
            is CellNr -> {
                cellId = primaryCell.nci?.toString() ?: "N/A"
                val signal = primaryCell.signal
                dbm = signal.ssRsrp ?: dbm
                
                techDetails[R.string.label_pci] = primaryCell.pci?.toString() ?: "N/A"
                techDetails[R.string.label_tac] = primaryCell.tac?.toString() ?: "N/A"
                primaryCell.band?.let { 
                    val bandInfo = CellUtils.getNrBandInfo(it.channelNumber)
                    techDetails[R.string.label_nrarfcn] = it.channelNumber.toString()
                    techDetails[R.string.label_band] = bandInfo.band
                    nrBandsSummary = if (it.number == 0) {
                        isNrUncertain = true
                        context.getString(R.string.sa_5g_uncertain)
                    } else {
                        "5G • SA ${bandInfo.band} ${bandInfo.frequency}"
                    }
                } ?: run {
                    isNrUncertain = true
                    nrBandsSummary = context.getString(R.string.sa_5g_uncertain)
                }
                
                techDetails[R.string.label_ssrsrp] = "${signal.ssRsrp ?: "N/A"} dBm"
                techDetails[R.string.label_ssrsrq] = "${signal.ssRsrq ?: "N/A"} dB"
                techDetails[R.string.label_sssnr] = "${signal.ssSinr ?: "N/A"} dB"
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
            nrBands = nrBandsSummary,
            isNrUncertain = isNrUncertain
        )
    }

    private fun getNetworkTypeRes(primaryCell: ICell, allCells: List<ICell>): Int {
        val isNr = allCells.any { it is CellNr }
        return when (primaryCell) {
            is CellNr -> R.string.sa_5g
            is CellLte -> if (isNr) R.string.nsa_5g else R.string.lte_4g
            is CellWcdma -> R.string.type_3g
            is CellGsm -> R.string.type_2g
            else -> if (isNr) R.string.nsa_5g else R.string.unknown
        }
    }

    private fun getOperatorName(mcc: String, mnc: String): String = when ("$mcc$mnc") {
        // Turkey
        "28601" -> "Turkcell"
        "28602" -> "Vodafone TR"
        "28603" -> "Türk Telekom"
        "28604" -> "BIMcell"
        
        // USA
        "310260", "310160", "310200", "310210", "310220", "310230", "310240", "310250" -> "T-Mobile"
        "310410", "310030", "310070", "310150", "310170", "310380", "310560", "310680" -> "AT&T"
        "311480", "310010", "310012", "310013", "310110" -> "Verizon"
        
        // India
        "40445", "40554", "40555", "40556" -> "Airtel"
        "40420", "405840", "405841", "405874" -> "Jio"
        "40411", "40410", "40404" -> "Vi (Vodafone Idea)"
        "40434", "40466" -> "BSNL"
        
        // China
        "46000", "46002", "46007", "46008" -> "China Mobile"
        "46001", "46006", "46009" -> "China Unicom"
        "46003", "46005", "46011" -> "China Telecom"
        "46015" -> "China Broadnet"
        
        // Saudi Arabia
        "42001" -> "STC"
        "42003" -> "Mobily"
        "42004" -> "Zain SA"
        "42005" -> "Virgin Mobile"
        
        // UAE
        "42402" -> "Etisalat"
        "42403" -> "du"
        
        // UK
        "23410" -> "O2"
        "23415" -> "Vodafone UK"
        "23420" -> "Three"
        "23430", "23433" -> "EE"
        
        else -> "" // Return empty to use TelephonyManager's name as fallback
    }

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
            _speedTestState.value = SpeedTestState(isRunning = true, statusTextRes = R.string.measuring)
            
            try {
                // Step 1: Ping (5 seconds)
                _speedTestState.value = _speedTestState.value.copy(statusTextRes = R.string.measuring)
                val ping = measurePing()
                
                // Final ping update
                _speedTestState.value = _speedTestState.value.copy(ping = ping, progress = 0.1f)
                
                // Wait 1 second and RESET gauge
                delay(1000)
                _speedTestState.value = _speedTestState.value.copy(progress = 0.11f) // 0.11 will be "wait" state
                
                // Step 2: Download
                _speedTestState.value = _speedTestState.value.copy(statusTextRes = R.string.measuring)
                runDownloadTest()
                
                // Wait 1 second and RESET gauge
                delay(1000)
                _speedTestState.value = _speedTestState.value.copy(progress = 0.61f) // 0.61 will be "wait" state
                
                // Step 3: Upload
                _speedTestState.value = _speedTestState.value.copy(statusTextRes = R.string.measuring)
                runUploadTest()
                
                _speedTestState.value = _speedTestState.value.copy(
                    isRunning = false,
                    statusTextRes = R.string.speed_test_completed,
                    progress = 1.0f
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _speedTestState.value = SpeedTestState()
            }
        }
    }

    private suspend fun measurePing(): String {
        val startTime = System.currentTimeMillis()
        val duration = 5000L
        val pingValues = mutableListOf<Int>()
        
        while (System.currentTimeMillis() - startTime < duration) {
            yield()
            try {
                val pStartTime = System.currentTimeMillis()
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 1500)
                val pEndTime = System.currentTimeMillis()
                socket.close()
                
                val p = (pEndTime - pStartTime).toInt()
                pingValues.add(p)
                _speedTestState.value = _speedTestState.value.copy(
                    ping = p.toString(),
                    progress = ((System.currentTimeMillis() - startTime).toFloat() / duration) * 0.1f
                )
            } catch (e: Exception) { }
            delay(400)
        }
        
        return if (pingValues.isEmpty()) "0" else pingValues.average().toInt().toString()
    }

    private suspend fun runDownloadTest(): String {
        val testUrl = "https://speed.cloudflare.com/__down?bytes=50000000"
        val startTime = System.currentTimeMillis()
        var totalBytes = 0L
        val duration = 10000L
        val speedValues = mutableListOf<Double>()
        
        return try {
            val url = URL(testUrl)
            val connection = withContext(Dispatchers.IO) { url.openConnection() } as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024) // Smaller buffer
                var read: Int
                var lastUpdate = 0L
                while (System.currentTimeMillis() - startTime < duration) {
                    yield()
                    read = input.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 0 && elapsed - lastUpdate > 30) { // Update faster
                        val speedMbps = (totalBytes * 8 / 1_000_000.0) / (elapsed / 1000.0)
                        speedValues.add(speedMbps)
                        
                        val max = speedValues.maxOrNull() ?: 0.0
                        val avg = speedValues.average()
                        
                        _speedTestState.value = _speedTestState.value.copy(
                            download = String.format(Locale.US, "%.2f", speedMbps),
                            maxDownload = String.format(Locale.US, "%.2f", max),
                            avgDownload = String.format(Locale.US, "%.2f", avg),
                            downloadGraphData = speedValues.takeLast(60).map { it.toFloat() },
                            progress = 0.15f + (elapsed.toFloat() / duration * 0.45f)
                        )
                        lastUpdate = elapsed
                    }
                }
            }
            val totalElapsed = System.currentTimeMillis() - startTime
            val finalSpeed = (totalBytes * 8 / 1_000_000.0) / (totalElapsed / 1000.0)
            String.format(Locale.US, "%.2f", finalSpeed)
        } catch (e: Exception) { "0.00" }
    }

    private suspend fun runUploadTest(): String {
        val testUrl = "https://speed.cloudflare.com/__up"
        val startTime = System.currentTimeMillis()
        var totalBytes = 0L
        val duration = 10000L
        val speedValues = mutableListOf<Double>()
        
        return try {
            val url = URL(testUrl)
            val connection = withContext(Dispatchers.IO) { url.openConnection() } as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            // Setting a fixed length if we can estimate, or just remove chunked if possible
            // But Cloudflare up likes streaming. Let's use a very large chunk for better flow.
            connection.setChunkedStreamingMode(128 * 1024) 
            
            connection.outputStream.use { output ->
                val buffer = ByteArray(32 * 1024) 
                var lastUpdate = 0L
                while (System.currentTimeMillis() - startTime < duration) {
                    yield()
                    output.write(buffer)
                    totalBytes += buffer.size
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 0 && elapsed - lastUpdate > 50) { 
                        val speedMbps = (totalBytes * 8 / 1_000_000.0) / (elapsed / 1000.0)
                        speedValues.add(speedMbps)
                        
                        val displaySpeed = if (speedValues.size > 3) speedValues.takeLast(3).average() else speedMbps
                        
                        val max = speedValues.maxOrNull() ?: 0.0
                        val avg = speedValues.average()
                        
                        _speedTestState.value = _speedTestState.value.copy(
                            upload = String.format(Locale.US, "%.2f", displaySpeed),
                            maxUpload = String.format(Locale.US, "%.2f", max),
                            avgUpload = String.format(Locale.US, "%.2f", avg),
                            uploadGraphData = speedValues.takeLast(60).map { it.toFloat() },
                            progress = 0.65f + (elapsed.toFloat() / duration * 0.35f)
                        )
                        lastUpdate = elapsed
                    }
                }
            }
            val totalElapsed = System.currentTimeMillis() - startTime
            val finalSpeed = (totalBytes * 8 / 1_000_000.0) / (totalElapsed / 1000.0)
            String.format(Locale.US, "%.2f", finalSpeed)
        } catch (e: Exception) { "0.00" }
    }
}
