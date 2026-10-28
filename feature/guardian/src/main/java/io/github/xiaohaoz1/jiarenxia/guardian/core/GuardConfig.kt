package io.github.xiaohaoz1.jiarenxia.guardian.core

/** 一位紧急联系人：姓名仅展示用（可为空），号码是拨号/短信对象。 */
data class EmergencyContact(
    val name: String = "",
    val number: String = "",
) {
    /** 短信与界面里的称呼：无名时直接展示号码（给家人看的短信可带完整号码）。 */
    fun label(): String = name.ifBlank { number }

    override fun toString(): String =
        "EmergencyContact(name=" + (if (name.isEmpty()) "" else "***") +
            ", number=" + maskNumber(number) + ")"
}

/** 拨号策略：有人接通就停止拨打其他人（默认）/ 继续拨打所有联系人。 */
enum class AnswerPolicy { STOP_ON_ANSWER, CALL_ALL }

/**
 * 守护配置。devFastThresholds 是仅调试包可用的开发快速阈值（30 秒/15 秒），
 * 供开发者真机回归；正式包中即使存储残留旧值也由存储层强制视为关闭。
 * 面向用户的验证手段是实机演练（Drill），不再改变真实守护行为。
 */
data class GuardConfig(
    val enabled: Boolean = false,
    /**
     * 有序紧急联系人（1–3 位）：报警时先群发紧急短信，
     * 再按顺序依次拨号，超过上限的联系人被忽略。
     */
    val contacts: List<EmergencyContact> = emptyList(),
    /** 拨号策略：有人接通是否继续拨打其他联系人。 */
    val answerPolicy: AnswerPolicy = AnswerPolicy.STOP_ON_ANSWER,
    val thresholdMinutes: Int = 480,
    val confirmWindowMinutes: Int = 2,
    val dailySmsEnabled: Boolean = true,
    val dailySmsMinuteOfDay: Int = 9 * 60,
    /**
     * 夜间免打扰：到点的报警若落在 [quietStartMinuteOfDay, quietEndMinuteOfDay)
     * 时段内则顺延到时段结束，期间不响铃不拨号——睡得久的人不会每天凌晨被误报。
     * 代价是时段内真出事要等结束才被发现，所以可关可改。
     */
    val quietEnabled: Boolean = true,
    val quietStartMinuteOfDay: Int = 23 * 60,
    val quietEndMinuteOfDay: Int = 7 * 60,
    /** 开发快速阈值：仅调试包生效（存储层在非调试包强制读为 false）。 */
    val devFastThresholds: Boolean = false,
) {
    /** 参与拨号/短信的有效联系人：号码非空，最多 [MAX_CONTACTS] 位，保序。 */
    val validContacts: List<EmergencyContact>
        get() = contacts.filter { it.number.isNotBlank() }.take(MAX_CONTACTS)

    /** 无活动阈值：开发快速阈值固定 30 秒。 */
    fun effectiveThresholdMillis(): Long =
        if (devFastThresholds) 30_000L else thresholdMinutes.toLong() * 60_000L

    /**
     * 使用中上限：一次解锁会话的最长时长，到点同样响铃确认。
     * 故意不受快速阈值影响——快速阈值只缩短“锁屏后”的倒计时，
     * 防止回归时误以为使用中也会 30 秒就响。
     */
    fun inUseCapMillis(): Long = thresholdMinutes.toLong() * 60_000L

    /** 响铃确认窗口：开发快速阈值固定 15 秒。 */
    fun effectiveConfirmWindowMillis(): Long =
        if (devFastThresholds) 15_000L else confirmWindowMinutes.toLong() * 60_000L

    /** 演练确认窗口：始终用正式配置的确认时长，不受快速阈值影响。 */
    fun drillConfirmWindowMillis(): Long = confirmWindowMinutes.toLong() * 60_000L

    companion object {
        /** 紧急联系人上限。 */
        const val MAX_CONTACTS = 3

        /** 无活动阈值下限：1 小时。 */
        const val MIN_THRESHOLD_MINUTES = 60
        /** 无活动阈值上限：24 小时——手机一整天没人用就该报警，更久失去守护意义。 */
        const val MAX_THRESHOLD_MINUTES = 24 * 60
    }

    /** 号码脱敏，防止 toString 把联系人号码写进日志。 */
    override fun toString(): String =
        "GuardConfig(enabled=" + enabled +
            ", contacts=" + contacts.joinToString(
                prefix = "[", postfix = "]"
            ) { it.toString() } +
            ", answerPolicy=" + answerPolicy +
            ", thresholdMinutes=" + thresholdMinutes +
            ", confirmWindowMinutes=" + confirmWindowMinutes +
            ", dailySmsEnabled=" + dailySmsEnabled +
            ", dailySmsMinuteOfDay=" + dailySmsMinuteOfDay +
            ", quietEnabled=" + quietEnabled +
            ", quietStartMinuteOfDay=" + quietStartMinuteOfDay +
            ", quietEndMinuteOfDay=" + quietEndMinuteOfDay +
            ", devFastThresholds=" + devFastThresholds + ")"
}

/**
 * 电话号码脱敏：保留前 3 位与后 4 位，中间以 **** 代替；
 * 7 位及以下只显示末 2 位。用于日志与 EventLog（ARCHITECTURE §43）。
 */
fun maskNumber(n: String): String {
    val digits = n.trim()
    return when {
        digits.length <= 2 -> digits
        digits.length <= 7 -> "*".repeat(digits.length - 2) + digits.takeLast(2)
        else -> digits.take(3) + "****" + digits.takeLast(4)
    }
}
