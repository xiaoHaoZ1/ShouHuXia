package io.github.xiaohaoz1.jiarenxia.guardian.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import io.github.xiaohaoz1.jiarenxia.guardian.core.ActivityKind
import io.github.xiaohaoz1.jiarenxia.guardian.core.AnswerPolicy
import io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq
import io.github.xiaohaoz1.jiarenxia.guardian.core.Drill
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillContactResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillOutcome
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillPhase
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardEngine
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.core.Phase
import io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 持久化：device-protected 存储（重启未解锁也能读写）。
 * 写操作全部 @Synchronized，save 后刷新 Flow 给 UI。
 */
class GuardStore(context: Context) : GuardEngine.Store {

    /** 仅调试包可用"开发快速阈值"：正式包即使存储残留 test_mode=true 也视为关闭。 */
    private val debuggable: Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val prefs: SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("guardian", Context.MODE_PRIVATE)

    val configFlow = MutableStateFlow(loadConfig())
    val stateFlow = MutableStateFlow(loadState())

    // ---------- config ----------

    @Synchronized
    override fun loadConfig(): GuardConfig {
        // 旧版本只有一位联系人：contacts_count 不存在且旧号码非空白 → 迁移为第 1 位
        val contacts = if (prefs.contains(K_CONTACTS_COUNT)) {
            val n = prefs.getInt(K_CONTACTS_COUNT, 0).coerceIn(0, GuardConfig.MAX_CONTACTS)
            (0 until n).map { i ->
                EmergencyContact(
                    name = prefs.getString("${K_CONTACT_PREFIX}${i}_name", "") ?: "",
                    number = prefs.getString("${K_CONTACT_PREFIX}${i}_number", "") ?: "",
                )
            }
        } else {
            val legacyNumber = prefs.getString(K_NUMBER, "") ?: ""
            if (legacyNumber.isBlank()) {
                emptyList()
            } else {
                listOf(
                    EmergencyContact(
                        name = prefs.getString(K_NAME, "") ?: "",
                        number = legacyNumber,
                    )
                )
            }
        }
        return GuardConfig(
            enabled = prefs.getBoolean(K_ENABLED, false),
            contacts = contacts,
            answerPolicy = runCatching {
                AnswerPolicy.valueOf(
                    prefs.getString(K_ANSWER_POLICY, AnswerPolicy.STOP_ON_ANSWER.name)
                        ?: AnswerPolicy.STOP_ON_ANSWER.name
                )
            }.getOrDefault(AnswerPolicy.STOP_ON_ANSWER),
            // 已存的超标值收敛到上限内（旧版本曾允许设到 72 小时）
            thresholdMinutes = prefs.getInt(K_THRESHOLD_MIN, 480)
                .coerceIn(GuardConfig.MIN_THRESHOLD_MINUTES,
                    GuardConfig.MAX_THRESHOLD_MINUTES),
            confirmWindowMinutes = prefs.getInt(K_CONFIRM_MIN, 2),
            dailySmsEnabled = prefs.getBoolean(K_DAILY_ENABLED, true),
            dailySmsMinuteOfDay = prefs.getInt(K_DAILY_MINUTE, 9 * 60),
            quietEnabled = prefs.getBoolean(K_QUIET_ENABLED, true),
            quietStartMinuteOfDay = prefs.getInt(K_QUIET_START, 23 * 60),
            quietEndMinuteOfDay = prefs.getInt(K_QUIET_END, 7 * 60),
            // 旧键 test_mode 迁移为 devFastThresholds；正式包强制读为关闭（老用户迁移）
            devFastThresholds = debuggable && prefs.getBoolean(K_TEST_MODE, false),
        )
    }

