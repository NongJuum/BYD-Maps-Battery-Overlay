package com.byd.mapsoverlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

/**
 * MainActivity: Settings & Configuration for Vehicle Data Overlay V2.1
 */
class MainActivity : AppCompatActivity() {

    private lateinit var switchMaster: SwitchCompat
    private lateinit var ivStatusDot: ImageView
    private lateinit var tvStatusDesc: TextView

    // Permission Card Views
    private lateinit var tvPermissionStatus: TextView
    private lateinit var btnGrantAllPermissions: MaterialButton
    private lateinit var btnOpenOverlaySettings: MaterialButton

    // Live Telemetry Preview Views
    private lateinit var tvSoc: TextView
    private lateinit var tvKwh: TextView
    private lateinit var tvRealRange: TextView
    private lateinit var tvHvTemp: TextView
    private lateinit var tvCellVolt: TextView
    private lateinit var tvStdRange: TextView
    private lateinit var tvConsumption: TextView
    private lateinit var tvCabinTemp: TextView
    private lateinit var tv12v: TextView
    private lateinit var tvVehicleModelBadge: TextView

    // Selection Switches - Group 1 (HV Battery & Range)
    private lateinit var switchSoc: SwitchCompat
    private lateinit var switchKwh: SwitchCompat
    private lateinit var switchRealRange: SwitchCompat
    private lateinit var switchDashRange: SwitchCompat
    private lateinit var switchHvBattTemp: SwitchCompat
    private lateinit var switchCellVolt: SwitchCompat
    private lateinit var switchSoh: SwitchCompat
    private lateinit var switch12v: SwitchCompat

    // Selection Switches - Group 2 (Driving, Temps, TPMS)
    private lateinit var switchConsume50km: SwitchCompat
    private lateinit var switchInstPower: SwitchCompat
    private lateinit var switchOutTemp: SwitchCompat
    private lateinit var switchTpms: SwitchCompat
    private lateinit var switchOdo: SwitchCompat
    private lateinit var switchSpeed: SwitchCompat
    private lateinit var switchGear: SwitchCompat

    // Selection Switches - Group 3 (System Status Indicators)
    private lateinit var switchSim: SwitchCompat
    private lateinit var switchWifi: SwitchCompat
    private lateinit var switchGps: SwitchCompat
    private lateinit var switchCameraDvr: SwitchCompat
    private lateinit var switchBluetooth: SwitchCompat

    private lateinit var rgDisplayMode: android.widget.RadioGroup
    private lateinit var rbModeFloating: android.widget.RadioButton
    private lateinit var rbModeStockBar: android.widget.RadioButton

    private lateinit var seekOpacity: android.widget.SeekBar
    private lateinit var tvOpacityValue: TextView
    private lateinit var seekScale: android.widget.SeekBar
    private lateinit var tvScaleValue: TextView
    private lateinit var rgOrientation: android.widget.RadioGroup
    private lateinit var rbHorizontal: android.widget.RadioButton
    private lateinit var rbVertical: android.widget.RadioButton
    private lateinit var rgHvTempMode: android.widget.RadioGroup
    private lateinit var rbHvTempMaxMin: android.widget.RadioButton
    private lateinit var rbHvTempAvg: android.widget.RadioButton
    private lateinit var rbHvTempMax: android.widget.RadioButton
    private lateinit var rbHvTempMin: android.widget.RadioButton
    private lateinit var rgTpmsUnit: android.widget.RadioGroup
    private lateinit var rbTpmsPsi: android.widget.RadioButton
    private lateinit var rbTpmsKpa: android.widget.RadioButton

    private lateinit var rgTextColorTheme: android.widget.RadioGroup
    private lateinit var rbTextColorWhite: android.widget.RadioButton
    private lateinit var rbTextColorCyan: android.widget.RadioButton
    private lateinit var rbTextColorEmerald: android.widget.RadioButton
    private lateinit var rbTextColorAmber: android.widget.RadioButton

    private lateinit var rgBgColorTheme: android.widget.RadioGroup
    private lateinit var rbBgColorBlack: android.widget.RadioButton
    private lateinit var rbBgColorGlass: android.widget.RadioButton
    private lateinit var rbBgColorMidnight: android.widget.RadioButton
    private lateinit var rbBgColorTransparent: android.widget.RadioButton

    private lateinit var btnResetPos: MaterialButton
    private lateinit var btnSaveConfig: MaterialButton

    private lateinit var bydBridge: BydVehicleBridge
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HiddenApiBypass.exemptAll()
        AutoPermissionHelper.ensureTelemetryDaemon(this)

        if (intent?.getBooleanExtra("extra_autostart_only", false) == true) {
            val prefs = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("service_running", true) && checkOverlayPermission()) {
                startOverlayService()
            }
            moveTaskToBack(true)
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        initViews()
        loadPreferences()
        setupListeners()
        setupTelemetryPreview()
        updatePermissionStatusBadge()

