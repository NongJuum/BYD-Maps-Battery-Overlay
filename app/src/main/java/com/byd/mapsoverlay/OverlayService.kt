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
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
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

    private fun updateDistanceSafely(newDistance: Double) {
        if (newDistance <= 0.0) return

        // Plausibility Guard: Prevent Next-Turn Step Glitch (e.g. dropping from 80 km to 0.4 km)
        if (latestDistanceKm > 3.0 && newDistance < (latestDistanceKm * 0.25)) {
            // Sudden drop over 75% while cruising indicates a turn-step (e.g. "400 m left turn")
            // Ignore turn-step glitch to prevent Yo-Yo Dest SoC fluctuations!
            android.util.Log.w("OverlayService", "Ignored sudden distance drop glitch: $latestDistanceKm km -> $newDistance km")
            return
        }

        if (kotlin.math.abs(newDistance - latestDistanceKm) > 10.0) {
            lockedSingleDestSoc = -1
        }
        latestDistanceKm = newDistance
        // Persist last known distance for self-healing recovery if LMK kills service
        prefs.edit().putFloat("last_known_distance_km", newDistance.toFloat()).apply()
        showOverlay()
        renderOverlay()
    }
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
        private const val KEY_POS_X_PORTRAIT = "pos_x_portrait"
        private const val KEY_POS_Y_PORTRAIT = "pos_y_portrait"
        private const val KEY_POS_X_LANDSCAPE = "pos_x_landscape"
        private const val KEY_POS_Y_LANDSCAPE = "pos_y_landscape"
        const val KEY_SCALE = "scale"
        const val KEY_SHOW_DEST_SOC = "show_dest_soc"
        const val KEY_SHOW_REAL_RANGE = "show_real_range"
        const val KEY_SHOW_MAPS_DIST = "show_maps_dist"
        const val KEY_SHOW_CONSUMPTION = "show_consumption"
        const val KEY_OVERLAY_THEME = "overlay_theme"
        const val THEME_CAPSULE = 0
        const val THEME_COMPACT = 1
        const val THEME_VERTICAL = 2
        private const val CHANNEL_ID = "byd_overlay_channel"
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_DISTANCE -> {
                    val dist = intent.getDoubleExtra(EXTRA_DISTANCE_KM, -1.0)
                        .takeIf { it > 0 }
                        ?: intent.getFloatExtra(EXTRA_DISTANCE_KM, -1f).toDouble()
                    updateDistanceSafely(dist)
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
        // Self-Healing State Recovery (Low-Memory Killer Resilience):
        // If system restarted service after kill (intent == null), restore state from preferences seamlessly!
        if (intent == null) {
            val savedDistance = prefs.getFloat("last_known_distance_km", -1f).toDouble()
            if (savedDistance > 0.0) {
                latestDistanceKm = savedDistance
                showOverlay()
                renderOverlay()
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_UPDATE_DISTANCE -> {
                val distance = intent.getDoubleExtra(EXTRA_DISTANCE_KM, -1.0)
                    .takeIf { it > 0 }
                    ?: intent.getFloatExtra(EXTRA_DISTANCE_KM, -1f).toDouble()
                updateDistanceSafely(distance)
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

    private fun isPortrait(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    private fun getSavedPosition(): Pair<Int, Int> {
        val portrait = isPortrait()
        val defaultX = 0
        val defaultY = if (portrait) 180 else 120
        val keyX = if (portrait) KEY_POS_X_PORTRAIT else KEY_POS_X_LANDSCAPE
        val keyY = if (portrait) KEY_POS_Y_PORTRAIT else KEY_POS_Y_LANDSCAPE

        // Fallback to legacy generic keys if mode-specific hasn't been set yet
        val x = prefs.getInt(keyX, prefs.getInt(KEY_POS_X, defaultX))
        val y = prefs.getInt(keyY, prefs.getInt(KEY_POS_Y, defaultY))
        return clampToSafeZone(x, y)
    }

    private fun saveCurrentPosition(x: Int, y: Int) {
        val (clampedX, clampedY) = clampToSafeZone(x, y)
        val portrait = isPortrait()
        val keyX = if (portrait) KEY_POS_X_PORTRAIT else KEY_POS_X_LANDSCAPE
        val keyY = if (portrait) KEY_POS_Y_PORTRAIT else KEY_POS_Y_LANDSCAPE

        prefs.edit()
            .putInt(keyX, clampedX)
            .putInt(keyY, clampedY)
            .putInt(KEY_POS_X, clampedX)
            .putInt(KEY_POS_Y, clampedY)
            .apply()
    }

    private fun clampToSafeZone(x: Int, y: Int): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // In gravity = BOTTOM | CENTER_HORIZONTAL:
        // x moves from -halfWidth to +halfWidth
        // y moves from 0 (bottom screen edge) to screenH (top screen edge / status bar)
        // DiLink 3.0 safe margins:
        // Bottom Air-con bar = approx 80dp (100px) -> min Y = 80
        // Top Status bar: อนุญาตให้ลากขึ้นไปทับแถบ Status Bar ด้านบนสุดได้เลย (คนขับนิยมวางตรงนี้เพราะพื้นที่ว่างเยอะ)
        // เราตั้งเพดานบนให้ลากชิดขอบบนสุดได้ถึง screenH - 10px (ไม่หลุดทะลุจอ)
        val halfW = (screenW / 2) - 40
        val clampedX = x.coerceIn(-halfW, halfW)
        val clampedY = y.coerceIn(80, (screenH - 10).coerceAtLeast(300))
        return Pair(clampedX, clampedY)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Auto-snap overlay to orientation-specific saved coordinates
        if (overlayView != null && windowParams != null) {
            val (targetX, targetY) = getSavedPosition()
            windowParams?.let { p ->
                p.x = targetX
                p.y = targetY
                try {
                    windowManager?.updateViewLayout(overlayView, p)
                } catch (_: Exception) {}
            }
        }
    }

    private fun resetOverlayPosition() {
        currentScale = 1.0f
        val (defaultX, defaultY) = if (isPortrait()) Pair(0, 180) else Pair(0, 120)
        saveCurrentPosition(defaultX, defaultY)
        prefs.edit().putFloat(KEY_SCALE, 1.0f).apply()

        if (latestDistanceKm <= 0.0) {
            latestDistanceKm = 25.0
        }
        showOverlay()
        overlayView?.scaleX = 1.0f
        overlayView?.scaleY = 1.0f
        windowParams?.let { params ->
            params.x = defaultX
            params.y = defaultY
            try {
                windowManager?.updateViewLayout(overlayView, params)
            } catch (_: Exception) {}
        }
        renderOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return

        val theme = prefs.getInt(KEY_OVERLAY_THEME, THEME_CAPSULE)
        val layoutRes = when (theme) {
            THEME_COMPACT -> R.layout.layout_battery_pill_compact
            THEME_VERTICAL -> R.layout.layout_battery_pill_vertical
            else -> R.layout.layout_battery_pill
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(layoutRes, null)
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

        val (savedX, savedY) = getSavedPosition()

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
                            val rawTargetX = initialX + (event.rawX - initialTouchX).toInt()
                            val rawTargetY = initialY - (event.rawY - initialTouchY).toInt()
                            val (clampedX, clampedY) = clampToSafeZone(rawTargetX, rawTargetY)
                            p.x = clampedX
                            p.y = clampedY
                            windowManager?.updateViewLayout(overlayView, p)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        windowParams?.let { p ->
                            saveCurrentPosition(p.x, p.y)
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

        // 1. Night Auto-Dimming & Low-Glow Synchronization
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        overlayView?.alpha = if (isNightMode) 0.72f else 0.95f

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
        // Match Maps Distance color to Dest SoC for harmonious, elegant cockpit aesthetics
        tvMapsDistance?.setTextColor(socColor)

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
