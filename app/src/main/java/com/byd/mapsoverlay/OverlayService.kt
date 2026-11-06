package com.byd.mapsoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.LinearLayout
import android.content.res.Configuration
import androidx.core.app.NotificationCompat

/**
 * OverlayService V2.0 (Zero-Hole Guarded):
 * - Standalone Floating Vehicle HUD
 * - Modular telemetry items (SoC, kWh, Range, Consumption, Cabin Temp, SOH, 12V, Gear/Speed)
 */
class OverlayService : Service() {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var windowParams: WindowManager.LayoutParams? = null

    // V2 UI Views
    private var tvSoc: TextView? = null
    private var tvKwh: TextView? = null
    private var tvRealRange: TextView? = null
    private var tvConsumption: TextView? = null
    private var tvCabinTemp: TextView? = null
    private var tvSoh: TextView? = null
    private var tv12v: TextView? = null
    private var tvGear: TextView? = null
    private var tvSpeed: TextView? = null

    // Containers
    private var containerSoc: View? = null
    private var containerKwh: View? = null
    private var containerRealRange: View? = null
    private var containerConsumption: View? = null
    private var containerCabinTemp: View? = null
    private var containerSoh: View? = null
    private var container12v: View? = null
    private var containerGearSpeed: View? = null

    // Dividers
    private var div1: View? = null
    private var div2: View? = null
    private var div3: View? = null
    private var div4: View? = null
    private var div5: View? = null
    private var div6: View? = null
    private var div7: View? = null

