package io.github.xiaohaoz1.jiarenxia.guardian

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.xiaohaoz1.jiarenxia.guardian.sys.GuardService
import io.github.xiaohaoz1.jiarenxia.guardian.sys.Notifications
import io.github.xiaohaoz1.jiarenxia.guardian.sys.ServiceLocator

/**
 * 守护模块入口（替代原独立 App 的 Application.onCreate）。
 * 由主应用 Application 在 onCreate 最前面调用 [init]；
 * 只用 device-protected 存储与系统服务，不碰 Room/DataStore/WorkManager。
 */
object GuardianModule {

    fun init(context: Context) {
        ServiceLocator.init(context)
        Notifications.ensureChannels(context)
        // 进程被拉起时若已开启守护或演练进行中，确保前台服务在跑
        try {
            if (ServiceLocator.store.configFlow.value.enabled ||
                ServiceLocator.store.stateFlow.value.drill.active) {
                ContextCompat.startForegroundService(
                    context, Intent(context, GuardService::class.java)
                )
            }
        } catch (t: Throwable) {
            Log.w("GuardianModule", "ensure service on init", t)
        }
    }
}