    @Synchronized
    override fun saveConfig(c: GuardConfig) {
        prefs.edit()
            .putBoolean(K_ENABLED, c.enabled)
            .putInt(K_CONTACTS_COUNT, c.contacts.size)
            .apply {
                c.contacts.take(GuardConfig.MAX_CONTACTS).forEachIndexed { i, ct ->
                    putString("${K_CONTACT_PREFIX}${i}_name", ct.name)
                    putString("${K_CONTACT_PREFIX}${i}_number", ct.number)
                }
            }
            // 新键写好后删掉旧单联系人键，避免下次加载误判"未迁移"
            .remove(K_NAME).remove(K_NUMBER)
            .putString(K_ANSWER_POLICY, c.answerPolicy.name)
            .putInt(K_THRESHOLD_MIN, c.thresholdMinutes)
            .putInt(K_CONFIRM_MIN, c.confirmWindowMinutes)
            .putBoolean(K_DAILY_ENABLED, c.dailySmsEnabled)
            .putInt(K_DAILY_MINUTE, c.dailySmsMinuteOfDay)
            .putBoolean(K_QUIET_ENABLED, c.quietEnabled)
            .putInt(K_QUIET_START, c.quietStartMinuteOfDay)
            .putInt(K_QUIET_END, c.quietEndMinuteOfDay)
            .putBoolean(K_TEST_MODE, c.devFastThresholds)
            .apply()
        configFlow.value = c
    }

    /** UI 设置页用：读-改-写，然后由调用方发 Event.ConfigChanged。 */
    @Synchronized
    fun updateConfig(transform: (GuardConfig) -> GuardConfig) {
        saveConfig(transform(loadConfig()))
    }

    // ---------- state ----------

    @Synchronized
    override fun loadState(): GuardState = GuardState(
        phase = runCatching {
            Phase.valueOf(prefs.getString(K_PHASE, Phase.MONITORING.name) ?: Phase.MONITORING.name)
        }.getOrDefault(Phase.MONITORING),
        lastActivityAt = prefs.getLong(K_LAST_AT, 0),
        lastActivityKind = prefs.getString(K_LAST_KIND, null)
            ?.let { runCatching { ActivityKind.valueOf(it) }.getOrNull() },
        deadlineAt = prefs.getLong(K_DEADLINE, 0),
        confirmEndAt = prefs.getLong(K_CONFIRM_END, 0),
        nextDailySmsAt = prefs.getLong(K_NEXT_SMS, 0),
        alertedAt = prefs.getLong(K_ALERTED_AT, 0),
        selfDialing = prefs.getBoolean(K_SELF_DIALING, false),
        lastRingAt = prefs.getLong(K_LAST_RING, 0),
        inUse = prefs.getBoolean(K_IN_USE, false),
        lastLockAt = prefs.getLong(K_LAST_LOCK, 0),
        drill = loadDrill(),
        lastDrillResult = loadRecord("drill_result"),
        qualifiedPass = loadQualifiedPass(),
        alertSeq = loadSeq("alert_seq_"),
        alertSmsFlags = prefs.getInt(K_ALERT_SMS_FLAGS, 0),
    )

    @Synchronized
    override fun saveState(s: GuardState) {
        prefs.edit()
            .putString(K_PHASE, s.phase.name)
            .putLong(K_LAST_AT, s.lastActivityAt)
            .putString(K_LAST_KIND, s.lastActivityKind?.name)
            .putLong(K_DEADLINE, s.deadlineAt)
            .putLong(K_CONFIRM_END, s.confirmEndAt)
            .putLong(K_NEXT_SMS, s.nextDailySmsAt)
            .putLong(K_ALERTED_AT, s.alertedAt)
            .putBoolean(K_SELF_DIALING, s.selfDialing)
            .putLong(K_LAST_RING, s.lastRingAt)
            .putBoolean(K_IN_USE, s.inUse)
            .putLong(K_LAST_LOCK, s.lastLockAt)
            // 演练运行时状态
            .putString(K_DRILL_PHASE, s.drill.phase.name)
            .putInt(K_DRILL_WAIT_MIN, s.drill.waitMinutes)
            .putLong(K_DRILL_STARTED, s.drill.startedAt)
            .putLong(K_DRILL_LOCKED, s.drill.lockedAt)
            .putLong(K_DRILL_PLANNED, s.drill.plannedAt)
            .putLong(K_DRILL_PLANNED_ELAPSED, s.drill.plannedAtElapsed)
            .putLong(K_DRILL_FIRED, s.drill.firedAt)
            .putLong(K_DRILL_SHOWN_AT, s.drill.screenShownAt)
            .putBoolean(K_DRILL_SHOWN_LOCKED, s.drill.screenShownLocked)
            .putLong(K_DRILL_CONFIRM_END, s.drill.confirmEndAt)
            .putString(K_DRILL_CONTACTS_KEY, s.drill.contactsKey)
            .putBoolean(K_DRILL_SMS_SENT, s.drill.smsSentFlag)
            .putInt(K_DRILL_SMS_SUBMITTED, s.drill.smsSubmittedMask)
            .putInt(K_DRILL_SMS_OK, s.drill.smsOkMask)
            .putInt(K_DRILL_SMS_FAIL, s.drill.smsFailMask)
            .putInt(K_DRILL_ASK_INDEX, s.drill.askIndex)
            .putInt(K_DRILL_SMS_CONFIRMED, s.drill.smsConfirmedMask)
            .putInt(K_DRILL_CALL_CONFIRMED, s.drill.callConfirmedMask)
            .putInt(K_ALERT_SMS_FLAGS, s.alertSmsFlags)
            .apply {
                writeSeq(this, "alert_seq_", s.alertSeq)
                writeSeq(this, "drill_seq_", s.drill.calls)
                // 演练记录：lastDrillResult（任何结局）与 qualifiedPass（门槛凭据）
                writeRecord(this, "drill_result", s.lastDrillResult)
                writeRecord(this, "drill_qual", s.qualifiedPass)
            }
            .apply()
        stateFlow.value = s
    }

