package com.byd.mapsoverlay

import android.content.Context
import android.content.SharedPreferences

/**
 * จัดการตัวเลือกการแสดงผลข้อมูลรถใน Overlay V2.0
 * ให้ผู้ใช้สามารถเลือกเปิด-ปิดฟิลด์ข้อมูลได้เองอย่างอิสระ
 */
object VehicleOverlayConfig {
    private const val PREFS_NAME = "byd_vehicle_overlay_v2_prefs"

    // หมวด 1: แบตเตอรี่และระยะทาง
    const val KEY_SHOW_SOC = "pref_show_soc"
    const val KEY_SHOW_REMAIN_KWH = "pref_show_remain_kwh"
    const val KEY_SHOW_REAL_RANGE = "pref_show_real_range"
    const val KEY_SHOW_DASHBOARD_RANGE = "pref_show_dashboard_range"
    const val KEY_SHOW_BATTERY_SOH = "pref_show_battery_soh"
    const val KEY_SHOW_12V = "pref_show_12v"

    // หมวด 2: การขับขี่
    const val KEY_SHOW_CONSUMPTION_50KM = "pref_show_consumption_50km"
    const val KEY_SHOW_CONSUMPTION_TOTAL = "pref_show_consumption_total"
    const val KEY_SHOW_SPEED = "pref_show_speed"
    const val KEY_SHOW_GEAR = "pref_show_gear"
    const val KEY_SHOW_ODO = "pref_show_odo"

    // หมวด 3: ยาง TPMS
    const val KEY_SHOW_TPMS_PRESSURE = "pref_show_tpms_pressure"
    const val KEY_SHOW_TPMS_TEMP = "pref_show_tpms_temp"

    // หมวด 4: การชาร์จไฟและสภาพแวดล้อม
    const val KEY_SHOW_CHARGING_POWER = "pref_show_charging_power"
    const val KEY_SHOW_CHARGING_TIME = "pref_show_charging_time"
    const val KEY_SHOW_OUTSIDE_TEMP = "pref_show_outside_temp"

    // การปรับแต่งหน้าต่าง Overlay
    const val KEY_OVERLAY_OPACITY = "pref_overlay_opacity" // 50 - 100%

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Getters & Setters - หมวด 1
    fun isShowSoc(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_SOC, true)
    fun setShowSoc(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_SOC, value).apply()

    fun isShowRemainKwh(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_REMAIN_KWH, true)
    fun setShowRemainKwh(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_REMAIN_KWH, value).apply()

    fun isShowRealRange(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_REAL_RANGE, true)
    fun setShowRealRange(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_REAL_RANGE, value).apply()

    fun isShowDashboardRange(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_DASHBOARD_RANGE, false)
    fun setShowDashboardRange(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_DASHBOARD_RANGE, value).apply()

    fun isShowBatterySoh(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_BATTERY_SOH, false)
    fun setShowBatterySoh(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_BATTERY_SOH, value).apply()

    fun isShow12v(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_12V, false)
    fun setShow12v(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_12V, value).apply()

    // Getters & Setters - หมวด 2
    fun isShowConsumption50km(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_CONSUMPTION_50KM, true)
    fun setShowConsumption50km(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_CONSUMPTION_50KM, value).apply()

    fun isShowConsumptionTotal(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_CONSUMPTION_TOTAL, false)
    fun setShowConsumptionTotal(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_CONSUMPTION_TOTAL, value).apply()

    fun isShowSpeed(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_SPEED, false)
    fun setShowSpeed(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_SPEED, value).apply()

    fun isShowGear(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_GEAR, false)
    fun setShowGear(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_GEAR, value).apply()

    fun isShowOdo(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_ODO, false)
    fun setShowOdo(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_ODO, value).apply()

    // Getters & Setters - หมวด 3
    fun isShowTpmsPressure(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_TPMS_PRESSURE, false)
    fun setShowTpmsPressure(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_TPMS_PRESSURE, value).apply()

    fun isShowTpmsTemp(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_TPMS_TEMP, false)
    fun setShowTpmsTemp(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_TPMS_TEMP, value).apply()

    // Getters & Setters - หมวด 4
    fun isShowChargingPower(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_CHARGING_POWER, false)
    fun setShowChargingPower(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_CHARGING_POWER, value).apply()

    fun isShowChargingTime(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_CHARGING_TIME, false)
    fun setShowChargingTime(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_CHARGING_TIME, value).apply()

    fun isShowOutsideTemp(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_OUTSIDE_TEMP, false)
    fun setShowOutsideTemp(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SHOW_OUTSIDE_TEMP, value).apply()

    // Getters & Setters - ทั่วไป
    fun getOpacity(context: Context): Int = prefs(context).getInt(KEY_OVERLAY_OPACITY, 90)
    fun setOpacity(context: Context, value: Int) = prefs(context).edit().putInt(KEY_OVERLAY_OPACITY, value).apply()
}
