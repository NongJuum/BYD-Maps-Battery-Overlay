package com.byd.mapsoverlay

import android.content.Context

data class BydCarPreset(
    val id: String,
    val displayName: String,
    val nominalBatteryKwh: Double
)

object VehicleProfileManager {
    private const val PREFS_NAME = "byd_vehicle_profile_prefs"
    private const val KEY_PRESET_ID = "preset_id"
    private const val KEY_CUSTOM_MODEL_NAME = "custom_model_name"
    private const val KEY_CUSTOM_BATTERY_KWH = "custom_battery_kwh"
    private const val KEY_USER_SOH_PERCENT = "user_soh_percent"

    const val PRESET_AUTO = "AUTO_OBD"
    const val PRESET_CUSTOM = "CUSTOM"

    val presets = listOf(
        // Default: Auto OBD / BMS direct detection
        BydCarPreset(PRESET_AUTO, "📡 Auto: Direct OBD / BMS Sync (Recommended)", 0.0),
        BydCarPreset("DOLPHIN_TH_50_25", "BYD Dolphin Standard / Thai Spec (50.25 kWh)", 50.25),
        BydCarPreset("DOLPHIN_STD_44_9", "BYD Dolphin Standard Range (44.9 kWh)", 44.9),
        BydCarPreset("DOLPHIN_EXT_60_48", "BYD Dolphin Extended Range (60.48 kWh)", 60.48),
        BydCarPreset("ATTO3_STD_49_92", "BYD Atto 3 Standard Range (49.92 kWh)", 49.92),
        BydCarPreset("ATTO3_STD_50_25", "BYD Atto 3 Standard (50.25 kWh)", 50.25),
        BydCarPreset("ATTO3_EXT_60_48", "BYD Atto 3 Extended Range (60.48 kWh)", 60.48),
        BydCarPreset("SEAL_DYN_61_44", "BYD Seal Dynamic (61.44 kWh)", 61.44),
        BydCarPreset("SEAL_PREM_82_56", "BYD Seal Premium / Performance (82.56 kWh)", 82.56),
        BydCarPreset("M6_STD_55_4", "BYD M6 Dynamic (55.4 kWh)", 55.4),
        BydCarPreset("M6_EXT_71_8", "BYD M6 Extended (71.8 kWh)", 71.8),
        BydCarPreset("SEALION6_18_3", "BYD Sealion 6 DM-i PHEV (18.3 kWh)", 18.3),
        BydCarPreset("SEALION7_PREM_82_5", "BYD Sealion 7 Premium (82.5 kWh)", 82.5),
        BydCarPreset("SEALION7_AWD_91_3", "BYD Sealion 7 Performance AWD (91.3 kWh)", 91.3),
        BydCarPreset(PRESET_CUSTOM, "✏️ Custom Vehicle & Battery Specs...", 50.25)
    )

    fun getLocalizedPresets(context: Context): List<BydCarPreset> {
        val autoLabel = try { context.getString(R.string.preset_auto_obd) } catch (e: Exception) { "📡 Auto: Direct OBD / BMS Sync (Recommended)" }
        val customLabel = try { context.getString(R.string.preset_custom) } catch (e: Exception) { "✏️ Custom Vehicle & Battery Specs..." }
        return presets.map {
            when (it.id) {
                PRESET_AUTO -> it.copy(displayName = autoLabel)
                PRESET_CUSTOM -> it.copy(displayName = customLabel)
                else -> it
            }
        }
    }

    fun getSelectedPresetId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // ค่าพื้นฐานเบื้องต้นเป็น PRESET_AUTO (ดึงจาก OBD ก่อนเสมอ)
        return prefs.getString(KEY_PRESET_ID, PRESET_AUTO) ?: PRESET_AUTO
    }

    fun getCustomModelName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_MODEL_NAME, "BYD EV") ?: "BYD EV"
    }

    fun getCustomBatteryKwh(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_CUSTOM_BATTERY_KWH, 50.25f).toDouble()
    }

    fun getUserSohPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_USER_SOH_PERCENT, 98).coerceIn(50, 100)
    }

    fun saveProfile(
        context: Context,
        presetId: String,
        customModelName: String,
        customBatteryKwh: Double,
        sohPercent: Int
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PRESET_ID, presetId)
            .putString(KEY_CUSTOM_MODEL_NAME, customModelName.ifBlank { "BYD EV" })
            .putFloat(KEY_CUSTOM_BATTERY_KWH, customBatteryKwh.toFloat())
            .putInt(KEY_USER_SOH_PERCENT, sohPercent.coerceIn(50, 100))
            .apply()
    }

    /**
     * คืนค่า (ชื่อรุ่นรถ, ความจุแบตเตอรี่โรงงาน kWh, ค่า SOH %)
     * ถ้าเป็น PRESET_AUTO (ค่าเริ่มต้น) จะคืนค่า nominalBatteryKwh = 0.0 เพื่อให้ดึงจาก OBD / BMS รถโดยตรงก่อนเสมอ
     */
    fun getConfiguredVehicleSpec(context: Context): Triple<String, Double, Int> {
        val presetId = getSelectedPresetId(context)
        val soh = getUserSohPercent(context)

        if (presetId == PRESET_AUTO) {
            return Triple("", 0.0, soh)
        }
        if (presetId == PRESET_CUSTOM) {
            val name = getCustomModelName(context)
            val kwh = getCustomBatteryKwh(context)
            return Triple("$name (${String.format("%.2f", kwh)} kWh)", kwh, soh)
        }

        val preset = presets.find { it.id == presetId }
        return if (preset != null) {
            Triple(preset.displayName, preset.nominalBatteryKwh, soh)
        } else {
            Triple("", 0.0, soh)
        }
    }
}
