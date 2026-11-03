package com.byd.mapsoverlay

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.regex.Pattern

class MapsNotificationListener : NotificationListenerService() {
    private val tag = "MapsNotification"
    private val eventHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val removalCheckRunnable = Runnable {
        syncActiveNavigationNotifications()
    }
    private val removalDebounceMs = 800L

    private val explicitMapsPackages = setOf(
        "com.google.android.apps.maps",
        "com.google.android.apps.mapslite",
        "com.google.android.apps.navlite",
        "app.morphe.android.apps.maps",
        "app.revanced.android.apps.maps",
        "app.vanced.android.apps.maps",
        "com.mgoogle.android.gms",
        "com.gbox.android",
        "com.gspace.android"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(tag, "NotificationListener connected -> one-time active route sync")
        eventHandler.removeCallbacks(removalCheckRunnable)
        syncActiveNavigationNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        eventHandler.removeCallbacks(removalCheckRunnable)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) {
            if (isNavigationNotification(sbn)) {
                // A refreshed/reposted route notification cancels a pending hide.
                eventHandler.removeCallbacks(removalCheckRunnable)
            }
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

            val distanceKm = extractTotalRemainingDistance(subText, text, bigText)
            if (distanceKm != null && distanceKm > 0.0) {
                val broadcastIntent = Intent(OverlayService.ACTION_UPDATE_DISTANCE).apply {
                    putExtra(OverlayService.EXTRA_DISTANCE_KM, distanceKm)
                }
                sendBroadcast(broadcastIntent)

                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_UPDATE_DISTANCE
                    putExtra(OverlayService.EXTRA_DISTANCE_KM, distanceKm)
                }
                try {
                    startService(serviceIntent)
                } catch (e: Exception) {
                    Log.w(tag, "startService fallback to broadcast", e)
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null && isNavigationNotification(sbn)) {
            Log.d(tag, "Maps navigation notification removed -> checking active routes")
            // Maps may replace its notification while navigation is still active.
            eventHandler.removeCallbacks(removalCheckRunnable)
            eventHandler.postDelayed(removalCheckRunnable, removalDebounceMs)
        }
    }

    private fun syncActiveNavigationNotifications() {
        try {
            val activeRoutes = activeNotifications
                ?.filter { isNavigationNotification(it) }
                .orEmpty()

            if (activeRoutes.isEmpty()) {
                Log.d(tag, "No active Maps navigation notification -> hide overlay")
                hideOverlay()
            } else {
                activeRoutes.forEach { processMapsNotification(it) }
            }
        } catch (e: Exception) {
            Log.w(tag, "Active navigation sync error", e)
        }
    }

    private fun hideOverlay() {
        sendBroadcast(Intent(OverlayService.ACTION_HIDE_OVERLAY))
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_OVERLAY
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.w(tag, "startService hide fallback", e)
        }
    }

    private fun isNavigationNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        val notif = sbn.notification ?: return false

        if (explicitMapsPackages.contains(pkg) || pkg.endsWith(".maps") || pkg.contains("google.android.apps.maps") || pkg.contains("gmaps")) {
            return true
        }

        val isNavCategory = notif.category == Notification.CATEGORY_NAVIGATION
        val groupKey = (notif.group ?: "").lowercase()
        val channelId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            (notif.channelId ?: "").lowercase()
        } else ""

        if (isNavCategory || groupKey.contains("navigation_status") || channelId.contains("navigation")) {
            return true
        }

        val hasExitNavAction = notif.actions?.any { action ->
            val actionTitle = action.title?.toString()?.lowercase() ?: ""
            actionTitle.contains("exit navigation") ||
                actionTitle.contains("ออกจากการนำทาง") ||
                actionTitle.contains("หยุดนำทาง") ||
                actionTitle.contains("dismiss navigation")
        } == true

        return hasExitNavAction
    }

    private fun extractTotalRemainingDistance(
        subText: String,
        text: String,
        bigText: String
    ): Double? {
        parseMaxDistanceFromText(subText)?.let { return it }

        if (text.contains("·") || text.contains("•")) {
            parseMaxDistanceFromText(text)?.let { return it }
        }
        if (bigText.contains("·") || bigText.contains("•")) {
            parseMaxDistanceFromText(bigText)?.let { return it }
        }

        parseMaxDistanceFromText("$subText | $bigText")?.let { return it }

        return parseMaxDistanceFromText(text)
    }

    private fun parseMaxDistanceFromText(input: String): Double? {
        if (input.isBlank()) return null
        val normalized = input.replace('\u00A0', ' ').replace('\u202F', ' ')
        val foundDistancesKm = mutableListOf<Double>()

        try {
            val kmPattern = Pattern.compile("([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?)\\s*(?:km|กม\\.|กม)", Pattern.CASE_INSENSITIVE)
            val kmMatcher = kmPattern.matcher(normalized)
            while (kmMatcher.find()) {
                val rawNum = kmMatcher.group(1)?.replace(",", "")
                rawNum?.toDoubleOrNull()?.let { foundDistancesKm.add(it) }
            }

            val mPattern = Pattern.compile("([0-9]+(?:,[0-9]{3})*)\\s*(?:m(?!in)|ม\\.|เมตร)(?:\\b|\\s|$)", Pattern.CASE_INSENSITIVE)
            val mMatcher = mPattern.matcher(normalized)
            while (mMatcher.find()) {
                val rawNum = mMatcher.group(1)?.replace(",", "")
                rawNum?.toDoubleOrNull()?.let { foundDistancesKm.add(it / 1000.0) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Parse distance error", e)
        }

        return foundDistancesKm.maxOrNull()
    }
}
