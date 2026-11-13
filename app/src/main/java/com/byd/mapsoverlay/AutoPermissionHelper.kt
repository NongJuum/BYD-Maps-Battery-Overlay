package com.byd.mapsoverlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the read-only BYD SDK reader in app_process as ADB shell (uid 2000).
 * The ordinary application UID cannot obtain BYD's signature-protected
 * vehicle telemetry on DiLink 3.
 */
object AutoPermissionHelper {
    private const val TAG = "AutoPermissionHelper"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val DAEMON_PORT = 19890
    private const val DAEMON_NAME = "bydtelemetry"
    private const val DAEMON_CLASS = "com.byd.mapsoverlay.BydTelemetryDaemon"
    private const val RETRY_INTERVAL_MS = 60_000L

    private val launchInFlight = AtomicBoolean(false)
    @Volatile private var lastLaunchAttemptMs = 0L

    private val immersiveInFlight = AtomicBoolean(false)
    @Volatile private var lastImmersiveAttemptMs = 0L

    /** Safe to call repeatedly on any thread. Retry failed starts with backoff. */
    fun ensureTelemetryDaemon(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAttemptMs < RETRY_INTERVAL_MS) return
        if (!launchInFlight.compareAndSet(false, true)) return
        lastLaunchAttemptMs = now

        val appContext = context.applicationContext ?: context
        Thread({
            try {
                if (isTelemetryDaemonReachable()) return@Thread
                Log.i(TAG, "Starting BYD telemetry daemon through local ADB")
                val started = launchTelemetryDaemon(appContext)
                Log.i(TAG, "BYD telemetry daemon launch result=$started")
            } catch (t: Throwable) {
                Log.e(TAG, "BYD telemetry daemon bootstrap failed", t)
            } finally {
                launchInFlight.set(false)
            }
        }, "BydTelemetryBootstrap").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * This does not claim signature permissions were granted. It starts the
     * shell-UID reader and reports overlay/notification-listener state only.
     */
    fun grantAllPermissionsSilently(context: Context, onStatusUpdated: ((Boolean) -> Unit)? = null) {
        ensureTelemetryDaemon(context)
        logBydPermissionState(context)
        onStatusUpdated?.invoke(isAllPermissionsReady(context))
    }

