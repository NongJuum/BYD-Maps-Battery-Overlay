package com.byd.mapsoverlay

import android.content.Context
import android.provider.Settings
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AutoPermissionHelperAlpha:
 * Advanced ADB manager and permission helper engineered for native DiLink 3 vehicle hardware integration.
 *
 * Built to eliminate all failure holes:
 * 1. Background Polling Loop (`startAdbAuthPolling`):
 *    Polls every 2.5 seconds for up to 2.5 minutes while waiting for the user to tap
 *    "[✓] Always allow from this computer" and "[OK]" on the car screen.
 *    Properly closes unauthenticated sockets to avoid file descriptor exhaustion.
 * 2. Full Privileged Setup:
 *    Executes `pm grant` for all BYDAUTO common permissions, `appops SYSTEM_ALERT_WINDOW allow`,
 *    notification listener enablement, and background execution whitelist (`dumpsys deviceidle whitelist`).
 * 3. Robust Daemon Startup:
 *    Uses `/proc/net/tcp` (hex 4DAE = port 19890) to check daemon status without depending on `nc` or `pidof`.
 *    Uses `nohup` + `export CLASSPATH` so the daemon process is detached and survives ADB disconnect.
 * 4. Silent Remote Update (`installApkSilently`):
 *    Installs APKs silently via ADB `pm install -r -d` and restarts the app.
 */
object AutoPermissionHelperAlpha {
    private const val TAG = "AutoPermHelperAlpha"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val DAEMON_PORT = 19890
    private const val DAEMON_NAME = "bydtelemetry"
    private const val DAEMON_CLASS = "com.byd.mapsoverlay.BydTelemetryDaemon"

    private val isPollingActive = AtomicBoolean(false)
    @Volatile var isAdbAuthorized = false
        private set