    /** 拨号序列读写：真实报警用前缀 "alert_seq_"，演练用 "drill_seq_"。 */
    private fun writeSeq(e: SharedPreferences.Editor, prefix: String, s: CallSeq) {
        e.putString("${prefix}step", s.step.name)
            .putLong("${prefix}started_at", s.startedAt)
            .putInt("${prefix}round", s.round)
            .putInt("${prefix}index", s.index)
            .putLong("${prefix}dialed_at", s.dialedAt)
            .putString("${prefix}dialed_number", s.dialedNumber)
            .putBoolean("${prefix}offhook_seen", s.offhookSeen)
            .putInt("${prefix}checks", s.checks)
            .putInt("${prefix}placed_mask", s.placedMask)
            .putInt("${prefix}answered_mask", s.answeredMask)
            .putInt("${prefix}round_answered_mask", s.roundAnsweredMask)
            .putLong("${prefix}next_round_at", s.nextRoundAt)
    }

    private fun loadSeq(prefix: String): CallSeq = CallSeq(
        step = runCatching {
            SeqStep.valueOf(
                prefs.getString("${prefix}step", SeqStep.IDLE.name) ?: SeqStep.IDLE.name
            )
        }.getOrDefault(SeqStep.IDLE),
        startedAt = prefs.getLong("${prefix}started_at", 0),
        round = prefs.getInt("${prefix}round", 0),
        index = prefs.getInt("${prefix}index", 0),
        dialedAt = prefs.getLong("${prefix}dialed_at", 0),
        dialedNumber = prefs.getString("${prefix}dialed_number", "") ?: "",
        offhookSeen = prefs.getBoolean("${prefix}offhook_seen", false),
        checks = prefs.getInt("${prefix}checks", 0),
        placedMask = prefs.getInt("${prefix}placed_mask", 0),
        answeredMask = prefs.getInt("${prefix}answered_mask", 0),
        roundAnsweredMask = prefs.getInt("${prefix}round_answered_mask", 0),
        nextRoundAt = prefs.getLong("${prefix}next_round_at", 0),
    )

