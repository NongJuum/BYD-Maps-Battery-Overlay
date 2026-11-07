package com.byd.mapsoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * BydVehicleBridge V2.0 (Zero-Hole Architecture)
 * - Decoupled from Google Maps
 * - Safe sensor polling with Null-safety & Exception containment
 * - Cabin Temperature reading via BYDAutoAcDevice (with DiLink permission wrapper)
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
    var cabinTemperatureC: Int = 25 // อุณหภูมิในห้องโดยสาร (°C)
        private set
    var aux12vVoltage: Double = 13.8 // แรงดันแบตเตอรี่ลูกเล็ก 12V
        private set
    var currentGear: String = "D"
        private set
    var vehicleSpeedKmH: Int = 0
        private set

    var detectedVehicleModel: String = "BYD EV (Live Connected)"
        private set
    @Volatile var diagnosticStatus: String = "พร้อมเชื่อมต่อ BYD API"
        private set

    private var executor: ScheduledExecutorService? = null
    private val deviceCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Any, Long>>()
    private val cacheTtlMs = 60_000L

    // ADAS & Light Device Listeners (Zero-CPU event callback)
    private var registeredAdasListener: Any? = null
    private var registeredLightListener: Any? = null

    fun startListening() {
        applyUserVehicleProfile()
        BlindspotAudioChime.init(context)
        registerSafetyListeners()

        executor = Executors.newSingleThreadScheduledExecutor()
        executor?.scheduleWithFixedDelay({
            try {
                applyUserVehicleProfile()
                // 1. Direct native BYD hardware API reading with BydPermissionContext (Zero-Hole Guard)
                pollDirectBydHardwareApi()
                // 2. Poll Cabin Temperature
                pollCabinTemperature()
                // 3. Read shell daemon on port 19890
                fetchTelemetryDaemonStatus()
            } catch (t: Throwable) {
                Log.w(tag, "Zero-hole cycle catch: ${t.message}")
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    fun stopListening() {
        unregisterSafetyListeners()
        BlindspotAudioChime.release()
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
            ApiCandidate("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", listOf("getElecPercentageValue", "getLastElecConPHMValue", "getElecDrivingRangeValue")),
            ApiCandidate("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", listOf("getLast50KmPowerConsume")),
            ApiCandidate("android.hardware.bydauto.power.BYDAutoPowerDevice", listOf("getBatteryRemainPowerEV", "getRemainingBatteryPower"))
        )

        for (candidate in candidates) {
            val clazz = try {
                Class.forName(candidate.className)
            } catch (e: Throwable) {
                continue
            }

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
                    }
                } catch (_: Throwable) {
                    // Zero Hole: Suppress and maintain stability
                }
            }
        }

        if (updated) {
            recalculateRealTelemetry()
            notifyUpdate()
        }
    }

    private fun pollCabinTemperature() {
        try {
            val acCls = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice")
            val wrappedCtx = BydPermissionContext.wrap(context)
            var dev = obtainDeviceInstance(acCls)
            if (dev == null) {
                try {
                    val ctor = acCls.getDeclaredConstructor(Context::class.java)
                    ctor.setAccessible(true)
                    dev = ctor.newInstance(wrappedCtx)
                } catch (_: Throwable) {}
            }
            if (dev != null) {
                val method = acCls.getMethod("getTemprature", Int::class.javaPrimitiveType)
                method.isAccessible = true
                val t = (method.invoke(dev, 1) as? Number)?.toInt() ?: 25
                if (t in 15..40) {
                    cabinTemperatureC = t
                }
            }
        } catch (_: Throwable) {
            // Zero-Hole Guard: keep last known safe temperature
        }
    }

    private data class ApiCandidate(val className: String, val getters: List<String>)

    private fun obtainDeviceInstance(clazz: Class<*>): Any? {
        val cached = deviceCache[clazz.name]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.second) < cacheTtlMs) return cached.first

        val wrappedContext = BydPermissionContext.wrap(context)
        // 1. getInstance(Context)
        try {
            val m = clazz.getMethod("getInstance", Context::class.java)
            val inst = m.invoke(null, wrappedContext)
            if (inst != null) {
                deviceCache[clazz.name] = Pair(inst, now)
                return inst
            }
        } catch (_: Throwable) {}

        // 2. self()
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
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cache-Control", "no-cache")
            try {
                if (conn.responseCode != 200) return
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val socObj = json.optJSONObject("soc")
                val soc = socObj?.optDouble("percent", -1.0) ?: -1.0
                val rangeObj = json.optJSONObject("range")
                val range = rangeObj?.optInt("elecRangeKm", 0) ?: 0
                val remain = json.optDouble("remainEnergyKwh", -1.0)
                val consumptionObj = json.optJSONObject("consumption")
                val consumption = consumptionObj?.optDouble("kwhPer100km", -1.0) ?: -1.0

                var supplemented = false
                if (currentSoc <= 0.0 && soc in 0.0..100.0) {
                    currentSoc = soc
                    supplemented = true
                }
                if (elecRangeKm <= 0 && range > 0) {
                    elecRangeKm = range
                    supplemented = true
                }
                if (remainEnergyKwh <= 0.5 && remain in 0.1..150.0) {
                    remainEnergyKwh = remain
                    supplemented = true
                }
                if (currentConsumption <= 0.0 && consumption in 1.0..60.0) {
                    supplemented = applyConsumptionUpdate(consumption) || supplemented
                }

                if (supplemented) {
                    recalculateRealTelemetry()
                    notifyUpdate()
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {}
    }

    private fun recalculateRealTelemetry() {
        try {
            val effCapacity = if (estimatedCapacityKwh > 0.0) estimatedCapacityKwh else 48.7
            val effKwh = if (remainEnergyKwh > 0.5) remainEnergyKwh else (currentSoc / 100.0) * effCapacity
            val effConsumption = if (currentConsumption > 0.0) currentConsumption else 12.0
            if (effConsumption > 0.0) {
                realRangeKm = ((effKwh / effConsumption) * 100.0 * 10.0).roundToInt() / 10.0
            }
        } catch (_: Throwable) {}
    }

    private fun notifyUpdate() {
        mainHandler.post {
            onTelemetryUpdate(currentSoc, currentConsumption)
        }
    }

    /**
     * ลงทะเบียน Event Listener สำหรับ ADAS (BSD) และ Light (Turn signal flash)
     * ทำงานแบบ Passive Callback ไม่รันลูป ไม่กิน CPU
     */
    private fun registerSafetyListeners() {
        try {
            // 1. ลงทะเบียน ADAS Listener
            val adasClass = try { Class.forName("android.hardware.bydauto.adas.BYDAutoADASDevice") } catch (_: Throwable) { null }
            if (adasClass != null) {
                val adasDevice = obtainDeviceInstance(adasClass)
                if (adasDevice != null) {
                    val adasListener = object : android.hardware.bydauto.adas.AbsBYDAutoADASListener() {
                        override fun onBSDStateChanged(state: Int) {
                            Log.d(tag, "BYD Event onBSDStateChanged: $state")
                            // state != 0 แสดงว่าระบบตรวจพบวัตถุหรือกำลังเตือน
                            BlindspotAudioChime.isBsdActive = (state > 0)
                            checkAndTriggerChime()
                        }
                    }
                    val regMethod = adasClass.methods.firstOrNull { 
                        it.name == "registerListener" && it.parameterTypes.size == 1 && 
                        it.parameterTypes[0].isAssignableFrom(adasListener.javaClass)
                    }
                    regMethod?.invoke(adasDevice, adasListener)
                    registeredAdasListener = Pair(adasDevice, adasListener)
                    Log.d(tag, "Successfully registered ADAS BSD listener")
                }
            }

            // 2. ลงทะเบียน Light Listener (ตรวจจับไฟเลี้ยวและไฟกระพริบฉุกเฉิน/เตือน)
            val lightClass = try { Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice") } catch (_: Throwable) { null }
            if (lightClass != null) {
                val lightDevice = obtainDeviceInstance(lightClass)
                if (lightDevice != null) {
                    val lightListener = object : android.hardware.bydauto.light.AbsBYDAutoLightListener() {
                        override fun onTurnLightFlashStateChanged(state: Int) {
                            Log.d(tag, "BYD Event onTurnLightFlashStateChanged: $state")
                            // 2: Left Flash, 3: Left Fast, 4: Right Flash, 5: Right Fast, 6: Danger Flash
                            val isFlashing = state in listOf(2, 3, 4, 5, 6, 9)
                            BlindspotAudioChime.isTurnSignalActive = isFlashing
                            checkAndTriggerChime()
                        }

                        override fun onTurnLightStateChanged(state: Int) {
                            // 2: Short, 3: Long
                            val isOn = state in 2..5
                            BlindspotAudioChime.isTurnSignalActive = isOn
                            checkAndTriggerChime()
                        }
                    }
                    val regMethod = lightClass.methods.firstOrNull { 
                        it.name == "registerListener" && it.parameterTypes.size == 1 && 
                        it.parameterTypes[0].isAssignableFrom(lightListener.javaClass)
                    }
                    regMethod?.invoke(lightDevice, lightListener)
                    registeredLightListener = Pair(lightDevice, lightListener)
                    Log.d(tag, "Successfully registered Light Turn Signal listener")
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to register safety listeners: ${t.message}")
        }
    }

    private fun checkAndTriggerChime() {
        // เงื่อนไข: ดักเฉพาะตอน "มีรถในจุดอับ (BSD Active)" + "ไฟกระพริบเลี้ยว (Turn Signal Flash)"
        if (BlindspotAudioChime.isBsdActive && BlindspotAudioChime.isTurnSignalActive) {
            BlindspotAudioChime.onBlindspotFlashingDetected(context)
        }
    }

    private fun unregisterSafetyListeners() {
        try {
            (registeredAdasListener as? Pair<*, *>)?.let { (dev, listener) ->
                val unreg = dev?.javaClass?.methods?.firstOrNull { it.name == "unregisterListener" && it.parameterCount == 1 }
                unreg?.invoke(dev, listener)
            }
            registeredAdasListener = null

            (registeredLightListener as? Pair<*, *>)?.let { (dev, listener) ->
                val unreg = dev?.javaClass?.methods?.firstOrNull { it.name == "unregisterListener" && it.parameterCount == 1 }
                unreg?.invoke(dev, listener)
            }
            registeredLightListener = null
        } catch (t: Throwable) {
            Log.w(tag, "Failed to unregister safety listeners: ${t.message}")
        }
    }
}
