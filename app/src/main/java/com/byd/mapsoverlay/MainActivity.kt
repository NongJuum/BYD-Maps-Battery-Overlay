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
import android.util.Log
import android.widget.Toast
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
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

        // In-process BYD system whitelist registration & hidden API bypass
        HiddenApiBypass.exemptAll()
        BydDataCacheWhitelist.ensureWhitelisted(this)

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
        MapsNotificationListener.requestRebindIfDisconnected(this)

        // Post-update: use the stable Main-branch silent BYD permission bootstrap.
        if (intent.getBooleanExtra("post_update", false)) {
            val statusText = findViewById<TextView>(R.id.tv_status)
            statusText.text = getString(R.string.toast_obd_requesting)
            AutoPermissionHelper.grantAllPermissionsSilently(this) { ready ->
                runOnUiThread {
                    statusText.text = if (ready) getString(R.string.toast_obd_success)
                        else getString(R.string.toast_obd_daemon_started)
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
            BydDataCacheWhitelist.ensureWhitelisted(this)
        }

        // Surface the real PackageManager failure returned by the detached OTA script.
        if (intent.getBooleanExtra("ota_failed", false)) {
            val statusPath = intent.getStringExtra("ota_status_file")
            val details = try {
                statusPath?.let { java.io.File(it).readLines().drop(1).joinToString(" ").trim() }
                    ?.takeIf { it.isNotBlank() } ?: "ไม่พบรายละเอียดจากระบบติดตั้ง"
            } catch (_: Exception) {
                "ไม่สามารถอ่านรายละเอียดข้อผิดพลาดได้"
            }
            Toast.makeText(this, "อัปเดตไม่สำเร็จ: $details", Toast.LENGTH_LONG).show()
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

        // Online OTA Update Button
        val btnCheckUpdate = findViewById<Button>(R.id.btn_check_update)
        btnCheckUpdate.setOnClickListener {
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.text = "⏳..."
            AppUpdaterAlpha.checkUpdate(this) { info ->
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.text = getString(R.string.btn_check_update)
                if (info != null) {
                    val changelog = if (isTh) info.changelogTh else info.changelogEn
                    AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_update_title)
                        .setMessage(getString(R.string.dialog_update_msg, info.versionName, AppUpdaterAlpha.getCurrentVersionName(this), changelog))
                        .setPositiveButton(R.string.dialog_update_btn_install) { _, _ ->
                            @Suppress("DEPRECATION")
                            val progressDialog = android.app.ProgressDialog(this).apply {
                                setTitle(getString(R.string.dialog_update_title))
                                setMessage(getString(R.string.toast_update_downloading, 0))
                                setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
                                max = 100
                                isIndeterminate = false
                                setCancelable(false)
                                show()
                            }
                            AppUpdaterAlpha.downloadAndInstall(
                                context = this,
                                info = info,
                                onProgress = { progress ->
                                    progressDialog.progress = progress
                                    progressDialog.setMessage(getString(R.string.toast_update_downloading, progress))
                                },
                                onStatus = { status ->
                                    progressDialog.setMessage(status)
                                },
                                onFinished = { success, msg ->
                                    progressDialog.dismiss()
                                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                        .setNegativeButton(R.string.dialog_update_btn_cancel, null)
                        .show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_update_latest, AppUpdaterAlpha.getCurrentVersionName(this)), Toast.LENGTH_SHORT).show()
                }
            }
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
        val switchDisableAutoStart = findViewById<Switch>(R.id.switch_disable_auto_start)
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
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_DISTANCE
                putExtra(OverlayService.EXTRA_DISTANCE_KM, testDist)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_DISTANCE).apply {
                setPackage(packageName)
                putExtra(OverlayService.EXTRA_DISTANCE_KM, testDist)
            })

            val hasOverlay = Settings.canDrawOverlays(this)
            val msg = if (hasOverlay) {
                getString(R.string.toast_overlay_shown)
            } else {
                if (isTh) "กำลังรอสิทธิ์ Overlay จาก ADB... หากมีหน้าต่างขึ้นบนจอให้กดยืนยัน" else "Waiting for Overlay permission from ADB..."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnResetPosition.setOnClickListener {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_RESET_POSITION
            }
            startService(serviceIntent)
            sendBroadcast(Intent(OverlayService.ACTION_RESET_POSITION).apply {
                setPackage(packageName)
            })
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
                            val updateIntent = Intent(this, OverlayService::class.java).apply {
                                action = OverlayService.ACTION_PROFILE_UPDATED
                            }
                            startService(updateIntent)
                            sendBroadcast(Intent(OverlayService.ACTION_PROFILE_UPDATED).apply {
                                setPackage(packageName)
                            })
                            vehicleBridge.notifyUpdate()
                        }
                    }
                }
            }
        }

        // Use the proven Main-branch IVI permission path for Maps and Overlay.
        // DiLink's Settings activities do not expose these special-access screens;
        // configure them directly through the authorized local ADB shell.
        btnOverlay.setOnClickListener {
            tvStatus.text = if (isTh) "กำลังเปิดสิทธิ์ Overlay ผ่าน IVI ADB..." else "Enabling Overlay permission via IVI ADB..."
            AutoPermissionHelper.enableMapsAndOverlayAccess(this) { result ->
                runOnUiThread {
                    tvStatus.text = result
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnNotification.setOnClickListener {
            tvStatus.text = if (isTh) "กำลังเปิดสิทธิ์อ่านการนำทาง Google Maps ผ่าน IVI ADB..." else "Enabling Google Maps navigation listener via IVI ADB..."
            AutoPermissionHelper.enableMapsAndOverlayAccess(this) { result ->
                runOnUiThread {
                    tvStatus.text = result
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    if (result.contains("สำเร็จ") || result.contains("success", ignoreCase = true)) {
                        vehicleBridge.restartConnection()
                        vehicleBridge.notifyUpdate()
                    }
                }
            }
        }

        // Keep the proven Main-branch OBD permission bootstrap.
        btnObdPermission.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_obd_requesting), Toast.LENGTH_SHORT).show()
            AutoPermissionHelper.grantAllPermissionsSilently(this) { ready ->
                runOnUiThread {
                    vehicleBridge.restartConnection()
                    sendBroadcast(Intent(OverlayService.ACTION_PROFILE_UPDATED))
                    val msg = if (ready) {
                        getString(R.string.toast_obd_success)
                    } else {
                        getString(R.string.toast_obd_daemon_started)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    vehicleBridge.notifyUpdate()
                }
            }
        }

        switchDisableAutoStart.isChecked = AutoPermissionHelperAlpha.isAutoStartDisabled(this)
        switchDisableAutoStart.setOnCheckedChangeListener { _, isDisabled ->
            getSharedPreferences(AutoPermissionHelperAlpha.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(AutoPermissionHelperAlpha.KEY_DISABLE_AUTO_START, isDisabled)
                .apply()

            AutoPermissionHelperAlpha.setAutoStartEnabled(this, enabled = !isDisabled) { _, message ->
                runOnUiThread {
                    tvStatus.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }

        btnDonate.setOnClickListener {
            showDonateDialog()
        }

        tvStatus.text = getString(R.string.tv_status_ready)

        // Match the proven Main-branch startup behavior. Do not launch the
        // Alpha permission poller automatically; Maps/Overlay are granted by
        // the dedicated IVI ADB action above.
        AutoPermissionHelper.grantAllPermissionsSilently(this)
    }

    private fun openOverlayPermissionSettings() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is already granted", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Some head-unit Settings builds do not expose the app-specific overlay page.
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (fallbackError: Exception) {
                Log.e("MainActivity", "Unable to open Overlay permission settings", fallbackError)
                Toast.makeText(this, "เปิดหน้า Overlay permission ไม่ได้", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openNotificationListenerSettings() {
        val expectedComponent = android.content.ComponentName(
            this,
            MapsNotificationListener::class.java
        )
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        val isEnabled = enabledListeners.split(':')
            .mapNotNull { android.content.ComponentName.unflattenFromString(it) }
            .contains(expectedComponent)

        if (isEnabled) {
            Toast.makeText(this, "Maps notification access is already enabled", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Log.e("MainActivity", "Unable to open Notification Listener settings", e)
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (fallbackError: Exception) {
                Log.e("MainActivity", "Unable to open app settings fallback", fallbackError)
                Toast.makeText(this, "เปิดหน้า Maps notification access ไม่ได้", Toast.LENGTH_LONG).show()
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

    override fun onResume() {
        super.onResume()
        MapsNotificationListener.requestRebindIfDisconnected(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        vehicleBridge.stopListening()
    }
}

