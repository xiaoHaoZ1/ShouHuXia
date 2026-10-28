package io.github.xiaohaoz1.jiarenxia.guardian.core

/** 引擎唯一入口 GuardEngine.handle 的事件全集。幂等、可重入、可乱序。 */
sealed interface Event {
    /** 检测到“使用了手机”。at 为空取当前时间（UsageStats 找回时带历史时间戳）。 */
    data class Activity(val kind: ActivityKind, val at: Long? = null) : Event

    /** 解锁广播（USER_PRESENT）：开启一段使用会话，等同于 Activity(UNLOCK)。 */
    object UserPresent : Event

    /**
     * 熄屏广播（SCREEN_OFF）：解锁会话中的熄屏记为 LOCK_AFTER_USE；
     * 报警流程中的熄屏只结束使用会话，不算平安。
     */
    object ScreenOff : Event

    /**
     * 会话对账：服务启动 / 看门狗 / 守护页可见时调用，
     * 用 isDeviceInUse 实况校对 inUse 标记，找回漏掉的解锁/熄屏。
     */
    object SyncSession : Event

    /** 无活动阈值到期的闹钟。 */
    object DeadlineAlarm : Event

    /** 响铃确认窗口结束的闹钟。 */
    object ConfirmEndAlarm : Event

    /** 看门狗：自查服务存活与闹钟是否被吞。 */
    object WatchdogAlarm : Event

    /** 每日平安短信到点。 */
    object DailySmsAlarm : Event

    /** 来电响铃（只记时间戳，不算活动——没人接听不代表人没事）。 */
    object CallRinging : Event

    /** 电话摘机（接听/拨出；自拨号除外）。 */
    object CallOffhook : Event

    /** PHONE_STATE 回到 IDLE：通话结束，拨号序列可转查记录。 */
    object CallIdle : Event

    /** 通话记录检查闹钟（拨号序列 step==CHECKING）。 */
    object CallCheckAlarm : Event

    /** 拨号兜底闹钟（拨出后防漏收 IDLE，超时仍在通话则再等）。 */
    object CallFallbackAlarm : Event

    /** 下一轮拨号闹钟（真实报警序列 step==ROUND_WAIT）。 */
    object NextRoundAlarm : Event

    /** 一条短信的发出结果回传（sentIntent；多段短信每段一条）。 */
    data class SmsSent(val kind: SmsKind, val index: Int, val ok: Boolean) : Event

    /** 用户在响铃页/通知上点了“我没事”。 */
    object ImOk : Event

    /** 用户点了“马上拨打”。 */
    object AlertNow : Event

    /** 开机 / 锁定开机 / 包升级：计时基准作废，统一入口做状态修复。 */
    object SystemReset : Event

    /** 系统时间或时区变化：按墙钟与 elapsedRealtime 差值判偏移，再决定是否中止演练。 */
    object ClockShift : Event

    /** 设置页保存了新配置。 */
    object ConfigChanged : Event

    /** 开关守护。 */
    data class EnabledChanged(val enabled: Boolean) : Event

    /** 调试：立即走到期确认流程（不等 deadline）。 */
    object ForceDeadline : Event

    // ---------- 实机演练（独立于守护开关） ----------

    /** 用户发起演练：waitMinutes 为选择的锁屏等待时长。 */
    data class DrillStart(val waitMinutes: Int) : Event

    /** 用户点了"停止测试"（演练页或演练通知按钮），中止本次演练。 */
    object DrillStop : Event

    /** 演练到点闹钟（计划响铃时刻，锁屏时刻 + 等待时长）。 */
    object DrillAlarm : Event

    /** 演练确认窗口结束闹钟：到点自动群发测试短信并依次拨号。 */
    object DrillConfirmEndAlarm : Event

    /** 全屏页上报"已显示"：keyguardLocked 为当时的 KeyguardManager.isKeyguardLocked。 */
    data class DrillScreenShown(val keyguardLocked: Boolean) : Event

    /** 逐位问答：第 index 位联系人收到测试短信了吗 / 接到测试电话了吗。 */
    data class DrillContactAnswer(
        val index: Int,
        val smsReceived: Boolean,
        val callReceived: Boolean,
    ) : Event
}
