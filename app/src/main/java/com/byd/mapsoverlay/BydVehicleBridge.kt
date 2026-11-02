package com.byd.mapsoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * BydVehicleBridge:
 * - รุ่นรถและขนาดความจุแบตเตอรี่ตั้งต้น: อ่านจาก VehicleProfileManager ที่ผู้ใช้เลือกรุ่นรถหรือกรอกเองเป็นหลัก
 *   (ป้องกันปัญหาบางรุ่นดึงขนาดแบตผิดพลาด) และหาก BMS ส่งค่า kWh จริงมา ก็ใช้ประกอบได้
 * - ข้อมูลการขับขี่สด (Live Telemetry): ดึงจาก OBD / CAN Bus ของรถโดยตรง 100%:
 *   1. % แบตเตอรี่ปัจจุบัน (SoC) -> BYDAutoStatisticDevice.getElecPercentageValue
 *   2. อัตราการกินไฟเฉลี่ย 50 กม. ล่าสุด (Past 50 km) -> BYDAutoInstrumentDevice.getLast50KmPowerConsume / getLastElecConPHMValue
 *   3. พลังงานคงเหลือจริง (kWh) และระยะทางหน้าปัด -> BYDAutoPowerDevice.getBatteryRemainPowerEV / getElecDrivingRangeValue
 */
