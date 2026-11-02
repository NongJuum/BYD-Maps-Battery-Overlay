package com.byd.mapsoverlay

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var vehicleBridge: BydVehicleBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AutoPermissionHelper.grantAllPermissionsSilently(this)

        // Start overlay floating service automatically
        try {
            val serviceIntent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val tvDetectedModel = findViewById<TextView>(R.id.tv_detected_model)
        val spinnerCarModel = findViewById<Spinner>(R.id.spinner_car_model)
        val layoutCustomInputs = findViewById<LinearLayout>(R.id.layout_custom_inputs)
        val etCustomModelName = findViewById<EditText>(R.id.et_custom_model_name)
        val etCustomBatteryKwh = findViewById<EditText>(R.id.et_custom_battery_kwh)
        val etSohPercent = findViewById<EditText>(R.id.et_soh_percent)
        val btnSaveProfile = findViewById<Button>(R.id.btn_save_vehicle_profile)

        val tvLiveSoc = findViewById<TextView>(R.id.tv_live_soc)
        val tvBatteryHealth = findViewById<TextView>(R.id.tv_battery_health)
        val tvLiveConsumptionReal = findViewById<TextView>(R.id.tv_live_consumption_real)
        val tvRealRange = findViewById<TextView>(R.id.tv_real_range)
        val tvNedcRange = findViewById<TextView>(R.id.tv_nedc_range)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        val btnTestOverlay = findViewById<Button>(R.id.btn_test_overlay)
        val btnResetPosition = findViewById<Button>(R.id.btn_reset_position)
        val btnOverlay = findViewById<Button>(R.id.btn_permission_overlay)
        val btnNotification = findViewById<Button>(R.id.btn_permission_notification)
        val btnObdPermission = findViewById<Button>(R.id.btn_permission_obd)
        val btnDonate = findViewById<Button>(R.id.btn_donate)

        // ตั้งค่ารายการรุ่นรถใน Spinner
        val presets = VehicleProfileManager.presets
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            presets.map { it.displayName }
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.textSize = 14f
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.setBackgroundColor(Color.parseColor("#263238"))
                view.setPadding(24, 20, 24, 20)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCarModel.adapter = adapter

        // โหลดค่าที่ผู้ใช้เคยบันทึกไว้
        val savedPresetId = VehicleProfileManager.getSelectedPresetId(this)
        val initialIndex = presets.indexOfFirst { it.id == savedPresetId }.coerceAtLeast(0)
        spinnerCarModel.setSelection(initialIndex)
        etCustomModelName.setText(VehicleProfileManager.getCustomModelName(this))
        etCustomBatteryKwh.setText(String.format("%.2f", VehicleProfileManager.getCustomBatteryKwh(this)))
        etSohPercent.setText(VehicleProfileManager.getUserSohPercent(this).toString())

        spinnerCarModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = presets[position]
                layoutCustomInputs.visibility = if (selected.id == VehicleProfileManager.PRESET_CUSTOM) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSaveProfile.setOnClickListener {
            val selectedPreset = presets[spinnerCarModel.selectedItemPosition]
            val customName = etCustomModelName.text.toString().trim().ifBlank { "BYD EV" }
            val customKwh = etCustomBatteryKwh.text.toString().trim().toDoubleOrNull()?.coerceIn(10.0, 150.0) ?: 50.25
            val soh = etSohPercent.text.toString().trim().toIntOrNull()?.coerceIn(50, 100) ?: 98

            VehicleProfileManager.saveProfile(
                context = this,
                presetId = selectedPreset.id,
                customModelName = customName,
                customBatteryKwh = customKwh,
                sohPercent = soh
            )

            vehicleBridge.applyUserVehicleProfile()
            vehicleBridge.notifyUpdate()
            sendBroadcast(Intent(OverlayService.ACTION_PROFILE_UPDATED))

            Toast.makeText(
                this,
                "บันทึกข้อมูลรถเรียบร้อย: ${vehicleBridge.detectedVehicleModel} (ความจุคำนวณจริง ${vehicleBridge.estimatedCapacityKwh} kWh)",
                Toast.LENGTH_SHORT
            ).show()
        }

        vehicleBridge = BydVehicleBridge(this) { soc, consumption ->
            runOnUiThread {
                tvDetectedModel.text = "🚗 ${vehicleBridge.detectedVehicleModel}"
                val remainKwhText = if (vehicleBridge.remainEnergyKwh > 0.0) {
                    String.format(" (OBD BMS %.1f kWh)", vehicleBridge.remainEnergyKwh)
                } else ""
                tvLiveSoc.text = if (soc > 0.0) {
                    String.format("🔋 แบตเตอรี่ปัจจุบัน (OBD SoC): %.0f%%%s", soc, remainKwhText)
                } else {
                    "🔋 แบตเตอรี่ปัจจุบัน (OBD SoC): รอซิงค์สัญญาณจากรถ..."
                }

                tvBatteryHealth.text = String.format(
                    "🩺 ความจุแบตที่ใช้คำนวณ (หัก SOH %d%%): %.1f kWh (จากสเปก %.2f kWh)",
                    vehicleBridge.sohPercent,
                    vehicleBridge.estimatedCapacityKwh,
                    vehicleBridge.nominalCapacityKwh
                )

                tvLiveConsumptionReal.text = if (consumption > 0.0) {
                    String.format("⚡ อัตรากินไฟ 50 กม. ล่าสุด (OBD Past 50km): %.1f kWh/100km", consumption)
                } else {
                    "⚡ อัตรากินไฟ 50 กม. ล่าสุด (OBD Past 50km): รอซิงค์สัญญาณจากรถ..."
                }

                tvRealRange.text = if (vehicleBridge.realRangeKm > 0.0) {
                    String.format("🎯 ระยะทางวิ่งได้จริงคงเหลือ: ~%.0f กม.", vehicleBridge.realRangeKm)
                } else {
                    "🎯 ระยะทางวิ่งได้จริงคงเหลือ: รอข้อมูล SoC และอัตรากินไฟจาก OBD..."
                }

                tvNedcRange.text = String.format("📋 ระยะทางบนหน้าปัดรถ (Dashboard Range): %d กม.", vehicleBridge.elecRangeKm)
            }
        }
        vehicleBridge.startListening()
        vehicleBridge.notifyUpdate()

        btnTestOverlay.setOnClickListener {
            val testDist = 25.0
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_DISTANCE).apply {
                putExtra(OverlayService.EXTRA_DISTANCE_KM, testDist)
            })
            Toast.makeText(this, "แสดงแถบลอยบนหน้าจอแล้ว (ลากเปลี่ยนตำแหน่งได้เลย)", Toast.LENGTH_SHORT).show()
        }

        btnResetPosition.setOnClickListener {
            sendBroadcast(Intent(OverlayService.ACTION_RESET_POSITION))
            Toast.makeText(this, "รีเซ็ตตำแหน่งและขนาดแถบลอยกลับตรงกลางเรียบร้อย", Toast.LENGTH_SHORT).show()
        }

        btnOverlay.setOnClickListener {
            AutoPermissionHelper.grantAllPermissionsSilently(this) { ready ->
                runOnUiThread {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "✅ เปิดสิทธิ์วางทับหน้าจอเรียบร้อยแล้ว!", Toast.LENGTH_SHORT).show()
                    } else if (!AutoPermissionHelper.openOverlayPermissionScreen(this)) {
                        Toast.makeText(
                            this,
                            "💡 กรุณาเปิดสวิตช์ ADB / Wireless Debugging ในหน้าตั้งค่ารถ แล้วกดปุ่มนี้อีกครั้ง ระบบจะปลดล็อกสิทธิ์ให้อัตโนมัติทันที",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        btnNotification.setOnClickListener {
            AutoPermissionHelper.grantAllPermissionsSilently(this) { ready ->
                runOnUiThread {
                    if (ready) {
                        Toast.makeText(this, "✅ เชื่อมต่อสิทธิ์ Google Maps และ OBD ครบถ้วนแล้ว!", Toast.LENGTH_SHORT).show()
                    } else if (!AutoPermissionHelper.openNotificationPermissionScreen(this)) {
                        Toast.makeText(
                            this,
                            "💡 กรุณาเปิดสวิตช์ ADB / Wireless Debugging ในหน้าตั้งค่ารถ แล้วกดปุ่มนี้อีกครั้ง ระบบจะปลดล็อกสิทธิ์ให้อัตโนมัติทันที",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        btnObdPermission.setOnClickListener {
            Toast.makeText(this, "🔄 กำลังขอสิทธิ์ OBD และรีเซ็ตการเชื่อมต่อข้อมูลรถ...", Toast.LENGTH_SHORT).show()
            AutoPermissionHelper.grantAllPermissionsSilently(this) { ready ->
                runOnUiThread {
                    vehicleBridge.restartConnection()
                    sendBroadcast(Intent(OverlayService.ACTION_PROFILE_UPDATED))
                    val msg = if (ready) {
                        "✅ ซ่อมสิทธิ์ทั้งหมดและเชื่อมต่อท่อข้อมูล OBD / Google Maps ใหม่สำเร็จ!"
                    } else {
                        "✅ ส่งคำขอสิทธิ์ OBD และรีสตาร์ทตัวดึงข้อมูลรถเรียบร้อยแล้ว!"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnDonate.setOnClickListener {
            showDonateDialog()
        }

        tvStatus.text = "✅ พร้อมใช้งาน: ข้อมูลรุ่นรถใช้ตามที่คุณตั้งค่า + ข้อมูลแบตเตอรี่และอัตรากินไฟดึงสดจาก OBD"
    }

    private fun showDonateDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_donate, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.btn_copy_skrill)?.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Skrill Email", "canon.non.7@gmail.com")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "คัดลอกอีเมล Skrill (canon.non.7@gmail.com) แล้ว", Toast.LENGTH_SHORT).show()
        }

        dialogView.findViewById<Button>(R.id.btn_close_dialog).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9001 && ::vehicleBridge.isInitialized) {
            vehicleBridge.restartConnection()
            sendBroadcast(Intent(OverlayService.ACTION_PROFILE_UPDATED))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vehicleBridge.stopListening()
    }
}