    /** 写一条演练记录：prefix 为 "drill_result"（上次结果）或 "drill_qual"（门槛凭据）。 */
    private fun writeRecord(
        e: SharedPreferences.Editor, prefix: String, r: DrillResult?
    ) {
        if (r == null) {
            e.putBoolean("${prefix}_has", false)
            return
        }
        e.putBoolean("${prefix}_has", true)
            .putString("${prefix}_outcome", r.outcome.name)
            .putString("${prefix}_reason", r.reason)
            .putInt("${prefix}_wait_min", r.waitMinutes)
            .putLong("${prefix}_planned_at", r.plannedAt)
            .putLong("${prefix}_fired_at", r.firedAt)
            .putLong("${prefix}_delay_ms", r.delayMillis)
            .putBoolean("${prefix}_on_time", r.onTime)
            .putBoolean("${prefix}_fullscreen", r.fullscreenOk)
            .putLong("${prefix}_finished_at", r.finishedAt)
            .putLong("${prefix}_version_code", r.versionCode)
            .putString("${prefix}_fingerprint", r.fingerprint)
            .putInt("${prefix}_schema", r.schema)
            .putString("${prefix}_contacts_key", r.contactsKey)
            .putInt("${prefix}_cr_count", r.contactResults.size)
            .apply {
                r.contactResults.forEachIndexed { i, cr ->
                    putString("${prefix}_cr_${i}_name", cr.name)
                    putString("${prefix}_cr_${i}_number", cr.number)
                    putBoolean("${prefix}_cr_${i}_sms", cr.smsSent)
                    putBoolean("${prefix}_cr_${i}_call", cr.callAnswered)
                    putBoolean("${prefix}_cr_${i}_sms_ok", cr.smsConfirmed)
                    putBoolean("${prefix}_cr_${i}_call_ok", cr.callConfirmed)
                }
            }
    }

    private fun loadDrill(): Drill = Drill(
        phase = runCatching {
            DrillPhase.valueOf(
                prefs.getString(K_DRILL_PHASE, DrillPhase.NONE.name) ?: DrillPhase.NONE.name
            )
        }.getOrDefault(DrillPhase.NONE),
        waitMinutes = prefs.getInt(K_DRILL_WAIT_MIN, 0),
        startedAt = prefs.getLong(K_DRILL_STARTED, 0),
        lockedAt = prefs.getLong(K_DRILL_LOCKED, 0),
        plannedAt = prefs.getLong(K_DRILL_PLANNED, 0),
        plannedAtElapsed = prefs.getLong(K_DRILL_PLANNED_ELAPSED, 0),
        firedAt = prefs.getLong(K_DRILL_FIRED, 0),
        screenShownAt = prefs.getLong(K_DRILL_SHOWN_AT, 0),
        screenShownLocked = prefs.getBoolean(K_DRILL_SHOWN_LOCKED, false),
        confirmEndAt = prefs.getLong(K_DRILL_CONFIRM_END, 0),
        contactsKey = prefs.getString(K_DRILL_CONTACTS_KEY, "") ?: "",
        calls = loadSeq("drill_seq_"),
        smsSentFlag = prefs.getBoolean(K_DRILL_SMS_SENT, false),
        smsSubmittedMask = prefs.getInt(K_DRILL_SMS_SUBMITTED, 0),
        smsOkMask = prefs.getInt(K_DRILL_SMS_OK, 0),
        smsFailMask = prefs.getInt(K_DRILL_SMS_FAIL, 0),
        askIndex = prefs.getInt(K_DRILL_ASK_INDEX, 0),
        smsConfirmedMask = prefs.getInt(K_DRILL_SMS_CONFIRMED, 0),
        callConfirmedMask = prefs.getInt(K_DRILL_CALL_CONFIRMED, 0),
    )

