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
 * Starts the read-only BYD SDK reader in an app_process process running as
 * ADB shell (uid 2000). The normal application UID cannot obtain BYD's
 * signature-protected vehicle telemetry on DiLink 3.
 */
object AutoPermissionHelper {
    private const val TAG = "AutoPermissionHelper"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val DAEMON_PORT = 19878
    private const val DAEMON_NAME = "byd_maps_telemetry_daemon"
    private const val DAEMON_CLASS = "com.byd.mapsoverlay.BydTelemetryDaemon"
    private const val RETRY_INTERVAL_MS = 15_000L

    private val launchInFlight = AtomicBoolean(false)
    @Volatile private var lastLaunchAttemptMs = 0L

    /**
     * Safe to call repeatedly. A lightweight loopback health check avoids
     * reconnecting to adbd while the shell-UID telemetry daemon is healthy.
     * Failed starts are retried with a 15-second backoff.
     */
    fun ensureTelemetryDaemon(context: Context) {
        if (isTelemetryDaemonReachable()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAttemptMs < RETRY_INTERVAL_MS) return
        if (!launchInFlight.compareAndSet(false, true)) return
        lastLaunchAttemptMs = now

        val appContext = context.applicationContext ?: context
        Thread({
            try {
                if (isTelemetryDaemonReachable()) return@Thread
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
     * This helper does not pretend that an ordinary app process can grant
     * signature-level BYDAUTO permissions. It starts the shell reader and
     * reports the independently managed Android overlay/listener state.
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

    /**
     * Connects to the head unit's local adbd. The generated key is persisted
     * in app-private storage so the ADB authorization is stable across runs.
     */
    private fun launchTelemetryDaemon(context: Context): Boolean {
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        val keyPair = try {
            if (privateKey.exists() && publicKey.exists()) {
                AdbKeyPair.read(privateKey, publicKey)
            } else {
                AdbKeyPair.generate(privateKey, publicKey)
                AdbKeyPair.read(privateKey, publicKey)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create/read persistent ADB key pair", t)
            return false
        }

        return try {
            // Dadb 1.2.8 supports bounded TCP-connect and socket timeouts.
            Dadb.create(ADB_HOST, ADB_PORT, keyPair, 2_000, 5_000).use { adb ->
                // Force the CNXN/AUTH handshake before opening a shell stream.
                if (!adb.supportsFeature("shell_v2")) {
                    Log.w(TAG, "adbd did not advertise shell_v2; continuing with its available shell transport")
                }

                val command = """
                    APK_PATH=$(pm path com.byd.mapsoverlay | sed -n 's/^package://p' | head -n 1)
                    if [ -z "$APK_PATH" ]; then
                      echo APK_NOT_FOUND
                      exit 21
                    fi
                    if pidof $DAEMON_NAME >/dev/null 2>&1; then
                      echo ALREADY_RUNNING
                      exit 0
                    fi
                    CLASSPATH="$APK_PATH" app_process /system/bin --nice-name=$DAEMON_NAME $DAEMON_CLASS </dev/null >/data/local/tmp/$DAEMON_NAME.log 2>&1 &
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
                "Local ADB connection to 127.0.0.1:5555 failed. Confirm the head unit's ADB service is enabled and authorize this app's key if prompted.",
                t
            )
            false
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
            // These checks describe the ordinary app UID only. The daemon runs
            // as shell UID and uses the BYD SDK with a permission-bypass Context.
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
}
