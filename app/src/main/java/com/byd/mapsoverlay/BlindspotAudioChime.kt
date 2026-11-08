package com.byd.mapsoverlay

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * BlindspotAudioChime V2.4 (Independent Cadence Repeating Pulse + Strict Directional Gate)
 * - Zero AudioFocus disruption: Android AudioFlinger mixes PCM directly over music (Spotify) and navigation.
 * - Directional Safety Gate:
 *     Only chimes when signaling LEFT AND obstacle is on LEFT,
 *     OR signaling RIGHT AND obstacle is on RIGHT.
 *     Opposing direction (e.g. obstacle on right, signal left) is 100% SILENT.
 * - Repeating Pulsed Alert:
 *     Continuously pulses (beep... beep... beep...) at a customizable interval (~420ms default)
 *     as long as the hazard and turn signal match, and stops immediately when cleared!
 * - Never disrupts ongoing phone calls.
 */
object BlindspotAudioChime {
    private const val TAG = "BlindspotAudioChime"
    const val ACTION_BSD_ALERT_BROADCAST = "com.byd.mapsoverlay.ACTION_BSD_ALERT"

    private var mediaSoundPool: SoundPool? = null
    private val mediaSoundMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val loadedMediaIds = java.util.Collections.synchronizedSet(HashSet<Int>())

    private var appCtx: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var isBsdActive: Boolean = false
    @Volatile var isLeftTurnActive: Boolean = false
    @Volatile var isRightTurnActive: Boolean = false
    @Volatile var isLeftDangerActive: Boolean = false
    @Volatile var isRightDangerActive: Boolean = false
    @Volatile var isPulsing: Boolean = false

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!isPulsing) return
            val ctx = appCtx
            if (ctx != null && VehicleOverlayConfig.isBsdSoundEnabled(ctx)) {
                val shouldContinue = (isLeftTurnActive && isLeftDangerActive) || (isRightTurnActive && isRightDangerActive)
                if (shouldContinue) {
                    playSingleChimeTick(ctx)
                    val intervalMs = VehicleOverlayConfig.getBsdPulseIntervalMs(ctx)
                    mainHandler.postDelayed(this, intervalMs)
                } else {
                    stopPulsing()
                }
            } else {
                stopPulsing()
            }
        }
    }

    fun init(context: Context) {
        appCtx = context.applicationContext ?: context
        if (mediaSoundPool != null) return

        try {
            val mediaAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaSoundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(mediaAttr)
                .build().apply {
                    setOnLoadCompleteListener { _, sampleId, status ->
                        if (status == 0) loadedMediaIds.add(sampleId)
                    }
                }

            for (i in 1..10) {
                val key = "tone_$i"
                val resId = context.resources.getIdentifier("bsd_$key", "raw", context.packageName)
                if (resId != 0) {
                    val mId = mediaSoundPool?.load(context, resId, 1) ?: 0
                    if (mId != 0) mediaSoundMap[key] = mId
                }
            }
            Log.d(TAG, "Initialized Zero-Interference SoundPool (${mediaSoundMap.size} tones)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize SoundPool: ${t.message}")
        }
    }

    /**
     * Updates live turn signal and directional obstacle states.
     * Evaluates strict directional match and starts/stops the repeating pulse chime.
     */
    fun updateBsmState(
        leftTurn: Boolean,
        rightTurn: Boolean,
        leftDanger: Boolean,
        rightDanger: Boolean,
        context: Context
    ) {
        val ctx = context.applicationContext ?: context
        appCtx = ctx
        isLeftTurnActive = leftTurn
        isRightTurnActive = rightTurn
        isLeftDangerActive = leftDanger
        isRightDangerActive = rightDanger
        isBsdActive = leftDanger || rightDanger

        val shouldPulse = (leftTurn && leftDanger) || (rightTurn && rightDanger)

        if (shouldPulse) {
            startPulsing(ctx)
        } else {
            stopPulsing()
        }
    }

    @Synchronized
    fun startPulsing(context: Context) {
        val ctx = context.applicationContext ?: context
        appCtx = ctx
        if (isPulsing) return

        if (!VehicleOverlayConfig.isBsdSoundEnabled(ctx)) return

        isPulsing = true
        mainHandler.removeCallbacks(pulseRunnable)
        // Play first pulse immediately!
        playSingleChimeTick(ctx)
        val intervalMs = VehicleOverlayConfig.getBsdPulseIntervalMs(ctx)
        mainHandler.postDelayed(pulseRunnable, intervalMs)
        Log.i(TAG, "Started BSM repeating chime pulse (interval=${intervalMs}ms, leftTurn=$isLeftTurnActive, rightTurn=$isRightTurnActive)")
    }

    @Synchronized
    fun stopPulsing() {
        if (!isPulsing) return
        isPulsing = false
        mainHandler.removeCallbacks(pulseRunnable)
        Log.i(TAG, "Stopped BSM repeating chime pulse")
    }

    private fun playSingleChimeTick(context: Context) {
        val selectedType = VehicleOverlayConfig.getBsdSoundType(context)
        playToneDirect(selectedType, context)
    }

    fun playToneDirect(type: String, context: Context? = null) {
        val ctx = context?.applicationContext ?: context ?: appCtx
        if (ctx != null && mediaSoundPool == null) {
            init(ctx)
        }

        try {
            val audioManager = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val inCall = audioManager != null && (
                audioManager.mode == AudioManager.MODE_IN_CALL ||
                audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
                audioManager.mode == AudioManager.MODE_RINGTONE
            )

            // When in call: DO NOT alter stream volumes, mix PCM audio via AudioFlinger cleanly at comfortable level
            val chimeVolume = if (inCall) 0.8f else 1.0f
            if (!inCall && audioManager != null) {
                try {
                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (curVol <= 2) {
                        val minAudible = (maxVol * 0.20).toInt().coerceAtLeast(5)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, minAudible, 0)
                    }
                } catch (_: Throwable) {}
            }

            var playedViaPool = false
            val mSoundId = mediaSoundMap[type] ?: mediaSoundMap["tone_4"] ?: mediaSoundMap.values.firstOrNull()
            if (mSoundId != null && loadedMediaIds.contains(mSoundId)) {
                val streamId = mediaSoundPool?.play(mSoundId, chimeVolume, chimeVolume, 2, 0, 1.0f) ?: 0
                if (streamId != 0) playedViaPool = true
            }

            if (!playedViaPool && ctx != null) {
                playFallbackMediaPlayer(ctx, type)
            }

            if (ctx != null) {
                sendBsdBroadcast(ctx, type)
            }
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
                putExtra("left_turn", isLeftTurnActive)
                putExtra("right_turn", isRightTurnActive)
                putExtra("left_danger", isLeftDangerActive)
                putExtra("right_danger", isRightDangerActive)
                putExtra("timestamp_ms", System.currentTimeMillis())
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Broadcast failed: ${t.message}")
        }
    }

    fun release() {
        stopPulsing()
        try {
            mediaSoundPool?.release()
            mediaSoundPool = null
            mediaSoundMap.clear()
            loadedMediaIds.clear()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing SoundPool: ${t.message}")
        }
    }
}
