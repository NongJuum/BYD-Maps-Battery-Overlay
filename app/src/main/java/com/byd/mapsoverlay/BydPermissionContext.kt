package com.byd.mapsoverlay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

/**
 * BydPermissionContext:
 * Permissive ContextWrapper inspired by Overdrive (BydDeviceHelper.BydPermissionContext).
 * Intercepts Android permission queries for BYDAUTO signature permissions
 * (such as android.permission.BYDAUTO_STATISTIC_GET) and returns GRANTED (0).
 *
 * This allows the BYD client SDK (TsManagerImpl / BYDAutoStatisticDevice) to function
 * without throwing SecurityException when called in-process on DiLink 3.
 */
class BydPermissionContext(base: Context) : ContextWrapper(base) {

    private fun isBydPermission(permission: String?): Boolean {
        return permission != null && (
            permission.startsWith("android.permission.BYD") ||
            permission.startsWith("com.byd.permission.BYD")
        )
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        if (isBydPermission(permission)) {
            return PackageManager.PERMISSION_GRANTED
        }
        return super.checkPermission(permission, pid, uid)
    }

    override fun checkCallingPermission(permission: String): Int {
        if (isBydPermission(permission)) {
            return PackageManager.PERMISSION_GRANTED
        }
        return super.checkCallingPermission(permission)
    }

    override fun checkCallingOrSelfPermission(permission: String): Int {
        if (isBydPermission(permission)) {
            return PackageManager.PERMISSION_GRANTED
        }
        return super.checkCallingOrSelfPermission(permission)
    }

    override fun checkSelfPermission(permission: String): Int {
        if (isBydPermission(permission)) {
            return PackageManager.PERMISSION_GRANTED
        }
        return super.checkSelfPermission(permission)
    }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (isBydPermission(permission)) {
            return
        }
        super.enforcePermission(permission, pid, uid, message)
    }

    override fun enforceCallingPermission(permission: String, message: String?) {
        if (isBydPermission(permission)) {
            return
        }
        super.enforceCallingPermission(permission, message)
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (isBydPermission(permission)) {
            return
        }
        super.enforceCallingOrSelfPermission(permission, message)
    }

    /**
     * Crucial: Return `this` so BYD TsManagerImpl / internal factories cannot
     * unwrap back to the non-wrapped Application Context.
     */
    override fun getApplicationContext(): Context {
        return this
    }

    companion object {
        fun wrap(context: Context): Context {
            return if (context is BydPermissionContext) context else BydPermissionContext(context)
        }
    }
}
