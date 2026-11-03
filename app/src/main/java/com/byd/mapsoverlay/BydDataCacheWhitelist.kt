package com.byd.mapsoverlay

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * BydDataCacheWhitelist:
 * Exact replica of Overdrive's `BydDataCacheWhitelist` (`com.overdrive.app.util.BydDataCacheWhitelist`).
 *
 * Configures the 4 critical BYD vehicle services required on DiLink 3 (Android 10):
 * 1. Obtains privileged context via `ActivityThread.systemMain().getSystemContext().createPackageContext(..., 3)`.
 * 2. `accmodemanager`: `mService.setPkg2AccWhiteList(packageName)` - Prevents the car from cutting power / killing the app in ACC/driving modes.
 * 3. `bg_datacache`: `setAppOpsData(appUid, 0)` - Grants the app access to the vehicle telemetry bus on DiLink 3.
 * 4. `byd_datacached`: `setAppStartupData(appUid, 0)` - Whitelists app startup on DiLink 4/5.
 * 5. `BYDAutoStartupAppDevice`: `setStartupAppEnable(packageName, true)` - Ensures the head unit allows automatic background launch.
 */
object BydDataCacheWhitelist {
    private const val TAG = "BydDataCacheWhitelist"
    @Volatile private var isWhitelisted = false

    fun ensureWhitelisted(context: Context): Boolean {
        if (isWhitelisted) return true

        HiddenApiBypass.exemptAll()
        val packageName = context.packageName

        // Overdrive's exact pattern: Obtain privileged system context via ActivityThread.systemMain()
        var contextCreatePackageContext: Context? = null
        try {
            val cls = Class.forName("android.app.ActivityThread")
            val method = cls.getMethod("systemMain")
            val holder = arrayOfNulls<Any>(1)
            val thread = Thread({
                try {
                    holder[0] = method.invoke(null)
                } catch (t: Throwable) {
                    Log.d(TAG, "systemMain invoke error: ${t.message}")
                }
            }, "SysMainThread")
            thread.start()
            thread.join(3000L)
            if (thread.isAlive) {
                thread.interrupt()
            } else {
                val sysThread = holder[0]
                if (sysThread != null) {
                    val systemContextObj = cls.getMethod("getSystemContext").invoke(sysThread)
                    if (systemContextObj is Context) {
                        // CONTEXT_INCLUDE_CODE (1) | CONTEXT_IGNORE_SECURITY (2) = 3
                        contextCreatePackageContext = systemContextObj.createPackageContext(packageName, 3)
                        Log.i(TAG, "Successfully created privileged package context from systemMain")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "systemMain bypass skipped/failed: ${t.message}")
        }

        val targetContext = BydPermissionContext.wrap(contextCreatePackageContext ?: context)

        // 1. Whitelist in ACC Mode Manager (Prevents vehicle from disconnecting telemetry)
        whitelistAccMode(targetContext, packageName)

        // 2. Whitelist in DataCache service (bg_datacache on DiLink 3)
        registerDataCache(targetContext, packageName)

        // 3. Enable startup in BYDAutoStartupAppDevice
        enableStartupApp(targetContext, packageName)

        isWhitelisted = true
        return true
    }

    private fun whitelistAccMode(context: Context, packageName: String) {
        try {
            val accModeManager = context.getSystemService("accmodemanager")
            if (accModeManager != null) {
                val field: Field = accModeManager.javaClass.getDeclaredField("mService")
                field.isAccessible = true
                val mService = field.get(accModeManager)
                if (mService != null) {
                    val method: Method = mService.javaClass.getDeclaredMethod("setPkg2AccWhiteList", String::class.java)
                    method.isAccessible = true
                    method.invoke(mService, packageName)
                    Log.i(TAG, "Successfully added $packageName to setPkg2AccWhiteList")
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "accmodemanager whitelist skipped/failed: ${t.message}")
        }
    }

    private fun registerDataCache(context: Context, packageName: String) {
        try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val appUid = appInfo.uid

            // DiLink 3 (Android 10): bg_datacache
            try {
                val service = context.getSystemService("bg_datacache")
                if (service != null) {
                    val method: Method = service.javaClass.getMethod(
                        "setAppOpsData",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(service, appUid.toString(), 0)
                    Log.i(TAG, "Successfully registered UID $appUid with bg_datacache")
                }
            } catch (t: Throwable) {
                Log.d(TAG, "bg_datacache registration: ${t.message}")
            }

            // DiLink 4 / 5 (API 31+): byd_datacached
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    val service = context.getSystemService("byd_datacached")
                    if (service != null) {
                        val method: Method = service.javaClass.getMethod(
                            "setAppStartupData",
                            String::class.java,
                            Int::class.javaPrimitiveType
                        )
                        method.invoke(service, appUid.toString(), 0)
                        Log.i(TAG, "Successfully registered UID $appUid with byd_datacached")
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "byd_datacached registration skipped: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "registerDataCache error: ${t.message}")
        }
    }

    private fun enableStartupApp(context: Context, packageName: String) {
        try {
            val clazz = Class.forName("android.hardware.bydauto.startup.BYDAutoStartupAppDevice")
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            val device = getInstance.invoke(null, BydPermissionContext.wrap(context)) ?: return

            val getMethod = clazz.getMethod("getStartupAppEnable", String::class.java)
            val setMethod = clazz.getMethod("setStartupAppEnable", String::class.java, Boolean::class.javaPrimitiveType)

            for (attempt in 0 until 5) {
                if (java.lang.Boolean.TRUE == getMethod.invoke(device, packageName)) {
                    Log.i(TAG, "BYDAutoStartupAppDevice: $packageName already enabled")
                    return
                }
                setMethod.invoke(device, packageName, true)
                if (java.lang.Boolean.TRUE == getMethod.invoke(device, packageName)) {
                    Log.i(TAG, "BYDAutoStartupAppDevice: $packageName enabled successfully")
                    return
                }
                SystemClock.sleep(100)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "enableStartupApp skipped/failed: ${t.message}")
        }
    }
}
