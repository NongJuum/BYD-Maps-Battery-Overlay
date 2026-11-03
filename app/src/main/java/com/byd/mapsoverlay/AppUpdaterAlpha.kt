package com.byd.mapsoverlay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelogTh: String,
    val changelogEn: String
)

object AppUpdaterAlpha {
    private const val TAG = "AppUpdaterAlpha"
    private const val VERSION_URL = "https://raw.githubusercontent.com/NongJuum/BYD-Maps-Battery-Overlay/beta/version.json"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    /**
     * ตรวจสอบเวอร์ชันใหม่จาก GitHub
     */
    fun checkUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
        Thread {
            try {
                val url = URL(VERSION_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")

                if (conn.responseCode == 200) {
                    val rawJson = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(rawJson)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val remoteVersionName = json.optString("versionName", "")
                    val apkUrl = json.optString("apkUrl", "")
                    val changelogTh = json.optString("changelogTh", "")
                    val changelogEn = json.optString("changelogEn", "")

                    val currentCode = getCurrentVersionCode(context)
                    Log.i(TAG, "Current versionCode=$currentCode, Remote versionCode=$remoteVersionCode")

                    if (remoteVersionCode > currentCode && apkUrl.isNotBlank()) {
                        val info = UpdateInfo(
                            versionCode = remoteVersionCode,
                            versionName = remoteVersionName,
                            apkUrl = apkUrl,
                            changelogTh = changelogTh,
                            changelogEn = changelogEn
                        )
                        mainHandler.post { onResult(info) }
                        return@Thread
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Check update failed: ${t.message}")
            }
            mainHandler.post { onResult(null) }
        }.start()
    }

    /**
     * ดาวน์โหลดและติดตั้งผ่าน ADB Silent Installer หรือ Intent Fallback
     */
    fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onStatus: (String) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ) {
        val appContext = context.applicationContext ?: context
        Thread {
            try {
                mainHandler.post { onStatus("กำลังเชื่อมต่อดาวน์โหลด...") }
                val targetFile = File(appContext.cacheDir, "beta_update.apk")
                if (targetFile.exists()) targetFile.delete()

                val url = URL(info.apkUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.useCaches = false

                if (conn.responseCode != 200) {
                    mainHandler.post { onFinished(false, "ดาวน์โหลดล้มเหลว HTTP ${conn.responseCode}") }
                    return@Thread
                }

                val totalLength = conn.contentLength
                var downloaded = 0
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalLength > 0) {
                                val progress = ((downloaded.toLong() * 100) / totalLength).toInt()
                                mainHandler.post { onProgress(progress) }
                            }
                        }
                    }
                }

                if (!targetFile.exists() || targetFile.length() < 10000) {
                    mainHandler.post { onFinished(false, "ไฟล์ดาวน์โหลดไม่สมบูรณ์") }
                    return@Thread
                }

                val archiveInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    appContext.packageManager.getPackageArchiveInfo(
                        targetFile.absolutePath,
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
                }
                if (archiveInfo == null) {
                    mainHandler.post { onFinished(false, "ไฟล์ที่ดาวน์โหลดไม่ใช่ APK ที่สมบูรณ์") }
                    return@Thread
                }
                if (archiveInfo.packageName != appContext.packageName) {
                    mainHandler.post { onFinished(false, "Package ไม่ตรงกัน: พบ ${archiveInfo.packageName} แต่แอปนี้คือ ${appContext.packageName}") }
                    return@Thread
                }

                mainHandler.post { onStatus("ดาวน์โหลดเสร็จสิ้น กำลังตรวจสอบช่องทางติดตั้ง...") }
                // Always attempt a real authenticated ADB install; a listening TCP port is not authorization.
                // On any ADB/auth/install failure, fall back to Android Package Installer for user confirmation.
                AutoPermissionHelperAlpha.installApkSilently(appContext, targetFile) { success, msg ->
                    if (success) {
                        mainHandler.post { onFinished(true, msg) }
                    } else {
                        Log.w(TAG, "Silent ADB install failed; falling back to Android installer: $msg")
                        mainHandler.post { onStatus("ติดตั้งผ่าน ADB ไม่สำเร็จ กำลังเปิดตัวติดตั้ง Android...") }
                        mainHandler.post { launchSystemInstaller(appContext, targetFile, msg, onFinished) }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Download/install error", t)
                mainHandler.post { onFinished(false, "เกิดข้อผิดพลาด: ${t.message}") }
            }
        }.start()
    }
}

private fun launchSystemInstaller(
    context: Context,
    apkFile: File,
    adbFailure: String,
    onFinished: (Boolean, String) -> Unit
) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        context.startActivity(installIntent)
        onFinished(true, "เปิดหน้าติดตั้ง Android แล้ว กรุณากดยืนยันบนหน้าจอรถ (ADB: $adbFailure)")
    } catch (e: Exception) {
        Log.e("AppUpdaterAlpha", "Unable to launch system package installer", e)
        onFinished(false, "ติดตั้งผ่าน ADB ไม่สำเร็จ ($adbFailure) และเปิดตัวติดตั้ง Android ไม่ได้: ${e.message}")
    }
}