    /** 读一条演练记录：prefix 为 "drill_result" 或 "drill_qual"；未存过返回 null。 */
    private fun loadRecord(prefix: String): DrillResult? {
        if (!prefs.getBoolean("${prefix}_has", false)) return null
        val outcome = runCatching {
            DrillOutcome.valueOf(
                prefs.getString("${prefix}_outcome", DrillOutcome.ABORTED.name)
                    ?: DrillOutcome.ABORTED.name
            )
        }.getOrDefault(DrillOutcome.ABORTED)
        // 联系人 key：旧记录只有 ${prefix}_contact 单号码，经 contactsKey 规范化复用；
        // schema 缺省为 1（旧结构，判失效需重新演练）
        val contactsKey = prefs.getString("${prefix}_contacts_key", null)
            ?: prefs.getString("${prefix}_contact", null)?.let {
                DrillRules.contactsKey(listOf(EmergencyContact(number = it)))
            } ?: ""
        val crCount = prefs.getInt("${prefix}_cr_count", 0).coerceAtLeast(0)
        return DrillResult(
            outcome = outcome,
            reason = prefs.getString("${prefix}_reason", "") ?: "",
            waitMinutes = prefs.getInt("${prefix}_wait_min", 0),
            plannedAt = prefs.getLong("${prefix}_planned_at", 0),
            firedAt = prefs.getLong("${prefix}_fired_at", 0),
            delayMillis = prefs.getLong("${prefix}_delay_ms", -1),
            onTime = prefs.getBoolean("${prefix}_on_time", false),
            fullscreenOk = prefs.getBoolean("${prefix}_fullscreen", false),
            contactResults = (0 until crCount).map { i ->
                DrillContactResult(
                    name = prefs.getString("${prefix}_cr_${i}_name", "") ?: "",
                    number = prefs.getString("${prefix}_cr_${i}_number", "") ?: "",
                    smsSent = prefs.getBoolean("${prefix}_cr_${i}_sms", false),
                    callAnswered = prefs.getBoolean("${prefix}_cr_${i}_call", false),
                    smsConfirmed = prefs.getBoolean("${prefix}_cr_${i}_sms_ok", false),
                    callConfirmed = prefs.getBoolean("${prefix}_cr_${i}_call_ok", false),
                )
            },
            finishedAt = prefs.getLong("${prefix}_finished_at", 0),
            versionCode = prefs.getLong("${prefix}_version_code", -1),
            fingerprint = prefs.getString("${prefix}_fingerprint", "") ?: "",
            contactsKey = contactsKey,
            schema = prefs.getInt("${prefix}_schema", 1),
        )
    }

    /**
     * 开启门槛凭据。旧版本只有一条 drill_result_* 记录：若它是合格的通过记录
     * （PASSED 且等待 ≥30 分钟），首次加载时升格为 qualifiedPass。
     * 一旦 drill_qual_has 键存在就以它为准（包括显式存 false 的情况）。
     */
    private fun loadQualifiedPass(): DrillResult? {
        if (prefs.contains(K_DQ_HAS)) {
            return if (prefs.getBoolean(K_DQ_HAS, false)) {
                loadRecord("drill_qual")
            } else {
                null
            }
        }
        return loadRecord("drill_result")?.takeIf {
            it.outcome == DrillOutcome.PASSED &&
                it.waitMinutes >= DrillRules.MIN_QUALIFY_MINUTES
        }
    }

    // ---------- 手动确认项（权限清单中"需手动确认"的必需项） ----------

    /**
     * 手动确认是否仍有效：记录的确认指纹须与当前 Build.FINGERPRINT 一致——
     * 系统升级（指纹变化）常会重置厂商设置，全部确认项随之失效。
     */
    fun isManualConfirmed(key: String, fingerprint: String): Boolean =
        prefs.getString(K_MANUAL_FP, null) == fingerprint &&
            (prefs.getStringSet(K_MANUAL_KEYS, emptySet()) ?: emptySet()).contains(key)

    /**
     * 勾选"我已设置 / 我的手机没有此项"。
     * 指纹已变化时先清空旧确认（旧确认本来也已失效），再记入新确认。
     */
    @Synchronized
    fun confirmManual(key: String, fingerprint: String) {
        val storedFp = prefs.getString(K_MANUAL_FP, null)
        val set: MutableSet<String> =
            if (storedFp == fingerprint) {
                (prefs.getStringSet(K_MANUAL_KEYS, emptySet()) ?: emptySet()).toMutableSet()
            } else {
                mutableSetOf()
            }
        set += key
        prefs.edit()
            .putStringSet(K_MANUAL_KEYS, set)
            .putString(K_MANUAL_FP, fingerprint)
            .apply()
        manualFlow.value = set.toSet()
    }

    /** 撤销手动确认（用户在清单页取消勾选）。 */
    @Synchronized
    fun revokeManual(key: String) {
        val set = (prefs.getStringSet(K_MANUAL_KEYS, emptySet()) ?: emptySet()).toMutableSet()
        if (set.remove(key)) {
            prefs.edit().putStringSet(K_MANUAL_KEYS, set).apply()
            manualFlow.value = set.toSet()
        }
    }

