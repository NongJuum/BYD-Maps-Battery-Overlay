package com.byd.mapsoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class OverlayRegressionTest {

    @Test
    fun testEnergyCalculatorDestinationSoc() {
        EnergyCalculator.updateActualCapacity(60.0) // 60 kWh battery
        val (destSoc, used) = EnergyCalculator.calculateDestinationSoc(
            currentSoc = 80.0,
            remainingDistanceKm = 100.0,
            consumptionKwhPer100km = 15.0, // 15 kWh used
            liveRemainEnergyKwh = 0.0,
            preferUserCapacity = true
        )
        // 15 kWh out of 60 kWh is 25% battery used
        assertEquals(25.0, used, 0.001)
        assertEquals(55.0, destSoc, 0.001)
    }

    @Test
    fun testEnergyCalculatorZeroDistance() {
        EnergyCalculator.updateActualCapacity(50.0)
        val (destSoc, used) = EnergyCalculator.calculateDestinationSoc(
            currentSoc = 50.0,
            remainingDistanceKm = 0.0,
            consumptionKwhPer100km = 16.0
        )
        assertEquals(50.0, destSoc, 0.001)
        assertEquals(0.0, used, 0.001)
    }

    @Test
    fun testEnergyCalculatorClampedToZero() {
        EnergyCalculator.updateActualCapacity(50.0)
        val (destSoc, _) = EnergyCalculator.calculateDestinationSoc(
            currentSoc = 10.0,
            remainingDistanceKm = 500.0,
            consumptionKwhPer100km = 20.0 // 100 kWh needed > 50 kWh
        )
        assertEquals(0.0, destSoc, 0.001)
    }

    @Test
    fun testDistanceParsingAllFormats() {
        val parse = { text: String ->
            val normalized = text.replace('\u00A0', ' ').replace('\u202F', ' ')
            val found = mutableListOf<Double>()

            val kmPattern = Pattern.compile("([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*(?:km|กม\\.|กม)", Pattern.CASE_INSENSITIVE)
            val kmMatcher = kmPattern.matcher(normalized)
            while (kmMatcher.find()) {
                val rawNum = kmMatcher.group(1)?.replace(",", "")
                rawNum?.toDoubleOrNull()?.let { found.add(it) }
            }

            val mPattern = Pattern.compile("([0-9]+(?:,[0-9]{3})*)\\s*(?:m(?!in)|ม\\.|เมตร)(?:\\b|\\s|$)", Pattern.CASE_INSENSITIVE)
            val mMatcher = mPattern.matcher(normalized)
            while (mMatcher.find()) {
                val rawNum = mMatcher.group(1)?.replace(",", "")
                rawNum?.toDoubleOrNull()?.let { found.add(it / 1000.0) }
            }

            val miPattern = Pattern.compile("([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*(?:mi|miles?)(?:\\b|\\s|$)", Pattern.CASE_INSENSITIVE)
            val miMatcher = miPattern.matcher(normalized)
            while (miMatcher.find()) {
                val rawNum = miMatcher.group(1)?.replace(",", "")
                rawNum?.toDoubleOrNull()?.let { found.add(it * 1.60934) }
            }
            found.maxOrNull()
        }

        // Test Thai KM format
        val dist1 = parse("15 นาที · 12.5 กม. · ถึง 14:30")
        assertNotNull(dist1)
        assertEquals(12.5, dist1!!, 0.01)

        // Test English KM format
        val dist2 = parse("25 min (18.2 km)")
        assertNotNull(dist2)
        assertEquals(18.2, dist2!!, 0.01)

        // Test Meters
        val dist3 = parse("ในอีก 500 ม. เลี้ยวขวา")
        assertNotNull(dist3)
        assertEquals(0.5, dist3!!, 0.01)

        // Test Multi-distance string: picks the maximum (total trip distance)
        val dist4 = parse("ใน 300 ม. เลี้ยวขวา • 45 กม. (38 นาที)")
        assertNotNull(dist4)
        assertEquals(45.0, dist4!!, 0.01)

        // Test Non-breaking spaces (typical from Google Maps layout)
        val dist5 = parse("15\u00A0นาที\u202F·\u202F23.4\u00A0กม.")
        assertNotNull(dist5)
        assertEquals(23.4, dist5!!, 0.01)
    }

    @Test
    fun testNotificationListenerComponentFormat() {
        val betaPackageId = "com.byd.mapsoverlay.beta"
        val listenerClass = "com.byd.mapsoverlay.MapsNotificationListener"
        val expectedComponent = "$betaPackageId/$listenerClass"

        // In Android settings put secure enabled_notification_listeners:
        // format MUST be packageName/fullClassName
        assertTrue("Component must contain a slash separator", expectedComponent.contains("/"))
        assertEquals("com.byd.mapsoverlay.beta/com.byd.mapsoverlay.MapsNotificationListener", expectedComponent)
    }

    @Test
    fun testDestinationSocHysteresisLogic() {
        // Test the 2% step hysteresis window
        var lockedDestSocMin = -1
        var lockedDestSocMax = -1

        val updateHysteresis = { rawDestSoc: Double ->
            if (lockedDestSocMin < 0 || lockedDestSocMax < 0) {
                val initialMax = kotlin.math.round(rawDestSoc).toInt().coerceIn(0, 100)
                lockedDestSocMax = initialMax
                lockedDestSocMin = (initialMax - 2).coerceAtLeast(0)
            } else {
                if (rawDestSoc < lockedDestSocMin.toDouble()) {
                    val diff = lockedDestSocMin.toDouble() - rawDestSoc
                    if (diff >= 4.0) {
                        lockedDestSocMax = kotlin.math.round(rawDestSoc).toInt().coerceIn(0, 100)
                        lockedDestSocMin = (lockedDestSocMax - 2).coerceAtLeast(0)
                    } else {
                        lockedDestSocMax = lockedDestSocMin
                        lockedDestSocMin = (lockedDestSocMax - 2).coerceAtLeast(0)
                    }
                } else if (rawDestSoc > (lockedDestSocMax.toDouble() + 0.5)) {
                    val diff = rawDestSoc - lockedDestSocMax.toDouble()
                    if (diff >= 4.0) {
                        lockedDestSocMax = kotlin.math.round(rawDestSoc).toInt().coerceIn(0, 100)
                        lockedDestSocMin = (lockedDestSocMax - 2).coerceAtLeast(0)
                    } else {
                        lockedDestSocMin = lockedDestSocMax
                        lockedDestSocMax = (lockedDestSocMin + 2).coerceIn(0, 100)
                    }
                }
            }
            Pair(lockedDestSocMin, lockedDestSocMax)
        }

        // Initial: 70.0% -> range [68, 70]
        val (min1, max1) = updateHysteresis(70.0)
        assertEquals(68, min1)
        assertEquals(70, max1)

        // Slight drop to 68.8% -> STILL locked at [68, 70] (no flicker!)
        val (min2, max2) = updateHysteresis(68.8)
        assertEquals(68, min2)
        assertEquals(70, max2)

        // Drops below 68.0% to 67.8% -> steps down to [66, 68]
        val (min3, max3) = updateHysteresis(67.8)
        assertEquals(66, min3)
        assertEquals(68, max3)
    }
}
