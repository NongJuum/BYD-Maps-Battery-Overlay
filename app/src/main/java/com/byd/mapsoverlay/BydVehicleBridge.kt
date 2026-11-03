package com.byd.mapsoverlay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier
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
 * - ทดลองอ่าน telemetry ผ่าน BYD native API; จะถือว่าอ่านสำเร็จต่อเมื่อ API คืนค่าจริงเท่านั้น:
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
    var smoothedConsumption: Double = 0.0 // Low-pass EMA smoothed consumption (kWh/100km) for stable display
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
    var detectedVehicleModel: String = "กำลังตรวจสอบ BYD native API..."
        private set
    @Volatile var diagnosticStatus: String = "ยังไม่ได้ตรวจสอบ BYD API"
        private set

    private var executor: ScheduledExecutorService? = null
    private val deviceCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun startListening() {
        applyUserVehicleProfile()

        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleWithFixedDelay({
            applyUserVehicleProfile()
            // 1. Direct native BYD hardware API reading with BydPermissionContext
            pollDirectBydHardwareApi()
            // 2. Fallback / supplementary check via telemetry daemon
            if (currentSoc <= 0.0) {
                fetchTelemetryDaemonStatus()
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    fun stopListening() {
        executor?.shutdownNow()
        executor = null
        deviceCache.clear()
    }

    /**
     * เริ่มรอบตรวจสอบ BYD native API ใหม่ทันที
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

    /**
     * ตรวจสอบ API ที่มีอยู่จริงบน firmware ก่อนเรียกใช้งาน
     * ไม่ถือว่า logcat เป็น CAN reader และไม่กลืนข้อผิดพลาดของ reflection
     */
    fun applyConsumptionUpdate(raw: Double): Boolean {
        if (raw !in 5.0..50.0) return false
        val newRaw = (raw * 10.0).roundToInt() / 10.0
        val changed = newRaw != currentConsumption
        currentConsumption = newRaw
        if (smoothedConsumption <= 0.0) {
            smoothedConsumption = currentConsumption
        } else {
            // Low-pass Exponential Moving Average (EMA):
            // alpha = 0.15 gives smooth transition over ~10-15 seconds, eliminating pedal spikes
            val filtered = (0.15 * currentConsumption) + (0.85 * smoothedConsumption)
            smoothedConsumption = (filtered * 10.0).roundToInt() / 10.0
        }
        return changed
    }

    private fun pollDirectBydHardwareApi() {
        var updated = false
        val diagnostics = mutableListOf<String>()

        val candidates = listOf(
            ApiCandidate(
                "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
                listOf("getElecPercentageValue", "getLastElecConPHMValue", "getElecDrivingRangeValue")
            ),
            ApiCandidate(
                "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
                listOf("getLast50KmPowerConsume")
            ),
            ApiCandidate(
                "android.hardware.bydauto.power.BYDAutoPowerDevice",
                listOf("getBatteryRemainPowerEV")
            )
        )

        for (candidate in candidates) {
            val clazz = try {
                Class.forName(candidate.className)
            } catch (e: Throwable) {
                diagnostics += "${candidate.className}: CLASS_NOT_FOUND (${e.javaClass.simpleName})"
                Log.w(tag, "BYD API class unavailable: ${candidate.className}", e)
                continue
            }

            val methods = (clazz.methods + clazz.declaredMethods)
                .distinctBy { methodSignature(it) }
                .sortedBy { it.name }
            val matchingMethods = methods.filter { it.name in candidate.getters }
            diagnostics += "${candidate.className}: CLASS_FOUND, getters=${matchingMethods.joinToString { methodSignature(it) }.ifBlank { "NONE" }}"

            val device = obtainDeviceInstance(clazz, diagnostics) ?: continue
            for (methodName in candidate.getters) {
                val method = matchingMethods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
                if (method == null) {
                    diagnostics += "$methodName: METHOD_NOT_FOUND_OR_UNSUPPORTED_SIGNATURE"
                    continue
                }

                try {
                    method.isAccessible = true
                    val value = method.invoke(if (Modifier.isStatic(method.modifiers)) null else device)
                    val number = value as? Number
                    if (number == null) {
                        diagnostics += "$methodName: NON_NUMERIC_RESULT (${value?.javaClass?.name ?: "null"})"
                        continue
                    }

                    val raw = number.toDouble()
                    when (methodName) {
                        "getElecPercentageValue" -> if (raw in 1.0..1000.0) {
                            val soc = if (raw > 100.0) raw / 10.0 else raw
                            if (soc in 1.0..100.0 && soc != currentSoc) {
                                currentSoc = soc
                                updated = true
                            }
                            diagnostics += "$methodName: OK=$soc"
                        } else diagnostics += "$methodName: OUT_OF_RANGE=$raw"

                        "getLast50KmPowerConsume", "getLastElecConPHMValue" -> if (raw in 5.0..40.0) {
                            if (applyConsumptionUpdate(raw)) {
                                updated = true
                            }
                            diagnostics += "$methodName: OK=$currentConsumption (smooth=$smoothedConsumption)"
                        } else diagnostics += "$methodName: OUT_OF_RANGE=$raw"

                        "getElecDrivingRangeValue" -> if (raw in 1.0..1200.0) {
                            val range = raw.toInt()
                            if (range != elecRangeKm) {
                                elecRangeKm = range
                                updated = true
                            }
                            diagnostics += "$methodName: OK=$range"
                        } else diagnostics += "$methodName: OUT_OF_RANGE=$raw"

                        "getBatteryRemainPowerEV" -> if (raw in 0.5..150.0) {
                            if (raw != remainEnergyKwh) {
                                remainEnergyKwh = raw
                                updated = true
                            }
                            diagnostics += "$methodName: OK=$raw kWh"
                        } else diagnostics += "$methodName: OUT_OF_RANGE=$raw"
                    }
                } catch (e: Throwable) {
                    val cause = e.cause ?: e
                    diagnostics += "$methodName: INVOCATION_FAILED (${cause.javaClass.simpleName}: ${cause.message})"
                    Log.e(tag, "BYD API invocation failed: $methodName", cause)
                }
            }
        }

        val permissionResults = listOf(
            "BYDAUTO_STATISTIC_GET",
            "BYDAUTO_INSTRUMENT_GET",
            "BYDAUTO_POWER_GET",
            "BYDAUTO_ENERGY_GET"
        ).map { shortName ->
            val permission = "android.permission.$shortName"
            val granted = try {
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) {
                false
            }
            "$shortName=${if (granted) "GRANTED" else "DENIED"}"
        }
        diagnostics += "permissions: ${permissionResults.joinToString()}"
        val newDiagnosticStatus = diagnostics.joinToString(" | ")
        if (newDiagnosticStatus != diagnosticStatus) {
            diagnosticStatus = newDiagnosticStatus
            Log.i(tag, "BYD native API diagnostic: $diagnosticStatus")
        }

        if (updated) {
            recalculateRealTelemetry()
            notifyUpdate()
        }
    }

    private data class ApiCandidate(
        val className: String,
        val getters: List<String>
    )

    private fun obtainDeviceInstance(clazz: Class<*>, diagnostics: MutableList<String>): Any? {
        val cached = deviceCache[clazz.name]
        if (cached != null) {
            return cached
        }

        val methods = clazz.methods + clazz.declaredMethods
        val wrappedContext = BydPermissionContext.wrap(context)

        // 1. Try getInstance(Context)
        val getInstanceWithContext = methods.firstOrNull {
            it.name == "getInstance" &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        }
        if (getInstanceWithContext != null) {
            try {
                getInstanceWithContext.isAccessible = true
                val instance = getInstanceWithContext.invoke(null, wrappedContext)
                if (instance != null) {
                    deviceCache[clazz.name] = instance
                    diagnostics += "${clazz.simpleName}: INSTANCE_OK (getInstance(Context))"
                    return instance
                }
            } catch (t: Throwable) {
                Log.d(tag, "${clazz.simpleName}.getInstance(Context) failed: ${t.message}")
            }
        }

        // 2. Try self() (DiLink 3 OEM singleton pattern used by DUDU launcher)
        val selfMethod = methods.firstOrNull {
            it.name == "self" &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0
        }
        if (selfMethod != null) {
            try {
                selfMethod.isAccessible = true
                val instance = selfMethod.invoke(null)
                if (instance != null) {
                    deviceCache[clazz.name] = instance
                    diagnostics += "${clazz.simpleName}: INSTANCE_OK (self())"
                    return instance
                }
            } catch (t: Throwable) {
                Log.d(tag, "${clazz.simpleName}.self() failed: ${t.message}")
            }
        }

        // 3. Try no-arg getInstance()
        val getInstanceNoArg = methods.firstOrNull {
            it.name == "getInstance" &&
                Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0
        }
        if (getInstanceNoArg != null) {
            try {
                getInstanceNoArg.isAccessible = true
                val instance = getInstanceNoArg.invoke(null)
                if (instance != null) {
                    deviceCache[clazz.name] = instance
                    diagnostics += "${clazz.simpleName}: INSTANCE_OK (getInstance())"
                    return instance
                }
            } catch (t: Throwable) {
                Log.d(tag, "${clazz.simpleName}.getInstance() failed: ${t.message}")
            }
        }

        diagnostics += "${clazz.simpleName}: INSTANCE_FACTORY_NOT_FOUND"
        return null
    }

    private fun methodSignature(method: Method): String =
        "${method.name}(${method.parameterTypes.joinToString { it.simpleName }}):${method.returnType.simpleName}"

    private fun fetchTelemetryDaemonStatus() {
        try {
            // Read our shell-UID telemetry daemon on loopback port 19890
            val url = URL("http://127.0.0.1:19890/status")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cache-Control", "no-cache")
            try {
                if (conn.responseCode != 200) {
                    diagnosticStatus = "Telemetry daemon HTTP ${conn.responseCode}"
                    return
                }
                val previousStatus = diagnosticStatus
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val daemonStatus = json.optString("status", "UNKNOWN")
                val daemonError = json.optString("error", "")
                diagnosticStatus = if (daemonError.isNotBlank()) {
                    "Shell telemetry daemon: $daemonStatus ($daemonError)"
                } else {
                    "Shell telemetry daemon: $daemonStatus (uid=${json.optInt("uid", -1)})"
                }
                val socObject = json.optJSONObject("soc")
                val soc = socObject?.optDouble("percent", -1.0) ?: -1.0
                if (soc in 0.0..100.0) currentSoc = soc

                val rangeObject = json.optJSONObject("range")
                val range = rangeObject?.optInt("elecRangeKm", 0) ?: 0
                if (range > 0) elecRangeKm = range

                val remain = json.optDouble("remainEnergyKwh", -1.0)
                if (remain in 0.1..150.0) remainEnergyKwh = remain

                val consumptionObject = json.optJSONObject("consumption")
                val consumption = consumptionObject?.optDouble("kwhPer100km", -1.0) ?: -1.0
                if (consumption in 1.0..60.0) {
                    applyConsumptionUpdate(consumption)
                }

                val hasVehicleData = soc >= 0.0 || range > 0 || remain > 0.0 || consumption > 0.0
                if (hasVehicleData) {
                    recalculateRealTelemetry()
                    notifyUpdate()
                } else if (diagnosticStatus != previousStatus) {
                    notifyUpdate()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Only update status to daemon error if we do not already have live vehicle data
            if (currentSoc <= 0.0) {
                val newStatus = "Telemetry daemon: ${e.javaClass.simpleName} (${e.message})"
                val changed = diagnosticStatus != newStatus
                diagnosticStatus = newStatus
                Log.w(tag, newStatus)
                if (changed) notifyUpdate()
            }
        }
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

        val effectiveConsumption = if (smoothedConsumption > 0.0) smoothedConsumption else currentConsumption
        if (currentSoc <= 0.0 || effectiveConsumption <= 0.0) return

        // คำนวณพลังงานที่ใช้ได้จริง ณ ปัจจุบัน (Usable Energy kWh)
        // ถ้าเป็นโหมดพื้นฐาน (ดึงจาก OBD) จะใช้ค่า remainEnergyKwh สดจาก BMS รถเป็นหลัก
        val usableEnergyKwh = if (userNominalKwh <= 0.0 && remainEnergyKwh > 0.5) {
            remainEnergyKwh
        } else if (estimatedCapacityKwh > 10.0) {
            (currentSoc / 100.0) * estimatedCapacityKwh
        } else {
            0.0
        }

        // คำนวณระยะทางวิ่งได้จริงคงเหลือ (Real Remaining Range) จาก Usable Energy + Smoothed Consumption
        if (usableEnergyKwh > 0.0 && effectiveConsumption > 0.0) {
            realRangeKm = ((usableEnergyKwh / effectiveConsumption) * 100.0 * 10.0).roundToInt() / 10.0
        }

        BydAppWidgetProvider.lastSoc = currentSoc
        BydAppWidgetProvider.lastConsumption = effectiveConsumption
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
            onTelemetryUpdate(currentSoc, if (smoothedConsumption > 0.0) smoothedConsumption else currentConsumption)
        }
    }
}
