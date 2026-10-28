package io.github.xiaohaoz1.jiarenxia.guardian.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 守护 UI 用纯函数（可单测）。
 */
object GuardianFormat {

    /** "今天 19:05" / "昨天 08:12" / "10月1日 08:12"。 */
    fun formatRelativeDayTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()
        val n = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime()
        val hm = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) {
            n.toLocalDate() -> "今天 $hm"
            n.toLocalDate().minusDays(1) -> "昨天 $hm"
            else -> "${t.monthValue}月${t.dayOfMonth}日 $hm"
        }
    }

    /** 相对时长："3 小时前" / "25 分钟前" / "刚刚"。 */
    fun formatAgo(at: Long, now: Long): String {
        val d = (now - at).coerceAtLeast(0)
        return when {
            d < 60_000 -> "刚刚"
            d < 3_600_000 -> "${d / 60_000} 分钟前"
            d < 86_400_000 -> "${d / 3_600_000} 小时前"
            else -> "${d / 86_400_000} 天前"
        }
    }

    /** 倒计时："4小时55分" / "3分20秒" / "40秒"。 */
    fun formatCountdown(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${sec}秒"
            else -> "${sec}秒"
        }
    }

    /**
     * 手动输入号码校验：只允许数字/+/-/空格，去掉分隔符后至少 5 位数字。
     */
    fun isValidContactNumber(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return false
        if (!t.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) return false
        return t.count { it.isDigit() } >= 5
    }

    // ---------- 报警序列展示 ----------

    /** 报警序列中每位联系人的当前状态（首页报警卡与演练进度共用）。 */
    enum class SeqContactState { WAITING, DIALING, ANSWERED, NO_ANSWER }

    /**
     * 计算前 n 位联系人的状态行（按位置判定，不看 placedMask——
     * placedMask 跨轮累计，会让第 2 轮把第 1 轮拨过的人误显为"未接通"）：
     * 接通过（跨轮累计）→ ANSWERED；当前正在拨/查 → DIALING；
     * 本轮排在本位之前 → NO_ANSWER；其余 → WAITING。
     * DONE 下 index 之后的位是"未拨打"（UI 层对 WAITING 按 step 换文案）。
     */
    fun seqContactStates(seq: CallSeq, n: Int): List<SeqContactState> =
        List(n) { i ->
            val answered = seq.answeredMask and (1 shl i) != 0
            when {
                answered -> SeqContactState.ANSWERED
                seq.step == SeqStep.IDLE || seq.step == SeqStep.START ->
                    SeqContactState.WAITING
                seq.step == SeqStep.ROUND_WAIT -> SeqContactState.NO_ANSWER
                seq.step == SeqStep.DONE ->
                    if (i <= seq.index) SeqContactState.NO_ANSWER
                    else SeqContactState.WAITING
                // DIALING/CHECKING：当前位在拨，之前位本轮未接
                i == seq.index -> SeqContactState.DIALING
                i < seq.index -> SeqContactState.NO_ANSWER
                else -> SeqContactState.WAITING
            }
        }

    /**
     * 报警结果的一句话描述："张三已接听" / "张三、李四已接听" /
     * "多次拨打无人接听，已发短信"。接听者按联系人顺序列名。
     */
    fun alertOutcomeText(
        state: GuardState, contacts: List<EmergencyContact>,
    ): String {
        val names = contacts.mapIndexedNotNull { i, c ->
            if (state.alertSeq.answeredMask and (1 shl i) != 0) c.label() else null
        }
        return if (names.isEmpty()) "多次拨打无人接听，已发短信"
        else names.joinToString("、") + "已接听"
    }

    /** 完整报警结果文案："已于 HH:mm 完成报警：<结果>"。 */
    fun alertResultText(
        state: GuardState, contacts: List<EmergencyContact>,
    ): String = "已于 " +
        (if (state.alertedAt > 0) TimeRules.formatClock(state.alertedAt) else "--") +
        " 完成报警：" + alertOutcomeText(state, contacts)

    /** 守护状态通知文案：前台服务通知与刷新共用。演练进行中优先显示"正在测试"。 */
    fun statusText(
        state: GuardState, contacts: List<EmergencyContact> = emptyList(),
    ): String = when {
        state.drill.phase == DrillPhase.RINGING -> "守护测试：正在响铃确认"
        state.drill.phase == DrillPhase.CALLING -> "守护测试：正在发测试短信并依次拨号"
        state.drill.phase == DrillPhase.ASK_ANSWER -> "守护测试：等待逐位确认"
        state.drill.active -> "守护测试进行中（请锁屏后放着别动）"
        else -> when (state.phase) {
            Phase.MONITORING -> when {
                state.inUse -> "守护中｜正在使用"
                state.lastActivityAt > 0 ->
                    "守护中｜上次使用 " + TimeRules.formatClock(state.lastActivityAt)
                else -> "守护中"
            }
            Phase.CONFIRMING -> "正在等待确认"
            Phase.ALERTING -> when (state.alertSeq.step) {
                SeqStep.DIALING, SeqStep.CHECKING -> {
                    val seq = state.alertSeq
                    val label = contacts.getOrNull(seq.index)?.label()
                    "正在报警：第 " + seq.round + " / " + AlertRules.MAX_ROUNDS +
                        " 轮，正在拨打 " + (label ?: "第 ${seq.index + 1} 位")
                }
                SeqStep.ROUND_WAIT ->
                    "正在报警：本轮无人接听，" +
                        TimeRules.formatClock(state.alertSeq.nextRoundAt) +
                        " 开始第 ${state.alertSeq.round + 1} 轮"
                SeqStep.START -> "正在报警：正在发紧急短信"
                else -> "正在报警"
            }
            Phase.ALERTED -> alertResultText(state, contacts)
        }
    }
}
