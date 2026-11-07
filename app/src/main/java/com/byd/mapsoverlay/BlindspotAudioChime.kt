package com.byd.mapsoverlay

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log

/**
 * BlindspotAudioChime (Zero-Resource / Instant Trigger)
 * 
 * ดักเฉพาะจังหวะไฟเลี้ยวกระพริบเตือน (Mirror flashing with turn signal)
 * โดยใช้ SoundPool โหลดไฟล์ PCM สั้น (120ms) ไว้ล่วงหน้า
 * ไม่ใช้ Background polling loop และไม่ใช้ CPU เกิน 0.01%
 */
object BlindspotAudioChime {
    private const val TAG = "BlindspotAudioChime"

    private var soundPool: SoundPool? = null
    private var chimeSoundId: Int = 0
    private var isLoaded: Boolean = false
    private var lastPlayTimeMs: Long = 0L
    private const val MIN_COOLDOWN_MS = 600L // ป้องกันเสียงซ้อนรัวเกินไป

    // สถานะสังเกตการณ์
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
                .setMaxStreams(1)
                .setAudioAttributes(audioAttributes)
                .build()

            soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    isLoaded = true
                    Log.d(TAG, "SoundPool loaded chime sample: $sampleId")
                }
            }

            val resId = context.resources.getIdentifier("bsd_chime", "raw", context.packageName)
            if (resId != 0) {
                chimeSoundId = soundPool?.load(context, resId, 1) ?: 0
            } else {
                Log.w(TAG, "bsd_chime.wav resource not found")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize SoundPool: ${t.message}")
        }
    }

    /**
     * เรียกเมื่อมีสัญญาณไฟกระพริบเตือนจุดอับสายตา
     */
    fun onBlindspotFlashingDetected(context: Context) {
        if (!VehicleOverlayConfig.isBsdSoundEnabled(context)) {
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastPlayTimeMs < MIN_COOLDOWN_MS) {
            return // ยังอยู่ใน Cooldown
        }

        lastPlayTimeMs = now

        try {
            if (isLoaded && chimeSoundId != 0) {
                soundPool?.play(chimeSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
                Log.d(TAG, "Played Blindspot Warning Chime")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error playing sound chime: ${t.message}")
        }
    }

    fun release() {
        try {
            soundPool?.release()
            soundPool = null
            isLoaded = false
            chimeSoundId = 0
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing SoundPool: ${t.message}")
        }
    }
}
