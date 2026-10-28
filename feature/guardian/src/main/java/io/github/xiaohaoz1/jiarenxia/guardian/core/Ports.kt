package io.github.xiaohaoz1.jiarenxia.guardian.core

/**
 * 引擎对平台（Android 层）的全部依赖。实现位于 sys/，单测用 FakePorts。
 */
interface Ports {

    /** 用户可见定时（setAlarmClock）：无活动到期。 */
    fun scheduleDeadline(at: Long)

    /** 用户可见定时：响铃确认窗口结束。 */
    fun scheduleConfirmEnd(at: Long)

    /** 用户可见定时：每日平安短信。 */
    fun scheduleDailySms(at: Long)

    /** 看门狗自查（setExactAndAllowWhileIdle）。 */
    fun scheduleWatchdog(delayMillis: Long)

    /** 取消确认阶段相关闹钟与确认通知。 */
    fun cancelConfirmAndAlert()

    /**
     * 取消所有守护闹钟（关守护时用），包含拨号序列的三个闹钟
     * （callCheck/callFallback/nextRound，同 cancelCallAlarms）。
     * 不含演练闹钟（演练闹钟用独立 requestCode，由 cancelDrill 负责）。
     */
    fun cancelAll()

    // ---------- 实机演练 ----------

    /** 用户可见定时：演练到点（锁屏时刻 + 等待时长）。 */
    fun scheduleDrillAlarm(at: Long)

    /** 用户可见定时：演练确认窗口结束（自动拨号）。 */
    fun scheduleDrillConfirmEnd(at: Long)

    /** 取消演练闹钟与演练确认通知（响铃由 stopRinging 单独负责；不管拨号序列闹钟）。 */
    fun cancelDrill()

    /** 演练响铃+震动+唤醒锁+演练专用全屏通知（文案标明"这是守护测试"）。 */
    fun startDrillRinging(confirmEndAt: Long)

    /** 当前应用 versionCode（演练记录有效性判定）。 */
    fun appVersionCode(): Long

    /** 系统指纹 Build.FINGERPRINT（演练记录与手动确认的有效性判定）。 */
    fun buildFingerprint(): String

    /** 开始响铃+震动+唤醒锁+全屏通知，confirmEndAt 用于界面倒计时。 */
    fun startConfirmRinging(confirmEndAt: Long)

    /** 停止响铃/震动并释放资源。 */
    fun stopRinging()

    /** 展示“需要确认”通知（fullScreenIntent → PreAlarmActivity）。 */
    fun showConfirmNotification(confirmEndAt: Long)

    /** 展示“已拨打紧急电话”结果通知。 */
    fun showAlertedNotification(alertedAt: Long)

    /** 拨打紧急电话（免提 extra），返回是否成功发起。 */
    fun placeCall(number: String): Boolean

    /** 电量快照（短信文案用；取不到时 pct=-1）。 */
    fun batteryStatus(): BatteryStatus

    /**
     * 提交发送一条短信给第 index 位联系人，返回是否成功提交
     * （无权限/异常返回 false）。真正发出结果由平台经 sentIntent
     * 异步回传 Event.SmsSent（kind+index 归因）。
     */
    fun sendSms(number: String, body: String, kind: SmsKind, index: Int): Boolean

    /**
     * 查通话记录：只查 since 之后、本号码的拨出记录，取最近一条——
     * DURATION>0 → ANSWERED，否则 NOT_ANSWERED；没有 → NOT_FOUND；
     * 无权限/异常 → UNAVAILABLE。
     */
    fun queryOutgoingCall(number: String, since: Long): CallLogResult

    /** 此刻是否仍在通话中（兜底闹钟判定 IDLE 广播是否丢失）。 */
    fun isInCall(): Boolean

    // ---------- 拨号序列闹钟（真实报警与演练共用，cancelDrill 不管） ----------

    /** 通话记录检查闹钟。 */
    fun scheduleCallCheck(at: Long)

    /** 拨号兜底闹钟（防漏收 IDLE）。 */
    fun scheduleCallFallback(at: Long)

    /** 下一轮拨号闹钟。 */
    fun scheduleNextRound(at: Long)

    /** 取消拨号序列的三个闹钟（check/fallback/nextRound）。 */
    fun cancelCallAlarms()

    /** 确保前台守护服务在跑。 */
    fun ensureServiceRunning()

    /** 停止前台守护服务及其常驻通知（关闭守护时调用）。 */
    fun stopService()

    /** 设备此刻是否正在使用：屏幕亮着且未处于锁屏。异常时按 false 处理。 */
    fun isDeviceInUse(): Boolean

    /** 开机至今的单调毫秒（elapsedRealtime）：与墙钟差值用于对时偏移判定。 */
    fun elapsedRealtime(): Long

    /**
     * UsageStats 兜底：返回 since 之后最后一次 KEYGUARD_HIDDEN 的时间戳；
     * 无权限/无事件/低版本返回 null。
     */
    fun reconcileMissedUnlockSince(since: Long): Long?

    /** 统一日志入口（Logcat + EventLog 落盘）。 */
    fun log(tag: String, msg: String)
}
