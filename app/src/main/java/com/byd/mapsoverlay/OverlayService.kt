package com.byd.mapsoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private var tvSocDisplay: TextView? = null
    private var tvRealRange: TextView? = null
    private var tvMapsDistance: TextView? = null
    private var tvConsumption: TextView? = null
    private var tvWarningBadge: TextView? = null

    // Containers & Dividers for custom toggling
    private var containerDestSoc: View? = null
    private var containerRealRange: View? = null
    private var containerMapsDistance: View? = null
    private var containerConsumption: View? = null
    private var divider1: View? = null
    private var divider2: View? = null
    private var divider3: View? = null

    private lateinit var bydBridge: BydVehicleBridge
    private var latestDistanceKm: Double = 0.0
    private var lockedSingleDestSoc: Int = -1
    private var lastRawDestSoc: Double = -1.0

    private lateinit var prefs: SharedPreferences
    private var windowParams: WindowManager.LayoutParams? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var currentScale = 1.0f
    private lateinit var scaleDetector: ScaleGestureDetector

    companion object {
        const val ACTION_UPDATE_DISTANCE = "com.byd.mapsoverlay.ACTION_UPDATE_DISTANCE"
        const val ACTION_HIDE_OVERLAY = "com.byd.mapsoverlay.ACTION_HIDE_OVERLAY"
        const val ACTION_RESET_POSITION = "com.byd.mapsoverlay.ACTION_RESET_POSITION"
        const val ACTION_PROFILE_UPDATED = "com.byd.mapsoverlay.ACTION_PROFILE_UPDATED"
        const val EXTRA_DISTANCE_KM = "EXTRA_DISTANCE_KM"
        private const val PREFS_NAME = "byd_overlay_prefs"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"
        private const val KEY_SCALE = "scale"
        const val KEY_SHOW_DEST_SOC = "show_dest_soc"
        const val KEY_SHOW_REAL_RANGE = "show_real_range"
        const val KEY_SHOW_MAPS_DIST = "show_maps_dist"
        const val KEY_SHOW_CONSUMPTION = "show_consumption"
        private const val CHANNEL_ID = "byd_overlay_channel"
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_DISTANCE -> {
                    val dist = intent.getDoubleExtra(EXTRA_DISTANCE_KM, -1.0)
                        .takeIf { it > 0 }
                        ?: intent.getFloatExtra(EXTRA_DISTANCE_KM, -1f).toDouble()
                    if (dist > 0) {
                        if (kotlin.math.abs(dist - latestDistanceKm) > 10.0) {
                            lockedSingleDestSoc = -1
                        }
                        latestDistanceKm = dist
                        showOverlay()
                        renderOverlay()
                    }
                }
                ACTION_HIDE_OVERLAY -> hideOverlay()
                ACTION_RESET_POSITION -> resetOverlayPosition()
                ACTION_PROFILE_UPDATED -> {
                    bydBridge.restartConnection()
                    if (overlayView != null) {
                        renderOverlay()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.exemptAll()
        BydDataCacheWhitelist.ensureWhitelisted(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentScale = prefs.getFloat(KEY_SCALE, 1.0f)

        startInForeground()

        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_DISTANCE)
            addAction(ACTION_HIDE_OVERLAY)
            addAction(ACTION_RESET_POSITION)
            addAction(ACTION_PROFILE_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(testReceiver, filter)
        }

        bydBridge = BydVehicleBridge(this) { _, _ ->
            if (overlayView != null) {
                renderOverlay()
            }
        }
        bydBridge.startListening()
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BYD Maps Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BYD Maps Battery")
                .setContentText("รันตรวจจับระยะทางและระดับแบตเตอรี่รถจาก OBD")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build()

            startForeground(101, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_DISTANCE -> {
                val distance = intent.getDoubleExtra(EXTRA_DISTANCE_KM, -1.0)
                    .takeIf { it > 0 }
                    ?: intent.getFloatExtra(EXTRA_DISTANCE_KM, -1f).toDouble()
                if (distance > 0) {
                    if (kotlin.math.abs(distance - latestDistanceKm) > 10.0) {
                        lockedSingleDestSoc = -1
                    }
                    latestDistanceKm = distance
                    showOverlay()
                    renderOverlay()
                }
            }
            ACTION_HIDE_OVERLAY -> {
                hideOverlay()
            }
            ACTION_RESET_POSITION -> {
                resetOverlayPosition()
            }
            ACTION_PROFILE_UPDATED -> {
                bydBridge.restartConnection()
                if (overlayView != null) {
                    renderOverlay()
                }
            }
        }
        return START_STICKY
    }

    private fun resetOverlayPosition() {
        currentScale = 1.0f
        prefs.edit()
            .putInt(KEY_POS_X, 0)
            .putInt(KEY_POS_Y, 120)
            .putFloat(KEY_SCALE, 1.0f)
            .apply()

        if (latestDistanceKm <= 0.0) {
            latestDistanceKm = 25.0
        }
        showOverlay()
        overlayView?.scaleX = 1.0f
        overlayView?.scaleY = 1.0f
        windowParams?.let { params ->
            params.x = 0
            params.y = 120
            try {
                windowManager?.updateViewLayout(overlayView, params)
            } catch (_: Exception) {}
        }
        renderOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return


        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.layout_battery_pill, null)
        overlayView = view

        tvSocDisplay = view.findViewById(R.id.tv_destination_soc)
        tvRealRange = view.findViewById(R.id.tv_real_range)
        tvMapsDistance = view.findViewById(R.id.tv_maps_distance)
        tvConsumption = view.findViewById(R.id.tv_consumption)
        tvWarningBadge = view.findViewById(R.id.tv_warning_badge)

        containerDestSoc = view.findViewById(R.id.container_dest_soc)
        containerRealRange = view.findViewById(R.id.container_real_range)
        containerMapsDistance = view.findViewById(R.id.container_maps_distance)
        containerConsumption = view.findViewById(R.id.container_consumption)
        divider1 = view.findViewById(R.id.divider_1)
        divider2 = view.findViewById(R.id.divider_2)
        divider3 = view.findViewById(R.id.divider_3)

        // Apply saved scale
        currentScale = prefs.getFloat(KEY_SCALE, 1.0f).coerceIn(0.7f, 1.4f)
        view.scaleX = currentScale
        view.scaleY = currentScale

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        }

        val savedX = prefs.getInt(KEY_POS_X, 0).coerceIn(-920, 920)
        val savedY = prefs.getInt(KEY_POS_Y, 120).coerceIn(0, 1030)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = savedX
            y = savedY
        }
        windowParams = params

        view.scaleX = currentScale
        view.scaleY = currentScale

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale *= detector.scaleFactor
                currentScale = currentScale.coerceIn(0.6f, 1.8f)
                view.scaleX = currentScale
                view.scaleY = currentScale
                prefs.edit().putFloat(KEY_SCALE, currentScale).apply()
                return true
            }
        })

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            if (event.pointerCount == 1) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams?.x ?: 0
                        initialY = windowParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        windowParams?.let { p ->
                            p.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(-920, 920)
                            p.y = (initialY - (event.rawY - initialTouchY).toInt()).coerceIn(0, 1030)
                            windowManager?.updateViewLayout(overlayView, p)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        windowParams?.let { p ->
                            prefs.edit()
                                .putInt(KEY_POS_X, p.x)
                                .putInt(KEY_POS_Y, p.y)
                                .apply()
                        }
                        true
                    }
                    else -> false
                }
            } else {
                true
            }
        }

        try {
            windowManager?.addView(view, params)
            android.util.Log.i("OverlayService", "Overlay successfully added to WindowManager")
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Failed to add overlay to WindowManager: ${e.message}", e)
            overlayView = null
        }
    }

    private fun renderOverlay() {
        if (overlayView == null && latestDistanceKm > 0) {
            showOverlay()
        }
        if (overlayView == null) return

        val currentSoc = bydBridge.currentSoc
        // Overlay must show the live CAN/OBD consumption value. Keep the conservative
        // rounded-up EMA exclusively for destination SoC estimation below.
        // Keep the live CAN/OBD value for the overlay; use conservative EMA only for destination SoC.
        val displayConsumption = bydBridge.currentConsumption
        val calculationConsumption = if (bydBridge.smoothedConsumption > 0.0) {
            bydBridge.smoothedConsumption
        } else {
            bydBridge.currentConsumption
        }
        val liveRemainKwh = bydBridge.remainEnergyKwh
        val realRange = bydBridge.realRangeKm

        val isUserConfiguredModel = VehicleProfileManager.getSelectedPresetId(this) != VehicleProfileManager.PRESET_AUTO

        // Apply user visibility preferences
        val showDestSoc = prefs.getBoolean(KEY_SHOW_DEST_SOC, true)
        val showRealRange = prefs.getBoolean(KEY_SHOW_REAL_RANGE, true)
        val showMapsDist = prefs.getBoolean(KEY_SHOW_MAPS_DIST, true)
        val showConsumption = prefs.getBoolean(KEY_SHOW_CONSUMPTION, true)

        containerDestSoc?.visibility = if (showDestSoc) View.VISIBLE else View.GONE
        containerRealRange?.visibility = if (showRealRange) View.VISIBLE else View.GONE
        containerMapsDistance?.visibility = if (showMapsDist) View.VISIBLE else View.GONE
        containerConsumption?.visibility = if (showConsumption) View.VISIBLE else View.GONE

        divider1?.visibility = if (showDestSoc && (showRealRange || showMapsDist || showConsumption)) View.VISIBLE else View.GONE
        divider2?.visibility = if (showRealRange && (showMapsDist || showConsumption)) View.VISIBLE else View.GONE
        divider3?.visibility = if (showMapsDist && showConsumption) View.VISIBLE else View.GONE

        val (rawDestSoc, _) = EnergyCalculator.calculateDestinationSoc(
            currentSoc = currentSoc,
            remainingDistanceKm = latestDistanceKm,
            consumptionKwhPer100km = calculationConsumption,
            liveRemainEnergyKwh = liveRemainKwh,
            preferUserCapacity = isUserConfiguredModel
        )

        // 1.5% Deadband Window for Single Number Display:
        // Keeps the display locked to `lockedSingleDestSoc` (e.g. ~69%)
        // Only updates when rawDestSoc changes by >= 1.0% to prevent flickering while driving.
        if (lockedSingleDestSoc < 0) {
            lockedSingleDestSoc = kotlin.math.round(rawDestSoc).toInt().coerceIn(0, 100)
        } else {
            val delta = rawDestSoc - lockedSingleDestSoc.toDouble()
            if (kotlin.math.abs(delta) >= 1.0) {
                lockedSingleDestSoc = kotlin.math.round(rawDestSoc).toInt().coerceIn(0, 100)
            }
        }
        val destSoc = lockedSingleDestSoc

        // 6-Tier Dynamic Color (Red 0-10% -> Orange 10-30% -> Yellow 30-50% -> Light Green 50-75% -> Dark Green 75-95% -> Cyan 95-100%)
        val socColor = EnergyCalculator.getDynamicSocColor(destSoc.toDouble())
        tvSocDisplay?.setTextColor(socColor)

        // Calculate Charging Stops Counter
        val stopsNeeded = EnergyCalculator.calculateChargingStops(
            currentSoc = currentSoc,
            remainingDistanceKm = latestDistanceKm,
            consumptionKwhPer100km = calculationConsumption,
            realRangeKm = realRange
        )

        val isBatteryInsufficient = latestDistanceKm > realRange || destSoc <= 0
        val isCriticalLow = destSoc <= 10 || currentSoc <= 10.0

        if (isBatteryInsufficient || stopsNeeded > 0) {
            tvSocDisplay?.text = "~$destSoc%"
            tvWarningBadge?.visibility = View.VISIBLE
            if (stopsNeeded > 0) {
                tvWarningBadge?.text = "⚠️🔌 ชาร์จ ~$stopsNeeded ครั้ง"
                tvWarningBadge?.setBackgroundColor(Color.parseColor("#D50000"))
            } else {
                tvWarningBadge?.text = "⚠️🔌 แบตไม่พอ"
                tvWarningBadge?.setBackgroundColor(Color.parseColor("#D50000"))
            }
        } else if (isCriticalLow) {
            tvSocDisplay?.text = "~$destSoc%"
            tvWarningBadge?.visibility = View.VISIBLE
            tvWarningBadge?.text = "⚠️🪫 แบตวิกฤต"
            tvWarningBadge?.setBackgroundColor(Color.parseColor("#FF1744"))
        } else {
            tvSocDisplay?.text = "~$destSoc%"
            tvWarningBadge?.visibility = View.GONE
        }

        // 2. ระยะที่รถวิ่งได้จริงคงเหลือ (🚗)
        tvRealRange?.text = String.format("%.0f km", realRange)

        // 3. ระยะทางรวมจากจุดปัจจุบันไปจุดปลายทางตาม Google Maps (📍)
        tvMapsDistance?.text = String.format("%.1f km", latestDistanceKm)

        // 4. อัตราสิ้นเปลืองปัจจุบันจาก CAN/OBD (⚡)
        tvConsumption?.text = String.format("%.1f", displayConsumption)

        BydAppWidgetProvider.lastDestText = if (stopsNeeded > 0) {
            String.format("🏁 ปลายทาง: แบต ~$destSoc%% (แวะชาร์จ ~$stopsNeeded ครั้ง, อีก %.1f km)", latestDistanceKm)
        } else {
            String.format("🏁 ปลายทาง: แบตเหลือ ~$destSoc%% (อีก %.1f km)", latestDistanceKm)
        }
        BydAppWidgetProvider.updateAllWidgets(this)
    }

    private fun hideOverlay() {
        lockedSingleDestSoc = -1
        lastRawDestSoc = -1.0
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
        BydAppWidgetProvider.lastDestText = "🏁 Google Maps: แตะเพื่อเริ่มนำทาง"
        BydAppWidgetProvider.updateAllWidgets(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_COMPLETE -> {
                // DiLink 3.0 Critical: Release heavy caches while preserving active navigation view
                lockedSingleDestSoc = -1
            }
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE -> {
                // Background Trim: Reset non-essential calculation states
                latestDistanceKm = 0.0
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(testReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        bydBridge.stopListening()
        hideOverlay()
    }
}
