package io.github.xiaohaoz1.jiarenxia.guardian.core

/**
 * 拨号序列（真实报警与实机演练共用）：先群发短信，再依次拨打联系人，
 * 每通结束后查通话记录判定是否接通。全部字段持久化，进程被杀后可续。
 */
enum class SeqStep {
    /** 无进行中的序列。 */
    IDLE,
    /** 已进入序列、紧急/测试短信尚未确认发完。 */
    START,
    /** 已拨出，等通话结束（OFFHOOK→IDLE 或兜底闹钟）。 */
    DIALING,
    /** 等通话记录检查（重试中）。 */
    CHECKING,
    /** 本轮无人接通，等下一轮闹钟（仅真实报警）。 */
    ROUND_WAIT,
    /** 序列结束。 */
    DONE,
}

/** 一通通拨号序列的进度（真实报警与演练共用，持久化）。 */
data class CallSeq(
    val step: SeqStep = SeqStep.IDLE,
    val startedAt: Long = 0,
    /** 当前轮次，1 起。 */
    val round: Int = 0,
    /** 当前联系人下标（指 GuardConfig.validContacts）。 */
    val index: Int = 0,
    /** 本通拨号时刻（也作"最后一通拨号时刻"，自摘机保护窗用）。 */
    val dialedAt: Long = 0,
    val dialedNumber: String = "",
    /** 本通拨号是否见过 OFFHOOK（通话真的建立过）。 */
    val offhookSeen: Boolean = false,
    /** 本通已查询通话记录次数。 */
    val checks: Int = 0,
    /** placeCall 成功发起过的联系人（位掩码）。 */
    val placedMask: Int = 0,
    /** 至今接通过的联系人（跨轮位掩码）。 */
    val answeredMask: Int = 0,
    /** 本轮接通过的联系人（位掩码）。 */
    val roundAnsweredMask: Int = 0,
    /** 下一轮闹钟时刻（step==ROUND_WAIT 时有效）。 */
    val nextRoundAt: Long = 0,
) {
    override fun toString(): String =
        "CallSeq(step=" + step + ", round=" + round + ", index=" + index +
            ", dialedAt=" + dialedAt +
            ", dialedNumber=" + maskNumber(dialedNumber) +
            ", offhookSeen=" + offhookSeen + ", checks=" + checks +
            ", placedMask=" + placedMask + ", answeredMask=" + answeredMask +
            ", roundAnsweredMask=" + roundAnsweredMask +
            ", nextRoundAt=" + nextRoundAt + ", startedAt=" + startedAt + ")"
}

/** 通话记录查询结果：接通 / 拨出未接通 / 无记录（可重试） / 查不了（按未接通）。 */
enum class CallLogResult { ANSWERED, NOT_ANSWERED, NOT_FOUND, UNAVAILABLE }

/** 短信种类：群发与 SmsSent 回传都按 kind+index 归因。 */
enum class SmsKind { DAILY, EMERGENCY, ANSWERED, SUMMARY, NO_ANSWER, RESOLVED, DRILL_TEST }

/** 电量快照：pct=-1 表示未知。 */
data class BatteryStatus(val pct: Int, val charging: Boolean)

/** 拨号序列的时序常量。 */
object AlertRules {
    /** 真实报警最多拨打轮数。 */
    const val MAX_ROUNDS = 2
    /** 两轮之间的间隔。 */
    const val ROUND_INTERVAL_MS = 3 * 60_000L
    /** 拨号后兜底闹钟：防漏收 IDLE，通话超此时长后转入查记录。 */
    const val CALL_FALLBACK_MS = 3 * 60_000L
    /** 兜底到点仍在通话中 → 再等。 */
    const val CALL_FALLBACK_RECHECK_MS = 60_000L
    /** IDLE 后延迟首查（等通话记录落库）。 */
    const val CALL_LOG_DELAY_MS = 3_000L
    /** 通话记录查询重试间隔。 */
    const val CALL_LOG_RETRY_MS = 5_000L
    /** 通话记录最多查询次数，查不到按未接通。 */
    const val CALL_LOG_MAX_CHECKS = 4
    /** 查询 since = dialedAt - 容差（容忍记录时间戳略微提前）。 */
    const val CALL_LOG_SINCE_TOLERANCE_MS = 10_000L
}
