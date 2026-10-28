package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import kotlin.concurrent.thread

/**
 * 开机 / 锁定开机（Direct Boot）/ 包升级 → SystemReset（计时基准作废）；
 * 时间或时区变化（含网络自动对时）→ ClockShift（偏移 >60 秒才中止演练）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> Event.SystemReset
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Event.ClockShift
            else -> return
        }
        val pending = goAsync()
        thread(name = "GuardBoot") {
            try {
                ServiceLocator.init(context)
                ServiceLocator.engine.handle(event)
            } catch (t: Throwable) {
                Log.e(TAG, "SystemReset failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "GuardBoot"
    }
}
