package com.byd.mapsoverlay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object AutomotiveInstallerHelper {
    private const val TAG = "AutoInstallerHelper"

    const val PKG_MICROG = "app.revanced.android.gms"
    const val PKG_TTS = "com.google.android.tts"
    const val PKG_MAPS_CAR = "app.morphe.android.apps.maps.automotive"

    const val TYPE_MICROG = 1
    const val TYPE_TTS = 3
    const val TYPE_MAPS_CAR = 4

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * ค้นหาไฟล์ APK ใน USB Storage หรือ Download/Internal Storage อย่างยืดหยุ่น (ไม่หลุดแม้ชื่อไฟล์เพี้ยนหรืออยู่ในโฟลเดอร์ย่อย)
     */
    fun findApkFile(context: Context, type: Int): File? {
        val searchDirs = mutableListOf<File>()

        // 1. External Media Dirs (USB Flash Drives on Android)
        context.getExternalFilesDirs(null).forEach { dir ->
            var parent = dir
            while (parent != null && parent.parentFile != null) {
                if (parent.name == "Android") {
                    parent.parentFile?.let { searchDirs.add(it) }
                    break
                }
                parent = parent.parentFile
            }
        }

        // 2. Internal Storage, Downloads, and Phone Transfer Folders
        val internalRoot = Environment.getExternalStorageDirectory()
        if (internalRoot != null) {
            searchDirs.add(internalRoot)
            searchDirs.add(File(internalRoot, "Download"))
            searchDirs.add(File(internalRoot, "Downloads"))
            searchDirs.add(File(internalRoot, "Bluetooth"))
            searchDirs.add(File(internalRoot, "Share"))
            searchDirs.add(File(internalRoot, "Transfer"))
            searchDirs.add(File(internalRoot, "Telegram"))
        }
        searchDirs.add(File("/sdcard"))
        searchDirs.add(File("/sdcard/Download"))
        searchDirs.add(File("/sdcard/Bluetooth"))
        searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))

        // 3. USB Drives and External Mounts
        searchDirs.add(File("/storage"))
        searchDirs.add(File("/storage/emulated/0"))
        searchDirs.add(File("/storage/emulated/0/Download"))
        searchDirs.add(File("/mnt/media_rw"))
        searchDirs.add(File("/mnt/usb"))
        searchDirs.add(File("/mnt/udisk"))
        searchDirs.add(File("/mnt/sdcard"))

        for (dir in searchDirs.distinct()) {
            val found = scanForApk(dir, type, maxDepth = 4)
            if (found != null && found.exists() && found.length() > 1024) {
                Log.i(TAG, "Found target APK for type $type: ${found.absolutePath}")
                return found
            }
        }
        return null
    }

    private fun isMatch(fileName: String, type: Int): Boolean {
        val lower = fileName.lowercase()
        if (!lower.endsWith(".apk") || lower.startsWith("._")) return false
        return when (type) {
            TYPE_MICROG -> (lower.startsWith("01") || lower.contains("microg")) && !lower.contains("tts") && !lower.contains("maps")
            TYPE_TTS -> (lower.startsWith("03") || lower.contains("tts")) && !lower.contains("microg") && !lower.contains("maps")
            TYPE_MAPS_CAR -> (lower.startsWith("04") || lower.contains("maps")) && !lower.contains("microg") && !lower.contains("tts") && !lower.contains("overlay")
            else -> false
        }
    }

    private fun scanForApk(dir: File, type: Int, maxDepth: Int): File? {
        if (!dir.exists() || !dir.canRead() || maxDepth < 0) return null
        val files = dir.listFiles() ?: return null
        // First check files in current directory
        for (f in files) {
            if (f.isFile && isMatch(f.name, type)) {
                return f
            }
        }
        // Then search subdirectories
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".")) {
                val sub = scanForApk(f, type, maxDepth - 1)
                if (sub != null) return sub
            }
        }
        return null
    }

    /**
     * สั่งเปิดตัวติดตั้ง PackageInstaller ของ Android ทันที
     */
    fun triggerInstall(context: Context, apkFile: File): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                } else {
                    Uri.fromFile(apkFile)
                }
                setDataAndType(uri, "application/vnd.android.package-archive")
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger install intent for ${apkFile.name}", e)
            false
        }
    }
}
