package io.github.xiaohaoz1.jiarenxia.guardian.core

/**
 * 实机演练（Drill）：替换全局测试模式的"在这台手机上真实走一遍"。
 * 复用真实链路（AlarmManager → 响铃 → 全屏确认页 → 群发测试短信 → 依次拨号），
 * 演练只发"【守护侠·测试】"短信，绝不发真实报警文案。
 * 演练状态持久化在 GuardState.drill / lastDrillResult / qualifiedPass，
 * 进程被杀后可恢复判定。
 */

/** 演练相位。NONE 表示没有进行中的演练（上次结果存于 GuardState.lastDrillResult）。 */
enum class DrillPhase {
    NONE,
    /** 已开始，等待用户锁屏（SCREEN_OFF）。 */
    WAIT_LOCK,
    /** 已锁屏计时，等到点闹钟。 */
    WAITING,
    /** 到点，响铃 + 全屏确认页展示中。 */
    RINGING,
    /** 已发测试短信，正在依次拨号/查记录。 */
    CALLING,
    /** 拨号完成，逐位询问"收到短信了吗 / 接到电话了吗"。 */
    ASK_ANSWER,
}

/** 进行中的演练运行时状态（持久化）。 */
data class Drill(
    val phase: DrillPhase = DrillPhase.NONE,
    /** 用户选择的锁屏等待分钟数。 */
    val waitMinutes: Int = 0,
    /** 用户点"开始演练"的时刻。 */
    val startedAt: Long = 0,
    /** SCREEN_OFF 时刻（开始时已锁屏则取 startedAt）。 */
    val lockedAt: Long = 0,
    /** 计划响铃时刻 = lockedAt + waitMinutes（墙钟）。 */
    val plannedAt: Long = 0,
    /** 计划时刻换算到 elapsedRealtime 坐标系，用于对时偏移判定。 */
    val plannedAtElapsed: Long = 0,
    /** 演练闹钟实际触发时刻。 */
    val firedAt: Long = 0,
    /** 全屏页上报"已显示"的时刻与当时是否锁屏。 */
    val screenShownAt: Long = 0,
    val screenShownLocked: Boolean = false,
    /** 演练确认窗口结束（自动拨号）时刻。 */
    val confirmEndAt: Long = 0,
    /** 开始时的联系人快照 key：演练期间改联系人 → 中止重来。 */
    val contactsKey: String = "",
    /** 拨号序列进度（CALLING 阶段复用真实报警同一套状态机）。 */
    val calls: CallSeq = CallSeq(),
    /** 测试短信群发是否已执行过（保证进程重启后不重复发）。 */
    val smsSentFlag: Boolean = false,
    /** 测试短信提交发送成功的联系人（位掩码）。 */
    val smsSubmittedMask: Int = 0,
    /** 测试短信回传 ok 的联系人（位掩码；多段短信任一失败即置 fail 位）。 */
    val smsOkMask: Int = 0,
    val smsFailMask: Int = 0,
    /** 逐位问答进度：当前正在问第几位联系人。 */
    val askIndex: Int = 0,
    /** 用户答"收到测试短信"的联系人（位掩码）。 */
    val smsConfirmedMask: Int = 0,
    /** 用户答"接到测试电话"的联系人（位掩码）。 */
    val callConfirmedMask: Int = 0,
) {
    val active: Boolean get() = phase != DrillPhase.NONE
}

enum class DrillOutcome { PASSED, FAILED, ABORTED }

/** 一位联系人的演练逐项结果：全部 true 该位才算通过。 */
data class DrillContactResult(
    val name: String,
    val number: String,
    /** 测试短信发出（已提交且 sentIntent 回传 ok）。 */
    val smsSent: Boolean,
    /** 测试电话接通（通话记录判定）。 */
    val callAnswered: Boolean,
    /** 用户答"收到测试短信"。 */
    val smsConfirmed: Boolean,
    /** 用户答"接到测试电话"。 */
    val callConfirmed: Boolean,
) {
    val ok: Boolean get() = smsSent && callAnswered && smsConfirmed && callConfirmed

    override fun toString(): String =
        "DrillContactResult(name=" + (if (name.isEmpty()) "" else "***") +
            ", number=" + maskNumber(number) +
            ", smsSent=" + smsSent + ", callAnswered=" + callAnswered +
            ", smsConfirmed=" + smsConfirmed + ", callConfirmed=" + callConfirmed + ")"
}

