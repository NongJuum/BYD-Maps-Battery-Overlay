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
        val simText: String = "📶 4G",
        val isSimActive: Boolean = true,
        val wifiText: String = "📶 Wi-Fi",
        val isWifiConnected: Boolean = false,
        val isGpsActive: Boolean = false,
        val isCameraActive: Boolean = false,
        val isBluetoothConnected: Boolean = false
    )

    private var currentData = StatusData()
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

        // 4. System Broadcasts (Wi-Fi, Bluetooth, Location)
        try {
            val filter = IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.RSSI_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
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
        val inUse = activeCameras.isNotEmpty()
        if (currentData.isCameraActive != inUse) {
            currentData = currentData.copy(isCameraActive = inUse)
            dispatchUpdate()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSimState(signalStrength: SignalStrength?) {
        val tm = telephonyManager ?: return
        val simState = tm.simState
        val isSimReady = simState == TelephonyManager.SIM_STATE_READY

        var simText = "📶 4G"
        var isSimActive = isSimReady

        if (simState == TelephonyManager.SIM_STATE_ABSENT) {
            simText = "❌ SIM"
            isSimActive = false
        } else if (isSimReady) {
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signalStrength?.level ?: tm.signalStrength?.level ?: 3
            } else {
                3
            }
            val bars = when (level) {
                4 -> "▮▮▮▮"
                3 -> "▮▮▮▯"
                2 -> "▮▮▯▯"
                1 -> "▮▯▯▯"
                else -> "▯▯▯▯"
            }
            simText = "📶 4G"
        }

        if (currentData.simText != simText || currentData.isSimActive != isSimActive) {
            currentData = currentData.copy(simText = simText, isSimActive = isSimActive)
            dispatchUpdate()
        }
    }

    private fun updateNetworkState() {
        val wm = wifiManager
        var wifiConnected = false
        var wifiText = "📶 Wi-Fi"

        try {
            val info: WifiInfo? = wm?.connectionInfo
            if (info != null && info.networkId != -1) {
                wifiConnected = true
                wifiText = "📶 Wi-Fi"
            }
        } catch (_: Throwable) {}

        if (currentData.isWifiConnected != wifiConnected || currentData.wifiText != wifiText) {
            currentData = currentData.copy(wifiText = wifiText, isWifiConnected = wifiConnected)
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

        if (currentData.isGpsActive != gpsActive) {
            currentData = currentData.copy(isGpsActive = gpsActive)
            dispatchUpdate()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateBluetoothState() {
        var btConnected = false
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && adapter.isEnabled) {
                val a2dpState = adapter.getProfileConnectionState(BluetoothProfile.A2DP)
                val headsetState = adapter.getProfileConnectionState(BluetoothProfile.HEADSET)
                btConnected = a2dpState == BluetoothProfile.STATE_CONNECTED || headsetState == BluetoothProfile.STATE_CONNECTED
            }
        } catch (_: Throwable) {}

        if (currentData.isBluetoothConnected != btConnected) {
            currentData = currentData.copy(isBluetoothConnected = btConnected)
            dispatchUpdate()
        }
    }

    private fun dispatchUpdate() {
        mainHandler.post {
            onUpdate(currentData)
        }
    }
}
