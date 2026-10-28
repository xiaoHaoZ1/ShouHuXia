package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * 使用情况访问兜底：服务被杀漏掉解锁广播时，
 * 到期前从系统 UsageStats 找回 KEYGUARD_HIDDEN（锁屏消失=解锁）。
 */
class UsageStatsReconcile(private val context: Context) {

    fun hasAccess(): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * since 之后最后一次解锁时间戳；无权限/低版本/无事件 → null。
     * KEYGUARD_HIDDEN 事件需要 API29+。
     */
    fun missedUnlockSince(since: Long): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (!hasAccess()) return null
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val events = usm.queryEvents(since + 1, System.currentTimeMillis())
            val ev = UsageEvents.Event()
            var max = -1L
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.KEYGUARD_HIDDEN && ev.timeStamp > max) {
                    max = ev.timeStamp
                }
            }
            if (max > since) max else null
        } catch (t: Throwable) {
            Log.w(TAG, "missedUnlockSince failed", t)
            null
        }
    }

    companion object {
        const val TAG = "GuardUsageStats"
    }
}