class BydVehicleBridge(
    private val context: Context,
    private val onTelemetryUpdate: (soc: Double, consumptionKwh: Double) -> Unit
) {
    private val tag = "BydVehicleBridge"
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentSoc: Double = 0.0
        private set
    var currentConsumption: Double = 0.0 // Live CAN bus 50km (kWh/100km)
        private set
    var remainEnergyKwh: Double = 0.0 // พลังงานแบตที่เหลืออยู่จริง (kWh)
        private set
    var elecRangeKm: Int = 0
        private set
    var sohPercent: Int = 100
        private set
    var nominalCapacityKwh: Double = 0.0
        private set
    var estimatedCapacityKwh: Double = 0.0
        private set
    var realRangeKm: Double = 0.0
        private set
    var detectedVehicleModel: String = "กำลังดึงข้อมูลรุ่นรถและแบตเตอรี่จาก OBD / BMS..."
        private set

    private var executor: ScheduledExecutorService? = null
    private var isStreamingCan = false
    private var canLogcatProcess: Process? = null

    fun startListening() {
        applyUserVehicleProfile()
        startContinuousCanStream()

        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleWithFixedDelay({
            applyUserVehicleProfile()
            pollDirectBydHardwareApi()
            fetchOptionalOverDriveStatus()
        }, 0, 2, TimeUnit.SECONDS)
    }

    fun stopListening() {
        isStreamingCan = false
        canLogcatProcess?.destroy()
        canLogcatProcess = null
        executor?.shutdownNow()
        executor = null
    }

    /**
     * รีสตาร์ทการเชื่อมต่อท่อข้อมูล OBD / CAN Bus ใหม่ทันที (ใช้เมื่อกดปุ่มขอสิทธิ์/ซ่อมสิทธิ์ในแอป)
     */
    fun restartConnection() {
        stopListening()
        startListening()
    }

    /**
     * โหลดข้อมูลรุ่นรถและขนาดแบตเตอรี่ที่ผู้ใช้ตั้งค่าไว้เป็นค่าหลักเบื้องต้น
     */
    fun applyUserVehicleProfile() {
        val (userModelName, userNominalKwh, userSoh) = VehicleProfileManager.getConfiguredVehicleSpec(context)
        if (userNominalKwh > 0.0) {
            detectedVehicleModel = userModelName
            nominalCapacityKwh = userNominalKwh
            sohPercent = userSoh
            estimatedCapacityKwh = ((nominalCapacityKwh * (sohPercent / 100.0)) * 10.0).roundToInt() / 10.0
            EnergyCalculator.updateActualCapacity(estimatedCapacityKwh)
            recalculateRealTelemetry()
        }
    }

    @SuppressLint("PrivateApi")
    private fun pollDirectBydHardwareApi() {
        var updated = false

        // 1. BYDAutoStatisticDevice (SoC %, Last 50km Consumption, Driving Range)
        try {
            val statClass = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice")
            val getInstance = statClass.getMethod("getInstance", Context::class.java)
            val statDevice = getInstance.invoke(null, context)

            if (statDevice != null) {
                runCatching {
                    val socVal = (statClass.getMethod("getElecPercentageValue").invoke(statDevice) as? Number)?.toDouble()
                    if (socVal != null && socVal in 1.0..1000.0) {
                        val normSoc = if (socVal > 100.0) socVal / 10.0 else socVal
                        if (normSoc != currentSoc) {
                            currentSoc = normSoc
                            updated = true
                        }
                    }
                }

                runCatching {
                    val conVal = (statClass.getMethod("getLastElecConPHMValue").invoke(statDevice) as? Number)?.toDouble()
                    if (conVal != null && conVal in 5.0..40.0) {
                        val rounded = (conVal * 10.0).roundToInt() / 10.0
                        if (rounded != currentConsumption) {
                            currentConsumption = rounded
                            updated = true
                        }
                    }
                }

                runCatching {
                    val rangeVal = (statClass.getMethod("getElecDrivingRangeValue").invoke(statDevice) as? Number)?.toInt()
                    if (rangeVal != null && rangeVal in 1..1200 && rangeVal != elecRangeKm) {
                        elecRangeKm = rangeVal
                        updated = true
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. BYDAutoInstrumentDevice (getLast50KmPowerConsume)
        try {
            val instClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
            val getInstance = instClass.getMethod("getInstance", Context::class.java)
            val instDevice = getInstance.invoke(null, context)

            if (instDevice != null) {
                runCatching {
                    val con50 = (instClass.getMethod("getLast50KmPowerConsume").invoke(instDevice) as? Number)?.toDouble()
                    if (con50 != null && con50 in 5.0..40.0) {
                        val rounded = (con50 * 10.0).roundToInt() / 10.0
                        if (rounded != currentConsumption) {
                            currentConsumption = rounded
                            updated = true
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. BYDAutoPowerDevice (getBatteryRemainPowerEV)
        try {
            val powerClass = Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice")
            val getInstance = powerClass.getMethod("getInstance", Context::class.java)
            val powerDevice = getInstance.invoke(null, context)

            if (powerDevice != null) {
                runCatching {
                    val remainKwh = (powerClass.getMethod("getBatteryRemainPowerEV").invoke(powerDevice) as? Number)?.toDouble()
                    if (remainKwh != null && remainKwh in 0.5..150.0 && remainKwh != remainEnergyKwh) {
                        remainEnergyKwh = remainKwh
                        updated = true
                    }
                }
            }
        } catch (_: Throwable) {}

        if (updated) {
            recalculateRealTelemetry()
            notifyUpdate()
        }
    }

    private fun startContinuousCanStream() {
        if (isStreamingCan) return
        isStreamingCan = true

        Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "logcat", "-v", "raw", "-s",
                    "BYDAutoInstrumentDevice:D",
                    "BYDAutoStatisticDevice:D",
                    "BYDAutoPowerDevice:D",
                    "BYDAutoEnergyDevice:D"
                ))
                canLogcatProcess = process
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                while (isStreamingCan) {
                    val line = reader.readLine() ?: break
                    var updated = false

                    // 1. อัตรากินไฟเฉลี่ย 50 กม. ล่าสุด (Past 50 km) จาก OBD/CAN
                    if (line.contains("getLast50KmPowerConsume:") || line.contains("getLastElecConPHMValue")) {
                        val parsedVal = when {
                            line.contains("getLast50KmPowerConsume:") ->
                                line.substringAfter("getLast50KmPowerConsume:").trim().split(" ").firstOrNull()?.toDoubleOrNull()
                            line.contains("getLastElecConPHMValue") && line.contains("value is:") ->
                                line.substringAfter("value is:").trim().split(" ").firstOrNull()?.toDoubleOrNull()
                            else -> null
                        }

                        if (parsedVal != null && parsedVal in 5.0..40.0) {
                            val rounded = (parsedVal * 10.0).roundToInt() / 10.0
                            if (rounded != currentConsumption) {
                                currentConsumption = rounded
                                Log.d(tag, "OBD/CAN 50km Consumption: $currentConsumption kWh/100km")
                                updated = true
                            }
                        }
                    }

                    // 2. เปอร์เซ็นต์แบตเตอรี่ปัจจุบัน (SoC %) จาก OBD/CAN
                    if (line.contains("getElecPercentageValue") && line.contains("value is:")) {
                        val socVal = line.substringAfter("value is:").trim().split(" ").firstOrNull()?.toDoubleOrNull()
                        if (socVal != null && socVal in 1.0..1000.0) {
                            val normalizedSoc = if (socVal > 100.0) socVal / 10.0 else socVal
                            if (normalizedSoc != currentSoc) {
                                currentSoc = normalizedSoc
                                Log.d(tag, "OBD/CAN SoC: $currentSoc%")
                                updated = true
                            }
                        }
                    }

                    // 3. พลังงานคงเหลือจริงในแบตเตอรี่เป็น kWh จาก BMS
                    if (line.contains("getBatteryRemainPowerEV") && line.contains("value is")) {
                        val kwhVal = line.substringAfter("value is").replace(":", "").trim().split(" ").firstOrNull()?.toDoubleOrNull()
                        if (kwhVal != null && kwhVal in 0.5..150.0 && kwhVal != remainEnergyKwh) {
                            remainEnergyKwh = kwhVal
                            Log.d(tag, "OBD/CAN Remaining Battery Energy: $remainEnergyKwh kWh")
                            updated = true
                        }
                    }

                    // 4. ระยะทางวิ่งคงเหลือบนหน้าปัด (Dashboard Range)
                    if (line.contains("getElecDrivingRangeValue") && line.contains("value is:")) {
                        val rangeVal = line.substringAfter("value is:").trim().split(" ").firstOrNull()?.toIntOrNull()
                        if (rangeVal != null && rangeVal in 1..1200 && rangeVal != elecRangeKm) {
                            elecRangeKm = rangeVal
                            updated = true
                        }
                    }

                    if (updated) {
                        recalculateRealTelemetry()
                        notifyUpdate()
                    }
                }
                reader.close()
            } catch (e: Exception) {
                Log.e(tag, "CAN stream error: ${e.message}")
            }
        }.start()
    }

    private fun fetchOptionalOverDriveStatus() {
        try {
            val url = URL("http://127.0.0.1:8080/status")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 600
            conn.readTimeout = 600
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val json = JSONObject(reader.readText())
                reader.close()

                if (currentSoc <= 0.0 && json.has("soc")) {
                    currentSoc = json.getJSONObject("soc").optDouble("percent", currentSoc)
                }

                if (elecRangeKm <= 0 && json.has("range")) {
                    elecRangeKm = json.getJSONObject("range").optInt("elecRangeKm", elecRangeKm)
                }

                recalculateRealTelemetry()
                notifyUpdate()
            }
        } catch (_: Exception) {}
    }

    private fun recalculateRealTelemetry() {
        val (userModelName, userNominalKwh, userSoh) = VehicleProfileManager.getConfiguredVehicleSpec(context)

        // พื้นฐานเบื้องต้น: ดึงและคำนวณจาก OBD / BMS ของรถโดยตรงก่อนเสมอ (PRESET_AUTO)
        if (userNominalKwh <= 0.0) {
            if (remainEnergyKwh > 0.5 && currentSoc >= 5.0) {
                // คำนวณความจุเต็ม 100% จริงจาก BMS สดๆ: remainEnergyKwh / (currentSoc / 100)
                val derivedFullCapacity = remainEnergyKwh / (currentSoc / 100.0)
                if (derivedFullCapacity in 15.0..150.0) {
                    estimatedCapacityKwh = (derivedFullCapacity * 10.0).roundToInt() / 10.0
                    EnergyCalculator.updateActualCapacity(estimatedCapacityKwh)
                    val (specKwh, modelName) = detectStandardBydBladeModel(estimatedCapacityKwh)
                    nominalCapacityKwh = specKwh
                    detectedVehicleModel = "$modelName [ซิงค์จาก OBD]"
                    sohPercent = ((estimatedCapacityKwh / nominalCapacityKwh) * 100.0).roundToInt().coerceIn(50, 100)
                }
            } else if (elecRangeKm > 0 && currentConsumption > 0.0 && currentSoc >= 5.0 && estimatedCapacityKwh <= 0.0) {
                // กรณี OBD ยังไม่ได้ส่ง remainEnergyKwh มาในวินาทีแรก ให้ประเมินจากระยะทางหน้าปัด + อัตรากินไฟ OBD
                val estRemainKwh = (elecRangeKm * currentConsumption) / 100.0
                val derivedFullCapacity = estRemainKwh / (currentSoc / 100.0)
                if (derivedFullCapacity in 15.0..150.0) {
                    estimatedCapacityKwh = (derivedFullCapacity * 10.0).roundToInt() / 10.0
                    EnergyCalculator.updateActualCapacity(estimatedCapacityKwh)
                    val (specKwh, modelName) = detectStandardBydBladeModel(estimatedCapacityKwh)
                    nominalCapacityKwh = specKwh
                    detectedVehicleModel = "$modelName [ซิงค์จาก OBD]"
                    sohPercent = ((estimatedCapacityKwh / nominalCapacityKwh) * 100.0).roundToInt().coerceIn(50, 100)
                }
            }
        } else {
            // กรณีที่ผู้ใช้ต้องการ Override (เมื่อรถบางคันดึงค่าขนาดแบตจาก OBD ผิดพลาด จึงเลือกใส่ข้อมูลรถด้วยตัวเอง)
            detectedVehicleModel = "$userModelName [กำหนดเอง]"
            nominalCapacityKwh = userNominalKwh
            sohPercent = userSoh
            estimatedCapacityKwh = ((nominalCapacityKwh * (sohPercent / 100.0)) * 10.0).roundToInt() / 10.0
            EnergyCalculator.updateActualCapacity(estimatedCapacityKwh)
        }

        if (currentSoc <= 0.0 || currentConsumption <= 0.0) return

        // คำนวณพลังงานที่ใช้ได้จริง ณ ปัจจุบัน (Usable Energy kWh)
        // ถ้าเป็นโหมดพื้นฐาน (ดึงจาก OBD) จะใช้ค่า remainEnergyKwh สดจาก BMS รถเป็นหลัก
        val usableEnergyKwh = if (userNominalKwh <= 0.0 && remainEnergyKwh > 0.5) {
            remainEnergyKwh
        } else if (estimatedCapacityKwh > 10.0) {
            (currentSoc / 100.0) * estimatedCapacityKwh
        } else {
            0.0
        }

        // คำนวณระยะทางวิ่งได้จริงคงเหลือ (Real Remaining Range) จาก OBD SoC + OBD Consumption
        if (usableEnergyKwh > 0.0 && currentConsumption > 0.0) {
            realRangeKm = ((usableEnergyKwh / currentConsumption) * 100.0 * 10.0).roundToInt() / 10.0
        }

        BydAppWidgetProvider.lastSoc = currentSoc
        BydAppWidgetProvider.lastConsumption = currentConsumption
        BydAppWidgetProvider.lastRealRange = realRangeKm
        BydAppWidgetProvider.lastSoh = sohPercent
    }

    private fun detectStandardBydBladeModel(measuredFullKwh: Double): Pair<Double, String> {
        val standardSpecs = listOf(
            30.08 to "BYD Seagull / Dolphin Mini (30.1 kWh)",
            38.88 to "BYD Seagull / Dolphin Mini Extended (38.9 kWh)",
            44.9 to "BYD Dolphin Standard / Atto 3 Standard (44.9 kWh)",
            50.25 to "BYD Dolphin Standard / Atto 3 (Blade 50.25 kWh)",
            60.48 to "BYD Dolphin Extended / Atto 3 Extended (60.48 kWh)",
            61.44 to "BYD Seal Dynamic (61.4 kWh)",
            71.8 to "BYD M6 Extended / Sealion 6 / Song Plus (71.8 kWh)",
            82.56 to "BYD Seal Premium / Performance / Sealion 7 (82.5 kWh)",
            85.44 to "BYD Han EV (85.4 kWh)",
            91.3 to "BYD Sealion 7 AWD (91.3 kWh)",
            108.8 to "BYD Tang EV (108.8 kWh)"
        )
        return standardSpecs.filter { it.first >= measuredFullKwh * 0.96 }
            .minByOrNull { kotlin.math.abs(it.first - measuredFullKwh) }
            ?: (measuredFullKwh to String.format("BYD EV (Blade Battery ~%.1f kWh)", measuredFullKwh))
    }

    fun notifyUpdate() {
        mainHandler.post {
            BydAppWidgetProvider.updateAllWidgets(context)
            onTelemetryUpdate(currentSoc, currentConsumption)
        }
    }
}
