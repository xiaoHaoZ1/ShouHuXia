package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 前台守护服务（FGS specialUse）：动态注册屏幕亮灭/解锁广播
 * （这些广播不能静态注册），直接转成引擎事件——使用会话判定在引擎内。
 * 进程被杀由看门狗/闹钟兜底拉起。
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT ->
                    ServiceLocator.engine.handle(Event.UserPresent)
                Intent.ACTION_SCREEN_OFF ->
                    ServiceLocator.engine.handle(Event.ScreenOff)
                // ACTION_SCREEN_ON：只亮屏未解锁不算活动（来消息亮屏），忽略
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // 旧版本曾用 tag 发过一条重复的状态通知，先清掉
        Notifications.cancelLegacyStatus(this)
        startForegroundGuarded()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 配置/状态一变就刷新常驻通知；
        // 存活条件：守护开启 或 演练进行中（演练需要收锁屏/解锁广播，也是被测对象本身）
        scope.launch {
            combine(
                ServiceLocator.store.configFlow,
                ServiceLocator.store.stateFlow,
            ) { c, s -> c to s }.collect { (config, state) ->
                if (!config.enabled && !state.drill.active) {
                    stopSelf()
                } else {
                    refreshStatusNotification()
                }
            }
        }
        // 服务中途被杀重启可能漏掉解锁/熄屏广播，启动时对账一次
        ServiceLocator.engine.handle(Event.SyncSession)
        // 顺带刷新"未使用应用限制"异步缓存（开启后系统可能收回豁免）
        Permissions.refreshUnusedRestrictions(this)
        Log.d(TAG, "GuardService created")
    }

    private fun startForegroundGuarded() {
        val notification = Notifications.statusNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Notifications.ID_STATUS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ID_STATUS, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceLocator.init(this)
        val s = ServiceLocator.store.stateFlow.value
        if (!ServiceLocator.store.configFlow.value.enabled && !s.drill.active) {
            Log.d(TAG, "守护未开启且无演练，stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun refreshStatusNotification() {
        try {
            Notifications.refreshStatus(this)
        } catch (t: Throwable) {
            Log.w(TAG, "refreshStatusNotification", t)
        }
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        try {
            unregisterReceiver(screenReceiver)
        } catch (t: Throwable) {
            // ignore
        }
        scope.cancel()
        Log.d(TAG, "GuardService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "GuardService"
    }
}
