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

        btnResetPos = findViewById(R.id.btn_reset_pos)
        btnSaveConfig = findViewById(R.id.btn_save_config)
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

        if (VehicleOverlayConfig.isStockBarMode(this)) {
            rbModeStockBar.isChecked = true
        } else {
            rbModeFloating.isChecked = true
        }

        val currentOpacity = VehicleOverlayConfig.getOpacity(this)
        seekOpacity.progress = currentOpacity.coerceIn(0, 100)
        tvOpacityValue.text = "${currentOpacity}%"

        val currentScale = VehicleOverlayConfig.getScale(this)
        seekScale.progress = currentScale.coerceIn(70, 150)
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

        // Instant toggle listeners for all 15 vehicle telemetry fields
        val allFieldSwitches = listOf(
            switchSoc, switchKwh, switchRealRange, switchDashRange,
            switchHvBattTemp, switchCellVolt, switchSoh, switch12v,
            switchConsume50km, switchInstPower, switchOutTemp,
            switchTpms, switchOdo, switchSpeed, switchGear
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
                val clamped = progress.coerceIn(70, 150)
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
                tv12v.text = String.format("%.1f V", bydBridge.aux12vVoltage)
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
