package com.byd.mapsoverlay

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log

/**
 * BlindspotAudioChime (Zero-Resource / Instant Trigger with Multi-Tone Selection)
 */
object BlindspotAudioChime {
    private const val TAG = "BlindspotAudioChime"

    private var soundPool: SoundPool? = null
    private val soundMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var lastPlayTimeMs: Long = 0L
    private const val MIN_COOLDOWN_MS = 600L

    @Volatile var isBsdActive: Boolean = false
    @Volatile var isTurnSignalActive: Boolean = false

    fun init(context: Context) {
        if (soundPool != null) return

        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(audioAttributes)
                .build()

            val toneKeys = listOf("german", "volvo", "cockpit", "tesla", "lexus", "sonar")
            for (key in toneKeys) {
                val resId = context.resources.getIdentifier("bsd_$key", "raw", context.packageName)
                if (resId != 0) {
                    val id = soundPool?.load(context, resId, 1) ?: 0
                    if (id != 0) {
                        soundMap[key] = id
                    }
                }
            }
            Log.d(TAG, "Initialized SoundPool with ${soundMap.size} tone styles.")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize SoundPool: ${t.message}")
        }
    }

    /**
     * สั่งเล่นเสียงตามประเภทที่ผู้ใช้เลือกไว้
     */
    fun onBlindspotFlashingDetected(context: Context) {
        if (!VehicleOverlayConfig.isBsdSoundEnabled(context)) return

        val now = SystemClock.uptimeMillis()
        if (now - lastPlayTimeMs < MIN_COOLDOWN_MS) return
        lastPlayTimeMs = now

        val selectedType = VehicleOverlayConfig.getBsdSoundType(context)
        playToneDirect(selectedType)
    }

    fun playToneDirect(type: String) {
        try {
            val soundId = soundMap[type] ?: soundMap["german"] ?: return
            soundPool?.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f)
            Log.d(TAG, "Played Blindspot Warning Chime: $type")
        } catch (t: Throwable) {
            Log.e(TAG, "Error playing sound: ${t.message}")
        }
    }

    fun release() {
        try {
            soundPool?.release()
            soundPool = null
            soundMap.clear()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing SoundPool: ${t.message}")
        }
    }
}
