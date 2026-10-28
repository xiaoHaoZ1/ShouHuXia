package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 所有 PendingIntent/闹钟集中在此。
 * 用户可见定时（到期/确认/日报）用 setAlarmClock——不受 Doze 影响；
 * 看门狗用 setExactAndAllowWhileIdle。
 */
class AlarmScheduler(private val context: Context) {

    private val am: AlarmManager = context.getSystemService(AlarmManager::class.java)

    private fun op(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 闹钟旁边点了跳转主界面。 */
    private fun showIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 用户可见定时优先 setAlarmClock（不受 Doze 影响）；
     * API31/32 未授权 SCHEDULE_EXACT_ALARM 时会抛 SecurityException，逐级降级。
     */
    private fun setUserVisibleAlarm(at: Long, pi: PendingIntent) {
        try {
            if (canScheduleExact()) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent()), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (se: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun scheduleDeadline(at: Long) {
        setUserVisibleAlarm(at, op(ACTION_DEADLINE, REQ_DEADLINE))
    }

    fun scheduleConfirmEnd(at: Long) {
        setUserVisibleAlarm(at, op(ACTION_CONFIRM_END, REQ_CONFIRM_END))
    }

    fun scheduleDailySms(at: Long) {
        setUserVisibleAlarm(at, op(ACTION_DAILY_SMS, REQ_DAILY_SMS))
    }

    fun scheduleWatchdog(delayMillis: Long) {
        val triggerAt = System.currentTimeMillis() + delayMillis
        val pi = op(ACTION_WATCHDOG, REQ_WATCHDOG)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (se: SecurityException) {
            // API31/32 未授权精确闹钟时降级
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** 演练到点闹钟：锁屏时刻 + 等待时长（独立于守护闹钟的 requestCode）。 */
    fun scheduleDrillAlarm(at: Long) {
        setUserVisibleAlarm(at, op(ACTION_DRILL, REQ_DRILL))
    }

    /** 演练确认窗口结束闹钟：到点自动拨号。 */
    fun scheduleDrillConfirmEnd(at: Long) {
        setUserVisibleAlarm(at, op(ACTION_DRILL_CONFIRM_END, REQ_DRILL_CONFIRM_END))
    }

    // ---------- 拨号序列闹钟（真实报警与演练共用） ----------

    /** 通话记录检查闹钟（通话结束回 IDLE 后首查 + 重试）。 */
    fun scheduleCallCheck(at: Long) {
        setUserVisibleAlarm(at, op(ACTION_CALL_CHECK, REQ_CALL_CHECK))
    }

    /** 拨号兜底闹钟：防漏收 IDLE 广播。 */
    fun scheduleCallFallback(at: Long) {
        setUserVisibleAlarm(at, op(ACTION_CALL_FALLBACK, REQ_CALL_FALLBACK))
    }

    /** 下一轮拨号闹钟（一轮无人接通后隔 ROUND_INTERVAL 再拨）。 */
    fun scheduleNextRound(at: Long) {
        setUserVisibleAlarm(at, op(ACTION_NEXT_ROUND, REQ_NEXT_ROUND))
    }

    /** 取消拨号序列的三个闹钟（check/fallback/nextRound）。 */
    fun cancelCallAlarms() {
        am.cancel(op(ACTION_CALL_CHECK, REQ_CALL_CHECK))
        am.cancel(op(ACTION_CALL_FALLBACK, REQ_CALL_FALLBACK))
        am.cancel(op(ACTION_NEXT_ROUND, REQ_NEXT_ROUND))
    }

    /** 取消演练闹钟与演练确认通知；不影响守护闹钟与拨号序列闹钟。 */
    fun cancelDrill() {
        am.cancel(op(ACTION_DRILL, REQ_DRILL))
        am.cancel(op(ACTION_DRILL_CONFIRM_END, REQ_DRILL_CONFIRM_END))
        Notifications.cancelDrill(context)
    }

    fun cancelConfirmAndAlert() {
        am.cancel(op(ACTION_CONFIRM_END, REQ_CONFIRM_END))
        Notifications.cancelConfirm(context)
    }

    fun cancelAll() {
        am.cancel(op(ACTION_DEADLINE, REQ_DEADLINE))
        am.cancel(op(ACTION_CONFIRM_END, REQ_CONFIRM_END))
        am.cancel(op(ACTION_DAILY_SMS, REQ_DAILY_SMS))
        am.cancel(op(ACTION_WATCHDOG, REQ_WATCHDOG))
        cancelCallAlarms()
        Notifications.cancelConfirm(context)
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    companion object {
        const val ACTION_DEADLINE = "io.github.xiaohaoz1.jiarenxia.guardian.alarm.DEADLINE"
        const val ACTION_CONFIRM_END = "io.github.xiaohaoz1.jiarenxia.guardian.alarm.CONFIRM_END"
        const val ACTION_DAILY_SMS = "io.github.xiaohaoz1.jiarenxia.guardian.alarm.DAILY_SMS"
        const val ACTION_WATCHDOG = "io.github.xiaohaoz1.jiarenxia.guardian.alarm.WATCHDOG"
        const val ACTION_DRILL = "io.github.xiaohaoz1.jiarenxia.guardian.alarm.DRILL"
        const val ACTION_DRILL_CONFIRM_END =
            "io.github.xiaohaoz1.jiarenxia.guardian.alarm.DRILL_CONFIRM_END"
        const val ACTION_CALL_CHECK =
            "io.github.xiaohaoz1.jiarenxia.guardian.alarm.CALL_CHECK"
        const val ACTION_CALL_FALLBACK =
            "io.github.xiaohaoz1.jiarenxia.guardian.alarm.CALL_FALLBACK"
        const val ACTION_NEXT_ROUND =
            "io.github.xiaohaoz1.jiarenxia.guardian.alarm.NEXT_ROUND"

        private const val REQ_DEADLINE = 1
        private const val REQ_CONFIRM_END = 2
        private const val REQ_DAILY_SMS = 3
        private const val REQ_WATCHDOG = 4
        // 演练闹钟独立 requestCode：cancelAll 不能误取消演练闹钟
        private const val REQ_DRILL = 5
        private const val REQ_DRILL_CONFIRM_END = 6
        // 拨号序列闹钟（check/fallback/nextRound，cancelAll 含、cancelDrill 不含）
        private const val REQ_CALL_CHECK = 7
        private const val REQ_CALL_FALLBACK = 8
        private const val REQ_NEXT_ROUND = 9
    }
}
