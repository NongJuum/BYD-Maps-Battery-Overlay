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
import android.provider.Settings
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

    private lateinit var bydBridge: BydVehicleBridge
    private var latestDistanceKm: Double = 0.0
    private var lockedDestSocMin: Int = -1
    private var lockedDestSocMax: Int = -1

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
                            lockedDestSocMin = -1
                            lockedDestSocMax = -1
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
        registerReceiver(testReceiver, filter)

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
                        lockedDestSocMin = -1
                        lockedDestSocMax = -1
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            android.util.Log.w("OverlayService", "Cannot show overlay: SYSTEM_ALERT_WINDOW not yet granted")
            return
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.layout_battery_pill, null)
        overlayView = view

        tvSocDisplay = view.findViewById(R.id.tv_destination_soc)
        tvRealRange = view.findViewById(R.id.tv_real_range)
        tvMapsDistance = view.findViewById(R.id.tv_maps_distance)
        tvConsumption = view.findViewById(R.id.tv_consumption)
        tvWarningBadge = view.findViewById(R.id.tv_warning_badge)

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
        if (overlayView == null) {
            if (Settings.canDrawOverlays(this) && latestDistanceKm > 0) {
                showOverlay()
            }
            if (overlayView == null) return
        }

        val currentSoc = bydBridge.currentSoc
        val consumption = if (bydBridge.smoothedConsumption > 0.0) bydBridge.smoothedConsumption else bydBridge.currentConsumption
        val liveRemainKwh = bydBridge.remainEnergyKwh
        val realRange = bydBridge.realRangeKm

        val isUserConfiguredModel = VehicleProfileManager.getSelectedPresetId(this) != VehicleProfileManager.PRESET_AUTO

        val (rawDestSoc, _) = EnergyCalculator.calculateDestinationSoc(
            currentSoc = currentSoc,
            remainingDistanceKm = latestDistanceKm,
            consumptionKwhPer100km = consumption,
            liveRemainEnergyKwh = liveRemainKwh,
            preferUserCapacity = isUserConfiguredModel
        )

        // 2% Step Hysteresis / Deadband Window:
        // Keeps the display locked to [lockedDestSocMin, lockedDestSocMax]% (e.g. 68-70%)
        // Only steps down when rawDestSoc actually drops below lockedDestSocMin (e.g. < 68.0%)
        // This eliminates flickering and high sensitivity while driving.
        if (lockedDestSocMin < 0 || lockedDestSocMax < 0) {
            val initialMax = kotlin.math.round(rawDestSoc).toInt().coerceIn(0, 100)
            lockedDestSocMax = initialMax
            lockedDestSocMin = (initialMax - 2).coerceAtLeast(0)
        } else {
            if (rawDestSoc < lockedDestSocMin.toDouble()) {
                // Fallen below current minimum: step down
                val diff = lockedDestSocMin.toDouble() - rawDestSoc
                if (diff >= 4.0) {
                    // Significant route change / traffic reroute: resynchronize directly
                    lockedDestSocMax = kotlin.math.round(rawDestSoc).toInt().coerceIn(0, 100)
                    lockedDestSocMin = (lockedDestSocMax - 2).coerceAtLeast(0)
                } else {
                    // Normal 2% step down
                    lockedDestSocMax = lockedDestSocMin
                    lockedDestSocMin = (lockedDestSocMax - 2).coerceAtLeast(0)
                }
            } else if (rawDestSoc > (lockedDestSocMax.toDouble() + 0.5)) {
                // Regenerated battery (e.g. long downhill) or route shortened: step up
                val diff = rawDestSoc - lockedDestSocMax.toDouble()
                if (diff >= 4.0) {
                    lockedDestSocMax = kotlin.math.round(rawDestSoc).toInt().coerceIn(0, 100)
                    lockedDestSocMin = (lockedDestSocMax - 2).coerceAtLeast(0)
                } else {
                    lockedDestSocMin = lockedDestSocMax
                    lockedDestSocMax = (lockedDestSocMin + 2).coerceIn(0, 100)
                }
            }
        }

        val destSocMin = lockedDestSocMin
        val destSocMax = lockedDestSocMax

        val isBatteryInsufficient = latestDistanceKm > realRange || rawDestSoc <= 2.0 || destSocMin <= 0
        val isLowBattery = destSocMin <= 20 || currentSoc <= 20.0

        val (socColor, badgeText, showBadge) = when {
            isBatteryInsufficient -> {
                Triple(Color.parseColor("#FF1744"), "⚠️🔌", true)
            }
            isLowBattery -> {
                Triple(Color.parseColor("#FFD600"), "⚠️🪫", true)
            }
            else -> {
                Triple(Color.parseColor("#69F0AE"), "", false)
            }
        }

        // 1. แบตเตอรี่ปลายทางแบบช่วงปลอดภัย (🏁🔋 เช่น 88-90%)
        tvSocDisplay?.setTextColor(socColor)
        if (isBatteryInsufficient) {
            tvSocDisplay?.text = if (destSocMax > 0) "0-$destSocMax%" else "0%"
        } else {
            tvSocDisplay?.text = "$destSocMin-$destSocMax%"
        }

        // 2. ระยะที่รถวิ่งได้จริงคงเหลือ (🚗)
        tvRealRange?.text = String.format("%.0f km", realRange)

        // 3. ระยะทางรวมจากจุดปัจจุบันไปจุดปลายทางตาม Google Maps (📍)
        tvMapsDistance?.text = String.format("%.1f km", latestDistanceKm)

        // 4. อัตราสิ้นเปลืองปัจจุบันจาก CAN/OBD (⚡)
        tvConsumption?.text = String.format("%.1f", consumption)

        if (showBadge) {
            tvWarningBadge?.visibility = View.VISIBLE
            tvWarningBadge?.text = badgeText
            tvWarningBadge?.setBackgroundColor(
                if (isBatteryInsufficient) Color.parseColor("#D50000") else Color.parseColor("#F57F17")
            )
        } else {
            tvWarningBadge?.visibility = View.GONE
        }

        BydAppWidgetProvider.lastDestText = if (isBatteryInsufficient) {
            "🏁 ⚠️ แบตฉิวเฉียด/ไม่พอถึงจุดหมาย! (คาดการณ์ $destSocMin-$destSocMax%)"
        } else {
            String.format("🏁 ปลายทาง: แบตเหลือ %d-%d%% (อีก %.1f km)", destSocMin, destSocMax, latestDistanceKm)
        }
        BydAppWidgetProvider.updateAllWidgets(this)
    }

    private fun hideOverlay() {
        lockedDestSocMin = -1
        lockedDestSocMax = -1
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
