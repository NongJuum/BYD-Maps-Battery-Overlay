package com.byd.mapsoverlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * จัดการเฉพาะสิทธิ์ overlay และ notification listener ที่แอปตรวจสอบ/ตั้งค่าได้
 * ส่วน BYDAUTO_*_GET เป็น signature permissions ต้องได้รับอนุญาตจาก firmware
 * ไม่สามารถยกระดับสิทธิ์ด้วย runtime dialog หรือ pm grant จากแอปทั่วไปได้
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
            val daemonStarted = launchTelemetryDaemon(context)
            Log.i(TAG, "Permission setup finished; shellAvailable=$shellAvailable, telemetryDaemon=$daemonStarted")
            onStatusUpdated?.invoke(isAllPermissionsReady(context))
        }.start()
    }

    /**
     * แสดงสถานะจริงของ BYD signature permissions ใน logcat
     * การติดตั้ง permission ใน Manifest ไม่ได้แปลว่าระบบอนุญาตให้แอปใช้
     */
    /**
     * Start the vehicle reader in an ADB shell app_process (UID 2000), following
     * OverDrive's DiLink 3 architecture. The normal app process cannot obtain the
     * vehicle SDK's signature-level access on this firmware.
     */
    private fun launchTelemetryDaemon(context: Context): Boolean {
        return try {
            val privateKey = File(context.filesDir, "adbkey")
            val publicKey = File(context.filesDir, "adbkey.pub")
            val keyPair = if (privateKey.exists() && publicKey.exists()) {
                AdbKeyPair.read(privateKey, publicKey)
            } else {
                AdbKeyPair.generate(privateKey, publicKey)
                AdbKeyPair.read(privateKey, publicKey)
            }

            Dadb.create("127.0.0.1", 5555, keyPair).use { adb ->
                val command = """
                    APK_PATH=${'$'}(pm path com.byd.mapsoverlay | sed -n 's/^package://p' | head -n 1)
                    if [ -z "${'$'}APK_PATH" ]; then echo APK_NOT_FOUND; exit 21; fi
                    if pidof byd_maps_telemetry_daemon >/dev/null 2>&1; then
                      echo ALREADY_RUNNING
                    else
                      CLASSPATH="${'$'}APK_PATH" app_process /system/bin --nice-name=byd_maps_telemetry_daemon com.byd.mapsoverlay.BydTelemetryDaemon </dev/null >/data/local/tmp/byd_maps_telemetry_daemon.log 2>&1 &
                      sleep 1
                      if pidof byd_maps_telemetry_daemon >/dev/null 2>&1; then echo STARTED; else cat /data/local/tmp/byd_maps_telemetry_daemon.log 2>/dev/null; exit 22; fi
                    fi
                """.trimIndent()
                val output = adb.shell(command)
                Log.i(TAG, "Telemetry daemon launch response: $output")
                output.contains("STARTED") || output.contains("ALREADY_RUNNING")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not launch telemetry daemon through local ADB. Check Wireless ADB authorization.", e)
            false
        }
    }

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
        val overlayOk = Settings.canDrawOverlays(context)

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
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings${'$'}AppDrawOverlaySettingsActivity")),
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
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings${'$'}NotificationAccessSettingsActivity")),
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