    /**
     * Start polling ADB connection in the background.
     * When the "Allow USB debugging?" dialog appears on DiLink 3,
     * this loop keeps probing until the user taps [OK], then immediately applies permissions.
     */
    fun startAdbAuthPolling(
        context: Context,
        onStatus: (message: String, isFinished: Boolean, isSuccess: Boolean) -> Unit
    ) {
        if (!isPollingActive.compareAndSet(false, true)) {
            onStatus("กำลังตรวจจับ ADB อยู่แล้ว...", false, false)
            return
        }

        val appContext = context.applicationContext ?: context
        Thread({
            var activeDadb: Dadb? = null
            try {
                // 1. Check if ADB port 5555 is open (with 15-second startup grace window)
                onStatus("กำลังตรวจสอบพอร์ต ADB (127.0.0.1:5555)...", false, false)
                var portOpen = false
                for (p in 0 until 10) {
                    if (isAdbPortOpen()) {
                        portOpen = true
                        break
                    }
                    try {
                        Thread.sleep(1500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }

                if (!portOpen) {
                    onStatus(
                        "พอร์ต ADB 5555 ยังไม่เปิด! กรุณาเปิด 'Developer options -> USB debugging' ในการตั้งค่ารถ แล้วกดปุ่มลองใหม่",
                        true,
                        false
                    )
                    return@Thread
                }

                // 2. Load or generate RSA keypair
                val keyPair = loadOrCreateAdbKeyPair(appContext)
                onStatus("กำลังเชื่อมต่อ ADB... หากมีหน้าต่างขึ้นบนจอรถ ให้ติ๊ก [✓] Always allow แล้วกด [OK]", false, false)

                val maxAttempts = 60 // 60 attempts * 2.5s = 150 seconds (2.5 mins)
                var attempt = 0

                while (attempt < maxAttempts) {
                    attempt++
                    var candidate: Dadb? = null
                    try {
                        candidate = Dadb.create(ADB_HOST, ADB_PORT, keyPair, 2000, 3000)
                        val testResp = candidate.shell("echo connected")
                        if (testResp.output.contains("connected")) {
                            Log.i(TAG, "ADB authorization succeeded on attempt $attempt!")
                            activeDadb = candidate
                            break
                        } else {
                            try { candidate.close() } catch (_: Throwable) {}
                        }
                    } catch (e: Throwable) {
                        try { candidate?.close() } catch (_: Throwable) {}
                        Log.d(TAG, "Attempt $attempt waiting for ADB auth: ${e.message}")
                    }

                    if (attempt % 2 == 0) {
                        val secondsLeft = ((maxAttempts - attempt) * 2.5).toInt()
                        onStatus(
                            "รอการกดยืนยันบนหน้าจอรถ ($secondsLeft วิ)... ติ๊ก [✓] Always allow แล้วกด [OK]",
                            false,
                            false
                        )
                    }

                    try {
                        Thread.sleep(2500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }

                if (activeDadb == null) {
                    onStatus("หมดเวลาการรอเชื่อมต่อ ADB กรุณาลองกดปุ่มใหม่อีกครั้ง", true, false)
                    return@Thread
                }

                // 3. Authorized! Apply full permissions and launch auxiliary daemon
                isAdbAuthorized = true
                onStatus("เชื่อมต่อ ADB สำเร็จแล้ว! กำลังบันทึกสิทธิ์และตั้งค่าระบบ...", false, false)

                val targetPkg = appContext.packageName

                val setupScript = """
                    # 1. Grant BYD hardware and system permissions (direct DiLink 3 access)
                    for P in \
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
                      BYD DEVICE_ACC DEVICE_POWER ACCESS_APPOPSDATA; do
                      pm grant $targetPkg android.permission.${'$'}P >/dev/null 2>&1 || true
                    done
                    pm grant $targetPkg com.byd.permission.BYDAUTO_COMMON >/dev/null 2>&1 || true
                    pm grant $targetPkg com.byd.car.server.PROVIDER >/dev/null 2>&1 || true

                    # 2. Grant Overlay AppOp
                    appops set $targetPkg SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1

                    # 3. Grant Notification Listener
                    COMPONENT=$targetPkg/com.byd.mapsoverlay.MapsNotificationListener
                    cmd notification allow_listener ${'$'}COMPONENT >/dev/null 2>&1
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

                    # 4. DiLink ACC Mode Manager Service Calls & System Properties
                    setprop persist.sys.acc.whitelist '$targetPkg' 2>&1
                    service call accmodemanager 1 s16 '$targetPkg' 2>/dev/null || true
                    service call accmodemanager 2 s16 '$targetPkg' 2>/dev/null || true
                    service call accmodemanager 3 s16 '$targetPkg' 2>/dev/null || true

                    # 5. DiLink Background, WAKE_LOCK, AUTO_START and Doze whitelist
                    appops set $targetPkg RUN_IN_BACKGROUND allow 2>/dev/null || true
                    appops set $targetPkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
                    appops set $targetPkg WAKE_LOCK allow 2>/dev/null || true
                    cmd appops set $targetPkg AUTO_START allow 2>/dev/null || true
                    cmd appops set $targetPkg BOOT_COMPLETED allow 2>/dev/null || true
                    dumpsys deviceidle whitelist +$targetPkg 2>/dev/null || true

                    # 6. DiLink Startup Provider & SSC Whitelists
                    CUR=${'$'}(settings get global ssc_whitelist 2>/dev/null); [ "${'$'}CUR" = null ] && CUR=; case ",${'$'}CUR," in *,$targetPkg,*) ;; *) settings put global ssc_whitelist "${'$'}{CUR:+${'$'}CUR,}$targetPkg" 2>/dev/null;; esac
                    CUR=${'$'}(settings get secure ssc_whitelist 2>/dev/null); [ "${'$'}CUR" = null ] && CUR=; case ",${'$'}CUR," in *,$targetPkg,*) ;; *) settings put secure ssc_whitelist "${'$'}{CUR:+${'$'}CUR,}$targetPkg" 2>/dev/null;; esac
                    content call --uri content://com.byd.appstartup/whitelist --method add --arg '$targetPkg' 2>/dev/null || true
                    setprop persist.sys.adb.status_memory_enable true 2>/dev/null || true

                    # 5. Launch auxiliary telemetry daemon if not running (port 19890 = hex 4DAE in /proc/net/tcp)
                    APK_PATH=${'$'}(pm path $targetPkg | sed -n 's/^package://p' | head -n 1)
                    if [ -n "${'$'}APK_PATH" ]; then
                      if ! grep -qi ":4DAE " /proc/net/tcp 2>/dev/null; then
                        export CLASSPATH="${'$'}APK_PATH"
                        nohup app_process /system/bin --nice-name=$DAEMON_NAME $DAEMON_CLASS $targetPkg </dev/null >/data/local/tmp/$DAEMON_NAME.log 2>&1 &
                        sleep 1
                        chmod 666 /data/local/tmp/$DAEMON_NAME.log 2>/dev/null || true
                      fi
                    fi
                    echo SETUP_COMPLETE
                """.trimIndent()

                val setupResp = activeDadb.shell(setupScript)
                Log.i(TAG, "Setup script exit=${setupResp.exitCode}, output=${setupResp.output}")

                try { activeDadb.close() } catch (_: Throwable) {}

                onStatus("✅ ปลดล็อกสิทธิ์ระดับระบบ DiLink สำเร็จทั้งหมด รถพร้อมอ่านข้อมูลแล้ว!", true, true)
            } catch (t: Throwable) {
                Log.e(TAG, "Error in ADB auth polling", t)
                try { activeDadb?.close() } catch (_: Throwable) {}
                onStatus("เกิดข้อผิดพลาด: ${t.javaClass.simpleName} - ${t.message}", true, false)
            } finally {
                isPollingActive.set(false)
            }
        }, "AdbAuthPollingThread").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Silent remote update:
     * Installs an APK file from device storage via ADB shell and restarts the app.
     */
    fun installApkSilently(
        context: Context,
        apkFile: File,
        onResult: (isSuccess: Boolean, message: String) -> Unit
    ) {
        val appContext = context.applicationContext ?: context
        Thread({
            try {
                if (!apkFile.exists() || apkFile.length() < 10000) {
                    onResult(false, "ไฟล์ APK ไม่ถูกต้องหรือไม่พบ")
                    return@Thread
                }

                val expectedBytes = apkFile.length()
                val keyPair = loadOrCreateAdbKeyPair(appContext)
                Dadb.create(ADB_HOST, ADB_PORT, keyPair, 2000, 60000).use { dadb ->
                    val tmpTarget = "/data/local/tmp/alpha_update.apk"
                    val progressFile = "/data/local/tmp/alpha_update_progress.json"
                    val installLog = "/data/local/tmp/alpha_install.log"

                    // Reference-style installer: verify staged APK, capture PM output,
                    // preserve the actual return code, and persist progress/error details.
                    val installScript = """
                        #!/system/bin/sh
                        APK_PATH="${'1"
                        TARGET_PKG="$2"
                        EXPECTED_BYTES="$3"
                        PROGRESS="$progressFile"
                        LOG="$installLog"
                        echo "[install] starting at $(date)" > "$LOG"
                        printf '{"phase":"installing","percent":-1,"message":"Installing Alpha update"}' > "$PROGRESS"

                        if [ ! -f "$APK_PATH" ]; then
                          echo "APK file missing: $APK_PATH" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}1"
                        TARGET_PKG="${'2"
                        EXPECTED_BYTES="$3"
                        PROGRESS="$progressFile"
                        LOG="$installLog"
                        echo "[install] starting at $(date)" > "$LOG"
                        printf '{"phase":"installing","percent":-1,"message":"Installing Alpha update"}' > "$PROGRESS"

                        if [ ! -f "$APK_PATH" ]; then
                          echo "APK file missing: $APK_PATH" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}2"
                        EXPECTED_BYTES="${'3"
                        PROGRESS="$progressFile"
                        LOG="$installLog"
                        echo "[install] starting at $(date)" > "$LOG"
                        printf '{"phase":"installing","percent":-1,"message":"Installing Alpha update"}' > "$PROGRESS"

                        if [ ! -f "$APK_PATH" ]; then
                          echo "APK file missing: $APK_PATH" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}3"
                        PROGRESS="$progressFile"
                        LOG="$installLog"
                        echo "[install] starting at ${'(date)" > "$LOG"
                        printf '{"phase":"installing","percent":-1,"message":"Installing Alpha update"}' > "$PROGRESS"

                        if [ ! -f "$APK_PATH" ]; then
                          echo "APK file missing: $APK_PATH" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}(date)" > "${'LOG"
                        printf '{"phase":"installing","percent":-1,"message":"Installing Alpha update"}' > "$PROGRESS"

                        if [ ! -f "$APK_PATH" ]; then
                          echo "APK file missing: $APK_PATH" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}LOG"
                        printf '{"phase":"installing","percent":-1,"message":"Installing Alpha update"}' > "${'PROGRESS"

                        if [ ! -f "$APK_PATH" ]; then
                          echo "APK file missing: $APK_PATH" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PROGRESS"

                        if [ ! -f "${'APK_PATH" ]; then
                          echo "APK file missing: $APK_PATH" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}APK_PATH" ]; then
                          echo "APK file missing: ${'APK_PATH" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}APK_PATH" | tee -a "${'LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "$PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK file missing"}' > "${'PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=$(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PROGRESS"
                          exit 20
                        fi

                        ACTUAL_BYTES=${'(wc -c < "$APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}(wc -c < "${'APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}APK_PATH" 2>/dev/null | tr -d ' ')
                        if [ "${'ACTUAL_BYTES" != "$EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}ACTUAL_BYTES" != "${'EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=$EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}EXPECTED_BYTES" ]; then
                          echo "APK size mismatch expected=${'EXPECTED_BYTES actual=$ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}EXPECTED_BYTES actual=${'ACTUAL_BYTES" | tee -a "$LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}ACTUAL_BYTES" | tee -a "${'LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "$PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}LOG"
                          printf '{"phase":"error","percent":-1,"message":"APK copy incomplete"}' > "${'PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "$LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PROGRESS"
                          exit 21
                        fi

                        echo "[install] running pm install" | tee -a "${'LOG"
                        PM_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}LOG"
                        PM_OUT=${'(pm install -r -d "$APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}(pm install -r -d "${'APK_PATH" 2>&1)
                        INSTALL_RC=$?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}APK_PATH" 2>&1)
                        INSTALL_RC=${'?
                        echo "$PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}?
                        echo "${'PM_OUT" | tee -a "$LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PM_OUT" | tee -a "${'LOG"
                        echo "[install] done rc=$INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}LOG"
                        echo "[install] done rc=${'INSTALL_RC at $(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}INSTALL_RC at ${'(date)" | tee -a "$LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}(date)" | tee -a "${'LOG"

                        if [ "$INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}LOG"

                        if [ "${'INSTALL_RC" != "0" ] || ! echo "$PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}INSTALL_RC" != "0" ] || ! echo "${'PM_OUT" | grep -qi '^Success'; then
                          case "$PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PM_OUT" | grep -qi '^Success'; then
                          case "${'PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=$(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PM_OUT" in
                            *INSTALL_FAILED_INSUFFICIENT_STORAGE*|*"No space left on device"*|*"not enough space"*|*"insufficient storage"*)
                              PM_OUT="พื้นที่จัดเก็บในรถไม่เพียงพอสำหรับอัปเดต กรุณาเพิ่มพื้นที่แล้วลองใหม่"
                              ;;
                          esac
                          PM_ESC=${'(printf %s "$PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}(printf %s "${'PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "$PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PM_OUT" | tr -d '\000-\037' | sed 's/\\/\\\\/g;s/"/\\"/g')
                          printf '{"phase":"error","percent":-1,"message":"Install failed","error":"%s","exitCode":%s}' "${'PM_ESC" "$INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PM_ESC" "${'INSTALL_RC" > "$PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}INSTALL_RC" > "${'PROGRESS"
                          echo "INSTALL_FAILED rc=$INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PROGRESS"
                          echo "INSTALL_FAILED rc=${'INSTALL_RC: $PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}INSTALL_RC: ${'PM_OUT"
                          exit "$INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PM_OUT"
                          exit "${'INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "$PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}INSTALL_RC"
                        fi

                        printf '{"phase":"complete","percent":100,"message":"Install successful"}' > "${'PROGRESS"
                        sleep 2
                        am start -n "$TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}PROGRESS"
                        sleep 2
                        am start -n "${'TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
}TARGET_PKG/com.byd.mapsoverlay.MainActivity" --ez post_update true >/dev/null 2>&1
                        echo "INSTALL_SUCCESS"
                        exit 0
                    """.trimIndent()

                    val encodedScript = android.util.Base64.encodeToString(
                        installScript.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    val writeResp = dadb.shell("printf '%s' '$encodedScript' | base64 -d > /data/local/tmp/alpha_install.sh && chmod 755 /data/local/tmp/alpha_install.sh")
                    if (writeResp.exitCode != 0) {
                        onResult(false, "เขียนสคริปต์ติดตั้งไม่สำเร็จ: ${writeResp.output}")
                        return@use
                    }

                    val copyScript = "cat '${apkFile.absolutePath}' > '$tmpTarget' && test \\"$(wc -c < '$tmpTarget' | tr -d ' ')\\" = '$expectedBytes'"
                    val copyResp = dadb.shell(copyScript)
                    if (copyResp.exitCode != 0) {
                        onResult(false, "คัดลอก APK ไปยังรถไม่ครบ (ต้องการ $expectedBytes bytes): ${copyResp.output}")
                        return@use
                    }

                    val runResp = dadb.shell("sh /data/local/tmp/alpha_install.sh '$tmpTarget' '${appContext.packageName}' '$expectedBytes'")
                    val output = runResp.output.trim()
                    Log.i(TAG, "Reference-style installer exit=${runResp.exitCode}, output=$output")

                    if (output.contains("INSTALL_SUCCESS") || (runResp.exitCode == 0 && output.contains("Success", ignoreCase = true))) {
                        onResult(true, "ติดตั้งเวอร์ชัน Alpha สำเร็จ กำลังเริ่มระบบใหม่...")
                    } else {
                        val detail = output.ifBlank { "ไม่มีข้อความตอบกลับจากตัวติดตั้ง (exit=${runResp.exitCode})" }
                        onResult(false, "ติดตั้งไม่สำเร็จ: $detail")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Silent install failed", t)
                onResult(false, "เกิดข้อผิดพลาดในการติดตั้ง: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "AdbSilentInstaller").apply {
            isDaemon = true
            start()
        }
    }

    fun isAdbPortOpen(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ADB_HOST, ADB_PORT), 800)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun loadOrCreateAdbKeyPair(context: Context): AdbKeyPair {
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")
        return if (privateKey.exists() && publicKey.exists()) {
            AdbKeyPair.read(privateKey, publicKey)
        } else {
            AdbKeyPair.generate(privateKey, publicKey)
            AdbKeyPair.read(privateKey, publicKey)
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
}
