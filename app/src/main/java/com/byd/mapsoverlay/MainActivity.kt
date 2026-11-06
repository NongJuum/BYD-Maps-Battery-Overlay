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
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.SwitchCompat

/**
 * MainActivity: Settings & Configuration for Vehicle Data Overlay V2.0
 */
class MainActivity : AppCompatActivity() {

    private lateinit var switchMaster: SwitchCompat
    private lateinit var ivStatusDot: ImageView
    private lateinit var tvStatusDesc: TextView

    // Live Telemetry Preview Views
    private lateinit var tvSoc: TextView
    private lateinit var tvKwh: TextView
    private lateinit var tvRealRange: TextView
    private lateinit var tvConsumption: TextView
    private lateinit var tvCabinTemp: TextView
    private lateinit var tv12v: TextView
    private lateinit var tvVehicleModelBadge: TextView

    // Selection Switches
    private lateinit var switchSoc: SwitchCompat
    private lateinit var switchKwh: SwitchCompat
    private lateinit var switchRealRange: SwitchCompat
    private lateinit var switchSoh: SwitchCompat
    private lateinit var switch12v: SwitchCompat

    private lateinit var switchCabinTemp: SwitchCompat
    private lateinit var switchConsume50km: SwitchCompat
    private lateinit var switchSpeed: SwitchCompat
    private lateinit var switchGear: SwitchCompat

    private lateinit var btnResetPos: MaterialButton
    private lateinit var btnSaveConfig: MaterialButton

    private lateinit var bydBridge: BydVehicleBridge
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadPreferences()
        setupListeners()
        setupTelemetryPreview()

        // Auto-start OverlayService if enabled
        val prefs = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("service_running", true) && checkOverlayPermission()) {
            startOverlayService()
        }
    }

    private fun initViews() {
        switchMaster = findViewById(R.id.switch_overlay_master)
        ivStatusDot = findViewById(R.id.iv_overlay_status_dot)
        tvStatusDesc = findViewById(R.id.tv_master_status_desc)

        tvSoc = findViewById(R.id.tv_dash_soc)
        tvKwh = findViewById(R.id.tv_dash_kwh)
        tvRealRange = findViewById(R.id.tv_dash_real_range)
        tvConsumption = findViewById(R.id.tv_dash_consumption)
        tvCabinTemp = findViewById(R.id.tv_dash_cabin_temp)
        tv12v = findViewById(R.id.tv_dash_12v)
        tvVehicleModelBadge = findViewById(R.id.tv_vehicle_model_badge)

        switchSoc = findViewById(R.id.switch_show_soc)
        switchKwh = findViewById(R.id.switch_show_remain_kwh)
        switchRealRange = findViewById(R.id.switch_show_real_range)
        switchSoh = findViewById(R.id.switch_show_soh)
        switch12v = findViewById(R.id.switch_show_12v)

        switchCabinTemp = findViewById(R.id.switch_show_cabin_temp)
        switchConsume50km = findViewById(R.id.switch_show_consume_50km)
        switchSpeed = findViewById(R.id.switch_show_speed)
        switchGear = findViewById(R.id.switch_show_gear)

        btnResetPos = findViewById(R.id.btn_reset_pos)
        btnSaveConfig = findViewById(R.id.btn_save_config)
    }

    private fun loadPreferences() {
        switchSoc.isChecked = VehicleOverlayConfig.isShowSoc(this)
        switchKwh.isChecked = VehicleOverlayConfig.isShowRemainKwh(this)
        switchRealRange.isChecked = VehicleOverlayConfig.isShowRealRange(this)
        switchSoh.isChecked = VehicleOverlayConfig.isShowBatterySoh(this)
        switch12v.isChecked = VehicleOverlayConfig.isShow12v(this)

        switchCabinTemp.isChecked = VehicleOverlayConfig.isShowCabinTemp(this)
        switchConsume50km.isChecked = VehicleOverlayConfig.isShowConsumption50km(this)
        switchSpeed.isChecked = VehicleOverlayConfig.isShowSpeed(this)
        switchGear.isChecked = VehicleOverlayConfig.isShowGear(this)

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

        btnSaveConfig.setOnClickListener {
            saveFieldPreferences()
            Toast.makeText(this, "บันทึกการตั้งค่า Overlay เรียบร้อย!", Toast.LENGTH_SHORT).show()
            if (switchMaster.isChecked) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_UPDATE_CONFIG
                }
                startService(serviceIntent)
            }
        }

        btnResetPos.setOnClickListener {
            val prefs = getSharedPreferences("byd_overlay_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("overlay_x", 40).putInt("overlay_y", 40).apply()
            Toast.makeText(this, "รีเซ็ตตำแหน่ง Overlay ไปที่มุมบนซ้ายแล้ว", Toast.LENGTH_SHORT).show()
            if (switchMaster.isChecked) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_RESET_POSITION
                }
                startService(serviceIntent)
            }
        }
    }

    private fun saveFieldPreferences() {
        VehicleOverlayConfig.setShowSoc(this, switchSoc.isChecked)
        VehicleOverlayConfig.setShowRemainKwh(this, switchKwh.isChecked)
        VehicleOverlayConfig.setShowRealRange(this, switchRealRange.isChecked)
        VehicleOverlayConfig.setShowBatterySoh(this, switchSoh.isChecked)
        VehicleOverlayConfig.setShow12v(this, switch12v.isChecked)

        VehicleOverlayConfig.setShowCabinTemp(this, switchCabinTemp.isChecked)
        VehicleOverlayConfig.setShowConsumption50km(this, switchConsume50km.isChecked)
        VehicleOverlayConfig.setShowSpeed(this, switchSpeed.isChecked)
        VehicleOverlayConfig.setShowGear(this, switchGear.isChecked)
    }

    private fun setupTelemetryPreview() {
        bydBridge = BydVehicleBridge(this) { soc, consumption ->
            mainHandler.post {
                if (soc > 0.0) tvSoc.text = String.format("%.0f %%", soc)
                val kwh = bydBridge.remainEnergyKwh
                if (kwh > 0.0) tvKwh.text = String.format("%.1f kWh", kwh)
                if (consumption > 0.0) tvConsumption.text = String.format("%.1f", consumption)
                val realRange = bydBridge.realRangeKm
                if (realRange > 0.0) {
                    tvRealRange.text = String.format("%.0f km", realRange)
                } else if (soc > 0.0 && consumption > 0.0) {
                    val effCap = if (bydBridge.estimatedCapacityKwh > 0.0) bydBridge.estimatedCapacityKwh else 48.7
                    val calcRange = ((soc / 100.0) * effCap / consumption) * 100.0
                    tvRealRange.text = String.format("%.0f km", calcRange)
                }
                tvCabinTemp.text = "${bydBridge.cabinTemperatureC}°C"
                tv12v.text = "13.8 V"
                tvVehicleModelBadge.text = bydBridge.detectedVehicleModel
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
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
