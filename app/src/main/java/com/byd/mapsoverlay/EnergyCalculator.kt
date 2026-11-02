package com.byd.mapsoverlay

import kotlin.math.max

object EnergyCalculator {
    // ความจุแบตเตอรี่เต็ม 100% ตามจริง (kWh) จากรุ่นรถที่ผู้ใช้ตั้งค่าไว้ (หักค่าเสื่อม SOH แล้ว) หรือจาก BMS
    var dynamicBatteryCapacityKwh: Double = 49.2
        private set

    fun updateActualCapacity(actualCapacityKwh: Double) {
        if (actualCapacityKwh in 10.0..150.0) {
            dynamicBatteryCapacityKwh = actualCapacityKwh
        }
    }

    /**
     * คำนวณเปอร์เซ็นต์แบตเตอรี่คงเหลือเมื่อถึงจุดหมายปลายทาง (Destination SoC %)
     * โดยอ้างอิงจาก:
     * 1. ความจุแบตเตอรี่จริงตามรุ่นรถที่ผู้ใช้ตั้งค่าไว้ (`dynamicBatteryCapacityKwh`)
     * 2. ข้อมูลสดจาก OBD / CAN Bus:
     *    - `currentSoc`: % แบตเตอรี่ปัจจุบันจาก OBD
     *    - `consumptionKwhPer100km`: อัตรากินไฟเฉลี่ย 50 กม. ล่าสุดจาก OBD
     * 3. `remainingDistanceKm`: ระยะทางที่เหลือไปถึงจุดหมายจาก Google Maps
     */
    fun calculateDestinationSoc(
        currentSoc: Double,
        remainingDistanceKm: Double,
        consumptionKwhPer100km: Double,
        liveRemainEnergyKwh: Double = 0.0,
        preferUserCapacity: Boolean = true
    ): Pair<Double, Double> {
        if (remainingDistanceKm <= 0.0 || currentSoc <= 0.0 || consumptionKwhPer100km <= 0.0) {
            return Pair(currentSoc, 0.0)
        }

        // พลังงานไฟฟ้าที่ต้องใช้ในการวิ่งไปถึงจุดหมาย (kWh)
        val tripEnergyNeededKwh = (remainingDistanceKm * consumptionKwhPer100km) / 100.0

        // กรณีผู้ใช้ตั้งค่ารุ่นรถและขนาดแบตเตอรี่ไว้เอง (preferUserCapacity = true):
        // คำนวณจากความจุแบตเตอรี่ที่ผู้ใช้ตั้งไว้ + % SoC สดจาก OBD + อัตรากินไฟสดจาก OBD
        if (preferUserCapacity && dynamicBatteryCapacityKwh > 10.0) {
            val percentageUsed = (tripEnergyNeededKwh / dynamicBatteryCapacityKwh) * 100.0
            val socAtDestination = max(0.0, currentSoc - percentageUsed)
            return Pair(socAtDestination, percentageUsed)
        }

        // กรณีเลือกโหมดดึงขนาดแบตอัตโนมัติจาก BMS (AUTO_OBD) และมีค่า liveRemainEnergyKwh
        if (liveRemainEnergyKwh > 0.5) {
            val kwhPerOnePercentSoc = liveRemainEnergyKwh / currentSoc
            val percentageUsed = tripEnergyNeededKwh / kwhPerOnePercentSoc
            val socAtDestination = max(0.0, currentSoc - percentageUsed)
            return Pair(socAtDestination, percentageUsed)
        }

        val effectiveCapacity = if (dynamicBatteryCapacityKwh > 10.0) dynamicBatteryCapacityKwh else 50.25
        val percentageUsed = (tripEnergyNeededKwh / effectiveCapacity) * 100.0
        val socAtDestination = max(0.0, currentSoc - percentageUsed)

        return Pair(socAtDestination, percentageUsed)
    }
}
