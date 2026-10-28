package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 到期响铃控制：STREAM_ALARM 音量兜底抬升、循环闹铃、震动、局部唤醒锁。
 * stopRinging 全部释放并恢复音量。幂等：重复 start 先停再开。
 */
class AlertController(private val context: Context) {

    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val power: PowerManager? = context.getSystemService(PowerManager::class.java)

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var prevAlarmVolume: Int = -1

    @Synchronized
    fun startConfirmRinging(confirmEndAt: Long) {
        stopRingingInternal()
        raiseAlarmVolume()
        startPlayer()
        startVibrate()
        acquireWakeLock()
        Notifications.showConfirm(context, confirmEndAt)
        Log.d(TAG, "startConfirmRinging confirmEnd=$confirmEndAt")
    }

    /** 演练响铃：同一套响铃/震动/唤醒锁，但通知是演练专用样式（"这是守护测试"）。 */
    @Synchronized
    fun startDrillRinging(confirmEndAt: Long) {
        stopRingingInternal()
        raiseAlarmVolume()
        startPlayer()
        startVibrate()
        acquireWakeLock()
        Notifications.showDrillConfirm(context, confirmEndAt)
        Log.d(TAG, "startDrillRinging confirmEnd=$confirmEndAt")
    }

    @Synchronized
    fun stopRinging() {
        stopRingingInternal()
        Notifications.cancelConfirm(context)
        Notifications.cancelDrill(context)
        Log.d(TAG, "stopRinging")
    }

    private fun stopRingingInternal() {
        try {
            player?.stop()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            player?.release()
        } catch (t: Throwable) {
            // ignore
        }
        player = null
        try {
            vibrator()?.cancel()
        } catch (t: Throwable) {
            // ignore
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) {
            // ignore
        }
        wakeLock = null
        restoreAlarmVolume()
    }

    /** 闹铃音量至少抬到一半，记住原值以便恢复。 */
    private fun raiseAlarmVolume() {
        try {
            val am = audio ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (cur < max / 2) {
                prevAlarmVolume = cur
                am.setStreamVolume(AudioManager.STREAM_ALARM, max / 2, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "raiseAlarmVolume", t)
        }
    }

    private fun restoreAlarmVolume() {
        if (prevAlarmVolume < 0) return
        try {
            audio?.setStreamVolume(AudioManager.STREAM_ALARM, prevAlarmVolume, 0)
        } catch (t: Throwable) {
            // ignore
        }
        prevAlarmVolume = -1
    }

    private fun startPlayer() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startPlayer", t)
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private fun startVibrate() {
        try {
            // 循环：响0.9s 停0.3s
            vibrator()?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 900, 300, 900), 0)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate", t)
        }
    }

    private fun acquireWakeLock() {
        try {
            wakeLock = power
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guard:confirm")
                ?.apply { acquire(10 * 60_000L) }
        } catch (t: Throwable) {
            Log.w(TAG, "wakeLock", t)
        }
    }

    companion object {
        const val TAG = "GuardAlert"
    }
}
