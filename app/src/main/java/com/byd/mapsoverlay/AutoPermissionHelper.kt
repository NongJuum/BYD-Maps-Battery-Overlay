package com.byd.mapsoverlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * AutoPermissionHelper:
 * ปลดล็อกสิทธิ์ทั้งหมดของรถ BYD อัตโนมัติเพื่อให้ผู้ใช้คนอื่นที่ได้ไฟล์ APK ไปติดตั้ง
 * สามารถใช้งานได้เหมือนเครื่องต้นแบบ 100% โดยไม่ต้องต่อคอมพิวเตอร์:
 *
 * 1. Runtime Permission Request สำหรับ `android.permission.BYDAUTO_*`
 * 2. Built-in Local Loopback ADB Client (`127.0.0.1:5555` via Dadb):
 *    เมื่อผู้ใช้เปิด Wireless Debugging / ADB บนจอรถ ตัวแอปจะเชื่อมต่อเข้า `127.0.0.1:5555` ของตัวเองโดยอัตโนมัติ
 *    เพื่อรันคำสั่งปลดล็อกทุกสิทธิ์ที่ถูกระบบ BYD บล็อกหน้าตั้งค่า:
 *    - `appops set com.byd.mapsoverlay SYSTEM_ALERT_WINDOW allow` (สิทธิ์วางทับหน้าจอ)
 *    - `cmd notification allow_listener com.byd.mapsoverlay/.MapsNotificationListener` (สิทธิ์อ่านระยะทาง Google Maps)
 *    - `pm grant com.byd.mapsoverlay android.permission.READ_LOGS` (สิทธิ์อ่าน CAN Bus / OBD Stream)
 *    - `pm grant com.byd.mapsoverlay android.permission.BYDAUTO_*` (สิทธิ์อ่านกล่อง BMS รถโดยตรง)
 * 3. Root / Shell Fallback (`su` / `sh`)
 * 4. BYD DiLink Custom Permission Manager Intents (กรณีผู้ใช้กดปุ่มตั้งค่าด้วยตัวเอง)
 */
object AutoPermissionHelper {
    private const val TAG = "AutoPermissionHelper"

    /**
     * ขอเฉพาะสิทธิ์ Android ที่แอปจัดการได้จริง
     *
     * BYDAUTO_*_GET เป็น signature permission บน firmware ที่ตรวจสอบแล้ว
     * จึงไม่แสดง permission dialog และไม่พยายาม pm grant จากแอปทั่วไป
     */
    fun grantAllPermissionsSilently(context: Context, onStatusUpdated: ((Boolean) -> Unit)? = null) {
        val packageName = context.packageName
        val commands = listOf(
            "appops set $packageName SYSTEM_ALERT_WINDOW allow",
            "cmd notification allow_listener $packageName/.MapsNotificationListener",
            "dumpsys deviceidle whitelist +$packageName"
        )

        Thread {
            var shellAvailable = false
            for (cmd in commands) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    val exitCode = process.waitFor()
                    if (exitCode == 0) shellAvailable = true
                    else Log.w(TAG, "Shell command failed (exit=$exitCode): $cmd")
                } catch (e: Exception) {
                    Log.w(TAG, "Shell command unavailable: $cmd", e)
                }
            }

            logBydPermissionState(context)
            Log.i(TAG, "Permission setup finished; shellAvailable=$shellAvailable")
            onStatusUpdated?.invoke(isAllPermissionsReady(context))
        }.start()
    }

    /**
     * แสดงสถานะจริงของ BYD signature permissions ใน logcat
     * การติดตั้ง permission ใน Manifest ไม่ได้แปลว่าระบบอนุญาตให้แอปใช้
     */
    fun logBydPermissionState(context: Context) {
        val names = listOf(
            "BYDAUTO_STATISTIC_GET",
            "BYDAUTO_INSTRUMENT_GET",
            "BYDAUTO_POWER_GET",
            "BYDAUTO_ENERGY_GET"
        )
        names.forEach { name ->
            val permission = "android.permission.$name"
            val granted = try {
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                false
            }
            Log.i(TAG, "$permission=${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    /**
     * ตรวจสอบว่าสิทธิ์สำคัญครบทั้ง Overlay และ Notification Listener หรือยัง
     */
    fun isAllPermissionsReady(context: Context): Boolean {
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        val notifOk = enabledListeners.contains(context.packageName)

        return overlayOk && notifOk
    }

    /**
     * เปิดหน้าตั้งค่าสิทธิ์วางทับหน้าจอ (รองรับทั้ง Android มาตรฐาน และหน้า Permission ของรถ BYD DiLink)
     */
    fun openOverlayPermissionScreen(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            Intent().setComponent(ComponentName("com.byd.carsettings", "com.byd.carsettings.permission.PermissionManageActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$AppDrawOverlaySettingsActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * เปิดหน้าตั้งค่าสิทธิ์อ่านแจ้งเตือน Google Maps (รองรับทั้ง Android มาตรฐาน และ BYD DiLink)
     */
    fun openNotificationPermissionScreen(context: Context): Boolean {
        val intents = listOf(
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$NotificationAccessSettingsActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }
}
