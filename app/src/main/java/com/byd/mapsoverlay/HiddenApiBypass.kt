package com.byd.mapsoverlay

import java.lang.reflect.Method

/**
 * HiddenApiBypass:
 * Exempts the app from Android hidden API restrictions via dalvik.system.VMRuntime,
 * allowing reflection into android.hardware.bydauto.* and android.app.ActivityThread on Android 9/10 (DiLink 3).
 */
object HiddenApiBypass {
    @Volatile
    private var isExempted = false

    fun exemptAll() {
        if (isExempted) return
        try {
            val forNameMethod = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                arrayOf<Class<*>>()::class.java
            )

            val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntimeMethod = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setHiddenApiExemptionsMethod = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf(Array<String>::class.java)
            ) as Method

            val vmRuntimeInstance = getRuntimeMethod.invoke(null)
            setHiddenApiExemptionsMethod.invoke(vmRuntimeInstance, arrayOf("L"))
            isExempted = true
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
