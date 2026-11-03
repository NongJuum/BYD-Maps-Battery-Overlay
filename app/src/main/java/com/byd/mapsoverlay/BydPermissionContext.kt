package com.byd.mapsoverlay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

/**
 * BydPermissionContext:
 * Unconditionally grants permission checks to prevent BYD SDK internal checks
 * or vehicle system services from throwing SecurityException on DiLink 3 (Android 10).
 */
class BydPermissionContext(base: Context) : ContextWrapper(base) {

    override fun checkCallingOrSelfPermission(permission: String): Int {
        return PackageManager.PERMISSION_GRANTED
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        return PackageManager.PERMISSION_GRANTED
    }

    override fun checkCallingPermission(permission: String): Int {
        return PackageManager.PERMISSION_GRANTED
    }

    override fun checkSelfPermission(permission: String): Int {
        return PackageManager.PERMISSION_GRANTED
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        // No-op (bypass)
    }

    override fun enforceCallingPermission(permission: String, message: String?) {
        // No-op (bypass)
    }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        // No-op (bypass)
    }

    override fun createPackageContext(packageName: String, flags: Int): Context {
        return try {
            super.createPackageContext(packageName, flags)
        } catch (_: Exception) {
            this
        }
    }

    override fun getApplicationContext(): Context {
        return this
    }

    companion object {
        fun wrap(context: Context): Context {
            return if (context is BydPermissionContext) context else BydPermissionContext(context)
        }
    }
}
