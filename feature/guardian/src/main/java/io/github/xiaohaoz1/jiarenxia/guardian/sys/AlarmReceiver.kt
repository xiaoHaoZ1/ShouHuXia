package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import kotlin.concurrent.thread

/** 闹钟回调统一入口：action → Event → engine.handle。directBootAware。 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event: Event = when (intent.action) {
            AlarmScheduler.ACTION_DEADLINE -> Event.DeadlineAlarm
            AlarmScheduler.ACTION_CONFIRM_END -> Event.ConfirmEndAlarm
            AlarmScheduler.ACTION_DAILY_SMS -> Event.DailySmsAlarm
            AlarmScheduler.ACTION_WATCHDOG -> Event.WatchdogAlarm
            AlarmScheduler.ACTION_DRILL -> Event.DrillAlarm
            AlarmScheduler.ACTION_DRILL_CONFIRM_END -> Event.DrillConfirmEndAlarm
            AlarmScheduler.ACTION_CALL_CHECK -> Event.CallCheckAlarm
            AlarmScheduler.ACTION_CALL_FALLBACK -> Event.CallFallbackAlarm
            AlarmScheduler.ACTION_NEXT_ROUND -> Event.NextRoundAlarm
            else -> return
        }
        // 看门狗顺带刷新"未使用应用限制"异步缓存（开启后被收回要能查到）
        if (event == Event.WatchdogAlarm) {
            Permissions.refreshUnusedRestrictions(context)
        }
        val pending = goAsync()
        thread(name = "GuardAlarm") {
            try {
                ServiceLocator.init(context)
                ServiceLocator.engine.handle(event)
            } catch (t: Throwable) {
                Log.e(TAG, "handle $event failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "GuardAlarm"
    }
}