    /** 手动确认的指纹是否已失效（存过确认但指纹与当前不一致）。 */
    fun manualFingerprintStale(currentFp: String): Boolean =
        prefs.getString(K_MANUAL_FP, null) != null &&
            prefs.getString(K_MANUAL_FP, null) != currentFp

    /** UI 刷新用：当前确认集合（需配合 fingerprint 判断有效性）。 */
    val manualFlow = MutableStateFlow<Set<String>>(
        prefs.getStringSet(K_MANUAL_KEYS, emptySet()) ?: emptySet()
    )

    /** 是否已申请过运行时权限（MainActivity 首次启动用）。 */
    var permsRequested: Boolean
        get() = prefs.getBoolean(K_PERMS_REQUESTED, false)
        set(v) = prefs.edit().putBoolean(K_PERMS_REQUESTED, v).apply()

    companion object {
        private const val K_ENABLED = "enabled"
        private const val K_NAME = "contact_name"
        private const val K_NUMBER = "contact_number"
        private const val K_CONTACTS_COUNT = "contacts_count"
        private const val K_CONTACT_PREFIX = "contact_"
        private const val K_ANSWER_POLICY = "answer_policy"
        private const val K_THRESHOLD_MIN = "threshold_minutes"
        private const val K_CONFIRM_MIN = "confirm_window_minutes"
        private const val K_DAILY_ENABLED = "daily_sms_enabled"
        private const val K_DAILY_MINUTE = "daily_sms_minute"
        private const val K_QUIET_ENABLED = "quiet_enabled"
        private const val K_QUIET_START = "quiet_start_minute"
        private const val K_QUIET_END = "quiet_end_minute"
        private const val K_TEST_MODE = "test_mode"
        private const val K_PHASE = "phase"
        private const val K_LAST_AT = "last_activity_at"
        private const val K_LAST_KIND = "last_activity_kind"
        private const val K_DEADLINE = "deadline_at"
        private const val K_CONFIRM_END = "confirm_end_at"
        private const val K_NEXT_SMS = "next_daily_sms_at"
        private const val K_ALERTED_AT = "alerted_at"
        private const val K_SELF_DIALING = "self_dialing"
        private const val K_LAST_RING = "last_ring_at"
        private const val K_IN_USE = "in_use"
        private const val K_LAST_LOCK = "last_lock_at"
        private const val K_ALERT_SMS_FLAGS = "alert_sms_flags"
        private const val K_PERMS_REQUESTED = "perms_requested"
        private const val K_DRILL_PHASE = "drill_phase"
        private const val K_DRILL_WAIT_MIN = "drill_wait_min"
        private const val K_DRILL_STARTED = "drill_started_at"
        private const val K_DRILL_LOCKED = "drill_locked_at"
        private const val K_DRILL_PLANNED = "drill_planned_at"
        private const val K_DRILL_PLANNED_ELAPSED = "drill_planned_elapsed"
        private const val K_DRILL_FIRED = "drill_fired_at"
        private const val K_DRILL_SHOWN_AT = "drill_shown_at"
        private const val K_DRILL_SHOWN_LOCKED = "drill_shown_locked"
        private const val K_DRILL_CONFIRM_END = "drill_confirm_end"
        private const val K_DRILL_CONTACTS_KEY = "drill_contacts_key"
        private const val K_DRILL_SMS_SENT = "drill_sms_sent"
        private const val K_DRILL_SMS_SUBMITTED = "drill_sms_submitted"
        private const val K_DRILL_SMS_OK = "drill_sms_ok"
        private const val K_DRILL_SMS_FAIL = "drill_sms_fail"
        private const val K_DRILL_ASK_INDEX = "drill_ask_index"
        private const val K_DRILL_SMS_CONFIRMED = "drill_sms_confirmed"
        private const val K_DRILL_CALL_CONFIRMED = "drill_call_confirmed"
        /** 是否有显式写过的门槛凭据（区分"从未保存"与"保存过 null"）。 */
        private const val K_DQ_HAS = "drill_qual_has"
        private const val K_MANUAL_KEYS = "manual_confirmed_keys"
        private const val K_MANUAL_FP = "manual_confirmed_fingerprint"
    }
}
