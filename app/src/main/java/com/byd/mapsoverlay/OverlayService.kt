package com.byd.mapsoverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * OverlayService V2.1:
 * - Standalone Floating Vehicle HUD
 * - Displays all selectable BYD Telemetry metrics (including HV Battery Temp & Cell Voltage)
 * - Preserves background-only opacity while keeping text 100% crisp
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
    private var tvDashRange: TextView? = null
    private var tvHvTemp: TextView? = null
    private var tvCellVolt: TextView? = null
    private var tvConsumption: TextView? = null
    private var tvInstPower: TextView? = null
    private var tvOutTemp: TextView? = null
    private var tvSoh: TextView? = null
    private var tv12v: TextView? = null
    private var tvTpms: TextView? = null
    private var tvOdo: TextView? = null
    private var tvGear: TextView? = null
    private var tvSpeed: TextView? = null

    // Containers
    private var containerSoc: View? = null
    private var containerKwh: View? = null
    private var containerRealRange: View? = null
    private var containerDashRange: View? = null
    private var containerHvTemp: View? = null
    private var containerCellVolt: View? = null
    private var containerConsumption: View? = null
    private var containerInstPower: View? = null
    private var containerOutTemp: View? = null
    private var containerSoh: View? = null
    private var container12v: View? = null
    private var containerTpms: View? = null
    private var containerOdo: View? = null
    private var containerGearSpeed: View? = null

    // Dividers
    private var allDividers: List<View?> = emptyList()

    private lateinit var bydBridge: BydVehicleBridge
    private lateinit var sharedPreferences: SharedPreferences

    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mainHandler.post {
                ensureOverlayAttachedAndVisible()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.exemptAll()
        AutoPermissionHelper.ensureTelemetryDaemon(this)

        sharedPreferences = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)

        bydBridge = BydVehicleBridge(this) { soc, consumption ->
            mainHandler.post {
                updateTelemetryDisplay(soc, consumption)
            }
        }
        bydBridge.startListening()

        startForeground(NOTIFICATION_ID, createNotification())
        createOverlayView()

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction("com.byd.intent.action.POWER_ON")
            addAction("com.byd.intent.action.ACC_ON")
        }
        try {
            registerReceiver(wakeReceiver, filter)
        } catch (_: Throwable) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESET_POSITION -> resetPosition()
            ACTION_UPDATE_CONFIG -> {
                applyUserFieldPreferences()
                updateTelemetryDisplay(bydBridge.currentSoc, bydBridge.currentConsumption)
            }
            ACTION_CAR_WAKE, ACTION_SHOW_OVERLAY -> {
                ensureOverlayAttachedAndVisible()
            }
            else -> {
                ensureOverlayAttachedAndVisible()
            }
        }
        return START_STICKY
    }

    private fun ensureOverlayAttachedAndVisible() {
        if (overlayView == null) {
            createOverlayView()
        } else {
            overlayView?.visibility = View.VISIBLE
            clampOverlayToScreenBounds()
            windowParams?.let { lp ->
                try {
                    windowManager?.updateViewLayout(overlayView, lp)
                } catch (_: Throwable) {}
            }
        }
        applyUserFieldPreferences()
        if (::bydBridge.isInitialized) {
            updateTelemetryDisplay(bydBridge.currentSoc, bydBridge.currentConsumption)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            applyUserFieldPreferences()
            clampOverlayToScreenBounds()
        }
    }

    private fun clampOverlayToScreenBounds() {
        val params = windowParams ?: return
        if (VehicleOverlayConfig.isStockBarMode(this)) {
            params.x = 245
            params.y = 0
            try {
                windowManager?.updateViewLayout(overlayView, params)
            } catch (_: Throwable) {}
            return
        }
        val view = overlayView ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        if (screenW < 500 || screenH < 300) return

        val viewW = if (view.width > 0) view.width else (300 * dm.density).toInt()
        val viewH = if (view.height > 0) view.height else (40 * dm.density).toInt()

        val maxX = (screenW - viewW).coerceAtLeast(0)
        val maxY = (screenH - viewH).coerceAtLeast(0)

        var changed = false
        if (params.x > maxX) {
            params.x = maxX
            changed = true
        }
        if (params.y > maxY) {
            params.y = maxY
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
        tvDashRange = v.findViewById(R.id.tv_v2_dash_range)
        tvHvTemp = v.findViewById(R.id.tv_v2_hv_temp)
        tvCellVolt = v.findViewById(R.id.tv_v2_cell_volt)
        tvConsumption = v.findViewById(R.id.tv_v2_consumption)
        tvInstPower = v.findViewById(R.id.tv_v2_inst_power)
        tvOutTemp = v.findViewById(R.id.tv_v2_out_temp)
        tvSoh = v.findViewById(R.id.tv_v2_soh)
        tv12v = v.findViewById(R.id.tv_v2_12v)
        tvTpms = v.findViewById(R.id.tv_v2_tpms)
        tvOdo = v.findViewById(R.id.tv_v2_odo)
        tvGear = v.findViewById(R.id.tv_v2_gear)
        tvSpeed = v.findViewById(R.id.tv_v2_speed)

        // Bind Containers
        containerSoc = v.findViewById(R.id.container_v2_soc)
        containerKwh = v.findViewById(R.id.container_v2_kwh)
        containerRealRange = v.findViewById(R.id.container_v2_real_range)
        containerDashRange = v.findViewById(R.id.container_v2_dash_range)
        containerHvTemp = v.findViewById(R.id.container_v2_hv_temp)
        containerCellVolt = v.findViewById(R.id.container_v2_cell_volt)
        containerConsumption = v.findViewById(R.id.container_v2_consumption)
        containerInstPower = v.findViewById(R.id.container_v2_inst_power)
        containerOutTemp = v.findViewById(R.id.container_v2_out_temp)
        containerSoh = v.findViewById(R.id.container_v2_soh)
        container12v = v.findViewById(R.id.container_v2_12v)
        containerTpms = v.findViewById(R.id.container_v2_tpms)
        containerOdo = v.findViewById(R.id.container_v2_odo)
        containerGearSpeed = v.findViewById(R.id.container_v2_gear_speed)

        // Bind Dividers
        allDividers = listOf(
            v.findViewById(R.id.divider_v2_1),
            v.findViewById(R.id.divider_v2_2),
            v.findViewById(R.id.divider_v2_dash_range),
            v.findViewById(R.id.divider_v2_hv_temp),
            v.findViewById(R.id.divider_v2_cell_volt),
            v.findViewById(R.id.divider_v2_3),
            v.findViewById(R.id.divider_v2_inst_power),
            v.findViewById(R.id.divider_v2_out_temp),
            v.findViewById(R.id.divider_v2_5),
            v.findViewById(R.id.divider_v2_6),
            v.findViewById(R.id.divider_v2_tpms),
            v.findViewById(R.id.divider_v2_odo),
            v.findViewById(R.id.divider_v2_7)
        )

        applyUserFieldPreferences()

        val candidateTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            )
        } else {
            listOf(
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            )
        }

        val isStockBar = VehicleOverlayConfig.isStockBarMode(this)
        val savedX = if (isStockBar) 245 else sharedPreferences.getInt("overlay_x", 245)
        val savedY = if (isStockBar) 0 else sharedPreferences.getInt("overlay_y", 32)

        for (layoutType in candidateTypes) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = savedX
                y = savedY
            }

            try {
                windowManager?.addView(overlayView, params)
                windowParams = params
                break
            } catch (e: Exception) {
                android.util.Log.w("OverlayService", "Layout type $layoutType rejected: ${e.message}")
            }
        }

        // Allow dragging from anywhere on the floating pill (only in floating mode)
        v.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(view: View?, event: MotionEvent): Boolean {
                if (VehicleOverlayConfig.isStockBarMode(this@OverlayService)) return false
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
                        if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val screenWidth = resources.displayMetrics.widthPixels
                            val screenHeight = resources.displayMetrics.heightPixels
                            val viewWidth = overlayView?.width ?: 300
                            val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
                            val maxY = (screenHeight - (overlayView?.height ?: 60)).coerceAtLeast(0)
                            currentParams.x = (initialX + dx).coerceIn(0, maxX)
                            currentParams.y = (initialY + dy).coerceIn(0, maxY)
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

        // Auto Boundary Enforcer: if the overlay expands or is near the right screen edge,
        // automatically shift it so that the tail end is NEVER cut off! (only for floating mode)
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (VehicleOverlayConfig.isStockBarMode(this)) return@addOnLayoutChangeListener
            val cp = windowParams ?: return@addOnLayoutChangeListener
            val w = v.width
            if (w <= 0) return@addOnLayoutChangeListener
            val sw = resources.displayMetrics.widthPixels
            val maxX = (sw - w).coerceAtLeast(0)
            if (cp.x > maxX) {
                cp.x = maxX
                try {
                    windowManager?.updateViewLayout(v, cp)
                    sharedPreferences.edit().putInt("overlay_x", maxX).apply()
                } catch (_: Throwable) {}
            }
        }
    }

    private fun resetPosition() {
        val params = windowParams ?: return
        if (VehicleOverlayConfig.isStockBarMode(this)) {
            params.x = 245
            params.y = 0
            sharedPreferences.edit().putInt("overlay_x", 245).putInt("overlay_y", 0).apply()
            try {
                windowManager?.updateViewLayout(overlayView, params)
            } catch (_: Exception) {}
            return
        }
        val view = overlayView ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val viewW = if (view.width > 0) view.width else (400 * dm.density).toInt()
        val viewH = if (view.height > 0) view.height else (40 * dm.density).toInt()

        val centerX = ((screenW - viewW) / 2).coerceAtLeast(20)
        val centerY = ((screenH - viewH) / 2).coerceAtLeast(20)

        params.x = centerX
        params.y = centerY
        sharedPreferences.edit().putInt("overlay_x", centerX).putInt("overlay_y", centerY).apply()
        try {
            windowManager?.updateViewLayout(overlayView, params)
        } catch (_: Exception) {}
    }

    private fun applyUserFieldPreferences() {
        val showSoc = VehicleOverlayConfig.isShowSoc(this)
        val showKwh = VehicleOverlayConfig.isShowRemainKwh(this)
        val showRealRange = VehicleOverlayConfig.isShowRealRange(this)
        val showDashRange = VehicleOverlayConfig.isShowDashboardRange(this)
        val showHvTemp = VehicleOverlayConfig.isShowHvBattTemp(this)
        val showCellVolt = VehicleOverlayConfig.isShowCellVoltage(this)
        val showConsumption = VehicleOverlayConfig.isShowConsumption50km(this)
        val showInstPower = VehicleOverlayConfig.isShowInstantPower(this)
        val showOutTemp = VehicleOverlayConfig.isShowOutsideTemp(this)
        val showSoh = VehicleOverlayConfig.isShowBatterySoh(this)
        val show12v = VehicleOverlayConfig.isShow12v(this)
        val showTpms = VehicleOverlayConfig.isShowTpmsPressure(this)
        val showOdo = VehicleOverlayConfig.isShowOdo(this)
        val showGear = VehicleOverlayConfig.isShowGear(this)
        val showSpeed = VehicleOverlayConfig.isShowSpeed(this)
        val showGearSpeed = showGear || showSpeed

        containerSoc?.visibility = if (showSoc) View.VISIBLE else View.GONE
        containerKwh?.visibility = if (showKwh) View.VISIBLE else View.GONE
        containerRealRange?.visibility = if (showRealRange) View.VISIBLE else View.GONE
        containerDashRange?.visibility = if (showDashRange) View.VISIBLE else View.GONE
        containerHvTemp?.visibility = if (showHvTemp) View.VISIBLE else View.GONE
        containerCellVolt?.visibility = if (showCellVolt) View.VISIBLE else View.GONE
        containerConsumption?.visibility = if (showConsumption) View.VISIBLE else View.GONE
        containerInstPower?.visibility = if (showInstPower) View.VISIBLE else View.GONE
        containerOutTemp?.visibility = if (showOutTemp) View.VISIBLE else View.GONE
        containerSoh?.visibility = if (showSoh) View.VISIBLE else View.GONE
        container12v?.visibility = if (show12v) View.VISIBLE else View.GONE
        containerTpms?.visibility = if (showTpms) View.VISIBLE else View.GONE
        containerOdo?.visibility = if (showOdo) View.VISIBLE else View.GONE
        containerGearSpeed?.visibility = if (showGearSpeed) View.VISIBLE else View.GONE
        tvGear?.visibility = if (showGear) View.VISIBLE else View.GONE
        tvSpeed?.visibility = if (showSpeed) View.VISIBLE else View.GONE

        // Smart Divider Visibility: show divider only if the container before it is visible AND at least one container after it is visible
        val rootLayout = overlayView as? LinearLayout
        if (rootLayout != null) {
            val isVertical = VehicleOverlayConfig.isVerticalLayout(this)
            rootLayout.orientation = if (isVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

            for (div in allDividers) {
                if (div == null) continue
                val lp = div.layoutParams as? LinearLayout.LayoutParams ?: continue
                if (isVertical) {
                    lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                    lp.height = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    lp.setMargins(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
                } else {
                    lp.width = (1 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    lp.height = (11 * resources.displayMetrics.density).toInt()
                    lp.setMargins((3 * resources.displayMetrics.density).toInt(), 0, (3 * resources.displayMetrics.density).toInt(), 0)
                }
                div.layoutParams = lp
            }

            // Compute clean divider visibility dynamically
            val orderedPairs = listOf(
                Pair(containerSoc, allDividers.getOrNull(0)),
                Pair(containerKwh, allDividers.getOrNull(1)),
                Pair(containerRealRange, allDividers.getOrNull(2)),
                Pair(containerDashRange, allDividers.getOrNull(3)),
                Pair(containerHvTemp, allDividers.getOrNull(4)),
                Pair(containerCellVolt, allDividers.getOrNull(5)),
                Pair(containerConsumption, allDividers.getOrNull(6)),
                Pair(containerInstPower, allDividers.getOrNull(7)),
                Pair(containerOutTemp, allDividers.getOrNull(8)),
                Pair(containerSoh, allDividers.getOrNull(9)),
                Pair(container12v, allDividers.getOrNull(10)),
                Pair(containerTpms, allDividers.getOrNull(11)),
                Pair(containerOdo, allDividers.getOrNull(12))
            )
            val allContainersAfter = orderedPairs.map { it.first } + listOf(containerGearSpeed)
            for (idx in orderedPairs.indices) {
                val (cont, div) = orderedPairs[idx]
                val contVis = cont?.visibility == View.VISIBLE
                val anyLaterVis = allContainersAfter.drop(idx + 1).any { it?.visibility == View.VISIBLE }
                div?.visibility = if (contVis && anyLaterVis) View.VISIBLE else View.GONE
            }
        }

        // จัดการโหมดการแสดงผล (Stock Bar Mode vs Floating Pill)
        val isStockBar = VehicleOverlayConfig.isStockBarMode(this)
        val dragHandle = overlayView?.findViewById<View>(R.id.tv_drag_handle)
        if (isStockBar) {
            dragHandle?.visibility = View.GONE
            overlayView?.setBackgroundResource(R.drawable.bg_stock_bar_mode)
            overlayView?.elevation = 0f
            windowParams?.let { lp ->
                lp.x = 245
                lp.y = 0
                try {
                    windowManager?.updateViewLayout(overlayView, lp)
                } catch (_: Throwable) {}
            }
        } else {
            dragHandle?.visibility = View.VISIBLE
            overlayView?.setBackgroundResource(R.drawable.bg_kinex_dock_bar)
            overlayView?.elevation = 8 * resources.displayMetrics.density
            val opacityPercent = VehicleOverlayConfig.getOpacity(this).coerceIn(0, 100)
            val bgAlpha255 = (opacityPercent * 255 / 100).coerceIn(0, 255)
            overlayView?.background?.mutate()?.alpha = bgAlpha255
            windowParams?.let { lp ->
                lp.x = sharedPreferences.getInt("overlay_x", 245)
                lp.y = sharedPreferences.getInt("overlay_y", 32)
                try {
                    windowManager?.updateViewLayout(overlayView, lp)
                } catch (_: Throwable) {}
            }
        }
        overlayView?.alpha = 1.0f

        // Native typography & padding scale (NEVER clips Surface boundary!)
        val scale = (VehicleOverlayConfig.getScale(this) / 100f).coerceIn(0.7f, 1.5f)
        overlayView?.scaleX = 1.0f
        overlayView?.scaleY = 1.0f

        val baseSp = 11.5f * scale
        val allTv = listOf(
            tvSoc, tvKwh, tvRealRange, tvDashRange, tvHvTemp, tvCellVolt,
            tvConsumption, tvInstPower, tvOutTemp, tvSoh,
            tv12v, tvTpms, tvOdo, tvGear, tvSpeed
        )
        for (tv in allTv) {
            tv?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, baseSp)
        }
        val padH = if (isStockBar) (4 * resources.displayMetrics.density * scale).toInt() else (8 * resources.displayMetrics.density * scale).toInt()
        val padV = if (isStockBar) (2 * resources.displayMetrics.density * scale).toInt() else (3 * resources.displayMetrics.density * scale).toInt()
        rootLayout?.setPadding(padH, padV, padH, padV)
    }

    private fun updateTelemetryDisplay(soc: Double, consumption: Double) {
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
            tvRealRange?.text = "${realRange.toInt()} km"
        } else if (soc > 0.0 && consumption > 0.0) {
            val effCapacity = if (bydBridge.estimatedCapacityKwh > 0.0) bydBridge.estimatedCapacityKwh else 48.7
            val calcRange = ((soc / 100.0) * effCapacity / consumption) * 100.0
            tvRealRange?.text = "${calcRange.toInt()} km"
        }

        if (bydBridge.elecRangeKm > 0) {
            tvDashRange?.text = "${bydBridge.elecRangeKm} km"
        }

        // HV Main Battery Temperature (Configurable: Max/Min, Avg, Max, Min) & Cell Voltage
        val hvTempMode = VehicleOverlayConfig.getHvBattTempMode(this)
        tvHvTemp?.text = bydBridge.getFormattedHvBattTemp(hvTempMode)

        tvCellVolt?.text = String.format("%.3f/%.3fV", bydBridge.cellVoltMaxV, bydBridge.cellVoltMinV)

        if (consumption > 0.0) {
            tvConsumption?.text = String.format("%.1f", consumption)
        }

        tvInstPower?.text = String.format("%.1f kW", bydBridge.instantPowerKw)
        tvOutTemp?.text = "${bydBridge.outsideTemperatureC}°C"
        tvSoh?.text = "${bydBridge.sohPercent}%"
        tv12v?.text = String.format("%.1fV", bydBridge.aux12vVoltage)
        val tpmsUnit = VehicleOverlayConfig.getTpmsUnit(this)
        tvTpms?.text = bydBridge.getFormattedTpms(tpmsUnit)
        if (bydBridge.totalMileageKm > 0) {
            tvOdo?.text = String.format("%.0f km", bydBridge.totalMileageKm)
        }
        tvGear?.text = bydBridge.currentGear
        tvSpeed?.text = "${bydBridge.vehicleSpeedKmH} km/h"
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
            .setContentTitle("BYD Vehicle Data Overlay V2.1")
            .setContentText("กำลังอ่านและแสดงผลข้อมูลรถ + อุณหภูมิแบตใหญ่แบบ Real-time")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(wakeReceiver)
        } catch (_: Throwable) {}
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
        const val ACTION_SHOW_OVERLAY = "com.byd.mapsoverlay.ACTION_SHOW_OVERLAY"
        const val ACTION_CAR_WAKE = "com.byd.mapsoverlay.ACTION_CAR_WAKE"
        const val EXTRA_DISTANCE_KM = "extra_distance_km"
    }
}