/** 上次演练结果（持久化；开启门槛与首页/设置页展示用）。 */
data class DrillResult(
    val outcome: DrillOutcome,
    /** 失败/中止原因或通过摘要（UI 直接展示）。 */
    val reason: String,
    val waitMinutes: Int,
    /** 计划/实际响铃时刻与延迟（未触发时 firedAt=0、delay=-1）。 */
    val plannedAt: Long,
    val firedAt: Long,
    val delayMillis: Long,
    /** 逐项检查：全部 true 才算通过。 */
    val onTime: Boolean,
    val fullscreenOk: Boolean,
    /** 逐位联系人的四项结果（中止/未到问答时可为空列表）。 */
    val contactResults: List<DrillContactResult>,
    val finishedAt: Long,
    /** 记录时的环境指纹：任一变化 → 记录失效，需重新演练。 */
    val versionCode: Long,
    val fingerprint: String,
    /** 记录时的联系人快照 key（DrillRules.contactsKey）：联系人变了记录失效。 */
    val contactsKey: String,
    /** 演练记录结构版本：旧结构（无联系人逐项/短信验证）一律判失效。 */
    val schema: Int = DrillRules.SCHEMA,
)

/** 演练判定与开启门槛的纯函数（可单测）。 */
object DrillRules {

    /** 可选等待时长（分钟）：<30 只作快速检查，不计入开启条件。 */
    val WAIT_OPTIONS_MIN = listOf(1, 10, 30, 60, 120)

    /** 计入开启条件的最短等待。 */
    const val MIN_QUALIFY_MINUTES = 30

    /** 触发延迟上限（实际 − 计划）。 */
    const val ON_TIME_TOLERANCE_MS = 60_000L

    /** 全屏页须在触发后此时限内、且处于锁屏时显示。 */
    const val FULLSCREEN_WINDOW_MS = 30_000L

    /**
     * 确认结束闹钟逾期仍允许拨号的宽限：进程被强停后闹钟丢失，
     * 用户数小时后才打开 App 时绝不能突然拨号——逾期超过宽限判失败。
     */
    const val CONFIRM_END_GRACE_MS = 60_000L

    /**
     * 演练记录结构版本：v2 支持多位联系人并验证测试短信。
     * 老记录（schema<2 或缺省读为 1）一律失效——老演练没验证短信。
     */
    const val SCHEMA = 2

    /**
     * 联系人快照 key：只看顺序与号码（号码只保留数字和开头的 '+'），
     * 改名不失效；增/删/改号码/换顺序都失效。
     */
    fun contactsKey(contacts: List<EmergencyContact>): String =
        contacts.joinToString("|") { c ->
            val t = c.number.trim()
            (if (t.startsWith("+")) "+" else "") + t.filter(Char::isDigit)
        }

    /** 演练记录是否满足开启门槛：通过 + 等待达标 + 环境/联系人都未变。 */
    fun isRecordValid(
        r: DrillResult?,
        versionCode: Long,
        fingerprint: String,
        contactsKey: String,
    ): Boolean = invalidReasons(r, versionCode, fingerprint, contactsKey).isEmpty()

    /** 记录不满足开启条件的全部原因（为空 = 有效）。供引擎门槛与界面"已失效"提示共用。 */
    fun invalidReasons(
        r: DrillResult?,
        versionCode: Long,
        fingerprint: String,
        contactsKey: String,
    ): List<String> {
        if (r == null) return listOf("尚未完成实机演练")
        if (r.outcome != DrillOutcome.PASSED) {
            return listOf(if (r.outcome == DrillOutcome.ABORTED) "上次演练中止" else "上次演练未通过")
        }
        val reasons = mutableListOf<String>()
        if (r.waitMinutes < MIN_QUALIFY_MINUTES) reasons += "上次测试等待不足 ${MIN_QUALIFY_MINUTES} 分钟"
        if (r.schema < SCHEMA) reasons += "演练规则已更新（支持多位联系人），请重新测试"
        if (r.versionCode != versionCode) reasons += "App 已升级"
        if (r.fingerprint != fingerprint) reasons += "系统已升级"
        if (r.contactsKey != contactsKey) reasons += "紧急联系人已变更"
        return reasons
    }
}
