package io.github.xiaohaoz1.jiarenxia.guardian.core

/**
 * 状态机相位。DISABLED 不是相位——是否开启由 GuardConfig.enabled 表达，
 * 关闭时保留相位便于 UI 展示与恢复。
 */
enum class Phase {
    MONITORING,
    CONFIRMING,
    ALERTING,
    ALERTED,
}

/**
 * 持久化状态（墙钟毫秒）。selfDialing 是“自己拨出的报警电话不算活动”的双保险之一。
 * drill/lastDrillResult/qualifiedPass 是实机演练状态与结果：演练独立于守护开关，
 * 守护未开启时也能进行（“演练通过”是开启的前提）。
 */
data class GuardState(
    val phase: Phase = Phase.MONITORING,
    val lastActivityAt: Long = 0L,
    val lastActivityKind: ActivityKind? = null,
    val deadlineAt: Long = 0L,
    val confirmEndAt: Long = 0L,
    val nextDailySmsAt: Long = 0L,
    val alertedAt: Long = 0L,
    val selfDialing: Boolean = false,
    /** 最近一次来电响铃时刻，用于区分“接听来电”和“我们自己拨出的报警电话”。 */
    val lastRingAt: Long = 0L,
    /** 引擎认为手机正处于解锁使用会话中：此时不计倒计时，走使用中上限。 */
    val inUse: Boolean = false,
    /** 最近一次“使用后熄屏”的时刻（仅展示用）。 */
    val lastLockAt: Long = 0L,
    /** 进行中的实机演练（无演练时各字段为默认值）。 */
    val drill: Drill = Drill(),
    /** 最近一次演练的结果（任何结局），仅用于界面展示。 */
    val lastDrillResult: DrillResult? = null,
    /**
     * 最近一次"通过且等待 ≥30 分钟"的演练记录——开启门槛与异常判定的凭据。
     * 更新规则：PASSED 且等待达标则写入；FAILED（任何时长）清空；ABORTED 不动。
     */
    val qualifiedPass: DrillResult? = null,
    /** 真实报警拨号序列进度（无序列时为默认值）。 */
    val alertSeq: CallSeq = CallSeq(),
    /** 报警短信群发标记位（SMS_*_SENT），解除报警时清零。 */
    val alertSmsFlags: Int = 0,
) {
    companion object {
        /** 紧急短信已群发（先于任何拨号置位）。 */
        const val SMS_EMERGENCY_SENT = 1
        /** 结果短信（接听/全部拨完/无人接听）已群发。 */
        const val SMS_FINAL_SENT = 2
        /** 解除短信已群发。 */
        const val SMS_RESOLVED_SENT = 4
    }
}
