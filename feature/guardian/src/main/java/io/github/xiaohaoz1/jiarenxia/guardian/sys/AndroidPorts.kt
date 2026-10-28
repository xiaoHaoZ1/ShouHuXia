package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import io.github.xiaohaoz1.jiarenxia.guardian.core.BatteryStatus
import io.github.xiaohaoz1.jiarenxia.guardian.core.CallLogResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.Ports
import io.github.xiaohaoz1.jiarenxia.guardian.core.SmsKind

/** Ports 的 Android 实现：把引擎指令转给闹钟/响铃/通知/拨号/短信等子系统。 */
class AndroidPorts : Ports {

    private fun scheduler() = ServiceLocator.scheduler
    private fun alert() = ServiceLocator.alert

    override fun scheduleDeadline(at: Long) = scheduler().scheduleDeadline(at)

    override fun scheduleConfirmEnd(at: Long) = scheduler().scheduleConfirmEnd(at)

    override fun scheduleDailySms(at: Long) = scheduler().scheduleDailySms(at)

    override fun scheduleWatchdog(delayMillis: Long) =
        scheduler().scheduleWatchdog(delayMillis)

    override fun cancelConfirmAndAlert() = scheduler().cancelConfirmAndAlert()

    override fun cancelAll() = scheduler().cancelAll()

    override fun scheduleDrillAlarm(at: Long) = scheduler().scheduleDrillAlarm(at)

    override fun scheduleDrillConfirmEnd(at: Long) = scheduler().scheduleDrillConfirmEnd(at)

    override fun cancelDrill() = scheduler().cancelDrill()

    override fun startDrillRinging(confirmEndAt: Long) =
        alert().startDrillRinging(confirmEndAt)

    override fun appVersionCode(): Long = try {
        val ctx = ServiceLocator.app
        PackageInfoCompat.getLongVersionCode(
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        )
    } catch (t: Throwable) {
        0L
    }

    override fun buildFingerprint(): String = Build.FINGERPRINT ?: ""

    override fun startConfirmRinging(confirmEndAt: Long) =
        alert().startConfirmRinging(confirmEndAt)

    override fun stopRinging() = alert().stopRinging()

    override fun showConfirmNotification(confirmEndAt: Long) =
        Notifications.showConfirm(ServiceLocator.app, confirmEndAt)

    override fun showAlertedNotification(alertedAt: Long) =
        Notifications.showAlerted(ServiceLocator.app, alertedAt)

    override fun placeCall(number: String): Boolean =
        ServiceLocator.telecom.placeCall(number)

    override fun batteryStatus(): BatteryStatus = ServiceLocator.sms.batteryInfo()

    override fun sendSms(number: String, body: String, kind: SmsKind, index: Int): Boolean =
        ServiceLocator.sms.send(number, body, kind, index)

    override fun queryOutgoingCall(number: String, since: Long): CallLogResult =
        ServiceLocator.callLog.queryOutgoingCall(number, since)

    @SuppressLint("MissingPermission") // 调用前已检查 READ_PHONE_STATE
    override fun isInCall(): Boolean {
        val ctx = ServiceLocator.app
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            ctx.getSystemService(TelecomManager::class.java)?.isInCall == true
        } catch (t: Throwable) {
            false
        }
    }

    override fun scheduleCallCheck(at: Long) = scheduler().scheduleCallCheck(at)

    override fun scheduleCallFallback(at: Long) = scheduler().scheduleCallFallback(at)

    override fun scheduleNextRound(at: Long) = scheduler().scheduleNextRound(at)

    override fun cancelCallAlarms() = scheduler().cancelCallAlarms()

    override fun ensureServiceRunning() {
        try {
            val ctx = ServiceLocator.app
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, GuardService::class.java)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "ensureServiceRunning failed", t)
        }
    }

    override fun stopService() {
        val ctx = ServiceLocator.app
        ctx.stopService(Intent(ctx, GuardService::class.java))
        Notifications.cancelLegacyStatus(ctx)
    }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun isDeviceInUse(): Boolean = try {
        val ctx = ServiceLocator.app
        val pm = ctx.getSystemService(PowerManager::class.java)
        val kg = ctx.getSystemService(KeyguardManager::class.java)
        pm != null && pm.isInteractive && kg != null && !kg.isKeyguardLocked
    } catch (t: Throwable) {
        false
    }

    override fun reconcileMissedUnlockSince(since: Long): Long? =
        ServiceLocator.usageStats.missedUnlockSince(since)

    override fun log(tag: String, msg: String) {
        Log.d(tag, msg)
        try {
            ServiceLocator.eventLog.append(tag, msg)
        } catch (t: Throwable) {
            // 日志失败不影响业务
        }
    }

    companion object {
        const val TAG = "GuardPorts"
    }
}
