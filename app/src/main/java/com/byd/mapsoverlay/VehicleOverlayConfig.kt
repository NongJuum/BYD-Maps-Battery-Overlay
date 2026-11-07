package com.byd.mapsoverlay

import android.content.Context
import android.content.SharedPreferences

/**
 * จัดการตัวเลือกการแสดงผลข้อมูลรถใน Overlay V2.1
 * ให้ผู้ใช้สามารถเลือกเปิด-ปิดฟิลด์ข้อมูลที่เข้าถึงได้ทั้งหมดอย่างอิสระ
 */
object VehicleOverlayConfig {
    private const val PREFS_NAME = "byd_vehicle_overlay_v2_prefs"

    // หมวด 1: แบตเตอรี่แรงดันสูง (HV Battery) & ระยะทาง
    const val KEY_SHOW_SOC = "pref_show_soc"
    const val KEY_SHOW_REMAIN_KWH = "pref_show_remain_kwh"
    const val KEY_SHOW_REAL_RANGE = "pref_show_real_range"
    const val KEY_SHOW_DASHBOARD_RANGE = "pref_show_dashboard_range"
    const val KEY_SHOW_HV_BATT_TEMP = "pref_show_hv_batt_temp"
    const val KEY_SHOW_CELL_VOLTAGE = "pref_show_cell_voltage"
    const val KEY_SHOW_BATTERY_SOH = "pref_show_battery_soh"
    const val KEY_SHOW_12V = "pref_show_12v"

    // หมวด 2: การขับขี่ & พลังงาน
    const val KEY_SHOW_CONSUMPTION_50KM = "pref_show_consumption_50km"
    const val KEY_SHOW_INSTANT_POWER = "pref_show_instant_power"
    const val KEY_SHOW_SPEED = "pref_show_speed"
    const val KEY_SHOW_GEAR = "pref_show_gear"
    const val KEY_SHOW_ODO = "pref_show_odo"

    // หมวด 3: สภาพแวดล้อม & ยาง TPMS
    const val KEY_SHOW_CABIN_TEMP = "pref_show_cabin_temp"
    const val KEY_SHOW_OUTSIDE_TEMP = "pref_show_outside_temp"
    const val KEY_SHOW_TPMS_PRESSURE = "pref_show_tpms_pressure"

    // การปรับแต่งหน้าต่าง Overlay
    const val KEY_OVERLAY_OPACITY = "pref_overlay_opacity" // 0 - 100%
    const val KEY_OVERLAY_SCALE = "pref_overlay_scale" // 70 - 150% (Default 100)
    const val KEY_OVERLAY_ORIENTATION = "pref_overlay_orientation" // "horizontal" or "vertical"

    // หมวด 5: ระบบเตือนความปลอดภัย (Safety Chime)
    const val KEY_BSD_SOUND_ENABLED = "pref_bsd_sound_enabled"
    const val KEY_BSD_SOUND_TYPE = "pref_bsd_sound_type" // "tone_1" to "tone_10"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // หมวด 1: แบตเตอรี่และระยะทาง
    fun isShowSoc(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_SOC, true)
    fun setShowSoc(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_SOC, value).apply()

    fun isShowRemainKwh(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_REMAIN_KWH, true)
    fun setShowRemainKwh(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_REMAIN_KWH, value).apply()

    fun isShowRealRange(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_REAL_RANGE, true)
    fun setShowRealRange(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_REAL_RANGE, value).apply()

    fun isShowDashboardRange(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_DASHBOARD_RANGE, false)
    fun setShowDashboardRange(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_DASHBOARD_RANGE, value).apply()

    fun isShowHvBattTemp(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_HV_BATT_TEMP, true)
    fun setShowHvBattTemp(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_HV_BATT_TEMP, value).apply()

    fun isShowCellVoltage(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_CELL_VOLTAGE, false)
    fun setShowCellVoltage(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_CELL_VOLTAGE, value).apply()

    fun isShowBatterySoh(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_BATTERY_SOH, false)
    fun setShowBatterySoh(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_BATTERY_SOH, value).apply()

    fun isShow12v(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_12V, false)
    fun setShow12v(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_12V, value).apply()

    // หมวด 2: การขับขี่
    fun isShowConsumption50km(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_CONSUMPTION_50KM, true)
    fun setShowConsumption50km(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_CONSUMPTION_50KM, value).apply()

    fun isShowInstantPower(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_INSTANT_POWER, false)
    fun setShowInstantPower(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_INSTANT_POWER, value).apply()

    fun isShowSpeed(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_SPEED, false)
    fun setShowSpeed(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_SPEED, value).apply()

    fun isShowGear(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_GEAR, false)
    fun setShowGear(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_GEAR, value).apply()

    fun isShowOdo(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_ODO, false)
    fun setShowOdo(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_ODO, value).apply()

    // หมวด 3: อุณหภูมิและยาง
    fun isShowCabinTemp(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_CABIN_TEMP, true)
    fun setShowCabinTemp(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_CABIN_TEMP, value).apply()

    fun isShowOutsideTemp(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_OUTSIDE_TEMP, false)
    fun setShowOutsideTemp(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_OUTSIDE_TEMP, value).apply()

    fun isShowTpmsPressure(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_TPMS_PRESSURE, false)
    fun setShowTpmsPressure(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_TPMS_PRESSURE, value).apply()

    // การปรับแต่งทั่วไป
    fun getOpacity(context: Context): Int = prefs(context).getInt(KEY_OVERLAY_OPACITY, 85)
    fun setOpacity(context: Context, value: Int) = prefs(context).edit().putInt(KEY_OVERLAY_OPACITY, value.coerceIn(0, 100)).apply()

    fun getScale(context: Context): Int = prefs(context).getInt(KEY_OVERLAY_SCALE, 100)
    fun setScale(context: Context, value: Int) = prefs(context).edit().putInt(KEY_OVERLAY_SCALE, value.coerceIn(70, 150)).apply()

    fun isVerticalLayout(context: Context): Boolean = prefs(context).getString(KEY_OVERLAY_ORIENTATION, "horizontal") == "vertical"
    fun setVerticalLayout(context: Context, isVertical: Boolean) = prefs(context).edit().putString(KEY_OVERLAY_ORIENTATION, if (isVertical) "vertical" else "horizontal").apply()

    fun isBsdSoundEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BSD_SOUND_ENABLED, true)
    fun setBsdSoundEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_BSD_SOUND_ENABLED, enabled).apply()

    fun getBsdSoundType(context: Context): String = prefs(context).getString(KEY_BSD_SOUND_TYPE, "tone_4") ?: "tone_4"
    fun setBsdSoundType(context: Context, type: String) = prefs(context).edit().putString(KEY_BSD_SOUND_TYPE, type).apply()
}
