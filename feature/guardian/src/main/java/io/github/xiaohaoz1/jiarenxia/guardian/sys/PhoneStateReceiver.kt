package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import kotlin.concurrent.thread

/** 摘机/空闲都上报：摘机算活动，IDLE 用于判断拨出电话结束；自拨号由引擎过滤。 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val event = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> Event.CallOffhook
            TelephonyManager.EXTRA_STATE_RINGING -> Event.CallRinging
            TelephonyManager.EXTRA_STATE_IDLE -> Event.CallIdle
            else -> return
        }
        val pending = goAsync()
        thread(name = "GuardPhone") {
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
        const val TAG = "GuardPhone"
    }
}
