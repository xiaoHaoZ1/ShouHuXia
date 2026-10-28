package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import kotlin.concurrent.thread

/** 确认通知按钮：我没事 / 马上拨打 / 演练通知的"停止测试"。 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event: Event = when (intent.action) {
            ACTION_IM_OK -> Event.ImOk
            ACTION_ALERT_NOW -> Event.AlertNow
            ACTION_DRILL_STOP -> Event.DrillStop
            else -> return
        }
        val pending = goAsync()
        thread(name = "GuardNotifAction") {
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
        const val TAG = "GuardNotifAction"
        const val ACTION_IM_OK = "io.github.xiaohaoz1.jiarenxia.guardian.action.IM_OK"
        const val ACTION_ALERT_NOW = "io.github.xiaohaoz1.jiarenxia.guardian.action.ALERT_NOW"
        const val ACTION_DRILL_STOP =
            "io.github.xiaohaoz1.jiarenxia.guardian.action.DRILL_STOP"
    }
}
