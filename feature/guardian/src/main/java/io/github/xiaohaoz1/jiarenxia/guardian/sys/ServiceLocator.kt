package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.content.Context
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardEngine
import io.github.xiaohaoz1.jiarenxia.guardian.core.SystemClock
import io.github.xiaohaoz1.jiarenxia.guardian.data.EventLog
import io.github.xiaohaoz1.jiarenxia.guardian.data.GuardStore

/** 手工服务定位单例（进程内唯一）。所有组件经它拿到 engine/ports。 */
object ServiceLocator {

    @Volatile
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    val store: GuardStore by lazy { GuardStore(app) }
    val eventLog: EventLog by lazy { EventLog(app) }
    val scheduler: AlarmScheduler by lazy { AlarmScheduler(app) }
    val alert: AlertController by lazy { AlertController(app) }
    val telecom: TelecomCaller by lazy { TelecomCaller(app) }
    val sms: SmsReporter by lazy { SmsReporter(app) }
    val callLog: CallLogReader by lazy { CallLogReader(app) }
    val usageStats: UsageStatsReconcile by lazy { UsageStatsReconcile(app) }
    val ports: AndroidPorts by lazy { AndroidPorts() }
    val engine: GuardEngine by lazy { GuardEngine(store, SystemClock, ports) }
}
