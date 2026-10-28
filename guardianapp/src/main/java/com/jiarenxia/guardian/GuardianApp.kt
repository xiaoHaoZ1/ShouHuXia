package com.jiarenxia.guardian

import android.app.Application
import io.github.xiaohaoz1.jiarenxia.guardian.GuardianModule

/**
 * 守护侠应用入口。
 * 只初始化守护模块：GuardianModule.init 内部只用 device-protected 存储与
 * 系统服务，重启后未解锁也能响铃、拨号（Direct Boot 安全）。
 * 守护侠没有 Room/DataStore/WorkManager，因此无需 StartupGate 门控。
 */
class GuardianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GuardianModule.init(this)
    }
}
