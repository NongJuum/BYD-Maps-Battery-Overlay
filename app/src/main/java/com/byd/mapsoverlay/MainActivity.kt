package com.byd.mapsoverlay

import android.content.Context
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

    override fun attachBaseContext(newBase: Context) {
        val lang = LanguageHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LanguageHelper.wrapContext(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        // Language switcher button
        val btnSwitchLanguage = findViewById<Button>(R.id.btn_switch_language)
        val isTh = LanguageHelper.isThai(this)
        btnSwitchLanguage.text = if (isTh) "🌐 Switch to English" else "🌐 เปลี่ยนเป็นภาษาไทย"
        btnSwitchLanguage.setOnClickListener {
            val targetLang = if (isTh) LanguageHelper.LANG_EN else LanguageHelper.LANG_TH
            LanguageHelper.setLanguage(this, targetLang)
            recreate()
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

        // ตั้งค่ารายการรุ่นรถใน Spinner (ตามภาษาที่เลือก)
        val presets = VehicleProfileManager.getLocalizedPresets(this)
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

            val savedMsg = getString(R.string.toast_profile_saved, "${vehicleBridge.detectedVehicleModel} (${vehicleBridge.estimatedCapacityKwh} kWh)")
            Toast.makeText(this, savedMsg, Toast.LENGTH_SHORT).show()
        }

        vehicleBridge = BydVehicleBridge(this) { soc, consumption ->
            runOnUiThread {
                tvDetectedModel.text = "🚗 ${vehicleBridge.detectedVehicleModel}"
                val remainKwhText = if (vehicleBridge.remainEnergyKwh > 0.0) {
                    String.format(" (OBD BMS %.1f kWh)", vehicleBridge.remainEnergyKwh)
                } else ""

                tvLiveSoc.text = if (soc > 0.0) {
                    getString(R.string.tv_live_soc_format, soc, remainKwhText)
                } else {
                    getString(R.string.tv_live_soc_waiting)
                }

                tvBatteryHealth.text = getString(
                    R.string.tv_usable_capacity_format,
                    vehicleBridge.sohPercent,
                    vehicleBridge.estimatedCapacityKwh,
                    vehicleBridge.nominalCapacityKwh
                )

                tvLiveConsumptionReal.text = if (consumption > 0.0) {
                    getString(R.string.tv_consumption_format, consumption)
                } else {
                    getString(R.string.tv_consumption_waiting)
                }

                tvRealRange.text = if (vehicleBridge.realRangeKm > 0.0) {
                    getString(R.string.tv_real_range_format, vehicleBridge.realRangeKm)
                } else {
                    getString(R.string.tv_real_range_waiting)
                }

                tvNedcRange.text = getString(R.string.tv_nedc_range_format, vehicleBridge.elecRangeKm)
                tvStatus.text = vehicleBridge.diagnosticStatus
            }
        }
        vehicleBridge.startListening()
        vehicleBridge.notifyUpdate()

        btnTestOverlay.setOnClickListener {
            val testDist = 25.0
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_DISTANCE).apply {
                putExtra(OverlayService.EXTRA_DISTANCE_KM, testDist)
            })
            Toast.makeText(this, getString(R.string.toast_overlay_shown), Toast.LENGTH_SHORT).show()
        }

        btnResetPosition.setOnClickListener {
            sendBroadcast(Intent(OverlayService.ACTION_RESET_POSITION))
            Toast.makeText(this, getString(R.string.toast_overlay_reset), Toast.LENGTH_SHORT).show()
        }

        val triggerAdbAuth = {
            tvStatus.text = if (isTh) "กำลังตรวจจับ ADB... หากมีหน้าต่างขึ้น ให้กด [✓] Always allow แล้ว [OK]" else "Connecting ADB... Tap [✓] Always allow and [OK] if prompted"
            Toast.makeText(this, getString(R.string.toast_obd_requesting), Toast.LENGTH_SHORT).show()
            AutoPermissionHelperAlpha.startAdbAuthPolling(this) { message, isFinished, isSuccess ->
                runOnUiThread {
                    tvStatus.text = message
                    if (isFinished) {
                        val toastMsg = if (isSuccess) {
                            getString(R.string.toast_obd_success)
                        } else {
                            message
                        }
                        Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()
                        if (isSuccess) {
                            vehicleBridge.restartConnection()
                            sendBroadcast(Intent(OverlayService.ACTION_PROFILE_UPDATED))
                            vehicleBridge.notifyUpdate()
                        }
                    }
                }
            }
        }

        btnOverlay.setOnClickListener {
            triggerAdbAuth()
        }

        btnNotification.setOnClickListener {
            triggerAdbAuth()
        }

        btnObdPermission.setOnClickListener {
            triggerAdbAuth()
        }

        btnDonate.setOnClickListener {
            showDonateDialog()
        }

        tvStatus.text = getString(R.string.tv_status_ready)

        // Automatically poll ADB authorization in the background on startup (Alpha DiLink 3)
        AutoPermissionHelperAlpha.startAdbAuthPolling(this) { message, isFinished, isSuccess ->
            runOnUiThread {
                tvStatus.text = message
                if (isFinished && isSuccess) {
                    vehicleBridge.restartConnection()
                    sendBroadcast(Intent(OverlayService.ACTION_PROFILE_UPDATED))
                    vehicleBridge.notifyUpdate()
                }
            }
        }
    }

    private fun showDonateDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_donate, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.btn_open_bmc)?.setOnClickListener {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.buymeacoffee.com/nongjuum"))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "https://www.buymeacoffee.com/nongjuum", Toast.LENGTH_LONG).show()
            }
        }

        dialogView.findViewById<Button>(R.id.btn_copy_bmc)?.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Buy Me a Coffee", "https://www.buymeacoffee.com/nongjuum")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_bmc_copied), Toast.LENGTH_SHORT).show()
        }

        dialogView.findViewById<Button>(R.id.btn_copy_skrill)?.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Skrill Email", "canon.non.7@gmail.com")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_skrill_copied), Toast.LENGTH_SHORT).show()
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