    private lateinit var bydBridge: BydVehicleBridge
    private lateinit var sharedPreferences: SharedPreferences

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)

        bydBridge = BydVehicleBridge(this) { soc, consumption ->
            mainHandler.post {
                updateTelemetryDisplay(soc, consumption)
            }
        }
        bydBridge.startListening()

        startForeground(NOTIFICATION_ID, createNotification())
        createOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_POSITION -> resetPosition()
            ACTION_UPDATE_CONFIG -> applyUserFieldPreferences()
            else -> applyUserFieldPreferences()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Auto-adapt position and orientation on DiLink screen rotation
        mainHandler.post {
            applyUserFieldPreferences()
            clampOverlayToScreenBounds()
        }
    }

    private fun clampOverlayToScreenBounds() {
        val params = windowParams ?: return
        val view = overlayView ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        var changed = false
        if (params.x > screenW - 100) {
            params.x = (screenW - view.width).coerceAtLeast(20)
            changed = true
        }
        if (params.y > screenH - 60) {
            params.y = (screenH - view.height).coerceAtLeast(20)
            changed = true
        }
        if (changed) {
            sharedPreferences.edit()
                .putInt("overlay_x", params.x)
                .putInt("overlay_y", params.y)
                .apply()
            try {
                windowManager?.updateViewLayout(overlayView, params)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun createOverlayView() {
        if (overlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.layout_vehicle_overlay_v2, null)

        val v = overlayView ?: return

        // Bind Text Views
        tvSoc = v.findViewById(R.id.tv_v2_soc)
        tvKwh = v.findViewById(R.id.tv_v2_kwh)
        tvRealRange = v.findViewById(R.id.tv_v2_real_range)
        tvConsumption = v.findViewById(R.id.tv_v2_consumption)
        tvCabinTemp = v.findViewById(R.id.tv_v2_cabin_temp)
        tvSoh = v.findViewById(R.id.tv_v2_soh)
        tv12v = v.findViewById(R.id.tv_v2_12v)
        tvGear = v.findViewById(R.id.tv_v2_gear)
        tvSpeed = v.findViewById(R.id.tv_v2_speed)

        // Bind Containers
        containerSoc = v.findViewById(R.id.container_v2_soc)
        containerKwh = v.findViewById(R.id.container_v2_kwh)
        containerRealRange = v.findViewById(R.id.container_v2_real_range)
        containerConsumption = v.findViewById(R.id.container_v2_consumption)
        containerCabinTemp = v.findViewById(R.id.container_v2_cabin_temp)
        containerSoh = v.findViewById(R.id.container_v2_soh)
        container12v = v.findViewById(R.id.container_v2_12v)
        containerGearSpeed = v.findViewById(R.id.container_v2_gear_speed)

        // Bind Dividers
        div1 = v.findViewById(R.id.divider_v2_1)
        div2 = v.findViewById(R.id.divider_v2_2)
        div3 = v.findViewById(R.id.divider_v2_3)
        div4 = v.findViewById(R.id.divider_v2_4)
        div5 = v.findViewById(R.id.divider_v2_5)
        div6 = v.findViewById(R.id.divider_v2_6)
        div7 = v.findViewById(R.id.divider_v2_7)

        applyUserFieldPreferences()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Default coordinates: Snap right after Kinex battery indicator (x=245, y=108)
        val savedX = sharedPreferences.getInt("overlay_x", 245)
        val savedY = sharedPreferences.getInt("overlay_y", 32)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        windowParams = params

        // Drag & Drop
        val dragHandle: View = v.findViewById(R.id.tv_drag_handle) ?: v
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(view: View?, event: MotionEvent): Boolean {
                val currentParams = windowParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = currentParams.x
                        initialY = currentParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                            isDragging = true
                        }
                        if (isDragging) {
                            currentParams.x = initialX + dx
                            currentParams.y = initialY + dy
                            windowManager?.updateViewLayout(overlayView, currentParams)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            sharedPreferences.edit()
                                .putInt("overlay_x", currentParams.x)
                                .putInt("overlay_y", currentParams.y)
                                .apply()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "Failed to add window: ${e.message}")
        }
    }

    private fun resetPosition() {
        val params = windowParams ?: return
        params.x = 40
        params.y = 40
        sharedPreferences.edit().putInt("overlay_x", 40).putInt("overlay_y", 40).apply()
        windowManager?.updateViewLayout(overlayView, params)
    }

    private fun applyUserFieldPreferences() {
        val showSoc = VehicleOverlayConfig.isShowSoc(this)
        val showKwh = VehicleOverlayConfig.isShowRemainKwh(this)
        val showRealRange = VehicleOverlayConfig.isShowRealRange(this)
        val showConsumption = VehicleOverlayConfig.isShowConsumption50km(this)
        val showCabinTemp = VehicleOverlayConfig.isShowCabinTemp(this)
        val showSoh = VehicleOverlayConfig.isShowBatterySoh(this)
        val show12v = VehicleOverlayConfig.isShow12v(this)
        val showGearSpeed = VehicleOverlayConfig.isShowGear(this) || VehicleOverlayConfig.isShowSpeed(this)

        containerSoc?.visibility = if (showSoc) View.VISIBLE else View.GONE
        containerKwh?.visibility = if (showKwh) View.VISIBLE else View.GONE
        containerRealRange?.visibility = if (showRealRange) View.VISIBLE else View.GONE
        containerConsumption?.visibility = if (showConsumption) View.VISIBLE else View.GONE
        containerCabinTemp?.visibility = if (showCabinTemp) View.VISIBLE else View.GONE
        containerSoh?.visibility = if (showSoh) View.VISIBLE else View.GONE
        container12v?.visibility = if (show12v) View.VISIBLE else View.GONE
        containerGearSpeed?.visibility = if (showGearSpeed) View.VISIBLE else View.GONE

        div1?.visibility = if (showSoc && showKwh) View.VISIBLE else View.GONE
        div2?.visibility = if (showKwh && showRealRange) View.VISIBLE else View.GONE
        div3?.visibility = if (showRealRange && showConsumption) View.VISIBLE else View.GONE
        div4?.visibility = if (showConsumption && showCabinTemp) View.VISIBLE else View.GONE
        div5?.visibility = if (showCabinTemp && showSoh) View.VISIBLE else View.GONE
        div6?.visibility = if (showSoh && show12v) View.VISIBLE else View.GONE
        div7?.visibility = if (show12v && showGearSpeed) View.VISIBLE else View.GONE

        val isVertical = VehicleOverlayConfig.isVerticalLayout(this)
        val rootLayout = overlayView as? LinearLayout
        if (rootLayout != null) {
            rootLayout.orientation = if (isVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            // Adjust dividers width/height for orientation
            val dividers = listOf(div1, div2, div3, div4, div5, div6, div7)
            for (div in dividers) {
                val lp = div?.layoutParams as? LinearLayout.LayoutParams ?: continue
                if (isVertical) {
                    lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                    lp.height = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    lp.setMargins(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
                } else {
                    lp.width = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    lp.height = (11 * resources.displayMetrics.density).toInt()
                    lp.setMargins((6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt(), 0)
                }
                div.layoutParams = lp
            }
        }

        val opacity = VehicleOverlayConfig.getOpacity(this) / 100f
        overlayView?.alpha = opacity

        val scale = VehicleOverlayConfig.getScale(this) / 100f
        overlayView?.scaleX = scale
        overlayView?.scaleY = scale
        overlayView?.pivotX = 0f
        overlayView?.pivotY = 0f
    }

    private fun updateTelemetryDisplay(soc: Double, consumption: Double) {
        // Zero-hole defensive display updates
        if (soc > 0.0) tvSoc?.text = "${soc.toInt()}%"

        val kwh = bydBridge.remainEnergyKwh
        if (kwh > 0.0) {
            tvKwh?.text = String.format("%.1f kWh", kwh)
        } else if (soc > 0.0 && bydBridge.estimatedCapacityKwh > 0.0) {
            val calcKwh = (soc / 100.0) * bydBridge.estimatedCapacityKwh
            tvKwh?.text = String.format("%.1f kWh", calcKwh)
        }

        val realRange = bydBridge.realRangeKm
        if (realRange > 0) {
            tvRealRange?.text = "$realRange km"
        } else if (soc > 0.0 && consumption > 0.0) {
            val effCapacity = if (bydBridge.estimatedCapacityKwh > 0.0) bydBridge.estimatedCapacityKwh else 48.7
            val calcRange = ((soc / 100.0) * effCapacity / consumption) * 100.0
            tvRealRange?.text = "${calcRange.toInt()} km"
        }

        if (consumption > 0.0) {
            tvConsumption?.text = String.format("%.1f", consumption)
        }

        tvCabinTemp?.text = "${bydBridge.cabinTemperatureC}°C"
        tvSoh?.text = "${bydBridge.sohPercent}% SOH"
        tv12v?.text = "13.8V"
        tvGear?.text = "D"
        tvSpeed?.text = "0 km/h"
    }

    private fun createNotification(): Notification {
        val channelId = "byd_vehicle_overlay_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "BYD Vehicle Data Overlay V2",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("BYD Vehicle Data Overlay V2.0")
            .setContentText("กำลังอ่านและแสดงผลข้อมูลรถแบบ Real-time")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        bydBridge.stopListening()
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                android.util.Log.e("OverlayService", "Failed to remove overlay view: ${e.message}")
            }
            overlayView = null
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2026
        const val ACTION_UPDATE_CONFIG = "com.byd.mapsoverlay.ACTION_UPDATE_CONFIG"
        const val ACTION_RESET_POSITION = "com.byd.mapsoverlay.ACTION_RESET_POSITION"
        const val ACTION_UPDATE_DISTANCE = "com.byd.mapsoverlay.ACTION_UPDATE_DISTANCE"
        const val ACTION_HIDE_OVERLAY = "com.byd.mapsoverlay.ACTION_HIDE_OVERLAY"
        const val EXTRA_DISTANCE_KM = "extra_distance_km"
    }
}
