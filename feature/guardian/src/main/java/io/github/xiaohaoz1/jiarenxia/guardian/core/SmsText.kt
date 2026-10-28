package io.github.xiaohaoz1.jiarenxia.guardian.core

/**
 * 短信文案（纯 Kotlin，可单测）。品牌前缀“【守护侠】”，演练短信用“【守护侠·测试】”。
 * 短信是发给家人看的，姓名/号码不打码（EmergencyContact.label()）；
 * 日志里仍一律走 maskNumber。
 */
object SmsText {

    /**
     * 每日平安短信："【守护侠】一切正常。最后一次使用手机：MM-dd HH:mm（X小时前）。电量NN%…"
     * inUse（正在使用中）时最后使用时间写作“正在使用”。
     */
    fun daily(
        lastActivityAt: Long,
        now: Long,
        batteryPct: Int,
        charging: Boolean,
        inUse: Boolean,
    ): String {
        val lastPart = if (inUse) {
            "最后一次使用手机：正在使用。"
        } else {
            val lastStr =
                if (lastActivityAt > 0) TimeRules.formatDateTime(lastActivityAt) else "无记录"
            val ago = if (lastActivityAt > 0)
                TimeRules.formatDuration(now - lastActivityAt) + "前" else "未知"
            "最后一次使用手机：" + lastStr + "（" + ago + "）。"
        }
        val chg = if (charging) "，充电中" else ""
        return "【守护侠】一切正常。" + lastPart +
            "电量" + batteryPct + "%" + chg +
            "。若长时间未收到本短信，请检查手机是否关机或APP被清理。"
    }

    /**
     * 紧急短信（拨号序列开始前群发）：
     * 说明无人使用时长、最后使用时刻与电量，并预告"正在依次给你们打电话"。
     */
    fun emergency(
        lastActivityAt: Long,
        now: Long,
        batteryPct: Int,
        charging: Boolean,
    ): String {
        val idle = if (lastActivityAt > 0)
            TimeRules.formatDuration(now - lastActivityAt) else "未知时长"
        val last = if (lastActivityAt > 0)
            TimeRules.formatDateTime(lastActivityAt) else "无记录"
        val bat = if (batteryPct >= 0)
            "电量" + batteryPct + "%" + (if (charging) "，充电中" else "") else "电量未知"
        return "【守护侠】紧急：这是守护侠自动发出的提醒。手机已" + idle +
            "无人使用（最后使用：" + last + "），" + bat +
            "。正在依次给你们打电话，接通后若没人说话，" +
            "说明对方可能无法说话，请立即上门或联系附近的人。"
    }

    /** 有人接通（STOP_ON_ANSWER 时不再拨打其他人）。 */
    fun answered(name: String): String =
        "【守护侠】报警电话已由 " + name + " 接听，不再拨打其他联系人。"

    /** 全部拨完（CALL_ALL）：接听者按顺序列出。 */
    fun summary(names: List<String>): String =
        "【守护侠】报警电话已全部拨完，接听的有：" + names.joinToString("、") + "。"

    /** 多轮拨打均无人接通。 */
    fun noAnswer(): String =
        "【守护侠】多次拨打均无人接听，请尽快设法联系或上门查看。"

    /** 解除：手机恢复使用（可能是误报）。 */
    fun resolved(): String =
        "【守护侠】已解除：手机已恢复使用（可能是误报）。"

    /** 演练测试短信（绝不与真实报警文案混淆）。 */
    fun drillTest(): String =
        "【守护侠·测试】这是一次演练，不是真实报警。" +
            "稍后会给你打一通测试电话，请接听。"
}
