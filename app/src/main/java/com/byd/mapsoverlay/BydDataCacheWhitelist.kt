package com.byd.mapsoverlay

import android.content.Context
import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * BydDataCacheWhitelist:
 * Exact replica of Overdrive's BydDataCacheWhitelist (`com.overdrive.app.util.BydDataCacheWhitelist`).
 *
 * DiLink 3 (Android 10) uses a proprietary BYD service named `bg_datacache` (or `byd_datacached` on newer builds).
 * Calling `setAppOpsData(uid, 0)` on this service whitelists the application so that BYD's vehicle telemetry
 * daemon accepts connections from our application UID.
 */
object BydDataCacheWhitelist {
    private const val TAG = "BydDataCacheWhitelist"

    fun ensureWhitelisted(context: Context): Boolean {
        HiddenApiBypass.exemptAll()

        var privilegedContext: Context? = null
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            var activityThreadInstance: Any? = null
            try {
                activityThreadInstance = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            } catch (_: Throwable) {}

            if (activityThreadInstance == null) {
                try {
                    val systemMainMethod = activityThreadClass.getMethod("systemMain")
                    activityThreadInstance = systemMainMethod.invoke(null)
                } catch (_: Throwable) {}
            }

            if (activityThreadInstance != null) {
                val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
                val systemContext = getSystemContextMethod.invoke(activityThreadInstance) as? Context
                privilegedContext = systemContext?.createPackageContext(
                    context.packageName,
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Privileged context creation fallback: ${e.message}")
        }

        val targetContext = privilegedContext ?: context
        return registerWithBydService(targetContext, context.packageName)
    }

    private fun registerWithBydService(context: Context, packageName: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            val appUid = appInfo.uid

            var success = false

            // DiLink 4 / 5 (API 31+) service
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
                        Log.i(TAG, "Successfully registered with byd_datacached for UID $appUid")
                        success = true
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "byd_datacached registration skipped: ${t.message}")
                }
            }

            // DiLink 3 (Android 10) service: bg_datacache
            try {
                val service = context.getSystemService("bg_datacache")
                if (service != null) {
                    val method: Method = service.javaClass.getMethod(
                        "setAppOpsData",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(service, appUid.toString(), 0)
                    Log.i(TAG, "Successfully registered with bg_datacache for UID $appUid")
                    success = true
                }
            } catch (t: Throwable) {
                Log.d(TAG, "bg_datacache registration: ${t.message}")
            }

            success
        } catch (t: Throwable) {
            Log.w(TAG, "registerWithBydService failed: ${t.message}")
            false
        }
    }
}
