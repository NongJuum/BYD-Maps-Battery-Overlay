package com.byd.mapsoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * BydVehicleBridge V2.2 (Pure Telemetry Bridge)
 * - Auto-starts BydTelemetryDaemon (UID 2000) via AutoPermissionHelper
 * - Reads HV Battery Temp (Max/Min/Avg °C), Cell Voltage (Max/Min V), 12V Aux,
 *   SoC %, Remain kWh, Dashboard Range, Real Range, 50km Avg Consumption, Instant Power,
 *   Cabin Temp, Outside Temp, Speed, Gear, Odometer, and TPMS 4-wheel pressures.
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
    var smoothedConsumption: Double = 0.0 // Low-pass EMA smoothed
        private set
    var remainEnergyKwh: Double = 0.0 // พลังงานแบตเตอรี่คงเหลือจริง (kWh)
        private set
    var elecRangeKm: Int = 0 // ระยะทางบนหน้าปัดรถ (km)
        private set
    var sohPercent: Int = 0 // สุขภาพแบตเตอรี่จริงจาก CAN bus (0 = ยังไม่มีข้อมูลสด)
        private set
    var nominalCapacityKwh: Double = 0.0
        private set
    var estimatedCapacityKwh: Double = 0.0
        private set
    var realRangeKm: Double = 0.0
        private set

    // HV Main Battery Temperature & Cell Voltage (Initializes to 0.0 until live CAN bus data arrives)
    var hvBattTempAvgC: Double = 0.0
        private set
    var hvBattTempMaxC: Double = 0.0
        private set
    var hvBattTempMinC: Double = 0.0
        private set
    var cellVoltMaxV: Double = 0.0
        private set
    var cellVoltMinV: Double = 0.0
        private set

    // Temperatures, 12V, Driving, TPMS (Initializes to waiting state until live CAN bus data arrives)
    var cabinTemperatureC: Int = 0 // อุณหภูมิจริงในห้องโดยสาร (Real Indoor Sensor °C)
        private set
    var acSetpointTemperatureC: Int = 0 // อุณหภูมิแอร์ที่ตั้งไว้ (AC Setpoint °C)
        private set
    var outsideTemperatureC: Int = 0
        private set
    var aux12vVoltage: Double = 0.0
        private set
    var currentGear: String = "--"
        private set
    var vehicleSpeedKmH: Int = 0
        private set
    var instantPowerKw: Double = 0.0
        private set
    var totalMileageKm: Double = 0.0
        private set
    var tpmsFlKpa: Int = 0
        private set
    var tpmsFrKpa: Int = 0
        private set
    var tpmsRlKpa: Int = 0
        private set
    var tpmsRrKpa: Int = 0
        private set
    var isDashcamRecording: Boolean = false
        private set
    var tpmsSummary: String = "-- kPa"
        private set

    fun getFormattedTpms(unit: String): String {
        val isPsi = unit.lowercase() == "psi"
        fun fmt(kpa: Int): String {
            if (kpa <= 0) return "--"
            return if (isPsi) "${(kpa * 0.1450377).roundToInt()}" else "$kpa"
        }
        return "↖${fmt(tpmsFlKpa)} ↗${fmt(tpmsFrKpa)} | ↙${fmt(tpmsRlKpa)} ↘${fmt(tpmsRrKpa)}"
    }

    fun getDisplayAcTemp(): Int {
        return if (acSetpointTemperatureC in 14..35) {
            acSetpointTemperatureC
        } else if (cabinTemperatureC in 10..50) {
            cabinTemperatureC
        } else {
            24
        }
    }

    fun getFormattedHvBattTemp(mode: String): String {
        if (hvBattTempMaxC <= 0.0 && hvBattTempAvgC <= 0.0) return "--°C"
        return when (mode.lowercase()) {
            "max" -> String.format("%.0f°C", hvBattTempMaxC)
            "min" -> String.format("%.0f°C", hvBattTempMinC)
            "avg" -> String.format("%.0f°C", hvBattTempAvgC)
            else -> if (hvBattTempMaxC <= 0.0) "--/--°C" else String.format("%.0f/%.0f°C", hvBattTempMaxC, hvBattTempMinC)
        }
    }

    var detectedVehicleModel: String = "BYD Dolphin (Live Connected)"
        private set
    @Volatile var diagnosticStatus: String = "กำลังเชื่อมต่อ BYD Daemon..."
        private set
    @Volatile var isDaemonConnected: Boolean = false
        private set

    private var executor: ScheduledExecutorService? = null
    private val deviceCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Any, Long>>()
    private val cacheTtlMs = 60_000L

    fun startListening() {
        HiddenApiBypass.exemptAll()
        AutoPermissionHelper.ensureTelemetryDaemon(context)
        applyUserVehicleProfile()

        executor?.shutdownNow()
        executor = Executors.newSingleThreadScheduledExecutor()
        var tickCount = 0
        executor?.scheduleWithFixedDelay({
            try {
                tickCount++
                if (tickCount % 5 == 1) {
                    applyUserVehicleProfile()
                    if (!isDaemonConnected) {
                        AutoPermissionHelper.ensureTelemetryDaemon(context)
                    }
                    pollDirectBydHardwareApi()
                    pollCabinTemperature()
                }
                fetchTelemetryDaemonStatus()
            } catch (t: Throwable) {
                Log.w(tag, "Telemetry cycle catch: ${t.message}")
            }
        }, 0, 1000, TimeUnit.MILLISECONDS)
    }

    fun stopListening() {
        executor?.shutdownNow()
        executor = null
        deviceCache.clear()
    }

    fun applyUserVehicleProfile() {
        try {
            val (userModelName, userNominalKwh, userSoh) = VehicleProfileManager.getConfiguredVehicleSpec(context)
            if (userNominalKwh > 0.0) {
                detectedVehicleModel = userModelName
                nominalCapacityKwh = userNominalKwh
                sohPercent = userSoh
                estimatedCapacityKwh = ((nominalCapacityKwh * (sohPercent / 100.0)) * 10.0).roundToInt() / 10.0
                EnergyCalculator.updateActualCapacity(estimatedCapacityKwh)
                recalculateRealTelemetry()
            }
        } catch (t: Throwable) {
            Log.w(tag, "applyUserVehicleProfile fallback: ${t.message}")
        }
    }

    fun applyConsumptionUpdate(raw: Double): Boolean {
        if (raw !in 5.0..50.0) return false
        val newRaw = (raw * 10.0).roundToInt() / 10.0
        val changed = newRaw != currentConsumption
        currentConsumption = newRaw
        if (smoothedConsumption <= 0.0) {
            smoothedConsumption = kotlin.math.ceil(currentConsumption * 2.0) / 2.0
        } else {
            val filtered = (0.15 * currentConsumption) + (0.85 * smoothedConsumption)
            smoothedConsumption = kotlin.math.ceil(filtered * 2.0) / 2.0
        }
        return changed
    }

    private fun pollDirectBydHardwareApi() {
        var updated = false
        val candidates = listOf(
            ApiCandidate("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", listOf("getElecPercentageValue", "getLastElecConPHMValue", "getElecDrivingRangeValue", "getTotalMileageValue", "getInstantElecConValue")),
            ApiCandidate("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", listOf("getLast50KmPowerConsume", "getOutCarTemperature")),
            ApiCandidate("android.hardware.bydauto.power.BYDAutoPowerDevice", listOf("getBatteryRemainPowerEV", "getRemainingBatteryPower")),
            ApiCandidate("android.hardware.bydauto.speed.BYDAutoSpeedDevice", listOf("getCurrentSpeed")),
            ApiCandidate("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", listOf("getGearboxAutoModeType"))
        )

        for (candidate in candidates) {
            val clazz = try { Class.forName(candidate.className) } catch (_: Throwable) { continue }
            val device = obtainDeviceInstance(clazz) ?: continue
            for (methodName in candidate.getters) {
                try {
                    val method = clazz.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
                    method.isAccessible = true
                    val value = method.invoke(device)
                    val raw = (value as? Number)?.toDouble() ?: continue

                    when (methodName) {
                        "getElecPercentageValue" -> if (raw in 0.0..1000.0) {
                            val soc = if (raw > 100.0) raw / 10.0 else raw
                            if (soc in 0.0..100.0 && soc != currentSoc) {
                                currentSoc = soc
                                updated = true
                            }
                        }
                        "getLast50KmPowerConsume", "getLastElecConPHMValue" -> if (raw in 5.0..40.0) {
                            if (applyConsumptionUpdate(raw)) updated = true
                        }
                        "getElecDrivingRangeValue" -> if (raw in 1.0..1200.0) {
                            val range = raw.toInt()
                            if (range != elecRangeKm) {
                                elecRangeKm = range
                                updated = true
                            }
                        }
                        "getBatteryRemainPowerEV", "getRemainingBatteryPower" -> if (raw in 0.1..200.0) {
                            if (raw != remainEnergyKwh) {
                                remainEnergyKwh = raw
                                updated = true
                            }
                        }
                        "getOutCarTemperature" -> if (raw in -30.0..65.0) {
                            outsideTemperatureC = raw.toInt()
                            updated = true
                        }
                        "getCurrentSpeed" -> if (raw in 0.0..250.0) {
                            vehicleSpeedKmH = raw.toInt()
                            updated = true
                        }
                        "getGearboxAutoModeType" -> {
                            currentGear = mapGearCode(raw.toInt())
                            updated = true
                        }
                        "getTotalMileageValue" -> if (raw > 0.0) {
                            totalMileageKm = raw
                            updated = true
                        }
                        "getInstantElecConValue" -> if (raw in -100.0..250.0) {
                            instantPowerKw = raw
                            updated = true
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        if (updated) {
            recalculateRealTelemetry()
            notifyUpdate()
        }
    }

    private fun pollCabinTemperature() {
        // 1. Try real indoor temperature sensor first (BYDAutoSensorDevice.getTemperatureSensorValue)
        try {
            val sensorCls = Class.forName("android.hardware.bydauto.sensor.BYDAutoSensorDevice")
            val sensorDev = obtainDeviceInstance(sensorCls)
            if (sensorDev != null) {
                val m = sensorCls.getMethod("getTemperatureSensorValue")
                m.isAccessible = true
                val realT = (m.invoke(sensorDev) as? Number)?.toDouble()
                if (realT != null && realT in 1.0..70.0) {
                    cabinTemperatureC = realT.roundToInt()
                }
            }
        } catch (_: Throwable) {}

        // 2. Also read AC setpoint temperature and indoor cabin temperature
        try {
            val acCls = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice")
            val wrappedCtx = BydPermissionContext.wrap(context)
            var dev = obtainDeviceInstance(acCls)
            if (dev == null) {
                try {
                    val ctor = acCls.getDeclaredConstructor(Context::class.java)
                    ctor.isAccessible = true
                    dev = ctor.newInstance(wrappedCtx)
                } catch (_: Throwable) {}
            }
            if (dev != null) {
                val method = acCls.getMethod("getTemprature", Int::class.javaPrimitiveType)
                method.isAccessible = true
                val tSet = (method.invoke(dev, 1) as? Number)?.toInt()
                if (tSet != null && tSet in 15..35) {
                    acSetpointTemperatureC = tSet
                }
                val tCabin = (method.invoke(dev, 3) as? Number)?.toInt()
                if (tCabin != null && tCabin in 10..55) {
                    cabinTemperatureC = tCabin
                } else if (cabinTemperatureC == null && tSet != null) {
                    cabinTemperatureC = tSet
                }
            }
        } catch (_: Throwable) {}
    }

    private data class ApiCandidate(val className: String, val getters: List<String>)

    private fun obtainDeviceInstance(clazz: Class<*>): Any? {
        val cached = deviceCache[clazz.name]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.second) < cacheTtlMs) return cached.first

        val wrappedContext = BydPermissionContext.wrap(context)
        try {
            val m = clazz.getMethod("getInstance", Context::class.java)
            val inst = m.invoke(null, wrappedContext)
            if (inst != null) {
                deviceCache[clazz.name] = Pair(inst, now)
                return inst
            }
        } catch (_: Throwable) {}

        try {
            val m = clazz.getMethod("self")
            val inst = m.invoke(null)
            if (inst != null) {
                deviceCache[clazz.name] = Pair(inst, now)
                return inst
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun fetchTelemetryDaemonStatus() {
        try {
            val url = URL("http://127.0.0.1:19890/telemetry")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cache-Control", "no-cache")
            try {
                if (conn.responseCode != 200) {
                    isDaemonConnected = false
                    return
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                isDaemonConnected = true
                diagnosticStatus = "เชื่อมต่อสำเร็จ (UID ${json.optInt("uid", 2000)})"

                val socObj = json.optJSONObject("soc")
                val soc = socObj?.optDouble("percent", -1.0) ?: -1.0
                val rangeObj = json.optJSONObject("range")
                val range = rangeObj?.optInt("elecRangeKm", 0) ?: 0
                val remain = json.optDouble("remainEnergyKwh", -1.0)
                val consumptionObj = json.optJSONObject("consumption")
                val consumption = consumptionObj?.optDouble("kwhPer100km", -1.0) ?: -1.0

                // HV Battery Temp & Cell Voltage & 12V from BMS/Daemon
                val hvAvg = json.optDouble("hvBattTempAvgC", -100.0)
                val hvMax = json.optDouble("hvBattTempMaxC", -100.0)
                val hvMin = json.optDouble("hvBattTempMinC", -100.0)
                val cMax = json.optDouble("cellVoltMaxV", -1.0)
                val cMin = json.optDouble("cellVoltMinV", -1.0)
                val aux12 = json.optDouble("aux12v", -1.0)

                val cabinT = json.optInt("cabinTempC", -100)
                val realCabinT = json.optDouble("realCabinSensorTempC", -100.0)
                val acSetT = json.optInt("acSetpointTempC", -100)
                val outT = json.optInt("outsideTempC", -100)
                val spd = json.optInt("speedKmh", -1)
                val gearC = json.optInt("gearCode", -1)
                val odo = json.optDouble("totalMileageKm", -1.0)
                val instKw = json.optDouble("instantElecConKw", -999.0)

                var changed = false
                if (soc in 0.0..100.0 && soc != currentSoc) {
                    currentSoc = soc
                    changed = true
                }
                if (range in 1..1500 && range != elecRangeKm) {
                    elecRangeKm = range
                    changed = true
                }
                if (remain in 0.1..150.0 && remain != remainEnergyKwh) {
                    remainEnergyKwh = remain
                    changed = true
                }
                if (consumption in 1.0..60.0) {
                    if (applyConsumptionUpdate(consumption)) changed = true
                }
                if (hvAvg > -40.0) {
                    hvBattTempAvgC = hvAvg
                    if (hvMax > -40.0) hvBattTempMaxC = hvMax
                    if (hvMin > -40.0) hvBattTempMinC = hvMin
                    changed = true
                }
                if (cMax in 2.0..4.5) {
                    cellVoltMaxV = cMax
                    if (cMin in 2.0..4.5) cellVoltMinV = cMin
                    changed = true
                }
                if (aux12 in 8.0..18.0) {
                    aux12vVoltage = (aux12 * 10.0).roundToInt() / 10.0
                    changed = true
                }
                if (realCabinT in 1.0..70.0) {
                    cabinTemperatureC = realCabinT.roundToInt()
                    changed = true
                } else if (cabinT in 1..70) {
                    cabinTemperatureC = cabinT
                    changed = true
                }
                if (acSetT in 14..45) {
                    acSetpointTemperatureC = acSetT
                    changed = true
                }
                if (outT in -30..65) {
                    outsideTemperatureC = outT
                    changed = true
                }
                if (spd in 0..260) {
                    vehicleSpeedKmH = spd
                    changed = true
                }
                if (gearC >= 0) {
                    currentGear = mapGearCode(gearC)
                    changed = true
                }
                if (odo > 0.0) {
                    totalMileageKm = odo
                    changed = true
                }
                if (instKw > -200.0) {
                    instantPowerKw = instKw
                    changed = true
                }
                val rec = json.optBoolean("isDashcamRecording", false)
                if (rec != isDashcamRecording) {
                    isDashcamRecording = rec
                    changed = true
                }

                val tpmsObj = json.optJSONObject("tpms")
                if (tpmsObj != null) {
                    val fl = tpmsObj.optInt("fl", 0)
                    val fr = tpmsObj.optInt("fr", 0)
                    val rl = tpmsObj.optInt("rl", 0)
                    val rr = tpmsObj.optInt("rr", 0)
                    if (fl > 0 || fr > 0 || rl > 0 || rr > 0) {
                        if (fl > 0) tpmsFlKpa = fl
                        if (fr > 0) tpmsFrKpa = fr
                        if (rl > 0) tpmsRlKpa = rl
                        if (rr > 0) tpmsRrKpa = rr
                        tpmsSummary = getFormattedTpms(VehicleOverlayConfig.getTpmsUnit(context))
                        changed = true
                    }
                }

                if (changed) {
                    recalculateRealTelemetry()
                    notifyUpdate()
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            isDaemonConnected = false
        }
    }

    private fun mapGearCode(code: Int): String {
        return when (code) {
            1 -> "P"
            2 -> "R"
            3 -> "N"
            4 -> "D"
            else -> "P"
        }
    }

    private fun recalculateRealTelemetry() {
        try {
            // Strictly real data: if remainEnergyKwh and currentConsumption are available from CAN bus, calculate.
            // Otherwise use vehicle's real cluster electric range directly without any fabricated constants.
            if (remainEnergyKwh > 0.0 && currentConsumption > 0.0) {
                realRangeKm = ((remainEnergyKwh / currentConsumption) * 100.0 * 10.0).roundToInt() / 10.0
            } else if (elecRangeKm > 0) {
                realRangeKm = elecRangeKm.toDouble()
            }
        } catch (_: Throwable) {}
    }

    private fun notifyUpdate() {
        mainHandler.post {
            onTelemetryUpdate(currentSoc, currentConsumption)
        }
    }
}
