package com.byd.mapsoverlay

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log

/**
 * BlindspotAudioChime V2.1 (Multi-Channel DiLink 3.0 Audio + Broadcast Support)
 * - Plays simultaneously on USAGE_MEDIA (Music stream) and USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (Navi stream)
 *   because DiLink 3.0 mutes USAGE_ASSISTANCE_SONIFICATION for 3rd-party UIDs.
 * - Includes instant MediaPlayer fallback if SoundPool sample is still loading.
 * - Emits Broadcast Intent `com.byd.mapsoverlay.ACTION_BSD_ALERT` for external receivers / cluster integration.
 */
object BlindspotAudioChime {
    private const val TAG = "BlindspotAudioChime"
    const val ACTION_BSD_ALERT_BROADCAST = "com.byd.mapsoverlay.ACTION_BSD_ALERT"

    private var mediaSoundPool: SoundPool? = null
    private var naviSoundPool: SoundPool? = null
    private val mediaSoundMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val naviSoundMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val loadedMediaIds = java.util.Collections.synchronizedSet(HashSet<Int>())
    private val loadedNaviIds = java.util.Collections.synchronizedSet(HashSet<Int>())

    private var appCtx: Context? = null
    private var lastPlayTimeMs: Long = 0L
    private const val MIN_COOLDOWN_MS = 580L

    @Volatile var isBsdActive: Boolean = false
    @Volatile var isTurnSignalActive: Boolean = false
    @Volatile var isClusterAlarmActive: Boolean = false

    fun init(context: Context) {
        appCtx = context.applicationContext ?: context
        if (mediaSoundPool != null && naviSoundPool != null) return

        try {
            val mediaAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            mediaSoundPool = SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(mediaAttr)
                .build().apply {
                    setOnLoadCompleteListener { _, sampleId, status ->
                        if (status == 0) loadedMediaIds.add(sampleId)
                    }
                }

            val naviAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            naviSoundPool = SoundPool.Builder()
                .setMaxStreams(3)
                .setAudioAttributes(naviAttr)
                .build().apply {
                    setOnLoadCompleteListener { _, sampleId, status ->
                        if (status == 0) loadedNaviIds.add(sampleId)
                    }
                }

            for (i in 1..10) {
                val key = "tone_$i"
                val resId = context.resources.getIdentifier("bsd_$key", "raw", context.packageName)
                if (resId != 0) {
                    val mId = mediaSoundPool?.load(context, resId, 1) ?: 0
                    if (mId != 0) mediaSoundMap[key] = mId

                    val nId = naviSoundPool?.load(context, resId, 1) ?: 0
                    if (nId != 0) naviSoundMap[key] = nId
                }
            }
            Log.d(TAG, "Initialized Multi-Channel SoundPools (${mediaSoundMap.size} tones)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize SoundPools: ${t.message}")
        }
    }

    fun onBlindspotFlashingDetected(context: Context) {
        if (appCtx == null) init(context)
        if (!VehicleOverlayConfig.isBsdSoundEnabled(context)) return

        val now = SystemClock.uptimeMillis()
        if (now - lastPlayTimeMs < MIN_COOLDOWN_MS) return
        lastPlayTimeMs = now

        val selectedType = VehicleOverlayConfig.getBsdSoundType(context)
        playToneDirect(selectedType, context)
        sendBsdBroadcast(context, selectedType)
    }

    fun playToneDirect(type: String, context: Context? = null) {
        val ctx = context?.applicationContext ?: context ?: appCtx
        if (ctx != null && mediaSoundPool == null) {
            init(ctx)
        }

        try {
            val audioManager = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )

            // Ensure STREAM_MUSIC volume is audible on DiLink if user muted/lowered it too much
            if (audioManager != null) {
                try {
                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (curVol in 1..(maxVol / 8)) {
                        // Gently ensure at least ~20% volume so chime is heard
                        val minAudible = (maxVol * 0.22).toInt().coerceAtLeast(6)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minAudible, 0)
                    }
                } catch (_: Throwable) {}
            }

            var playedViaPool = false
            val mSoundId = mediaSoundMap[type] ?: mediaSoundMap["tone_4"] ?: mediaSoundMap.values.firstOrNull()
            if (mSoundId != null && loadedMediaIds.contains(mSoundId)) {
                val streamId = mediaSoundPool?.play(mSoundId, 1.0f, 1.0f, 2, 0, 1.0f) ?: 0
                if (streamId != 0) playedViaPool = true
            }

            val nSoundId = naviSoundMap[type] ?: naviSoundMap["tone_4"] ?: naviSoundMap.values.firstOrNull()
            if (nSoundId != null && loadedNaviIds.contains(nSoundId)) {
                val streamId = naviSoundPool?.play(nSoundId, 1.0f, 1.0f, 2, 0, 1.0f) ?: 0
                if (streamId != 0) playedViaPool = true
            }

            // Fallback to direct MediaPlayer if SoundPool sample wasn't ready yet
            if (!playedViaPool && ctx != null) {
                playFallbackMediaPlayer(ctx, type)
            }

            if (ctx != null) {
                sendBsdBroadcast(ctx, type)
            }
            Log.d(TAG, "Played Blindspot Warning Chime: $type (pool=$playedViaPool)")
        } catch (t: Throwable) {
            Log.e(TAG, "Error playing sound: ${t.message}")
        }
    }

    private fun playFallbackMediaPlayer(context: Context, type: String) {
        try {
            val resId = context.resources.getIdentifier("bsd_$type", "raw", context.packageName)
                .takeIf { it != 0 }
                ?: context.resources.getIdentifier("bsd_tone_4", "raw", context.packageName)
            if (resId == 0) return

            val mp = MediaPlayer.create(context, resId) ?: return
            mp.setVolume(1.0f, 1.0f)
            mp.setOnCompletionListener { player ->
                try { player.release() } catch (_: Throwable) {}
            }
            mp.start()
        } catch (t: Throwable) {
            Log.w(TAG, "Fallback MediaPlayer failed: ${t.message}")
        }
    }

    private fun sendBsdBroadcast(context: Context, toneType: String) {
        try {
            val intent = Intent(ACTION_BSD_ALERT_BROADCAST).apply {
                putExtra("tone_type", toneType)
                putExtra("bsd_active", isBsdActive)
                putExtra("turn_signal_active", isTurnSignalActive)
                putExtra("cluster_alarm_active", isClusterAlarmActive)
                putExtra("timestamp_ms", System.currentTimeMillis())
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Broadcast failed: ${t.message}")
        }
    }

    fun release() {
        try {
            mediaSoundPool?.release()
            naviSoundPool?.release()
            mediaSoundPool = null
            naviSoundPool = null
            mediaSoundMap.clear()
            naviSoundMap.clear()
            loadedMediaIds.clear()
            loadedNaviIds.clear()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing SoundPools: ${t.message}")
        }
    }
}
