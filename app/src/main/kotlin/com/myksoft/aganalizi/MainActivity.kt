package com.myksoft.aganalizi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.myksoft.aganalizi.databinding.ActivityMainBinding
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.cell.*
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import cz.mroczis.netmonster.core.model.connection.SecondaryConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val updateInterval = 500L
    private var isRunning = false
    private var isSpeedTestRunning = false
    
    private val netMonster by lazy { NetMonsterFactory.get(this) }

    companion object {
        val logBuffer = StringBuilder()
        fun addLog(msg: String) {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logBuffer.insert(0, "[$time] $msg\n")
            if (logBuffer.length > 50000) {
                logBuffer.setLength(40000)
            }
        }
    }

    private val previousValues = mutableMapOf<String, Int>()
    private val gridValueViews = mutableMapOf<android.widget.GridLayout, MutableMap<String, TextView>>()
    private var currentHubType: String? = null
    
    // Responsive sizing variables
    private var screenWidth = 0
    private var screenHeight = 0
    private var isLandscape = false
    private var responsiveScale = 1.0f

    // Logging state
    private var lastLoggedNetworkType: String? = null
    private var lastLoggedCellId: String? = null
    private var lastLoggedSsid: String? = null
    private var lastLoggedDbm: Int? = null
    private var lastLoggedWifiRssi: Int? = null

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full screen mode and edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Force layout to ignore system bar paddings
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            insets
        }
        
        updateScreenMetrics()

        binding.btnRequestPermissions.setOnClickListener {
            checkPermissions()
        }

        binding.btnOpenSettings.setOnClickListener {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = android.net.Uri.fromParts("package", packageName, null)
            intent.data = uri
            startActivity(intent)
        }

        checkPermissions()
        startUpdates()
        updateDeviceInfo()

        binding.btnAbout.setOnClickListener {
            val intent = android.content.Intent(this, AboutActivity::class.java)
            startActivity(intent)
        }
        
        setupSpeedTest()
        setupInfoButtons()

        addLog(getString(R.string.app_started))
    }

    private fun updateScreenMetrics() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        val density = metrics.density
        val widthDp = screenWidth / density
        val heightDp = screenHeight / density
        
        // Calculate scale factors for both dimensions
        // Standard phone is roughly 360x640 dp
        val horizontalScale = widthDp / 360f
        val verticalScale = heightDp / 640f
        
        // Use the smaller scale to ensure it fits both ways, but with a floor to keep it readable
        // We are more aggressive now to ensure it fits on one screen
        responsiveScale = (if (isLandscape) verticalScale * 1.2f else Math.min(horizontalScale, verticalScale)).coerceIn(0.65f, 1.1f)
        
        // Adjust grid columns based on width and orientation
        val columns = when {
            isLandscape && widthDp > 600 -> 3
            widthDp < 280 -> 1
            else -> 2
        }
        
        binding.gridTechnicalDetails.columnCount = columns
        binding.grid5GDetails.columnCount = columns
        binding.gridWifiIpDetails.columnCount = columns
        binding.gridWifiSignalQuality.columnCount = columns
        binding.gridWifiChannel.columnCount = columns
        binding.gridWifiSecurity.columnCount = columns
        
        // Adjust main hub text sizes
        binding.txtHubMainValue.textSize = 30f * responsiveScale
        binding.txtHubSubValue1.textSize = 22f * responsiveScale
        binding.txtHubSubValue2.textSize = 22f * responsiveScale
        binding.txtHubSubValue1Description.textSize = 12f * responsiveScale
        
        // Scale performance test text
        binding.txtSpeedPing.textSize = 13f * responsiveScale
        binding.txtSpeedDownload.textSize = 13f * responsiveScale
        binding.txtSpeedUpload.textSize = 13f * responsiveScale
        binding.btnStartSpeedTest.textSize = 13f * responsiveScale
        binding.btnOpenPerformance.textSize = 13f * responsiveScale
        
        // Adjust layout for landscape if needed
        val mainContainer = binding.mainContentContainer
        val activeHub = binding.cardActiveHub
        val secondaryContainer = binding.secondaryContentContainer
        val detailsContainer = binding.detailsContainer
        val performanceHub = binding.containerPerformanceTest

        // Reduce paddings and margins to save space
        val outerPadding = (6 * responsiveScale).toInt()
        val cardMargin = (4 * responsiveScale).toInt()
        
        if (isLandscape) {
            mainContainer.orientation = android.widget.LinearLayout.HORIZONTAL
            mainContainer.setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            
            activeHub.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.1f).apply {
                setMargins(cardMargin, cardMargin, cardMargin, cardMargin)
            }
            
            secondaryContainer.layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2.2f).apply {
                setMargins(cardMargin, cardMargin, cardMargin, cardMargin)
            }
            
            detailsContainer.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            performanceHub.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = cardMargin
            }
        } else {
            mainContainer.orientation = android.widget.LinearLayout.VERTICAL
            mainContainer.setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            
            activeHub.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.8f).apply {
                bottomMargin = cardMargin
            }
            
            secondaryContainer.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.2f)
            
            detailsContainer.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            performanceHub.layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = cardMargin
            }
        }
        
        // Adjust Toolbar height if needed
        binding.toolbar.layoutParams.height = (48 * responsiveScale * density).toInt()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        // Re-render grids to apply new text sizes and column counts
        refreshGrids()
    }

    private fun refreshGrids() {
        // This will force a re-render of the grids on the next update cycle
        gridValueViews.clear()
        binding.gridTechnicalDetails.removeAllViews()
        binding.grid5GDetails.removeAllViews()
        binding.gridWifiIpDetails.removeAllViews()
        binding.gridWifiSignalQuality.removeAllViews()
        binding.gridWifiChannel.removeAllViews()
        binding.gridWifiSecurity.removeAllViews()
    }

    private fun checkPermissions(): Boolean {
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            addLog("İzin isteği sonucu: ${if (allGranted) "Tümü Verildi" else "Eksik İzinler Var"}")
        }
    }

    private fun checkAndLogPermissions(): Boolean {
        var allGranted = true
        permissions.forEach { perm ->
            val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            if (!granted) allGranted = false
        }
        return allGranted
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    private fun startUpdates() {
        if (isRunning) return
        isRunning = true
        lifecycleScope.launch {
            while (isRunning) {
                updateUI()
                delay(updateInterval)
            }
        }
    }

    private fun updateUI() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        val permissionsGranted = checkAndLogPermissions()
        val locationEnabled = isLocationEnabled()
        
        if (!permissionsGranted || !locationEnabled) {
            binding.layoutWaitingPermissions.visibility = View.VISIBLE
            binding.mainContentContainer.visibility = View.GONE
            
            if (currentHubType != "WAITING") {
                addLog("İzinler veya Sensörler bekleniyor. İzinler: $permissionsGranted, Konum: $locationEnabled")
                currentHubType = "WAITING"
            }
            return
        } else {
            binding.layoutWaitingPermissions.visibility = View.GONE
            binding.mainContentContainer.visibility = View.VISIBLE
        }

        val cells = if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            netMonster.getCells()
        } else {
            emptyList()
        }
        
        val hasMobileSignal = cells.isNotEmpty()

        updateTime()

        if (isWifi) {
            showWifiHub()
        } else if (hasMobileSignal) {
            showMobileHub()
        } else {
            showNoConnectionHub()
        }
    }

    private fun updateTime() {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        binding.txtUpdateTime.text = getString(R.string.live_update, sdf.format(Date()))
    }

    private fun showMobileHub() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val cells = netMonster.getCells()
        
        // Find the primary registered cell
        val primaryCell = cells.firstOrNull { it.connectionStatus is PrimaryConnection } ?: cells.firstOrNull()
        
        if (primaryCell == null) {
            showNoConnectionHub()
            return
        }

        val operatorName = (primaryCell.network?.mcc?.let { mcc -> 
            primaryCell.network?.mnc?.let { mnc -> 
                getOperatorName(mcc, mnc)
            }
        } ?: (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName) ?: getString(R.string.unknown)

        applyOperatorTheme(operatorName)

        binding.txtHubTitle.text = getString(R.string.mobile_network)
        binding.txtHubMainValue.text = operatorName
        binding.txtHubSubLabel1.text = getString(R.string.signal_strength)
        binding.txtHubSubLabel2.text = getString(R.string.cell_id)

        binding.containerWifiDetails.visibility = View.GONE
        binding.containerMobileDetails.visibility = View.VISIBLE
        binding.containerPerformanceTest.visibility = View.VISIBLE
        
        if (currentHubType != "MOBILE") {
            clearGrid(binding.gridTechnicalDetails)
            clearGrid(binding.grid5GDetails)
            currentHubType = "MOBILE"
        }

        // Signal Strength & Cell ID
        var dbm = primaryCell.signal?.dbm ?: -1
        
        // For LTE, RSRP is a much better indicator of quality than RSSI/dbm
        if (primaryCell is CellLte) {
            val lteSignal = primaryCell.signal as? cz.mroczis.netmonster.core.model.signal.SignalLte
            lteSignal?.rsrp?.let { dbm = it.toInt() }
        } else if (primaryCell is CellNr) {
            val nrSignal = primaryCell.signal as? cz.mroczis.netmonster.core.model.signal.SignalNr
            nrSignal?.ssRsrp?.let { dbm = it }
        }

        binding.txtHubSubValue1.text = if (dbm != -1) "$dbm dBm" else getString(R.string.not_available)
        binding.txtHubSubValue1Description.text = if (dbm != -1) getSignalQualityDescription(dbm, false) else ""

        val cellId = when (primaryCell) {
            is CellLte -> primaryCell.eci?.toString()
            is CellNr -> primaryCell.nci?.toString()
            is CellWcdma -> primaryCell.cid?.toString()
            is CellGsm -> primaryCell.cid?.toString()
            else -> null
        }
        binding.txtHubSubValue2.text = if (cellId == null || cellId == "2147483647" || cellId == "0") getString(R.string.unknown) else cellId

        // Network Type & 5G Logic
        val networkTypeStr = getNetworkTypeString(primaryCell, cells)
        addDetailRow(binding.gridTechnicalDetails, getString(R.string.network_type), networkTypeStr)

        // Logging
        if (lastLoggedNetworkType != networkTypeStr || lastLoggedCellId != cellId) {
            addLog(getString(R.string.mobile_network_changed, operatorName, networkTypeStr, cellId ?: getString(R.string.unknown)))
            lastLoggedNetworkType = networkTypeStr
            lastLoggedCellId = cellId
        }
        
        if (dbm != -1 && lastLoggedDbm != dbm) {
            addLog(getString(R.string.mobile_signal_strength_log, dbm))
            lastLoggedDbm = dbm
        }

        // Operator Info
        primaryCell.network?.let {
            addDetailRow(binding.gridTechnicalDetails, "MCC", it.mcc)
            addDetailRow(binding.gridTechnicalDetails, "MNC", it.mnc)
        }

        // Technical Details based on Cell Type
        when (primaryCell) {
            is CellLte -> {
                addDetailRow(binding.gridTechnicalDetails, "CI", primaryCell.eci?.toString() ?: "N/A")
                addDetailRow(binding.gridTechnicalDetails, "eNb", (primaryCell.eci?.let { it / 256 })?.toString() ?: "N/A")
                addDetailRow(binding.gridTechnicalDetails, "CID", (primaryCell.eci?.let { it % 256 })?.toString() ?: "N/A")
                addDetailRow(binding.gridTechnicalDetails, getString(R.string.label_tac), primaryCell.tac?.toString() ?: "N/A")
                addDetailRow(binding.gridTechnicalDetails, getString(R.string.label_pci), primaryCell.pci?.toString() ?: "N/A")
                primaryCell.bandwidth?.let { addDetailRow(binding.gridTechnicalDetails, getString(R.string.label_bandwidth), "${it / 1000} MHz") }
                primaryCell.band?.let { 
                    addDetailRow(binding.gridTechnicalDetails, getString(R.string.label_earfcn), it.channelNumber.toString())
                    addDetailRow(binding.gridTechnicalDetails, getString(R.string.label_band), "B${it.number} (${it.name})")
                }
                
                val signal = primaryCell.signal as? cz.mroczis.netmonster.core.model.signal.SignalLte
                if (signal != null) {
                    addDetailValueRow(binding.gridTechnicalDetails, "RSSI", signal.rssi ?: -1, " dBm", "lte_rssi")
                    addDetailValueRow(binding.gridTechnicalDetails, "RSRP", signal.rsrp?.toInt() ?: -1, " dBm", "lte_rsrp")
                    addDetailValueRow(binding.gridTechnicalDetails, "RSRQ", signal.rsrq?.toInt() ?: -1, " dB", "lte_rsrq")
                    addDetailValueRow(binding.gridTechnicalDetails, "SNR", signal.snr?.toInt() ?: -1, " dB", "lte_snr")
                    
                    val ta = signal.timingAdvance
                    if (ta != null && ta != Int.MAX_VALUE) {
                        val distance = ta * 78 // roughly 78 meters per TA unit
                        addDetailRow(binding.gridTechnicalDetails, "TA", "$ta (~$distance m)")
                    } else {
                        addDetailRow(binding.gridTechnicalDetails, "TA", "N/A")
                    }
                } else {
                    primaryCell.signal.let { s ->
                        addDetailValueRow(binding.gridTechnicalDetails, "RSSI", s.dbm ?: -1, " dBm", "lte_rssi")
                    }
                }
                
                // 5G NSA Check
                val networkType = getNetworkTypeString(primaryCell, cells)
                var nrCell = cells.filterIsInstance<CellNr>().firstOrNull { 
                    it.connectionStatus is PrimaryConnection || it.connectionStatus is SecondaryConnection 
                }

                if (nrCell == null && networkType.contains("NSA")) {
                    // Aktif bulunamadıysa, ilk gördüğün NR hücresini al
                    nrCell = cells.filterIsInstance<CellNr>().firstOrNull()
                }

                if (nrCell != null) {
                    binding.layout5GDetails.visibility = View.VISIBLE
                    
                    addDetailRow(binding.grid5GDetails, getString(R.string.label_tac), nrCell.tac?.toString() ?: "N/A")
                    addDetailRow(binding.grid5GDetails, getString(R.string.label_pci), nrCell.pci?.toString() ?: "N/A")
                    
                    nrCell.band?.let { bandInfo ->
                        val arfcn = bandInfo.channelNumber
                        var bandNumber = bandInfo.number ?: 0
                        
                        // User's logic for Band 0 detection using ARFCN
                        if (bandNumber == 0) {
                            bandNumber = when {
                                arfcn in 620000..680000 -> 78
                                arfcn in 140000..150000 -> 28
                                arfcn in 150000..170000 -> 20
                                else -> 0
                            }
                        }
                        
                        addDetailRow(binding.grid5GDetails, getString(R.string.label_nrarfcn), arfcn.toString())
                        val bandName = if (bandNumber != 0) "n$bandNumber" else bandInfo.name ?: getString(R.string.unknown)
                        addDetailRow(binding.grid5GDetails, "5G Band", "$bandName ($bandNumber)")
                    }
                    
                    val nrSignal = nrCell.signal as? cz.mroczis.netmonster.core.model.signal.SignalNr
                    if (nrSignal != null) {
                        addDetailValueRow(binding.grid5GDetails, "SS RSRP", nrSignal.ssRsrp ?: -1, " dBm", "nr_rsrp")
                        addDetailValueRow(binding.grid5GDetails, "SS RSRQ", nrSignal.ssRsrq ?: -1, " dB", "nr_rsrq")
                        addDetailValueRow(binding.grid5GDetails, "SS SINR", nrSignal.ssSinr ?: -1, " dB", "nr_sinr")
                    } else {
                        nrCell.signal.let { s ->
                            addDetailValueRow(binding.grid5GDetails, "SS RSRP", s.dbm ?: -1, " dBm", "nr_rsrp")
                        }
                    }
                } else {
                    binding.layout5GDetails.visibility = View.GONE
                }
            }
            is CellNr -> {
                addDetailRow(binding.gridTechnicalDetails, getString(R.string.label_pci), primaryCell.pci?.toString() ?: "N/A")
                addDetailRow(binding.gridTechnicalDetails, getString(R.string.label_tac), primaryCell.tac?.toString() ?: "N/A")
                primaryCell.band?.let { bandInfo ->
                    val arfcn = bandInfo.channelNumber
                    var bandNumber = bandInfo.number ?: 0
                    
                    if (bandNumber == 0) {
                        bandNumber = when {
                            arfcn in 620000..680000 -> 78
                            arfcn in 140000..150000 -> 28
                            arfcn in 150000..170000 -> 20
                            else -> 0
                        }
                    }
                    
                    addDetailRow(binding.gridTechnicalDetails, getString(R.string.label_nrarfcn), arfcn.toString())
                    val bandName = if (bandNumber != 0) "n$bandNumber" else bandInfo.name ?: getString(R.string.unknown)
                    addDetailRow(binding.gridTechnicalDetails, "5G Band", "$bandName ($bandNumber)")
                }
                
                val nrSignal = primaryCell.signal as? cz.mroczis.netmonster.core.model.signal.SignalNr
                if (nrSignal != null) {
                    addDetailValueRow(binding.gridTechnicalDetails, "SS-RSRP", nrSignal.ssRsrp ?: -1, " dBm", "nr_rsrp")
                    addDetailValueRow(binding.gridTechnicalDetails, "SS-RSRQ", nrSignal.ssRsrq ?: -1, " dB", "nr_rsrq")
                    addDetailValueRow(binding.gridTechnicalDetails, "SS-SINR", nrSignal.ssSinr ?: -1, " dB", "nr_sinr")
                } else {
                    primaryCell.signal.let { s ->
                        addDetailValueRow(binding.gridTechnicalDetails, "SS-RSRP", s.dbm ?: -1, " dBm", "nr_rsrp")
                    }
                }
            }
            is CellWcdma -> {
                addDetailRow(binding.gridTechnicalDetails, "LAC", primaryCell.lac?.toString() ?: "N/A")
                addDetailRow(binding.gridTechnicalDetails, "PSC", primaryCell.psc?.toString() ?: "N/A")
                primaryCell.band?.let { addDetailRow(binding.gridTechnicalDetails, "UARFCN", it.channelNumber.toString()) }
            }
            is CellGsm -> {
                addDetailRow(binding.gridTechnicalDetails, "LAC", primaryCell.lac?.toString() ?: "N/A")
                addDetailRow(binding.gridTechnicalDetails, "BSIC", primaryCell.bsic?.toString() ?: "N/A")
                primaryCell.band?.let { addDetailRow(binding.gridTechnicalDetails, "ARFCN", it.channelNumber.toString()) }
            }
        }
    }

    private fun getNetworkTypeString(primaryCell: ICell, allCells: List<ICell>): String {
        var isNrAvailable = allCells.any { it is CellNr }
        
        // Fallback: Check ServiceState for 5G NSA indicators if NetMonster doesn't see NR cells
        // This is a common way to detect 5G NSA availability on Android
        if (!isNrAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    val ss = tm.serviceState
                    val ssString = ss?.toString() ?: ""
                    if (ssString.contains("nrState=CONNECTED") || 
                        ssString.contains("nrState=NOT_RESTRICTED") ||
                        ssString.contains("nrState=AVAILABLE")) {
                        isNrAvailable = true
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        return when (primaryCell) {
            is CellNr -> getString(R.string.sa_5g)
            is CellLte -> {
                if (isNrAvailable) {
                    getString(R.string.nsa_5g)
                } else {
                    getString(R.string.lte_4g)
                }
            }
            is CellWcdma -> getString(R.string.type_3g)
            is CellGsm -> getString(R.string.type_2g)
            else -> getString(R.string.unknown)
        }
    }

    private fun getOperatorName(mcc: String, mnc: String): String {
        return when ("$mcc$mnc") {
            "28601" -> "Turkcell"
            "28602" -> "vodafone TR"
            "28603" -> "Türk Telekom"
            else -> getString(R.string.operator_label, "$mcc$mnc")
        }
    }

    @Suppress("DEPRECATION")
    private fun showWifiHub() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo: WifiInfo? = wifiManager.connectionInfo
        
        var ssid = wifiInfo?.ssid?.replace("\"", "") ?: getString(R.string.unknown)
        if (ssid == "<unknown ssid>") {
            ssid = getString(R.string.hidden_ssid)
        }

        val surfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)

        binding.cardActiveHub.setCardBackgroundColor(surfaceColor)
        setHubTextColor(onSurfaceColor)

        binding.txtHubTitle.text = getString(R.string.wifi_connection)
        binding.txtHubMainValue.text = ssid
        binding.txtHubSubLabel1.text = getString(R.string.wifi_signal_rssi)
        binding.txtHubSubValue1.text = "${wifiInfo?.rssi ?: 0} dBm"
        binding.txtHubSubValue1Description.text = getSignalQualityDescription(wifiInfo?.rssi ?: 0, true)
        binding.txtHubSubLabel2.text = getString(R.string.wifi_speed)
        binding.txtHubSubValue2.text = "${wifiInfo?.linkSpeed ?: 0} Mbps"

        // Logging
        val rssi = wifiInfo?.rssi ?: 0
        if (lastLoggedSsid != ssid) {
            addLog(getString(R.string.wifi_network_changed, ssid))
            lastLoggedSsid = ssid
        }
        
        if (rssi != 0 && lastLoggedWifiRssi != rssi) {
            addLog(getString(R.string.wifi_signal_strength_log, ssid, rssi))
            lastLoggedWifiRssi = rssi
        }

        binding.containerWifiDetails.visibility = View.VISIBLE
        binding.containerMobileDetails.visibility = View.GONE
        binding.layout5GDetails.visibility = View.GONE

        if (currentHubType != "WIFI") {
            clearGrid(binding.gridWifiIpDetails)
            clearGrid(binding.gridWifiSignalQuality)
            clearGrid(binding.gridWifiChannel)
            clearGrid(binding.gridWifiSecurity)
            clearGrid(binding.gridTechnicalDetails)
            currentHubType = "WIFI"
        }

        wifiInfo?.let {
            // 1. Ağ Yapılandırması (IP Detayları)
            val dhcpInfo = wifiManager.dhcpInfo
            val ipAddress = formatIpAddress(dhcpInfo.ipAddress)
            val gateway = formatIpAddress(dhcpInfo.gateway)
            val dns1 = formatIpAddress(dhcpInfo.dns1)
            val dns2 = formatIpAddress(dhcpInfo.dns2)
            
            addDetailRow(binding.gridWifiIpDetails, getString(R.string.local_ip), ipAddress)
            addDetailRow(binding.gridWifiIpDetails, getString(R.string.gateway), gateway)
            addDetailRow(binding.gridWifiIpDetails, getString(R.string.dns1), dns1)
            if (dns2 != "0.0.0.0") addDetailRow(binding.gridWifiIpDetails, getString(R.string.dns2), dns2)

            // 2. Sinyal Kalitesi Analizi
            val currentRssi = it.rssi
            val signalQuality = when {
                currentRssi >= -60 -> getString(R.string.quality_excellent)
                currentRssi >= -70 -> getString(R.string.quality_good)
                else -> getString(R.string.quality_poor)
            }
            addDetailRow(binding.gridWifiSignalQuality, getString(R.string.signal_strength), "$currentRssi dBm")
            addDetailRow(binding.gridWifiSignalQuality, getString(R.string.status), signalQuality)

            // 3. Kanal ve Genişlik
            val freq = it.frequency
            val channel = when (freq) {
                in 2412..2484 -> (freq - 2412) / 5 + 1
                in 5170..5825 -> (freq - 5170) / 5 + 34
                else -> 0
            }
            addDetailRow(binding.gridWifiChannel, getString(R.string.frequency), "$freq MHz")
            if (channel > 0) addDetailRow(binding.gridWifiChannel, getString(R.string.channel), "$channel")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                addDetailRow(binding.gridWifiChannel, getString(R.string.tx_speed), "${it.txLinkSpeedMbps} Mbps")
                addDetailRow(binding.gridWifiChannel, getString(R.string.rx_speed), "${it.rxLinkSpeedMbps} Mbps")
            }

            // 4. Güvenlik Tipi
            var securityType = getString(R.string.unknown)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                securityType = when (it.currentSecurityType) {
                    WifiInfo.SECURITY_TYPE_OPEN -> getString(R.string.security_open)
                    WifiInfo.SECURITY_TYPE_WEP -> "WEP"
                    WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2-PSK"
                    WifiInfo.SECURITY_TYPE_EAP -> "WPA/WPA2-Enterprise"
                    WifiInfo.SECURITY_TYPE_SAE -> "WPA3-Personal"
                    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
                    WifiInfo.SECURITY_TYPE_OWE -> "OWE"
                    else -> getString(R.string.unknown)
                }
            }
            addDetailRow(binding.gridWifiSecurity, getString(R.string.encryption), securityType)
            
            var bssid = it.bssid ?: getString(R.string.not_available)
            if (bssid == "02:00:00:00:00:00") bssid = getString(R.string.security_hidden)
            addDetailRow(binding.gridWifiSecurity, getString(R.string.bssid), bssid)
            
            // 5. Performans Testi
            binding.containerPerformanceTest.visibility = View.VISIBLE
        }
        
        addDetailRow(binding.gridTechnicalDetails, getString(R.string.status), getString(R.string.wifi_active))
    }

    private fun formatIpAddress(ipAddress: Int): String {
        return if (ipAddress == 0) "0.0.0.0" else
            "${ipAddress and 0xFF}.${ipAddress shr 8 and 0xFF}.${ipAddress shr 16 and 0xFF}.${ipAddress shr 24 and 0xFF}"
    }

    private fun setupInfoButtons() {
        binding.btnInfoHub.setOnClickListener {
            showInfoDialog(getString(R.string.help_hub_title), getString(R.string.help_hub_desc))
        }
        binding.btnInfoTechnical.setOnClickListener {
            showInfoDialog(getString(R.string.help_technical_title), getString(R.string.help_technical_desc))
        }
        binding.btnInfo5G.setOnClickListener {
            showInfoDialog(getString(R.string.help_5g_title), getString(R.string.help_5g_desc))
        }
        binding.btnInfoWifi.setOnClickListener {
            showInfoDialog(getString(R.string.help_wifi_title), getString(R.string.help_wifi_desc))
        }
        binding.btnInfoPerformance.setOnClickListener {
            showInfoDialog(getString(R.string.help_performance_title), getString(R.string.help_performance_desc))
        }
    }

    private fun showInfoDialog(title: String, message: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Anladım", null)
            .show()
    }

    private fun setupSpeedTest() {
        binding.btnOpenPerformance.setOnClickListener {
            binding.layoutSpeedResults.visibility = View.VISIBLE
            binding.btnClosePerformance.visibility = View.VISIBLE
            binding.txtDataWarning.visibility = View.VISIBLE
        }

        binding.btnClosePerformance.setOnClickListener {
            if (isSpeedTestRunning) {
                isSpeedTestRunning = false
                return@setOnClickListener
            }
            binding.layoutSpeedResults.visibility = View.GONE
            binding.btnClosePerformance.visibility = View.GONE
            binding.txtDataWarning.visibility = View.GONE
        }

        binding.btnStartSpeedTest.setOnClickListener {
            if (isSpeedTestRunning) return@setOnClickListener

            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            builder.setTitle("Hız Testi Başlatılsın mı?")
            builder.setMessage("Bu test yaklaşık 20 saniye sürecek ve yüksek miktarda veri tüketebilir (1GB+). Devam etmek istiyor musunuz?")
            builder.setPositiveButton("Başlat") { _, _ ->
                runActualSpeedTest()
            }
            builder.setNegativeButton("İptal", null)
            builder.show()
        }
    }

    private fun runActualSpeedTest() {
        isSpeedTestRunning = true
        binding.btnStartSpeedTest.isEnabled = false
        binding.btnOpenPerformance.isEnabled = false
        binding.btnStartSpeedTest.text = "Test Yapılıyor..."
        binding.layoutSpeedResults.visibility = View.VISIBLE
        binding.layoutSpeedProgress.visibility = View.VISIBLE
        binding.btnClosePerformance.visibility = View.VISIBLE
        binding.btnClosePerformance.setImageResource(R.drawable.ic_close)
        
        binding.txtSpeedPing.text = getString(R.string.measuring)
        binding.txtSpeedDownload.text = getString(R.string.waiting)
        binding.txtSpeedUpload.text = getString(R.string.waiting)
        
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Ping
            val pingResult = measurePing()
            if (!isSpeedTestRunning) {
                resetSpeedTestUI()
                return@launch
            }
            
            withContext(Dispatchers.Main) {
                binding.txtSpeedPing.text = pingResult
                binding.txtSpeedDownload.text = getString(R.string.measuring)
            }
            
            // 2. Download
            val dlSpeed = runDownloadTest { currentSpeed, progress, remaining ->
                binding.txtSpeedDownload.text = currentSpeed
                binding.progressSpeedTest.progress = (progress * 50).toInt() // First 50%
                binding.txtSpeedCountdown.text = "İndirme Testi: ${remaining}s kaldı"
            }
            
            if (!isSpeedTestRunning) {
                resetSpeedTestUI()
                return@launch
            }
            
            withContext(Dispatchers.Main) {
                val dlStr = if (dlSpeed > 0) String.format(Locale.US, "%.1f Mbps", dlSpeed) else "Hata"
                binding.txtSpeedDownload.text = dlStr
                binding.txtSpeedUpload.text = getString(R.string.measuring)
            }
            
            // 3. Upload
            val ulSpeed = runUploadTest { currentSpeed, progress, remaining ->
                binding.txtSpeedUpload.text = currentSpeed
                binding.progressSpeedTest.progress = 50 + (progress * 50).toInt() // Last 50%
                binding.txtSpeedCountdown.text = "Yükleme Testi: ${remaining}s kaldı"
            }
            
            if (!isSpeedTestRunning) {
                resetSpeedTestUI()
                return@launch
            }
            
            withContext(Dispatchers.Main) {
                val dlStr = if (dlSpeed > 0) String.format(Locale.US, "%.1f Mbps", dlSpeed) else "Hata"
                val ulStr = if (ulSpeed > 0) String.format(Locale.US, "%.1f Mbps", ulSpeed) else getString(R.string.error_label)
                
                binding.txtSpeedUpload.text = ulStr
                resetSpeedTestUI()
                addLog(getString(R.string.speed_test_completed, pingResult, dlStr, ulStr))
            }
        }
    }

    private suspend fun resetSpeedTestUI() {
        withContext(Dispatchers.Main) {
            isSpeedTestRunning = false
            binding.btnStartSpeedTest.text = getString(R.string.repeat_test)
            binding.btnStartSpeedTest.isEnabled = true
            binding.btnOpenPerformance.isEnabled = true
            binding.layoutSpeedProgress.visibility = View.GONE
            binding.btnClosePerformance.visibility = View.VISIBLE
        }
    }

    private fun measurePing(): String {
        return try {
            val process = Runtime.getRuntime().exec("ping -c 3 8.8.8.8")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var avgPing = ""
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("min/avg/max/mdev")) {
                    val parts = line!!.split("=")[1].trim().split("/")
                    if (parts.size >= 2) {
                        avgPing = "${parts[1]} ms"
                    }
                }
            }
            val exitValue = process.waitFor()
            if (exitValue == 0 && avgPing.isNotEmpty()) avgPing else getString(R.string.failed_label)
        } catch (e: Exception) {
            getString(R.string.error_label)
        }
    }

    private suspend fun runDownloadTest(updateUi: (String, Float, Int) -> Unit): Double {
        val startTime = System.currentTimeMillis()
        val testDuration = 10000L // 10 seconds
        val speeds = mutableListOf<Double>()
        var totalBytes = 0L
        var lastUpdateTime = startTime

        // List of reliable speed test endpoints
        val testUrls = listOf(
            "https://cachefly.cachefly.net/100mb.test",
            "https://speed.cloudflare.com/__down?bytes=100000000",
            "http://speedtest-ams3.digitalocean.com/100mb.test"
        )

        return try {
            var urlIndex = 0
            while (isSpeedTestRunning && System.currentTimeMillis() - startTime < testDuration) {
                val currentUrl = testUrls[urlIndex % testUrls.size]
                val url = URL(currentUrl) 
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    addLog("Download Sunucu Hatası ($responseCode): $currentUrl")
                    urlIndex++ // Try next URL
                    if (urlIndex >= testUrls.size * 2) break // Avoid infinite loop if all fail
                    continue
                }

                connection.inputStream.use { inputStream ->
                    val buffer = ByteArray(32768)
                    var read = 0
                    
                    while (isSpeedTestRunning && inputStream.read(buffer).also { read = it } != -1) {
                        totalBytes += read
                        val currentTime = System.currentTimeMillis()
                        
                        if (currentTime - lastUpdateTime > 500) {
                            val elapsed = currentTime - startTime
                            val timeInSeconds = elapsed / 1000.0
                            val speedMbps = (totalBytes * 8 / 1_000_000.0) / timeInSeconds
                            speeds.add(speedMbps)
                            
                            val progress = (elapsed.toFloat() / testDuration).coerceIn(0f, 1f)
                            val remaining = ((testDuration - elapsed) / 1000).toInt().coerceAtLeast(0)

                            withContext(Dispatchers.Main) {
                                updateUi(String.format(Locale.US, "%.1f Mbps", speedMbps), progress, remaining)
                            }
                            lastUpdateTime = currentTime
                        }
                        
                        // Stop after 10 seconds
                        if (currentTime - startTime >= testDuration) {
                            break
                        }
                    }
                }
                
                if (System.currentTimeMillis() - startTime >= testDuration) {
                    break
                }
                urlIndex++ // Move to next chunk/URL if needed
            }
            
            if (!isSpeedTestRunning || speeds.isEmpty()) return 0.0
            speeds.average()
        } catch (e: Exception) {
            addLog("Download Hatası: ${e.javaClass.simpleName} - ${e.message}")
            -1.0
        }
    }

    private suspend fun runUploadTest(updateUi: (String, Float, Int) -> Unit): Double {
        var outputStream: OutputStream? = null
        return try {
            val url = URL("https://speed.cloudflare.com/__up")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setChunkedStreamingMode(16384)
            
            outputStream = connection.outputStream
            val buffer = ByteArray(16384) { 0 }
            val startTime = System.currentTimeMillis()
            val testDuration = 10000L // 10 seconds
            var totalBytes = 0L
            var lastUpdateTime = startTime
            
            val speeds = mutableListOf<Double>()
            
            while (isSpeedTestRunning) {
                outputStream.write(buffer)
                totalBytes += buffer.size
                val currentTime = System.currentTimeMillis()
                
                if (currentTime - lastUpdateTime > 500) {
                    val elapsed = currentTime - startTime
                    val timeInSeconds = elapsed / 1000.0
                    val speedMbps = (totalBytes * 8 / 1_000_000.0) / timeInSeconds
                    speeds.add(speedMbps)
                    
                    val progress = (elapsed.toFloat() / testDuration).coerceIn(0f, 1f)
                    val remaining = ((testDuration - elapsed) / 1000).toInt().coerceAtLeast(0)

                    withContext(Dispatchers.Main) {
                        updateUi(String.format(Locale.US, "%.1f Mbps", speedMbps), progress, remaining)
                    }
                    lastUpdateTime = currentTime
                }
                
                // Stop after 10 seconds
                if (currentTime - startTime >= testDuration) {
                    break
                }
            }
            outputStream.flush()
            
            if (!isSpeedTestRunning || speeds.isEmpty()) return 0.0
            speeds.average()
        } catch (e: Exception) {
            -1.0
        } finally {
            try { outputStream?.close() } catch (e: Exception) {}
        }
    }

    private fun showNoConnectionHub() {
        if (currentHubType != "NONE") {
            addLog(getString(R.string.connection_lost))
            currentHubType = "NONE"
            lastLoggedNetworkType = null
            lastLoggedCellId = null
            lastLoggedSsid = null
        }
        
        val surfaceVariantColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceVariant)
        val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)

        binding.cardActiveHub.setCardBackgroundColor(surfaceVariantColor)
        setHubTextColor(onSurfaceColor)
        binding.txtHubMainValue.text = getString(R.string.no_connection)
        binding.txtHubSubValue1.text = getString(R.string.not_available)
        binding.txtHubSubValue1Description.text = ""
        binding.txtHubSubValue2.text = getString(R.string.not_available)
        binding.gridTechnicalDetails.removeAllViews()
        binding.containerWifiDetails.visibility = View.GONE
        binding.containerMobileDetails.visibility = View.GONE
        binding.containerPerformanceTest.visibility = View.GONE
        binding.layout5GDetails.visibility = View.GONE
    }

    private fun applyOperatorTheme(operator: String) {
        val op = operator.lowercase()
        when {
            op.contains("vodafone") -> {
                binding.cardActiveHub.setCardBackgroundColor(Color.parseColor("#E60000"))
                setHubTextColor(Color.WHITE)
            }
            op.contains("turkcell") -> {
                binding.cardActiveHub.setCardBackgroundColor(Color.parseColor("#FFD700"))
                setHubTextColor(Color.BLACK)
            }
            op.contains("telekom") -> {
                binding.cardActiveHub.setCardBackgroundColor(Color.parseColor("#05BEC8"))
                setHubTextColor(Color.BLACK)
            }
            else -> {
                val surfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
                val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
                binding.cardActiveHub.setCardBackgroundColor(surfaceColor)
                setHubTextColor(onSurfaceColor)
            }
        }
    }

    private fun setHubTextColor(color: Int) {
        binding.txtHubMainValue.setTextColor(color)
        binding.txtHubTitle.setTextColor(color)
        binding.txtHubSubLabel1.setTextColor(color)
        binding.txtHubSubLabel2.setTextColor(color)
        binding.txtHubSubValue1.setTextColor(color)
        binding.txtHubSubValue2.setTextColor(color)
    }

    private fun getSignalQualityDescription(dbm: Int, isWifi: Boolean): String {
        if (dbm == 0 || dbm == -1) return ""
        
        return if (isWifi) {
            when {
                dbm >= -50 -> getString(R.string.signal_excellent)
                dbm >= -60 -> getString(R.string.signal_very_good)
                dbm >= -65 -> getString(R.string.signal_good)
                dbm >= -75 -> getString(R.string.signal_moderate)
                dbm >= -85 -> getString(R.string.signal_poor)
                dbm >= -90 -> getString(R.string.signal_very_poor)
                else -> getString(R.string.signal_terrible)
            }
        } else {
            // Mobile thresholds (primarily RSRP for LTE/5G)
            when {
                dbm >= -80 -> getString(R.string.signal_excellent)
                dbm >= -90 -> getString(R.string.signal_very_good)
                dbm >= -100 -> getString(R.string.signal_good)
                dbm >= -105 -> getString(R.string.signal_moderate)
                dbm >= -115 -> getString(R.string.signal_poor)
                dbm >= -125 -> getString(R.string.signal_very_poor)
                else -> getString(R.string.signal_terrible)
            }
        }
    }

    private fun updateDeviceInfo() {
        val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: getString(R.string.unknown)
        addLog(getString(R.string.device_info_log, Build.MODEL, Build.MANUFACTURER, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, androidId))
    }

    private fun clearGrid(grid: android.widget.GridLayout) {
        grid.removeAllViews()
        gridValueViews[grid]?.clear()
    }

    private fun addDetailRow(grid: android.widget.GridLayout, label: String, value: String, change: Int = 0) {
        val gridCache = gridValueViews.getOrPut(grid) { mutableMapOf() }
        val valueView = gridCache[label]
        
        val arrow = when {
            change > 0 -> " ▲"
            change < 0 -> " ▼"
            else -> ""
        }
        
        val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariantColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        val textColor = when {
            change > 0 -> Color.parseColor("#00C853")
            change < 0 -> Color.parseColor("#D50000")
            else -> onSurfaceColor
        }

        if (valueView != null) {
            valueView.text = "$value$arrow"
            valueView.setTextColor(textColor)
        } else {
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
                setPadding(0, 0, 8, 0)
            }

            val labelView = TextView(this).apply {
                text = label
                setTextColor(onSurfaceVariantColor)
                textSize = 9f * responsiveScale
                setPadding(0, (4 * responsiveScale).toInt(), 0, 0)
            }
            val newValueView = TextView(this).apply {
                text = "$value$arrow"
                setTextColor(textColor)
                textSize = 13f * responsiveScale
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, (8 * responsiveScale).toInt())
            }
            container.addView(labelView)
            container.addView(newValueView)
            grid.addView(container)
            gridCache[label] = newValueView
        }
    }

    private fun addDetailValueRow(grid: android.widget.GridLayout, label: String, value: Int, unit: String, key: String? = null) {
        if (value == Int.MAX_VALUE || value == Int.MIN_VALUE || value == -1) {
            addDetailRow(grid, label, getString(R.string.not_available))
            return
        }
        
        var change = 0
        if (key != null) {
            val prev = previousValues[key]
            if (prev != null) {
                change = value - prev
            }
            previousValues[key] = value
        }
        
        addDetailRow(grid, label, "$value$unit", change)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
