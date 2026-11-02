package com.byd.mapsoverlay

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

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

    private val bydHardwarePermissions = arrayOf(
        "android.permission.BYDAUTO_STATISTIC_COMMON",
        "android.permission.BYDAUTO_STATISTIC_GET",
        "android.permission.BYDAUTO_INSTRUMENT_COMMON",
        "android.permission.BYDAUTO_INSTRUMENT_GET",
        "android.permission.BYDAUTO_POWER_COMMON",
        "android.permission.BYDAUTO_POWER_GET",
        "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_ENERGY_GET",
        "android.permission.BYDAUTO_CHARGING_COMMON",
        "android.permission.BYDAUTO_CHARGING_GET",
        "android.permission.BYDAUTO_SPEED_COMMON",
        "android.permission.BYDAUTO_SPEED_GET",
        "android.permission.BYDAUTO_BODYWORK_COMMON",
        "android.permission.BYDAUTO_GEARBOX_COMMON",
        "com.byd.permission.BYDAUTO_COMMON"
    )

    fun grantAllPermissionsSilently(context: Context, onStatusUpdated: ((Boolean) -> Unit)? = null) {
        // 1. ขอ Runtime Permissions สำหรับฮาร์ดแวร์รถ BYD
        if (context is Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val missing = bydHardwarePermissions.filter {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) {
                    context.requestPermissions(missing.toTypedArray(), 9001)
                }
            } catch (_: Exception) {}
        }

        val packageName = context.packageName
        val commands = mutableListOf(
            "appops set $packageName SYSTEM_ALERT_WINDOW allow",
            "cmd notification allow_listener $packageName/com.byd.mapsoverlay.MapsNotificationListener",
            "cmd notification allow_listener $packageName/.MapsNotificationListener",
            "pm grant $packageName android.permission.READ_LOGS",
            "pm grant $packageName android.permission.POST_NOTIFICATIONS",
            "dumpsys deviceidle whitelist +$packageName"
        )
        bydHardwarePermissions.forEach { perm ->
            commands.add("pm grant $packageName $perm")
        }

        Thread {
            var grantedViaLocalAdb = false

            // 2. เชื่อมต่อ Local Loopback ADB (127.0.0.1:5555) ภายในตัวรถเอง (เหมือนที่ OverDrive และ Dudu ใช้ปลดล็อกตัวเอง!)
            try {
                val keyFile = File(context.filesDir, "adbkey")
                val keyPair = if (keyFile.exists()) {
                    AdbKeyPair.read(keyFile, File(context.filesDir, "adbkey.pub"))
                } else {
                    AdbKeyPair.generate(keyFile, File(context.filesDir, "adbkey.pub"))
                    AdbKeyPair.read(keyFile, File(context.filesDir, "adbkey.pub"))
                }

                val portsToTry = listOf(5555, 5556, 5037)
                for (port in portsToTry) {
                    try {
                        Dadb.create("127.0.0.1", port, keyPair).use { dadb ->
                            for (cmd in commands) {
                                runCatching { dadb.shell(cmd) }
                            }
                            grantedViaLocalAdb = true
                            Log.d(TAG, "Successfully granted all permissions via Local Loopback ADB (127.0.0.1:$port)")
                        }
                        if (grantedViaLocalAdb) break
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Local ADB loopback not active yet: ${e.message}")
            }

            // 3. ลองรันผ่าน su / sh กรณีเครื่องมี Root หรือ Shell พิเศษ
            if (!grantedViaLocalAdb) {
                for (cmd in commands) {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                        val exitVal = process.waitFor()
                        if (exitVal != 0) {
                            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
                        }
                    } catch (_: Exception) {}
                }
            }

            onStatusUpdated?.invoke(isAllPermissionsReady(context))
        }.start()
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