        val prefs = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("service_running", true) && checkOverlayPermission()) {
            startOverlayService()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("extra_autostart_only", false) == true) {
            val prefs = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("service_running", true) && checkOverlayPermission()) {
                startOverlayService()
            }
            moveTaskToBack(true)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusBadge()
    }

    private fun initViews() {
        switchMaster = findViewById(R.id.switch_overlay_master)
        ivStatusDot = findViewById(R.id.iv_overlay_status_dot)
        tvStatusDesc = findViewById(R.id.tv_master_status_desc)

        tvPermissionStatus = findViewById(R.id.tv_permission_status)
        btnGrantAllPermissions = findViewById(R.id.btn_grant_all_permissions)
        btnOpenOverlaySettings = findViewById(R.id.btn_open_overlay_settings)

        tvSoc = findViewById(R.id.tv_dash_soc)
        tvKwh = findViewById(R.id.tv_dash_kwh)
        tvRealRange = findViewById(R.id.tv_dash_real_range)
        tvHvTemp = findViewById(R.id.tv_dash_hv_temp)
        tvCellVolt = findViewById(R.id.tv_dash_cell_volt)
        tvStdRange = findViewById(R.id.tv_dash_std_range)
        tvConsumption = findViewById(R.id.tv_dash_consumption)
        tvCabinTemp = findViewById(R.id.tv_dash_cabin_temp)
        tv12v = findViewById(R.id.tv_dash_12v)
        tvVehicleModelBadge = findViewById(R.id.tv_vehicle_model_badge)

        switchSoc = findViewById(R.id.switch_show_soc)
        switchKwh = findViewById(R.id.switch_show_remain_kwh)
        switchRealRange = findViewById(R.id.switch_show_real_range)
        switchDashRange = findViewById(R.id.switch_show_dash_range)
        switchHvBattTemp = findViewById(R.id.switch_show_hv_batt_temp)
        switchCellVolt = findViewById(R.id.switch_show_cell_volt)
        switchSoh = findViewById(R.id.switch_show_soh)
        switch12v = findViewById(R.id.switch_show_12v)

        switchConsume50km = findViewById(R.id.switch_show_consume_50km)
        switchInstPower = findViewById(R.id.switch_show_inst_power)
        switchOutTemp = findViewById(R.id.switch_show_out_temp)
        switchTpms = findViewById(R.id.switch_show_tpms)
        switchOdo = findViewById(R.id.switch_show_odo)
        switchSpeed = findViewById(R.id.switch_show_speed)
        switchGear = findViewById(R.id.switch_show_gear)

        switchSim = findViewById(R.id.switch_show_sim)
        switchWifi = findViewById(R.id.switch_show_wifi)
        switchGps = findViewById(R.id.switch_show_gps)
        switchCameraDvr = findViewById(R.id.switch_show_camera_dvr)
        switchBluetooth = findViewById(R.id.switch_show_bluetooth)

        rgDisplayMode = findViewById(R.id.rg_display_mode)
        rbModeFloating = findViewById(R.id.rb_mode_floating)
        rbModeStockBar = findViewById(R.id.rb_mode_stock_bar)

        seekOpacity = findViewById(R.id.seek_overlay_opacity)
        tvOpacityValue = findViewById(R.id.tv_opacity_value)
        seekScale = findViewById(R.id.seek_overlay_scale)
        tvScaleValue = findViewById(R.id.tv_scale_value)

        rgOrientation = findViewById(R.id.rg_overlay_orientation)
        rbHorizontal = findViewById(R.id.rb_orientation_horizontal)
        rbVertical = findViewById(R.id.rb_orientation_vertical)
        rgHvTempMode = findViewById(R.id.rg_hv_temp_mode)
        rbHvTempMaxMin = findViewById(R.id.rb_hv_temp_max_min)
        rbHvTempAvg = findViewById(R.id.rb_hv_temp_avg)
        rbHvTempMax = findViewById(R.id.rb_hv_temp_max)
        rbHvTempMin = findViewById(R.id.rb_hv_temp_min)
        rgTpmsUnit = findViewById(R.id.rg_tpms_unit)
        rbTpmsPsi = findViewById(R.id.rb_tpms_psi)
        rbTpmsKpa = findViewById(R.id.rb_tpms_kpa)

        rgTextColorTheme = findViewById(R.id.rg_text_color_theme)
        rbTextColorWhite = findViewById(R.id.rb_text_color_white)
        rbTextColorCyan = findViewById(R.id.rb_text_color_cyan)
        rbTextColorEmerald = findViewById(R.id.rb_text_color_emerald)
        rbTextColorAmber = findViewById(R.id.rb_text_color_amber)

        rgBgColorTheme = findViewById(R.id.rg_bg_color_theme)
        rbBgColorBlack = findViewById(R.id.rb_bg_color_black)
        rbBgColorGlass = findViewById(R.id.rb_bg_color_glass)
        rbBgColorMidnight = findViewById(R.id.rb_bg_color_midnight)
        rbBgColorTransparent = findViewById(R.id.rb_bg_color_transparent)

        btnResetPos = findViewById(R.id.btn_reset_pos)
        btnSaveConfig = findViewById(R.id.btn_save_config)
        findViewById<android.view.View>(R.id.btn_field_order)?.setOnClickListener {
            showFieldOrderDialog()
        }
        setupTabsAndLivePreview()
    }

    private fun setupTabsAndLivePreview() {
        val btnFields = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_tab_fields)
        val btnStyle = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_tab_style)
        val btnSystem = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_tab_system)
        val tabFields = findViewById<android.view.View>(R.id.tab_container_fields)
        val tabStyle = findViewById<android.view.View>(R.id.tab_container_style)
        val tabSystem = findViewById<android.view.View>(R.id.tab_container_system)
        val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.main_scroll_view)

        fun selectTab(activeIdx: Int) {
            tabFields?.visibility = if (activeIdx == 0) android.view.View.VISIBLE else android.view.View.GONE
            tabStyle?.visibility = if (activeIdx == 1) android.view.View.VISIBLE else android.view.View.GONE
            tabSystem?.visibility = if (activeIdx == 2) android.view.View.VISIBLE else android.view.View.GONE

            val activeBg = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E88E5"))
            val inactiveBg = android.content.res.ColorStateList.valueOf(Color.parseColor("#1C2433"))
            btnFields?.backgroundTintList = if (activeIdx == 0) activeBg else inactiveBg
            btnStyle?.backgroundTintList = if (activeIdx == 1) activeBg else inactiveBg
            btnSystem?.backgroundTintList = if (activeIdx == 2) activeBg else inactiveBg

            btnFields?.setTextColor(if (activeIdx == 0) Color.WHITE else Color.parseColor("#B0BEC5"))
            btnStyle?.setTextColor(if (activeIdx == 1) Color.WHITE else Color.parseColor("#B0BEC5"))
            btnSystem?.setTextColor(if (activeIdx == 2) Color.WHITE else Color.parseColor("#B0BEC5"))
            scroll?.smoothScrollTo(0, 0)
        }

        btnFields?.setOnClickListener { selectTab(0) }
        btnStyle?.setOnClickListener { selectTab(1) }
        btnSystem?.setOnClickListener { selectTab(2) }
        selectTab(0)
        updateLivePreviewBar()
    }

    private fun updateLivePreviewBar() {
        val pill = findViewById<android.widget.LinearLayout>(R.id.ll_live_preview_pill) ?: return

        val textColor = when (VehicleOverlayConfig.getTextColorTheme(this)) {
            "cyan" -> Color.parseColor("#40C4FF")
            "emerald" -> Color.parseColor("#69F0AE")
            "amber" -> Color.parseColor("#FFB74D")
            "violet" -> Color.parseColor("#E1BEE7")
            else -> Color.parseColor("#ECEFF1")
        }
        val bgTheme = VehicleOverlayConfig.getBgColorTheme(this)
        val density = resources.displayMetrics.density
        if (bgTheme == "glass") {
            pill.setBackgroundResource(R.drawable.bg_pill_liquid_glass)
        } else {
            val bgColor = when (bgTheme) {
                "carbon" -> Color.parseColor("#F2121212")
                "midnight" -> Color.parseColor("#F20D1B2A")
                "transparent" -> Color.parseColor("#22000000")
                else -> Color.parseColor("#FF0B0E14")
            }
            val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = (if (VehicleOverlayConfig.isStockBarMode(this@MainActivity)) 6f else 18f) * density
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
            }
            pill.background = bgDrawable
        }

        val scale = (VehicleOverlayConfig.getScale(this) / 100f).coerceIn(0.7f, 1.8f)
        val labelSp = (10.0f * scale).coerceIn(8.5f, 15f)
        val valueSp = (12.0f * scale).coerceIn(10f, 18f)
        val labelColor = Color.parseColor("#90A4AE")
        val dividerColor = Color.parseColor("#33FFFFFF")

        val items = mutableListOf<Triple<String, String, Int>>() // (label, value, valueColor)
        val order = VehicleOverlayConfig.getFieldOrder(this)
        val hasBridge = ::bydBridge.isInitialized
        for (key in order) {
            when (key) {
                "soc" -> if (VehicleOverlayConfig.isShowSoc(this)) {
                    val v = if (hasBridge && bydBridge.currentSoc > 0) "${bydBridge.currentSoc.toInt()}%" else "80%"
                    items.add(Triple("🔋", v, Color.parseColor("#69F0AE")))
                }
                "kwh" -> if (VehicleOverlayConfig.isShowRemainKwh(this)) {
                    val v = if (hasBridge && bydBridge.remainEnergyKwh > 0) String.format("%.1f kWh", bydBridge.remainEnergyKwh) else "38.7 kWh"
                    items.add(Triple("⚡", v, textColor))
                }
                "real_range" -> if (VehicleOverlayConfig.isShowRealRange(this)) {
                    val v = if (hasBridge && bydBridge.realRangeKm > 0) "${bydBridge.realRangeKm} km" else "315 km"
                    items.add(Triple("REAL", v, textColor))
                }
                "dash_range" -> if (VehicleOverlayConfig.isShowDashboardRange(this)) {
                    val v = if (hasBridge && bydBridge.elecRangeKm > 0) "${bydBridge.elecRangeKm} km" else "349 km"
                    items.add(Triple("STD", v, textColor))
                }
                "hv_temp" -> if (VehicleOverlayConfig.isShowHvBattTemp(this)) {
                    val mode = VehicleOverlayConfig.getHvBattTempMode(this)
                    val hasHv = hasBridge && bydBridge.hvBattTempMaxC > 0.0
                    val hvVal = if (hasHv) bydBridge.hvBattTempMaxC else 27.0
                    val v = if (hasHv) bydBridge.getFormattedHvBattTemp(mode) else "27/26°C"
                    val hvIcon = when {
                        hvVal >= 45.0 -> "🔥"
                        hvVal < 15.0 -> "❄️"
                        else -> "🌡️"
                    }
                    val hvCol = when {
                        hvVal >= 55.0 -> Color.parseColor("#FF5252")
                        hvVal >= 45.0 -> Color.parseColor("#FF9100")
                        hvVal >= 40.0 -> Color.parseColor("#FFD54F")
                        hvVal < 15.0 -> Color.parseColor("#40C4FF")
                        else -> textColor
                    }
                    items.add(Triple(hvIcon, v, hvCol))
                }
                "cell_volt" -> if (VehicleOverlayConfig.isShowCellVoltage(this)) {
                    val v = if (hasBridge && bydBridge.cellVoltMaxV > 0) String.format("%.3f/%.3fV", bydBridge.cellVoltMaxV, bydBridge.cellVoltMinV) else "3.330/3.325V"
                    items.add(Triple("CELL", v, textColor))
                }
                "consume" -> if (switchConsume50km.isChecked) {
                    val v = if (hasBridge && bydBridge.currentConsumption > 0) String.format("%.1f", bydBridge.currentConsumption) else "10.5"
                    items.add(Triple("AVG", "$v kWh", textColor))
                }
                "inst_power" -> if (switchInstPower.isChecked) {
                    items.add(Triple("PWR", "12.4 kW", textColor))
                }
                "soh" -> if (VehicleOverlayConfig.isShowBatterySoh(this)) {
                    items.add(Triple("SOH", "99%", textColor))
                }
                "12v" -> if (VehicleOverlayConfig.isShow12v(this)) {
                    val v12 = if (hasBridge && bydBridge.aux12vVoltage > 0) bydBridge.aux12vVoltage else 13.8
                    val dip = if (hasBridge && bydBridge.aux12vMinDipV > 0) bydBridge.aux12vMinDipV else 12.0
                    val isLow = dip < 9.5 || v12 < 9.0
                    val str = if (isLow) String.format("⚠️ %.1fV [%.1fV]", v12, dip) else String.format("%.1fV [%.1fV]", v12, dip)
                    items.add(Triple("12V", str, if (isLow) Color.parseColor("#FF5252") else textColor))
                }
                "out_temp" -> if (switchOutTemp.isChecked) {
                    items.add(Triple("OUT", "29°C", textColor))
                }
                "tpms" -> if (switchTpms.isChecked) {
                    items.add(Triple("TPMS", "↖36 ↗36 | ↙36 ↘36", textColor))
                }
                "odo" -> if (VehicleOverlayConfig.isShowOdo(this)) {
                    items.add(Triple("ODO", "39647 km", textColor))
                }
                "speed" -> if (VehicleOverlayConfig.isShowSpeed(this)) {
                    items.add(Triple("SPD", "0 km/h", textColor))
                }
                "gear" -> if (VehicleOverlayConfig.isShowGear(this)) {
                    items.add(Triple("GEAR", "P", Color.parseColor("#40C4FF")))
                }
            }
        }

        pill.removeAllViews()
        if (items.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "กรุณาเปิดอย่างน้อย 1 ข้อมูลเพื่อแสดงตัวอย่าง"
                textSize = 12f
                setTextColor(labelColor)
            }
            pill.addView(emptyTv)
            return
        }

        items.forEachIndexed { idx, (lbl, value, valColor) ->
            if (idx > 0) {
                val divider = android.view.View(this).apply {
                    setBackgroundColor(dividerColor)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (1f * density).toInt().coerceAtLeast(1),
                        (14f * density * scale.coerceAtMost(1.3f)).toInt()
                    ).apply {
                        marginStart = (8f * density).toInt()
                        marginEnd = (8f * density).toInt()
                    }
                }
                pill.addView(divider)
            }
            val tvLbl = TextView(this).apply {
                text = lbl
                textSize = labelSp
                setTextColor(labelColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, (4f * density).toInt(), 0)
            }
            val tvVal = TextView(this).apply {
                text = value
                textSize = valueSp
                setTextColor(valColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            pill.addView(tvLbl)
            pill.addView(tvVal)
        }
    }

    private fun showFieldOrderDialog() {
        val currentOrder = VehicleOverlayConfig.getFieldOrder(this).toMutableList()
        val scrollView = android.widget.ScrollView(this)
        val listLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        scrollView.addView(listLayout)

        fun rebuildRows() {
            listLayout.removeAllViews()
            currentOrder.forEachIndexed { idx, key ->
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val vp = (6 * resources.displayMetrics.density).toInt()
                    setPadding(0, vp, 0, vp)
                }
                val tvLabel = android.widget.TextView(this).apply {
                    text = "${idx + 1}. ${VehicleOverlayConfig.getFieldLabel(key)}"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnUp = android.widget.Button(this).apply {
                    text = "▲"
                    isEnabled = idx > 0
                    setOnClickListener {
                        if (idx > 0) {
                            val tmp = currentOrder[idx - 1]
                            currentOrder[idx - 1] = currentOrder[idx]
                            currentOrder[idx] = tmp
                            rebuildRows()
                        }
                    }
                }
                val btnDown = android.widget.Button(this).apply {
                    text = "▼"
                    isEnabled = idx < currentOrder.size - 1
                    setOnClickListener {
                        if (idx < currentOrder.size - 1) {
                            val tmp = currentOrder[idx + 1]
                            currentOrder[idx + 1] = currentOrder[idx]
                            currentOrder[idx] = tmp
                            rebuildRows()
                        }
                    }
                }
                row.addView(tvLabel)
                row.addView(btnUp)
                row.addView(btnDown)
                listLayout.addView(row)
            }
        }
        rebuildRows()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("เรียงลำดับข้อมูลบนแถบ (Field Order)")
            .setView(scrollView)
            .setPositiveButton("บันทึก") { _, _ ->
                VehicleOverlayConfig.setFieldOrder(this, currentOrder)
                updateLivePreviewBar()
                startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_UPDATE_CONFIG })
                Toast.makeText(this, "บันทึกลำดับข้อมูลแล้ว", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("รีเซ็ตค่าเริ่มต้น") { _, _ ->
                VehicleOverlayConfig.setFieldOrder(this, VehicleOverlayConfig.DEFAULT_FIELD_ORDER)
                updateLivePreviewBar()
                startService(Intent(this, OverlayService::class.java).apply { action = OverlayService.ACTION_UPDATE_CONFIG })
                Toast.makeText(this, "รีเซ็ตลำดับข้อมูลแล้ว", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun updatePermissionStatusBadge() {
        val overlayOk = checkOverlayPermission()
        val daemonOk = if (::bydBridge.isInitialized) bydBridge.isDaemonConnected else false
        when {
            overlayOk && daemonOk -> {
                tvPermissionStatus.text = "✅ สิทธิ์ครบ + เชื่อมต่อ BYD Daemon แล้ว"
                tvPermissionStatus.setTextColor(Color.parseColor("#00E676"))
            }
            overlayOk -> {
                tvPermissionStatus.text = "🟡 สิทธิ์ทับจอพร้อม (กำลังเชื่อม Daemon)"
                tvPermissionStatus.setTextColor(Color.parseColor("#FFD54F"))
            }
            else -> {
                tvPermissionStatus.text = "⚠️ ยังไม่ได้เปิดสิทธิ์ทับหน้าจอ"
                tvPermissionStatus.setTextColor(Color.parseColor("#FF8A80"))
            }
        }
    }

    private fun loadPreferences() {
        switchSoc.isChecked = VehicleOverlayConfig.isShowSoc(this)
        switchKwh.isChecked = VehicleOverlayConfig.isShowRemainKwh(this)
        switchRealRange.isChecked = VehicleOverlayConfig.isShowRealRange(this)
        switchDashRange.isChecked = VehicleOverlayConfig.isShowDashboardRange(this)
        switchHvBattTemp.isChecked = VehicleOverlayConfig.isShowHvBattTemp(this)
        switchCellVolt.isChecked = VehicleOverlayConfig.isShowCellVoltage(this)
        switchSoh.isChecked = VehicleOverlayConfig.isShowBatterySoh(this)
        switch12v.isChecked = VehicleOverlayConfig.isShow12v(this)

        switchConsume50km.isChecked = VehicleOverlayConfig.isShowConsumption50km(this)
        switchInstPower.isChecked = VehicleOverlayConfig.isShowInstantPower(this)
        switchOutTemp.isChecked = VehicleOverlayConfig.isShowOutsideTemp(this)
        switchTpms.isChecked = VehicleOverlayConfig.isShowTpmsPressure(this)
        switchOdo.isChecked = VehicleOverlayConfig.isShowOdo(this)
        switchSpeed.isChecked = VehicleOverlayConfig.isShowSpeed(this)
        switchGear.isChecked = VehicleOverlayConfig.isShowGear(this)

        switchSim.isChecked = VehicleOverlayConfig.isShowSim(this)
        switchWifi.isChecked = VehicleOverlayConfig.isShowWifi(this)
        switchGps.isChecked = VehicleOverlayConfig.isShowGps(this)
        switchCameraDvr.isChecked = VehicleOverlayConfig.isShowCameraDvr(this)
        switchBluetooth.isChecked = VehicleOverlayConfig.isShowBluetooth(this)

        if (VehicleOverlayConfig.isStockBarMode(this)) {
            rbModeStockBar.isChecked = true
        } else {
            rbModeFloating.isChecked = true
        }

        val currentOpacity = VehicleOverlayConfig.getOpacity(this)
        seekOpacity.progress = currentOpacity.coerceIn(0, 100)
        tvOpacityValue.text = "${currentOpacity}%"

        val currentScale = VehicleOverlayConfig.getScale(this)
        seekScale.progress = currentScale.coerceIn(70, 200)
        tvScaleValue.text = "${currentScale}%"

        val isVertical = VehicleOverlayConfig.isVerticalLayout(this)
        if (isVertical) {
            rbVertical.isChecked = true
        } else {
            rbHorizontal.isChecked = true
        }

        when (VehicleOverlayConfig.getHvBattTempMode(this)) {
            "avg" -> rbHvTempAvg.isChecked = true
            "max" -> rbHvTempMax.isChecked = true
            "min" -> rbHvTempMin.isChecked = true
            else -> rbHvTempMaxMin.isChecked = true
        }

        if (VehicleOverlayConfig.getTpmsUnit(this) == "kpa") {
            rbTpmsKpa.isChecked = true
        } else {
            rbTpmsPsi.isChecked = true
        }

        when (VehicleOverlayConfig.getTextColorTheme(this)) {
            "cyan" -> rbTextColorCyan.isChecked = true
            "emerald" -> rbTextColorEmerald.isChecked = true
            "amber" -> rbTextColorAmber.isChecked = true
            else -> rbTextColorWhite.isChecked = true
        }

        when (VehicleOverlayConfig.getBgColorTheme(this)) {
            "glass" -> rbBgColorGlass.isChecked = true
            "midnight" -> rbBgColorMidnight.isChecked = true
            "transparent" -> rbBgColorTransparent.isChecked = true
            else -> rbBgColorBlack.isChecked = true
        }

        val prefs = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
        val isRunning = prefs.getBoolean("service_running", true)
        switchMaster.isChecked = isRunning
        updateMasterStatusUi(isRunning)
    }

    private fun updateMasterStatusUi(isActive: Boolean) {
        if (isActive) {
            tvStatusDesc.text = "สถานะ: กำลังแสดงผลบนหน้าจอ (Active)"
            tvStatusDesc.setTextColor(Color.parseColor("#81C784"))
            ivStatusDot.setColorFilter(Color.parseColor("#00E676"))
        } else {
            tvStatusDesc.text = "สถานะ: ปิดการทำงาน (Disabled)"
            tvStatusDesc.setTextColor(Color.parseColor("#EF5350"))
            ivStatusDot.setColorFilter(Color.parseColor("#78909C"))
        }
    }

    private fun setupListeners() {
        switchMaster.setOnCheckedChangeListener { _, isChecked ->
            updateMasterStatusUi(isChecked)
            if (isChecked) {
                if (checkOverlayPermission()) {
                    startOverlayService()
                } else {
                    switchMaster.isChecked = false
                    requestOverlayPermission()
                }
            } else {
                stopOverlayService()
            }
        }

        // Real Permission Grant Button via Local ADB + System Settings
        btnGrantAllPermissions.setOnClickListener {
            tvPermissionStatus.text = "⏳ กำลังปลดล็อกสิทธิ์ผ่าน ADB..."
            tvPermissionStatus.setTextColor(Color.parseColor("#FFD54F"))
            Toast.makeText(this, "กำลังขอสิทธิ์ระบบรถ + รัน BYD Telemetry Daemon...", Toast.LENGTH_SHORT).show()

            AutoPermissionHelper.enableMapsAndOverlayAccess(this) { resultMsg ->
                AutoPermissionHelper.grantAllPermissionsSilently(this) { _ ->
                    mainHandler.post {
                        Toast.makeText(this, resultMsg, Toast.LENGTH_LONG).show()
                        updatePermissionStatusBadge()
                        if (checkOverlayPermission()) {
                            switchMaster.isChecked = true
                            startOverlayService()
                        } else {
                            AutoPermissionHelper.openOverlayPermissionScreen(this)
                        }
                    }
                }
            }
        }

        btnOpenOverlaySettings.setOnClickListener {
            val opened = AutoPermissionHelper.openOverlayPermissionScreen(this)
            if (!opened) {
                requestOverlayPermission()
            }
        }

        // Instant toggle listeners for all vehicle telemetry and system status fields
        val allFieldSwitches = listOf(
            switchSoc, switchKwh, switchRealRange, switchDashRange,
            switchHvBattTemp, switchCellVolt, switchSoh, switch12v,
            switchConsume50km, switchInstPower, switchOutTemp,
            switchTpms, switchOdo, switchSpeed, switchGear,
            switchSim, switchWifi, switchGps, switchCameraDvr, switchBluetooth
        )
        for (sw in allFieldSwitches) {
            sw.setOnCheckedChangeListener { _, _ ->
                saveFieldPreferences()
                notifyOverlayServiceConfigChanged()
            }
        }

        rgDisplayMode.setOnCheckedChangeListener { _, checkedId ->
            val isStockBar = checkedId == R.id.rb_mode_stock_bar
            VehicleOverlayConfig.setStockBarMode(this, isStockBar)
            AutoPermissionHelper.setStockBarImmersive(isStockBar, this)
            notifyOverlayServiceConfigChanged()
        }

        seekOpacity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(0, 100)
                tvOpacityValue.text = "${clamped}%"
                if (fromUser) {
                    VehicleOverlayConfig.setOpacity(this@MainActivity, clamped)
                    notifyOverlayServiceConfigChanged()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        seekScale.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(70, 200)
                tvScaleValue.text = "${clamped}%"
                if (fromUser) {
                    VehicleOverlayConfig.setScale(this@MainActivity, clamped)
                    notifyOverlayServiceConfigChanged()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        rgOrientation.setOnCheckedChangeListener { _, checkedId ->
            val isVert = checkedId == R.id.rb_orientation_vertical
            VehicleOverlayConfig.setVerticalLayout(this, isVert)
            notifyOverlayServiceConfigChanged()
        }

        rgHvTempMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_hv_temp_avg -> "avg"
                R.id.rb_hv_temp_max -> "max"
                R.id.rb_hv_temp_min -> "min"
                else -> "max_min"
            }
            VehicleOverlayConfig.setHvBattTempMode(this, mode)
            notifyOverlayServiceConfigChanged()
        }

        rgTpmsUnit.setOnCheckedChangeListener { _, checkedId ->
            val unit = if (checkedId == R.id.rb_tpms_kpa) "kpa" else "psi"
            VehicleOverlayConfig.setTpmsUnit(this, unit)
            notifyOverlayServiceConfigChanged()
        }

        rgTextColorTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rb_text_color_cyan -> "cyan"
                R.id.rb_text_color_emerald -> "emerald"
                R.id.rb_text_color_amber -> "amber"
                else -> "white"
            }
            VehicleOverlayConfig.setTextColorTheme(this, theme)
            notifyOverlayServiceConfigChanged()
        }

        rgBgColorTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.rb_bg_color_glass -> "glass"
                R.id.rb_bg_color_midnight -> "midnight"
                R.id.rb_bg_color_transparent -> "transparent"
                else -> "black"
            }
            VehicleOverlayConfig.setBgColorTheme(this, theme)
            notifyOverlayServiceConfigChanged()
        }

        btnSaveConfig.setOnClickListener {
            saveFieldPreferences()
            Toast.makeText(this, "บันทึกการตั้งค่า Overlay เรียบร้อย!", Toast.LENGTH_SHORT).show()
            notifyOverlayServiceConfigChanged()
        }

        btnResetPos.setOnClickListener {
            val dm = resources.displayMetrics
            val centerX = (dm.widthPixels / 2) - 200
            val centerY = (dm.heightPixels / 2) - 30
            val prefs = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("overlay_x", centerX).putInt("overlay_y", centerY).apply()
            Toast.makeText(this, "🎯 รีเซ็ตตำแหน่ง Overlay มาไว้ตรงกลางจอแล้ว", Toast.LENGTH_SHORT).show()
            if (switchMaster.isChecked) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_RESET_POSITION
                }
                startService(serviceIntent)
            }
        }
    }

    private fun notifyOverlayServiceConfigChanged() {
        updateLivePreviewBar()
        if (switchMaster.isChecked && checkOverlayPermission()) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_CONFIG
            }
            startService(serviceIntent)
        }
    }

    private fun saveFieldPreferences() {
        VehicleOverlayConfig.setShowSoc(this, switchSoc.isChecked)
        VehicleOverlayConfig.setShowRemainKwh(this, switchKwh.isChecked)
        VehicleOverlayConfig.setShowRealRange(this, switchRealRange.isChecked)
        VehicleOverlayConfig.setShowDashboardRange(this, switchDashRange.isChecked)
        VehicleOverlayConfig.setShowHvBattTemp(this, switchHvBattTemp.isChecked)
        VehicleOverlayConfig.setShowCellVoltage(this, switchCellVolt.isChecked)
        VehicleOverlayConfig.setShowBatterySoh(this, switchSoh.isChecked)
        VehicleOverlayConfig.setShow12v(this, switch12v.isChecked)

        VehicleOverlayConfig.setShowConsumption50km(this, switchConsume50km.isChecked)
        VehicleOverlayConfig.setShowInstantPower(this, switchInstPower.isChecked)
        VehicleOverlayConfig.setShowOutsideTemp(this, switchOutTemp.isChecked)
        VehicleOverlayConfig.setShowTpmsPressure(this, switchTpms.isChecked)
        VehicleOverlayConfig.setShowOdo(this, switchOdo.isChecked)
        VehicleOverlayConfig.setShowSpeed(this, switchSpeed.isChecked)
        VehicleOverlayConfig.setShowGear(this, switchGear.isChecked)

        VehicleOverlayConfig.setShowSim(this, switchSim.isChecked)
        VehicleOverlayConfig.setShowWifi(this, switchWifi.isChecked)
        VehicleOverlayConfig.setShowGps(this, switchGps.isChecked)
        VehicleOverlayConfig.setShowCameraDvr(this, switchCameraDvr.isChecked)
        VehicleOverlayConfig.setShowBluetooth(this, switchBluetooth.isChecked)

        VehicleOverlayConfig.setStockBarMode(this, rbModeStockBar.isChecked)
        VehicleOverlayConfig.setOpacity(this, seekOpacity.progress.coerceIn(0, 100))
        VehicleOverlayConfig.setScale(this, seekScale.progress.coerceIn(70, 150))
        VehicleOverlayConfig.setVerticalLayout(this, rbVertical.isChecked)
    }

    private fun setupTelemetryPreview() {
        bydBridge = BydVehicleBridge(this) { soc, consumption ->
            mainHandler.post {
                if (soc > 0.0) tvSoc.text = String.format("%.0f %%", soc)
                val kwh = bydBridge.remainEnergyKwh
                if (kwh > 0.0) tvKwh.text = String.format("%.1f kWh", kwh)
                if (consumption > 0.0) tvConsumption.text = String.format("%.1f kWh", consumption)
                val realRange = bydBridge.realRangeKm
                if (realRange > 0.0) {
                    tvRealRange.text = String.format("%.0f km", realRange)
                } else if (soc > 0.0 && consumption > 0.0) {
                    val effCap = if (bydBridge.estimatedCapacityKwh > 0.0) bydBridge.estimatedCapacityKwh else 48.7
                    val calcRange = ((soc / 100.0) * effCap / consumption) * 100.0
                    tvRealRange.text = String.format("%.0f km", calcRange)
                }
                if (bydBridge.elecRangeKm > 0) {
                    tvStdRange.text = "${bydBridge.elecRangeKm} km"
                }
                tvHvTemp.text = String.format("%.0f / %.0f °C", bydBridge.hvBattTempMaxC, bydBridge.hvBattTempMinC)
                tvCellVolt.text = String.format("%.3f / %.3f V", bydBridge.cellVoltMaxV, bydBridge.cellVoltMinV)
                tvCabinTemp.text = "${bydBridge.outsideTemperatureC}°C"
                val v12 = bydBridge.aux12vVoltage
                val minDip = bydBridge.aux12vMinDipV
                val dipStr = if (minDip > 0.0) String.format(" [%.1fV]", minDip) else ""
                tv12v.text = if (v12 > 0.0) String.format("%.1f V%s", v12, dipStr) else "-- V"
                tvVehicleModelBadge.text = bydBridge.detectedVehicleModel
                updatePermissionStatusBadge()
            }
        }
        bydBridge.startListening()
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (!AutoPermissionHelper.openOverlayPermissionScreen(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", true).apply()
    }

    private fun stopOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        stopService(serviceIntent)
        getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        bydBridge.stopListening()
    }
}
