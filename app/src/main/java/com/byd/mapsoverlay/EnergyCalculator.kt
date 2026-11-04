package com.byd.mapsoverlay

import android.graphics.Color
import kotlin.math.ceil
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

    /**
     * คำนวณจำนวนครั้งที่ควรแวะชาร์จ (Charging Stops Counter)
     * อิงตามหลักปฏิบัติของรถ EV สากล:
     * - ขาแรก (First Leg): วิ่งจาก currentSoc ลงไปเหลือ 10% (Safe Buffer)
     * - รอบชาร์จ DC Fast Charge บนทางหลวง: ชาร์จจาก 10% -> 80% (70% ของความจุแบตเตอรี่)
     */
    fun calculateChargingStops(
        currentSoc: Double,
        remainingDistanceKm: Double,
        consumptionKwhPer100km: Double,
        realRangeKm: Double
    ): Int {
        if (remainingDistanceKm <= 0.0 || currentSoc <= 0.0 || consumptionKwhPer100km <= 0.0) {
            return 0
        }

        // ถ้ารถวิ่งได้จริงไกลกว่าระยะทางที่เหลือ ไม่ต้องแวะชาร์จ
        if (realRangeKm > 0 && remainingDistanceKm <= realRangeKm) {
            return 0
        }

        val safeCapacity = if (dynamicBatteryCapacityKwh > 10.0) dynamicBatteryCapacityKwh else 50.0
        val kmPerKwh = 100.0 / consumptionKwhPer100km

        // ขาแรก: วิ่งจาก currentSoc ลงไปเหลือ 10%
        val usableSocFirstLeg = max(0.0, currentSoc - 10.0)
        val firstLegKwh = (usableSocFirstLeg / 100.0) * safeCapacity
        val firstLegKm = firstLegKwh * kmPerKwh

        if (remainingDistanceKm <= firstLegKm) {
            return 0
        }

        // ระยะทางที่ขาดอยู่หลังจากวิ่งขาแรกหมด
        val remainingDeficitKm = remainingDistanceKm - firstLegKm

        // แต่ละรอบของการแวะชาร์จด่วน (10% -> 80% = 70% usable)
        val legUsableKwh = 0.70 * safeCapacity
        val legKm = max(30.0, legUsableKwh * kmPerKwh)

        val stops = ceil(remainingDeficitKm / legKm).toInt()
        return max(1, stops)
    }

    /**
     * ระบบไล่เฉดสีแบตเตอรี่ 6 สเต็ป (แดง ➔ ฟ้า):
     * 0-10%: แดงสด (#FF1744) - วิกฤต/โหมดเต่า
     * 10-30%: ส้ม (#FF6D00) - แบตเตอรี่ต่ำ
     * 30-50%: เหลือง (#FFD600) - ปานกลาง
     * 50-75%: เขียวอ่อน (#AEEA00) - พอดี ปลอดภัย
     * 75-95%: เขียวเข้ม (#00E676) - แบตเตอรี่เหลือเฟือ
     * 95-100%: ฟ้าสว่าง (#00E5FF) - เต็มเปี่ยม
     */
    fun getDynamicSocColor(soc: Double): Int {
        return when {
            soc <= 10.0 -> Color.parseColor("#FF1744")
            soc <= 30.0 -> Color.parseColor("#FF6D00")
            soc <= 50.0 -> Color.parseColor("#FFD600")
            soc <= 75.0 -> Color.parseColor("#AEEA00")
            soc <= 95.0 -> Color.parseColor("#00E676")
            else -> Color.parseColor("#00E5FF")
        }
    }
}

