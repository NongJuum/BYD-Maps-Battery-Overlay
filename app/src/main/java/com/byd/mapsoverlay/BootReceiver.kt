package com.byd.mapsoverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver:
 * รับ Event เมื่อรถ DiLink 3.0 สตาร์ทเครื่อง หรือตื่นจาก ACC Sleep (User Present)
 * เพื่อปลุก OverlayService และกู้คืน NotificationListener ทันที ป้องกันสิทธิ์แผนที่หลุด!
 */
class BootReceiver : BroadcastReceiver() {
    private val tag = "BootReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.d(tag, "Received broadcast action: $action -> Rebinding DiLink services")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_SCREEN_ON,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.byd.action.ACC_ON",
            "com.byd.action.IGN_ON",
            "com.byd.intent.action.POWER_ON",
            "com.byd.intent.action.ACC_ON" -> {
                Log.i(tag, "Car started / DiLink woke from sleep -> Ensuring services and permissions")
                
                // 1. ตรวจสอบและเชื่อมต่อ Local ADB Telemetry Daemon ทันที (Background thread)
                try {
                    AutoPermissionHelper.ensureTelemetryDaemon(context)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to ensure telemetry daemon", e)
                }

                // 2. ถ้าผู้ใช้เปิดโหมด Stock Bar ให้รีแอปพลาย immersive.status=* ทันที
                try {
                    if (VehicleOverlayConfig.isStockBarMode(context)) {
                        AutoPermissionHelper.setStockBarImmersive(true, context)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to reapply stock bar immersive", e)
                }

                // 3. กู้คืน NotificationListener Proxy เพื่อรักษาสิทธิ์เข้าถึงในระบบ
                try {
                    MapsNotificationListener.requestRebindIfDisconnected(context)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to rebind listener", e)
                }

                // 4. สตาร์ท Foreground Overlay Service ทันที
                val serviceIntent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_CAR_WAKE)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to start OverlayService on boot", e)
                }
            }

            "com.byd.action.ACC_OFF",
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(tag, "Car shutdown / DiLink entering sleep ($action) -> Forwarding sleep signal to OverlayService to preserve 12V battery")
                val sleepIntent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_CAR_SLEEP)
                try {
                    context.startService(sleepIntent)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to send sleep action to OverlayService", e)
                }
            }
        }
    }
}
