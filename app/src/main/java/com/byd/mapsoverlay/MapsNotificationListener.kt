package com.byd.mapsoverlay

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.regex.Pattern

class MapsNotificationListener : NotificationListenerService() {
    private val tag = "MapsNotification"
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val activePollRunnable = object : Runnable {
        override fun run() {
            try {
                activeNotifications?.forEach { sbn ->
                    if (isNavigationNotification(sbn)) {
                        processMapsNotification(sbn)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Poll active notifications error", e)
            }
            pollHandler.postDelayed(this, 3000)
        }
    }

    // Strict Launcher & Car System Blacklist (Never read as navigation)
    private val blacklistedLauncherPackages = setOf(
        "com.dudu.android",
        "com.dudu.launcher",
        "com.dudu.car",
        "com.dudu.auto",
        "com.overdrive.auto",
        "com.carwebguru",
        "altergames.carlauncher",
        "com.autonavi.amapauto",
        "com.byd.launcher",
        "com.byd.service",
        "com.byd.dilink"
    )

    private val explicitMapsPackages = setOf(
        // 1. Google Play Store & Official Direct APK releases
        "com.google.android.apps.maps",
        "com.google.android.apps.mapslite",
        "com.google.android.apps.navlite",
        "com.google.android.apps.gmm",
        "com.google.android.apps.gmm.car",

        // 2. Vanced, ReVanced & Morphe Patched Maps
        "app.morphe.android.apps.maps",
        "app.revanced.android.apps.maps",
        "app.vanced.android.apps.maps",
        "com.vanced.android.apps.maps",

        // 3. GBox Virtual Containers & Environments (DiLink 3.0 Sandbox)
        "com.gbox.android",
        "com.gbox.pub",
        "com.gbox.lite",
        "com.gbox.pro",
        "com.gbox.work",

        // 4. GPack & BYD DiLink Google Framework Installers
        "com.google.android.apps.gpack",
        "com.byd.gpack",
        "com.huawei.gpack",
        "com.dilink.gpack",
        "com.gpack.android",
        "com.gpack.installer",

        // 5. GSpace / Dual Space / Parallel App Engines
        "com.gspace.android",
        "com.gspace.android.free",
        "com.ludashi.dualspace",
        "com.ludashi.dualspacepro",
        "com.excelliance.dualaid",

        // 6. microG & GmsCore Sandboxed Maps
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.mgoogle.android.gms",
        "org.microg.gms.droidguard",
        "com.android.vending",

        // 7. Android Auto Projection Receivers (ZLink / CarLinkit / BYD Auto Link)
        "com.zjinnova.zlink",
        "com.zjinnova.zlink5",
        "com.zjinnova.zlink.auto",
        "com.byd.bydlink",
        "com.byd.auto.link",
        "com.byd.auto.projection",
        "com.google.android.projection.gearhead",
        "com.google.android.embedded.projection",
        "com.autonavi.link",
        "cn.manstep.phonemirrorbox",
        "com.carlink.android"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(tag, "NotificationListener connected -> starting active route sync")
        pollHandler.removeCallbacks(activePollRunnable)
        pollHandler.post(activePollRunnable)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        pollHandler.removeCallbacks(activePollRunnable)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) {
            processMapsNotification(sbn)
        }
    }

    private fun processMapsNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        if (isNavigationNotification(sbn)) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""
            val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""
            val tickerText = sbn.notification.tickerText?.toString() ?: ""

            val distanceKm = extractTotalRemainingDistance(subText, text, bigText, title, infoText, summaryText, tickerText)
            if (distanceKm != null && distanceKm > 0.0) {
                val broadcastIntent = Intent(OverlayService.ACTION_UPDATE_DISTANCE).apply {
                    setPackage(packageName)
                    putExtra(OverlayService.EXTRA_DISTANCE_KM, distanceKm)
                }
                sendBroadcast(broadcastIntent)

                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_UPDATE_DISTANCE
                    putExtra(OverlayService.EXTRA_DISTANCE_KM, distanceKm)
                }
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "startService fallback to broadcast", e)
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null && isNavigationNotification(sbn)) {
            val stillNavigating = try {
                activeNotifications?.any { other ->
                    other != null && other.id != sbn.id && isNavigationNotification(other)
                } ?: false
            } catch (_: Exception) {
                false
            }
            if (stillNavigating) {
                Log.d(tag, "Other navigation notification still active, keeping overlay")
                return
            }

            Log.d(tag, "Maps navigation ended [${sbn.packageName}] -> hide overlay")
            val broadcastIntent = Intent(OverlayService.ACTION_HIDE_OVERLAY).apply {
                setPackage(packageName)
            }
            sendBroadcast(broadcastIntent)
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_HIDE_OVERLAY
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.w(tag, "startService hide fallback", e)
            }
        }
    }

    private fun isNavigationNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        val notif = sbn.notification ?: return false

        // 1. HARD DROP: If from DUDU, Overdrive, or native car launcher/trip odometers -> Immediately ignore!
        if (blacklistedLauncherPackages.any { pkg.contains(it) } || pkg.contains("dudu") || pkg.contains("overdrive")) {
            return false
        }

        // Check if package matches known Maps, GBox, GPack, Vanced, MicroG, GSpace or Android Auto Projection packages
        val isExplicitMapsPkg = explicitMapsPackages.contains(pkg) ||
            pkg.endsWith(".maps") ||
            pkg.contains("google.android.apps.maps") ||
            pkg.contains("gmaps") ||
            pkg.contains("gbox") ||
            pkg.contains("gpack") ||
            pkg.contains("gspace") ||
            pkg.contains("microg") ||
            pkg.contains("zlink") ||
            pkg.contains("bydlink") ||
            pkg.contains("projection") ||
            pkg.contains("phonemirrorbox")

        val isNavCategory = notif.category == Notification.CATEGORY_NAVIGATION
        val groupKey = (notif.group ?: "").lowercase()
        val channelId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            (notif.channelId ?: "").lowercase()
        } else ""

        // Check for navigation exit / dismiss actions (English & Thai)
        val hasExitNavAction = notif.actions?.any { action ->
            val actionTitle = action.title?.toString()?.lowercase() ?: ""
            actionTitle.contains("exit navigation") ||
                actionTitle.contains("ออกจากการนำทาง") ||
                actionTitle.contains("หยุดนำทาง") ||
                actionTitle.contains("dismiss navigation") ||
                actionTitle.contains("ออก")
        } == true

        // Check extras text for navigation keywords when running inside containers (GBox/GPack/GSpace)
        val extras = notif.extras
        val title = (extras?.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().lowercase()
        val text = (extras?.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().lowercase()
        val subText = (extras?.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: "").toString().lowercase()
        val bigText = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "").toString().lowercase()
        val infoText = (extras?.getCharSequence(Notification.EXTRA_INFO_TEXT) ?: "").toString().lowercase()
        val ticker = (notif.tickerText ?: "").toString().lowercase()
        val allText = "$title $text $subText $bigText $infoText $ticker"

        // High-confidence navigation cues (bilingual TH/EN)
        val hasDistanceUnit = allText.contains("กม.") ||
            allText.contains("กม") ||
            allText.contains("km") ||
            allText.contains("เมตร") ||
            allText.contains(" m")
        val hasNavDirection = allText.contains("มุ่งหน้า") ||
            allText.contains("toward") ||
            allText.contains("เลี้ยว") ||
            allText.contains("turn") ||
            allText.contains("นำทาง") ||
            allText.contains("navigating")
        val hasTimeEstimate = allText.contains("นาที") ||
            allText.contains("min") ||
            allText.contains("ชม.") ||
            allText.contains(" hr")

        // Strict Security Clamping (Zero False-Positive Protection):
        // 1. MUST originate from verified Maps or Google Container package (GBox, GPack, GSpace, MicroG, Vanced)
        // 2. OR native Android CATEGORY_NAVIGATION from system framework
        if (!isExplicitMapsPkg && !isNavCategory) {
            // Reject all third-party chat/messaging apps (LINE, Messenger, SMS, Browser, Fitness)
            return false
        }

        // Within verified packages, validate navigation intent
        if (isNavCategory || groupKey.contains("navigation_status") || channelId.contains("navigation") || hasExitNavAction) {
            return true
        }

        // Fallback for sandboxes (GBox/GPack) where category might be demoted to default by the container
        if (isExplicitMapsPkg && hasDistanceUnit && (hasNavDirection || hasTimeEstimate)) {
            return true
        }

        return false
    }

    private fun extractTotalRemainingDistance(vararg sources: String): Double? {
        val allDistances = mutableListOf<Double>()
        for (src in sources) {
            if (src.isBlank()) continue
            allDistances.addAll(parseDistancesFromText(src))
        }
        return allDistances.maxOrNull()
    }

    private fun parseDistancesFromText(input: String): List<Double> {
        if (input.isBlank()) return emptyList()
        val normalized = input.replace('\u00A0', ' ').replace('\u202F', ' ')

        // Filter out speed, odometer, and trip info (km/h, กม./ชม., trip, odo)
        val lower = normalized.lowercase()
        if (lower.contains("km/h") || lower.contains("กม./ชม") || lower.contains("กม/ชม") || lower.contains("odometer") || lower.contains("odo")) {
            // If it only contains speed/odo without navigation keywords, ignore
            if (!lower.contains("มุ่งหน้า") && !lower.contains("toward") && !lower.contains("ทางออก") && !lower.contains("เลี้ยว")) {
                return emptyList()
            }
        }

        val foundDistancesKm = mutableListOf<Double>()

        try {
            // Kilometers: km, กม., กม
            val kmPattern = Pattern.compile("([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*(?:km|กม\\.|กม)", Pattern.CASE_INSENSITIVE)
            val kmMatcher = kmPattern.matcher(normalized)
            while (kmMatcher.find()) {
                val rawNum = kmMatcher.group(1)?.replace(",", "")
                rawNum?.toDoubleOrNull()?.let { foundDistancesKm.add(it) }
            }

            // Meters: m, ม., เมตร
            val mPattern = Pattern.compile("([0-9]+(?:,[0-9]{3})*)\\s*(?:m(?!in)|ม\\.|เมตร)(?:\\b|\\s|$)", Pattern.CASE_INSENSITIVE)
            val mMatcher = mPattern.matcher(normalized)
            while (mMatcher.find()) {
                val rawNum = mMatcher.group(1)?.replace(",", "")
                rawNum?.toDoubleOrNull()?.let { foundDistancesKm.add(it / 1000.0) }
            }

            // Miles: mi, mile, miles
            val miPattern = Pattern.compile("([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*(?:mi|miles?)(?:\\b|\\s|$)", Pattern.CASE_INSENSITIVE)
            val miMatcher = miPattern.matcher(normalized)
            while (miMatcher.find()) {
                val rawNum = miMatcher.group(1)?.replace(",", "")
                rawNum?.toDoubleOrNull()?.let { foundDistancesKm.add(it * 1.60934) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Parse distance error", e)
        }

        return foundDistancesKm
    }

    companion object {
        fun requestRebindIfDisconnected(context: android.content.Context) {
            val componentName = android.content.ComponentName(context, MapsNotificationListener::class.java)
            try {
                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                context.packageManager.setComponentEnabledSetting(
                    componentName,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    requestRebind(componentName)
                }
            } catch (e: Exception) {
                Log.w("MapsNotification", "Rebind error", e)
            }
        }
    }
}
