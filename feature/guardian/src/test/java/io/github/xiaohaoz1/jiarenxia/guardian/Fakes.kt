package io.github.xiaohaoz1.jiarenxia.guardian

import io.github.xiaohaoz1.jiarenxia.guardian.core.BatteryStatus
import io.github.xiaohaoz1.jiarenxia.guardian.core.CallLogResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.Clock
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillContactResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillOutcome
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardEngine
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.core.Ports
import io.github.xiaohaoz1.jiarenxia.guardian.core.SmsKind

class FakeClock(var t: Long) : Clock {
    override fun now(): Long = t
    fun advance(ms: Long) {
        t += ms
    }
}

class FakeStore(
    var config: GuardConfig = GuardConfig(),
    var state: GuardState = GuardState(),
) : GuardEngine.Store {
    override fun loadConfig(): GuardConfig = config
    override fun saveConfig(c: GuardConfig) {
        config = c
    }

    override fun loadState(): GuardState = state
    override fun saveState(s: GuardState) {
        state = s
    }
}

/** 生成一条满足开启门槛的演练通过记录（环境指纹与 FakePorts 默认一致）。 */
fun passedDrillRecord(
    contacts: List<EmergencyContact>,
    at: Long = 0L,
    waitMinutes: Int = 30,
    versionCode: Long = 1L,
    fingerprint: String = "fake-fp",
) = DrillResult(
    outcome = DrillOutcome.PASSED,
    reason = "演练通过",
    waitMinutes = waitMinutes,
    plannedAt = at,
    firedAt = at + 8_000L,
    delayMillis = 8_000L,
    onTime = true,
    fullscreenOk = true,
    contactResults = contacts.map {
        DrillContactResult(
            name = it.name, number = it.number,
            smsSent = true, callAnswered = true,
            smsConfirmed = true, callConfirmed = true,
        )
    },
    finishedAt = at + 120_000L,
    versionCode = versionCode,
    fingerprint = fingerprint,
    contactsKey = DrillRules.contactsKey(contacts),
)

class FakePorts : Ports {
    val deadlines = mutableListOf<Long>()
    val confirmEnds = mutableListOf<Long>()
    val dailySmsSchedules = mutableListOf<Long>()
    val watchdogDelays = mutableListOf<Long>()
    var cancelConfirmCount = 0
    var cancelAllCount = 0
    val ringingStarts = mutableListOf<Long>()
    var ringStopCount = 0
    val confirmNotifs = mutableListOf<Long>()
    val alertedNotifs = mutableListOf<Long>()
    val calls = mutableListOf<String>()
    var ensureCount = 0
    var stopServiceCount = 0
    var deviceInUse = true
    var reconcileResult: Long? = null
    var throwOnPlaceCall: Throwable? = null
    var placeCallResult = true
    val logs = mutableListOf<Pair<String, String>>()

    // 短信群发（kind+index 归因；ops 记顺序用于断言"先短信后拨号"）
    data class SentSms(
        val number: String,
        val body: String,
        val kind: SmsKind,
        val index: Int,
    )
    val sms = mutableListOf<SentSms>()
    var smsSubmitResult = true
    var battery = BatteryStatus(80, false)

    // 通话记录查询（默认拨出未接通）
    var callLog: (String, Long) -> CallLogResult = { _, _ -> CallLogResult.NOT_ANSWERED }
    val callLogQueries = mutableListOf<Pair<String, Long>>()
    var inCall = false

    // 拨号序列闹钟
    val callChecks = mutableListOf<Long>()
    val callFallbacks = mutableListOf<Long>()
    val nextRounds = mutableListOf<Long>()
    var cancelCallAlarmsCount = 0

    /** 按顺序记录的关键操作：如 "sms:EMERGENCY:0"、"call:138..."，用于断言时序。 */
    val ops = mutableListOf<String>()

    // 演练
    val drillAlarms = mutableListOf<Long>()
    val drillConfirmEnds = mutableListOf<Long>()
    val drillRingStarts = mutableListOf<Long>()
    var cancelDrillCount = 0

    /** 环境信息（演练记录有效性判定）。 */
    var versionCode = 1L
    var fingerprint = "fake-fp"

    override fun scheduleDeadline(at: Long) {
        deadlines += at
    }

    override fun scheduleConfirmEnd(at: Long) {
        confirmEnds += at
    }

    override fun scheduleDailySms(at: Long) {
        dailySmsSchedules += at
    }

    override fun scheduleWatchdog(delayMillis: Long) {
        watchdogDelays += delayMillis
    }

    override fun cancelConfirmAndAlert() {
        cancelConfirmCount++
    }

    override fun cancelAll() {
        cancelAllCount++
    }

    override fun scheduleDrillAlarm(at: Long) {
        drillAlarms += at
    }

    override fun scheduleDrillConfirmEnd(at: Long) {
        drillConfirmEnds += at
    }

    override fun cancelDrill() {
        cancelDrillCount++
    }

    override fun startDrillRinging(confirmEndAt: Long) {
        drillRingStarts += confirmEndAt
    }

    override fun appVersionCode(): Long = versionCode

    override fun buildFingerprint(): String = fingerprint

    override fun startConfirmRinging(confirmEndAt: Long) {
        ringingStarts += confirmEndAt
    }

    override fun stopRinging() {
        ringStopCount++
    }

    override fun showConfirmNotification(confirmEndAt: Long) {
        confirmNotifs += confirmEndAt
    }

    override fun showAlertedNotification(alertedAt: Long) {
        alertedNotifs += alertedAt
    }

    override fun placeCall(number: String): Boolean {
        throwOnPlaceCall?.let { throw it }
        calls += number
        ops += "call:$number"
        return placeCallResult
    }

    override fun batteryStatus(): BatteryStatus = battery

    override fun sendSms(number: String, body: String, kind: SmsKind, index: Int): Boolean {
        ops += "sms:$kind:$index"
        if (smsSubmitResult) sms += SentSms(number, body, kind, index)
        return smsSubmitResult
    }

    override fun queryOutgoingCall(number: String, since: Long): CallLogResult {
        callLogQueries += number to since
        return callLog(number, since)
    }

    override fun isInCall(): Boolean = inCall

    override fun scheduleCallCheck(at: Long) {
        callChecks += at
    }

    override fun scheduleCallFallback(at: Long) {
        callFallbacks += at
    }

    override fun scheduleNextRound(at: Long) {
        nextRounds += at
    }

    override fun cancelCallAlarms() {
        cancelCallAlarmsCount++
    }

    override fun ensureServiceRunning() {
        ensureCount++
    }

    override fun stopService() {
        stopServiceCount++
    }

    /** elapsedRealtime 坐标系：测试中手动同步于 FakeClock 推进。 */
    var elapsed = 0L

    override fun isDeviceInUse(): Boolean = deviceInUse

    override fun elapsedRealtime(): Long = elapsed

    override fun reconcileMissedUnlockSince(since: Long): Long? = reconcileResult

    override fun log(tag: String, msg: String) {
        logs += tag to msg
    }
}
