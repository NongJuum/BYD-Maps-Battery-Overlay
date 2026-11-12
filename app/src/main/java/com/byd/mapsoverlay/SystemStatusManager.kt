package com.byd.mapsoverlay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log

/**
 * SystemStatusManager:
 * จัดการตรวจจับสถานะระบบและการเชื่อมต่อที่จำเป็นของรถ DiLink 3.0 (Android 10)
 * ได้แก่: สัญญาณซิม 4G, ไวไฟ, การเข้าถึงพิกัด GPS, กล้องบันทึกรอบคัน (DVR/AVC) และบลูทูธ
 * เพื่อนำไปแสดงผลบนแถบ Stock Bar ด้านบนแบบเรียลไทม์
 */
class SystemStatusManager(
    private val context: Context,
    private val onUpdate: (StatusData) -> Unit
) {
    private val tag = "SystemStatusManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    data class StatusData(
        val simText: String = "4G ▂▄▆█",
        val isSimActive: Boolean = true,
        val simLevel: Int = 4,
        val wifiText: String = "Wi-Fi ▂▄▆█",
        val isWifiConnected: Boolean = false,
        val wifiLevel: Int = 4,
        val isGpsActive: Boolean = false,
        val gpsText: String = "📍 GPS",
        val isCameraActive: Boolean = false, // DASHCAM / DVR recording active
        val cameraText: String = "📷 DVR",
        val isBluetoothConnected: Boolean = false,
        val btText: String = "BT"
    )

    var currentData = StatusData()
        private set
    private var isMonitoring = false

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val activeCameras = mutableSetOf<String>()

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            activeCameras.add(cameraId)
            updateCameraState()
        }

        override fun onCameraAvailable(cameraId: String) {
            activeCameras.remove(cameraId)
            updateCameraState()
        }
    }

    private val phoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            super.onSignalStrengthsChanged(signalStrength)
            updateSimState(signalStrength)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post { updateNetworkState() }
        }

        override fun onLost(network: Network) {
            mainHandler.post { updateNetworkState() }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            mainHandler.post { updateNetworkState() }
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                WifiManager.RSSI_CHANGED_ACTION,
                LocationManager.PROVIDERS_CHANGED_ACTION,
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    refreshAllStatus()
                }
            }
        }
    }

    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            refreshAllStatus()
            mainHandler.postDelayed(this, 3000)
        }
    }

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true
        Log.d(tag, "Starting system status monitoring for Stock Bar")

        // 1. Camera Monitoring (DVR / Panoramic 360 camera activity)
        try {
            cameraManager?.registerAvailabilityCallback(cameraCallback, mainHandler)
        } catch (e: Throwable) {
            Log.w(tag, "Camera availability callback error: ${e.message}")
        }

        // 2. Telephony / SIM Monitoring
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        } catch (e: Throwable) {
            Log.w(tag, "Telephony listener error: ${e.message}")
        }

        // 3. Network Monitoring
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Throwable) {
            Log.w(tag, "Network callback error: ${e.message}")
        }

        // 4. System Broadcasts (Wi-Fi, Bluetooth, Location, BYD Dashcam/CDR)
        try {
            val filter = IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.RSSI_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction("android.cdr.action.STATE_CHANGED")
                addAction("com.byd.action.CDR_STATE_CHANGED")
            }
            context.registerReceiver(systemReceiver, filter)
        } catch (e: Throwable) {
            Log.w(tag, "Broadcast receiver registration error: ${e.message}")
        }

        // 5. Periodic polling for states that don't always trigger broadcasts (e.g. GPS provider usage, BT profiles)
        mainHandler.post(periodicRunnable)
        refreshAllStatus()
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        Log.d(tag, "Stopping system status monitoring")

        try { cameraManager?.unregisterAvailabilityCallback(cameraCallback) } catch (_: Throwable) {}
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Throwable) {}
        try { connectivityManager?.unregisterNetworkCallback(networkCallback) } catch (_: Throwable) {}
        try { context.unregisterReceiver(systemReceiver) } catch (_: Throwable) {}
        mainHandler.removeCallbacks(periodicRunnable)
    }

    fun refreshAllStatus() {
        updateCameraState()
        updateSimState(null)
        updateNetworkState()
        updateGpsState()
        updateBluetoothState()
        dispatchUpdate()
    }

    private fun updateCameraState() {
        var inUse = activeCameras.isNotEmpty()
        if (!inUse) {
            try {
                // Check recent file writes in Overdrive / DVR directories
                val candidateDirs = listOf(
                    java.io.File("/sdcard/Overdrive/trips"),
                    java.io.File("/sdcard/Overdrive/recordings"),
                    java.io.File("/sdcard/DVR")
                )
                val now = System.currentTimeMillis()
                for (dir in candidateDirs) {
                    if (dir.exists() && dir.isDirectory) {
                        val files = dir.listFiles()
                        if (files != null) {
                            for (f in files) {
                                if (now - f.lastModified() < 45_000L) {
                                    inUse = true
                                    break
                                }
                            }
                        }
                    }
                    if (inUse) break
                }
            } catch (_: Throwable) {}
        }
        val cameraText = if (inUse) "● REC" else "📷 DVR"
        if (currentData.isCameraActive != inUse || currentData.cameraText != cameraText) {
            currentData = currentData.copy(isCameraActive = inUse, cameraText = cameraText)
            dispatchUpdate()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSimState(signalStrength: SignalStrength?) {
        val tm = telephonyManager ?: return
        val simState = tm.simState
        val isSimReady = simState == TelephonyManager.SIM_STATE_READY

        var simText = "4G"
        var isSimActive = isSimReady
        var simLevel = 0

        if (simState == TelephonyManager.SIM_STATE_ABSENT) {
            simText = "❌ 4G"
            isSimActive = false
        } else if (isSimReady) {
            simLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signalStrength?.level ?: tm.signalStrength?.level ?: 3
            } else {
                3
            }
            val bars = when (simLevel) {
                4 -> "▂▄▆█"
                3 -> "▂▄▆_"
                2 -> "▂▄__"
                1 -> "▂___"
                else -> "____"
            }
            simText = "4G $bars"
        }

        if (currentData.simText != simText || currentData.isSimActive != isSimActive || currentData.simLevel != simLevel) {
            currentData = currentData.copy(simText = simText, isSimActive = isSimActive, simLevel = simLevel)
            dispatchUpdate()
        }
    }

    private fun updateNetworkState() {
        val wm = wifiManager
        var wifiConnected = false
        var wifiText = "Wi-Fi"
        var wifiLevel = 0

        try {
            val info: WifiInfo? = wm?.connectionInfo
            if (info != null && info.networkId != -1) {
                wifiConnected = true
                wifiLevel = WifiManager.calculateSignalLevel(info.rssi, 5)
                val bars = when (wifiLevel) {
                    4 -> "▂▄▆█"
                    3 -> "▂▄▆_"
                    2 -> "▂▄__"
                    1 -> "▂___"
                    else -> "____"
                }
                wifiText = "Wi-Fi $bars"
            } else {
                wifiText = "Wi-Fi"
            }
        } catch (_: Throwable) {}

        if (currentData.isWifiConnected != wifiConnected || currentData.wifiText != wifiText || currentData.wifiLevel != wifiLevel) {
            currentData = currentData.copy(wifiText = wifiText, isWifiConnected = wifiConnected, wifiLevel = wifiLevel)
            dispatchUpdate()
        }
    }

    private fun updateGpsState() {
        var gpsActive = false
        try {
            val lm = locationManager
            if (lm != null) {
                val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val isModeEnabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_OFF
                ) != Settings.Secure.LOCATION_MODE_OFF
                gpsActive = isGpsEnabled && isModeEnabled
            }
        } catch (_: Throwable) {}

        val gpsText = if (gpsActive) "📍 GPS" else "📍"
        if (currentData.isGpsActive != gpsActive || currentData.gpsText != gpsText) {
            currentData = currentData.copy(isGpsActive = gpsActive, gpsText = gpsText)
            dispatchUpdate()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateBluetoothState() {
        var btConnected = false
        var btEnabled = false
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                btEnabled = adapter.isEnabled
                if (btEnabled) {
                    // 1. Check bonded devices for any active connection via reflection
                    val bonded = adapter.bondedDevices
                    if (bonded != null) {
                        for (device in bonded) {
                            try {
                                val isConnectedMethod = device.javaClass.getMethod("isConnected")
                                if (isConnectedMethod.invoke(device) == true) {
                                    btConnected = true
                                    break
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    // 2. Check automotive profiles: 11 (A2DP_SINK), 16 (HEADSET_CLIENT), 2 (A2DP), 1 (HEADSET), 17 (PBAP_CLIENT)
                    if (!btConnected) {
                        val profiles = intArrayOf(11, 16, BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 17)
                        for (prof in profiles) {
                            try {
                                if (adapter.getProfileConnectionState(prof) == BluetoothProfile.STATE_CONNECTED) {
                                    btConnected = true
                                    break
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        val btText = when {
            btConnected -> "BT ●"
            btEnabled -> "BT"
            else -> "BT ✕"
        }
        if (currentData.isBluetoothConnected != btConnected || currentData.btText != btText) {
            currentData = currentData.copy(isBluetoothConnected = btConnected, btText = btText)
            dispatchUpdate()
        }
    }

    private fun dispatchUpdate() {
        mainHandler.post {
            onUpdate(currentData)
        }
    }
}