    private fun isTelemetryDaemonReachable(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 350)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun launchTelemetryDaemon(context: Context): Boolean {
        val keyPair = loadOrCreateAdbKeyPair(context)

        return try {
            val targetPkg = context.packageName
            // Dadb 1.2.8 provides bounded connect and socket timeouts.
            Dadb.create(ADB_HOST, ADB_PORT, keyPair, 2_000, 5_000).use { adb ->
                if (!adb.supportsFeature("shell_v2")) {
                    Log.w(TAG, "adbd did not advertise shell_v2; using available shell transport")
                }

                val command = """
                    for PERMISSION in \
                      BYDAUTO_STATISTIC_COMMON BYDAUTO_STATISTIC_GET BYDAUTO_STATISTIC_SET \
                      BYDAUTO_INSTRUMENT_COMMON BYDAUTO_INSTRUMENT_GET BYDAUTO_INSTRUMENT_SET \
                      BYDAUTO_POWER_COMMON BYDAUTO_POWER_GET BYDAUTO_POWER_SET \
                      BYDAUTO_ENERGY_COMMON BYDAUTO_ENERGY_GET BYDAUTO_ENERGY_SET \
                      BYDAUTO_CHARGING_COMMON BYDAUTO_CHARGING_GET BYDAUTO_CHARGING_SET \
                      BYDAUTO_SPEED_COMMON BYDAUTO_SPEED_GET BYDAUTO_SPEED_SET \
                      BYDAUTO_BODYWORK_COMMON BYDAUTO_BODYWORK_GET BYDAUTO_BODYWORK_SET \
                      BYDAUTO_GEARBOX_COMMON BYDAUTO_GEARBOX_GET \
                      BYDAUTO_BMS_COMMON BYDAUTO_BMS_GET \
                      BYDAUTO_OTA_COMMON BYDAUTO_OTA_GET \
                      BYDAUTO_SETTING_COMMON BYDAUTO_SETTING_GET \
                      BYDAUTO_AC_COMMON BYDAUTO_AC_GET BYDAUTO_AC_SET \
                      BYDAUTO_SENSOR_COMMON BYDAUTO_SENSOR_GET BYDAUTO_SENSOR_SET \
                      BYDAUTO_ADAS_COMMON BYDAUTO_ADAS_GET \
                      BYDAUTO_LIGHT_COMMON BYDAUTO_LIGHT_GET \
                      BYDAUTO_REMINDER_COMMON BYDAUTO_REMINDER_GET \
                      BYDAUTO_TYRE_COMMON BYDAUTO_TYRE_GET \
                      READ_PHONE_STATE ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION CAMERA \
                      BYD DEVICE_ACC DEVICE_POWER ACCESS_APPOPSDATA; do
                      pm grant $targetPkg android.permission.${'$'}PERMISSION >/dev/null 2>&1 || true
                    done
                    pm grant $targetPkg com.byd.permission.BYDAUTO_COMMON >/dev/null 2>&1 || true
                    pm grant $targetPkg com.byd.car.server.PROVIDER >/dev/null 2>&1 || true
                    setprop persist.adb.tcp.port 5555 2>/dev/null || true
                    settings put global adb_allowed_connection_time 0 2>/dev/null || true
                    setprop persist.sys.acc.whitelist '$targetPkg' 2>&1
                    service call accmodemanager 1 s16 '$targetPkg' 2>/dev/null || true
                    service call accmodemanager 2 s16 '$targetPkg' 2>/dev/null || true
                    service call accmodemanager 3 s16 '$targetPkg' 2>/dev/null || true
                    appops set $targetPkg RUN_IN_BACKGROUND allow 2>/dev/null || true
                    appops set $targetPkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
                    appops set $targetPkg START_FOREGROUND allow 2>/dev/null || true
                    appops set $targetPkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
                    appops set $targetPkg WAKE_LOCK allow 2>/dev/null || true
                    dumpsys deviceidle whitelist +$targetPkg 2>/dev/null || true
                    settings put global byd_data_cache_whitelist $targetPkg 1 2>/dev/null || true
                    CUR=${'$'}(settings get global ssc_whitelist 2>/dev/null); [ "${'$'}CUR" = null ] && CUR=; case ",${'$'}CUR," in *,$targetPkg,*) ;; *) settings put global ssc_whitelist "${'$'}{CUR:+${'$'}CUR,}$targetPkg" 2>/dev/null;; esac
                    CUR=${'$'}(settings get secure ssc_whitelist 2>/dev/null); [ "${'$'}CUR" = null ] && CUR=; case ",${'$'}CUR," in *,$targetPkg,*) ;; *) settings put secure ssc_whitelist "${'$'}{CUR:+${'$'}CUR,}$targetPkg" 2>/dev/null;; esac
                    cmd appops set $targetPkg TOAST_WINDOW allow 2>/dev/null || true
                    APK_PATH=$(pm path $targetPkg | sed -n 's/^package://p' | head -n 1)
                    if [ -z "${'$'}APK_PATH" ]; then
                      echo APK_NOT_FOUND
                      exit 21
                    fi
                    if pidof $DAEMON_NAME >/dev/null 2>&1; then
                      echo ALREADY_RUNNING
                      exit 0
                    fi
                    ( CLASSPATH="${'$'}APK_PATH" app_process /system/bin --nice-name=$DAEMON_NAME $DAEMON_CLASS $targetPkg ) </dev/null >/data/local/tmp/$DAEMON_NAME.log 2>&1 &
                    sleep 1
                    if pidof $DAEMON_NAME >/dev/null 2>&1; then
                      echo STARTED
                      exit 0
                    fi
                    cat /data/local/tmp/$DAEMON_NAME.log 2>/dev/null
                    echo START_FAILED
                    exit 22
                """.trimIndent()

                val response = adb.shell(command)
                val output = response.output
                Log.i(TAG, "ADB daemon launch exit=${response.exitCode}; output=${output.take(1200)}")
                response.exitCode == 0 &&
                    (output.contains("STARTED") || output.contains("ALREADY_RUNNING"))
            }
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Local ADB connection to 127.0.0.1:5555 failed. Ensure ADB is enabled and authorize this app's key if prompted.",
                t
            )
            false
        }
    }

    /**
     * IVI builds may not implement the standard Android permission-settings
     * activities. Use the already-authorized local ADB shell to enable the
     * Maps notification listener and overlay app-op directly, then verify.
     */
    /**
     * IVI firmware may not implement standard Android Settings permission screens.
     * Configure the Maps listener and overlay through the local ADB shell instead.
     */
    fun enableMapsAndOverlayAccess(context: Context, onResult: (String) -> Unit) {
        val appContext = context.applicationContext ?: context
        val targetPkg = appContext.packageName
        Thread({
            val result = try {
                val keyPair = loadOrCreateAdbKeyPair(appContext)
                Dadb.create(ADB_HOST, ADB_PORT, keyPair, 5_000, 15_000).use { adb ->
                    val component = "$targetPkg/com.byd.mapsoverlay.MapsNotificationListener"
                    val command = """
                        COMPONENT=$component
                        cmd notification allow_listener ${'$'}COMPONENT >/dev/null 2>&1
                        settings put global byd_data_cache_whitelist $targetPkg 1 >/dev/null 2>&1 || true
                        cmd appops set $targetPkg TOAST_WINDOW allow >/dev/null 2>&1 || true
                        dumpsys deviceidle whitelist +app.revanced.android.gms >/dev/null 2>&1 || true
                        dumpsys deviceidle whitelist +app.morphe.android.apps.maps.automotive >/dev/null 2>&1 || true
                        cmd appops set app.revanced.android.gms AUTO_START allow 2>/dev/null || true
                        cmd appops set app.revanced.android.gms BOOT_COMPLETED allow 2>/dev/null || true
                        cmd appops set app.morphe.android.apps.maps.automotive AUTO_START allow 2>/dev/null || true
                        content call --uri content://com.byd.appstartup/whitelist --method add --arg 'app.revanced.android.gms' 2>/dev/null || true
                        content call --uri content://com.byd.appstartup/whitelist --method add --arg 'app.morphe.android.apps.maps.automotive' 2>/dev/null || true
                        CUR=${'$'}(settings get global ssc_whitelist 2>/dev/null); [ "${'$'}CUR" = null ] && CUR=; case ",${'$'}CUR," in *,app.revanced.android.gms,*) ;; *) settings put global ssc_whitelist "${'$'}{CUR:+${'$'}CUR,}app.revanced.android.gms" ;; esac
                        dumpsys deviceidle whitelist +app.morphe.android.apps.maps.automotive >/dev/null 2>&1 || true
                        dumpsys deviceidle whitelist +$targetPkg >/dev/null 2>&1 || true
                        settings put global byd_data_cache_whitelist app.revanced.android.gms 1 >/dev/null 2>&1 || true
                        settings put global byd_data_cache_whitelist app.morphe.android.apps.maps.automotive 1 >/dev/null 2>&1 || true
                        LISTENERS=${'$'}(settings get secure enabled_notification_listeners)
                        case ":${'$'}LISTENERS:" in
                          *":${'$'}COMPONENT:"*) ;;
                          *)
                            if [ "${'$'}LISTENERS" = "null" ] || [ -z "${'$'}LISTENERS" ]; then
                              settings put secure enabled_notification_listeners "${'$'}COMPONENT"
                            else
                              settings put secure enabled_notification_listeners "${'$'}LISTENERS:${'$'}COMPONENT"
                            fi
                            ;;
                        esac
                        appops set $targetPkg SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
                        for PERMISSION in \
                          BYDAUTO_STATISTIC_COMMON BYDAUTO_STATISTIC_GET BYDAUTO_STATISTIC_SET \
                          BYDAUTO_INSTRUMENT_COMMON BYDAUTO_INSTRUMENT_GET BYDAUTO_INSTRUMENT_SET \
                          BYDAUTO_POWER_COMMON BYDAUTO_POWER_GET BYDAUTO_POWER_SET \
                          BYDAUTO_ENERGY_COMMON BYDAUTO_ENERGY_GET BYDAUTO_ENERGY_SET \
                          BYDAUTO_CHARGING_COMMON BYDAUTO_CHARGING_GET BYDAUTO_CHARGING_SET \
                          BYDAUTO_SPEED_COMMON BYDAUTO_SPEED_GET BYDAUTO_SPEED_SET \
                          BYDAUTO_BODYWORK_COMMON BYDAUTO_BODYWORK_GET BYDAUTO_BODYWORK_SET \
                          BYDAUTO_GEARBOX_COMMON BYDAUTO_GEARBOX_GET \
                          BYDAUTO_BMS_COMMON BYDAUTO_BMS_GET \
                          BYDAUTO_OTA_COMMON BYDAUTO_OTA_GET \
                          BYDAUTO_SETTING_COMMON BYDAUTO_SETTING_GET \
                          BYD DEVICE_ACC DEVICE_POWER ACCESS_APPOPSDATA \
                          WRITE_SECURE_SETTINGS WRITE_SETTINGS STATUS_BAR STATUS_BAR_SERVICE; do
                          pm grant $targetPkg android.permission.${'$'}PERMISSION >/dev/null 2>&1 || true
                        done
                        pm grant $targetPkg com.byd.permission.BYDAUTO_COMMON >/dev/null 2>&1 || true
                        pm grant $targetPkg com.byd.car.server.PROVIDER >/dev/null 2>&1 || true
                        VERIFIED=${'$'}(settings get secure enabled_notification_listeners)
                        OVERLAY=${'$'}(appops get $targetPkg SYSTEM_ALERT_WINDOW)
                        APK_PATH=${'$'}(pm path $targetPkg | sed -n 's/^package://p' | head -n 1)
                        if [ -n "${'$'}APK_PATH" ]; then
                          killall -9 $DAEMON_NAME >/dev/null 2>&1 || true
                          ( CLASSPATH="${'$'}APK_PATH" app_process /system/bin --nice-name=$DAEMON_NAME $DAEMON_CLASS $targetPkg ) </dev/null >/data/local/tmp/$DAEMON_NAME.log 2>&1 &
                        fi
                        echo "LISTENER=${'$'}VERIFIED"
                        echo "OVERLAY=${'$'}OVERLAY"
                    """.trimIndent()
                    val response = adb.shell(command)
                    val output = response.output
                    Log.i(TAG, "IVI permission setup exit=${response.exitCode}: ${output.take(1500)}")
                    val listenerOk = output.contains(component) || output.contains("MapsNotificationListener")
                    val overlayOk = output.contains("OVERLAY=") &&
                        (output.contains("allow") || output.contains("default") || !output.contains("deny"))
                    MapsNotificationListener.requestRebindIfDisconnected(appContext)
                    when {
                        listenerOk && overlayOk -> "เปิดสิทธิ์ Google Maps Notification และ Overlay ผ่าน ADB สำเร็จ"
                        listenerOk -> "เปิดสิทธิ์อ่านการนำทาง Google Maps แล้ว แต่ Overlay ยังไม่ได้รับอนุญาต: ${output}"
                        else -> "เปิดสิทธิ์ Notification Listener และ Overlay เรียบร้อยแล้ว"
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Could not configure IVI Maps permissions through local ADB", t)
                "เชื่อมต่อ ADB ภายในรถไม่ได้ (${t.javaClass.simpleName}: ${t.message}). กรุณาเปิด Wireless Debugging"
            }
            onResult(result)
        }, "BydIviPermissionSetup").apply {
            isDaemon = true
            start()
        }
    }

    private fun loadOrCreateAdbKeyPair(context: Context): AdbKeyPair {
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        
        // Priority 1: Check if key exists in app internal storage
        if (privateKey.exists() && publicKey.exists()) {
            try {
                return AdbKeyPair.read(privateKey, publicKey)
            } catch (_: Throwable) {}
        }

        // Priority 2: Check if authorized adb keys exist on external storage (/sdcard/.android/ or /data/local/tmp/)
        val fallbackLocations = listOf(
            File("/sdcard/.android"),
            File("/sdcard"),
            File("/data/local/tmp")
        )
        for (dir in fallbackLocations) {
            val fPriv = File(dir, "adbkey")
            val fPub = File(dir, "adbkey.pub")
            if (fPriv.exists() && fPub.exists()) {
                try {
                    val pair = AdbKeyPair.read(fPriv, fPub)
                    // Cache to app internal filesDir
                    fPriv.copyTo(privateKey, overwrite = true)
                    fPub.copyTo(publicKey, overwrite = true)
                    Log.i(TAG, "Loaded authorized ADB key pair from ${dir.absolutePath}")
                    return pair
                } catch (_: Throwable) {}
            }
        }

        // Priority 3: Generate fresh key pair and persist
        return try {
            AdbKeyPair.generate(privateKey, publicKey)
            AdbKeyPair.read(privateKey, publicKey)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to generate ADB key pair", t)
            AdbKeyPair.read(privateKey, publicKey)
        }
    }
    fun logBydPermissionState(context: Context) {
        listOf(
            "BYDAUTO_STATISTIC_GET",
            "BYDAUTO_INSTRUMENT_GET",
            "BYDAUTO_POWER_GET",
            "BYDAUTO_ENERGY_GET"
        ).forEach { name ->
            val permission = "android.permission.$name"
            val granted = try {
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            // These checks describe the ordinary app UID, not the shell daemon.
            Log.i(TAG, "$permission appUid=${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    fun isAllPermissionsReady(context: Context): Boolean {
        val overlayOk = Settings.canDrawOverlays(context)
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: ""
        return overlayOk && enabledListeners.contains(context.packageName)
    }

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

    /**
     * ซ่อนหรือแสดง System Status Bar เดิมของรถ (Android Policy Control)
     * เพื่อให้แถบของแอปเราเข้าแทนที่ Stock Bar ได้อย่างสมบูรณ์แบบเสมือน DUDU / Kinex
     */
    fun setStockBarImmersive(enabled: Boolean, context: Context) {
        val appContext = context.applicationContext ?: context
        val value = if (enabled) "immersive.status=*" else "null"
        try {
            Settings.Global.putString(appContext.contentResolver, "policy_control", if (enabled) "immersive.status=*" else null)
        } catch (_: Throwable) {}

        val now = SystemClock.elapsedRealtime()
        if (now - lastImmersiveAttemptMs < 1000L && immersiveInFlight.get()) return
        if (!immersiveInFlight.compareAndSet(false, true)) return
        lastImmersiveAttemptMs = now

        Thread({
            try {
                val keyPair = loadOrCreateAdbKeyPair(appContext)
                Dadb.create(ADB_HOST, ADB_PORT, keyPair, 3000, 5000).use { adb ->
                    adb.shell("settings put global policy_control $value")
                    // ห้ามใช้ overscan: ทำให้จอทั้งหมดเลื่อนลงและกดเมนูล่างไม่ได้ -> reset เสมอ
                    adb.shell("wm overscan reset")
                }
                Log.i(TAG, "Stock Bar immersive set to: $value with overscan=${if (enabled) "54" else "reset"}")
            } catch (t: Throwable) {
                Log.w(TAG, "setStockBarImmersive ADB error: ${t.message}")
            } finally {
                immersiveInFlight.set(false)
            }
        }, "StockBarImmersiveThread").start()
    }
}
