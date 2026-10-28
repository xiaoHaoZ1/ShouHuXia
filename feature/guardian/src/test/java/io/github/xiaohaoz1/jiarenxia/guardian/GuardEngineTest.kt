package io.github.xiaohaoz1.jiarenxia.guardian

import io.github.xiaohaoz1.jiarenxia.guardian.core.ActivityKind
import io.github.xiaohaoz1.jiarenxia.guardian.core.AlertRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.AnswerPolicy
import io.github.xiaohaoz1.jiarenxia.guardian.core.CallLogResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillOutcome
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillPhase
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardEngine
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.core.Phase
import io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep
import io.github.xiaohaoz1.jiarenxia.guardian.core.SmsKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardEngineTest {

    private val t0 = 1_700_000_000_000L
    private val num = "13800001111"
    private val num2 = "13800002222"
    private val c1 = EmergencyContact("张三", num)
    private val c2 = EmergencyContact("李四", num2)

    private fun fixture(
        // 免打扰时段依赖本机时区，既有用例默认关掉；免打扰行为有专项用例覆盖
        cfg: GuardConfig = GuardConfig(
            contacts = listOf(c1), quietEnabled = false
        ),
        st: GuardState = GuardState(),
        now: Long = t0,
        // 开启守护要求有效演练记录：默认预置一条（与 FakePorts 环境一致）；
        // 测开启门槛本身的用例传 withRecord = false
        withRecord: Boolean = true,
    ): Triple<GuardEngine, FakeClock, FakePorts> {
        val seeded =
            if (withRecord && st.qualifiedPass == null && cfg.validContacts.isNotEmpty()) {
                st.copy(qualifiedPass = passedDrillRecord(cfg.validContacts))
            } else {
                st
            }
        val store = FakeStore(cfg, seeded)
        val clock = FakeClock(now)
        val ports = FakePorts()
        return Triple(GuardEngine(store, clock, ports), clock, ports)
    }

    // ---------- 拨号序列驱动辅助 ----------

    /** 模拟本通通话结束：摘机 → 回到 IDLE → 通话记录检查闹钟。 */
    private fun endCall(
        e: GuardEngine, ports: FakePorts, clock: FakeClock,
        result: CallLogResult,
    ) {
        ports.callLog = { _, _ -> result }
        e.handle(Event.CallOffhook)
        e.handle(Event.CallIdle)
        clock.advance(AlertRules.CALL_LOG_DELAY_MS)
        e.handle(Event.CallCheckAlarm)
    }

    /** 把真实报警序列跑到相位落定：每通按 result 判定，防呆上限 30 步。 */
    private fun runAlertSeqToEnd(
        e: GuardEngine, ports: FakePorts, clock: FakeClock,
        result: CallLogResult = CallLogResult.NOT_ANSWERED,
    ) {
        var guard = 0
        while (e.snapshot().phase == Phase.ALERTING && guard++ < 30) {
            when (e.snapshot().alertSeq.step) {
                SeqStep.DIALING -> endCall(e, ports, clock, result)
                SeqStep.ROUND_WAIT -> {
                    clock.t = e.snapshot().alertSeq.nextRoundAt
                    e.handle(Event.NextRoundAlarm)
                }
                else -> break
            }
        }
    }

    /** 把演练拨号序列跑到 ASK_ANSWER：每通按 result 判定。 */
    private fun runDrillCalls(
        e: GuardEngine, ports: FakePorts, clock: FakeClock,
        result: CallLogResult = CallLogResult.ANSWERED,
    ) {
        var guard = 0
        while (e.snapshot().drill.phase == DrillPhase.CALLING && guard++ < 10) {
            if (e.snapshot().drill.calls.step == SeqStep.DIALING) {
                endCall(e, ports, clock, result)
            } else break
        }
    }

    /** 逐位问答全部答"都收到了"。 */
    private fun answerAllContacts(e: GuardEngine, n: Int) {
        for (i in 0 until n) {
            e.handle(Event.DrillContactAnswer(i, smsReceived = true, callReceived = true))
        }
    }

    private fun smsKinds(ports: FakePorts) = ports.sms.map { it.kind }

    // ---------- 开启/关闭 ----------

    @Test
    fun `enable switches to MONITORING and schedules all alarms`() {
        val (e, _, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        val s = e.snapshot()
        assertEquals(Phase.MONITORING, s.phase)
        assertEquals(t0, s.lastActivityAt)
        assertEquals(ActivityKind.ENABLE, s.lastActivityKind)
        assertEquals(t0 + 8 * 3600_000L, s.deadlineAt)
        assertEquals(s.deadlineAt, ports.deadlines.last())
        assertEquals(1, ports.ensureCount)
        assertEquals(1, ports.dailySmsSchedules.size)
        assertTrue(s.nextDailySmsAt > t0)
        assertEquals(GuardEngine.WATCHDOG_INTERVAL_MS, ports.watchdogDelays.last())
    }

    @Test
    fun `enable without contact is refused and logs`() {
        val (e, _, ports) = fixture(cfg = GuardConfig())
        e.handle(Event.EnabledChanged(true))
        assertFalse(e.snapshotConfig().enabled)
        assertTrue(ports.deadlines.isEmpty())
        assertEquals(0, ports.ensureCount)
        assertTrue(ports.logs.any { it.second.contains("未设置紧急联系人") })
        // 空白号码同样拒绝（validContacts 过滤空号）
        val (e2, _, _) = fixture(
            cfg = GuardConfig(contacts = listOf(EmergencyContact("某人", "   ")))
        )
        e2.handle(Event.EnabledChanged(true))
        assertFalse(e2.snapshotConfig().enabled)
    }

    @Test
    fun `disable stops ringing and cancels all alarms`() {
        val (e, _, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.EnabledChanged(false))
        val s = e.snapshot()
        assertFalse(e.snapshotConfig().enabled)
        assertEquals(0L, s.deadlineAt)
        assertEquals(0L, s.confirmEndAt)
        assertTrue(ports.cancelAllCount >= 1)
        assertTrue(ports.ringStopCount >= 1)
        assertTrue(ports.cancelCallAlarmsCount >= 1)
    }

    @Test
    fun `disable during alerting stops sequence without further sms`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow) // → ALERTING 拨号中
        assertEquals(SeqStep.DIALING, e.snapshot().alertSeq.step)
        e.handle(Event.EnabledChanged(false))
        val s = e.snapshot()
        // 序列不再续拨：step 收敛为 DONE、相位落 ALERTED，不发结果/解除短信
        assertEquals(SeqStep.DONE, s.alertSeq.step)
        assertEquals(Phase.ALERTED, s.phase)
        assertTrue(smsKinds(ports).none { it == SmsKind.RESOLVED || it == SmsKind.NO_ANSWER })
        // 通话闹钟被清：之后再触发序列事件应被忽略
        val callsBefore = ports.calls.size
        e.handle(Event.CallFallbackAlarm)
        e.handle(Event.CallCheckAlarm)
        assertEquals(callsBefore, ports.calls.size)
    }

    // ---------- 活动 ----------

    @Test
    fun `activity resets deadline`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(3600_000L)
        e.handle(Event.Activity(ActivityKind.UNLOCK))
        val s = e.snapshot()
        assertEquals(t0 + 3600_000L, s.lastActivityAt)
        assertEquals(t0 + 3600_000L + 8 * 3600_000L, s.deadlineAt)
        assertEquals(s.deadlineAt, ports.deadlines.last())
    }

    @Test
    fun `activity ignored when disabled`() {
        val (e, _, ports) = fixture()
        e.handle(Event.Activity(ActivityKind.UNLOCK))
        assertEquals(0L, e.snapshot().lastActivityAt)
        assertTrue(ports.deadlines.isEmpty())
    }

    // ---------- 到期 → 确认 ----------

    @Test
    fun `deadline alarm enters CONFIRMING when overdue`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        val s = e.snapshot()
        assertEquals(Phase.CONFIRMING, s.phase)
        assertEquals(1, ports.ringingStarts.size)
        assertEquals(1, ports.confirmNotifs.size)
        assertEquals(t0 + 8 * 3600_000L + 2 * 60_000L, s.confirmEndAt)
        assertEquals(s.confirmEndAt, ports.confirmEnds.last())
    }

    @Test
    fun `deadline alarm fired early reschedules and stays MONITORING`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(3600_000L)
        e.handle(Event.DeadlineAlarm)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
        assertTrue(ports.ringingStarts.isEmpty())
        assertEquals(e.snapshot().deadlineAt, ports.deadlines.last())
    }

    @Test
    fun `quiet window defers deadline into morning`() {
        // 20:00 开启、阈值 8 小时：deadline 原落凌晨 04:00，免打扰（默认 23–07）顺延到 07:00
        val zone = java.time.ZoneId.systemDefault()
        val d = java.time.LocalDate.now(zone)
        val t = d.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1)),
            now = t
        )
        e.handle(Event.EnabledChanged(true))
        val expect = d.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expect, e.snapshot().deadlineAt)
        // 到原该响铃的 04:00，提前到的闹钟被重挂，仍 MONITORING
        clock.t = d.plusDays(1).atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        e.handle(Event.DeadlineAlarm)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
        assertTrue(ports.ringingStarts.isEmpty())
    }

    @Test
    fun `stale deadline inside quiet window is deferred not alerted`() {
        // 旧 deadline 已存在且落在免打扰时段（如改配置前算的）：闹钟到时顺延而非报警
        val zone = java.time.ZoneId.systemDefault()
        val d = java.time.LocalDate.now(zone)
        val t = d.atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.MONITORING,
                lastActivityAt = t - 8 * 3600_000L,
                deadlineAt = t,
                inUse = false
            ),
            now = t
        )
        e.handle(Event.DeadlineAlarm)
        val expect = d.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(Phase.MONITORING, e.snapshot().phase)
        assertEquals(expect, e.snapshot().deadlineAt)
        assertEquals(expect, ports.deadlines.last())
        assertTrue(ports.ringingStarts.isEmpty())
    }

    @Test
    fun `deadline alarm reconciles missed unlock from usage stats`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        val missed = t0 + 4 * 3600_000L
        ports.reconcileResult = missed
        e.handle(Event.DeadlineAlarm)
        val s = e.snapshot()
        assertEquals(Phase.MONITORING, s.phase)
        assertEquals(missed, s.lastActivityAt)
        assertEquals(ActivityKind.USAGE_STATS, s.lastActivityKind)
        assertEquals(missed + 8 * 3600_000L, s.deadlineAt)
        assertTrue(ports.ringingStarts.isEmpty())
    }

    @Test
    fun `force deadline enters CONFIRMING immediately`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(60_000L)
        e.handle(Event.ForceDeadline)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
        assertEquals(1, ports.ringingStarts.size)
    }

    @Test
    fun `activity during CONFIRMING cancels alarm and resets`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        clock.advance(30_000L)
        e.handle(Event.Activity(ActivityKind.UNLOCK))
        val s = e.snapshot()
        assertEquals(Phase.MONITORING, s.phase)
        assertEquals(1, ports.ringStopCount)
        assertEquals(1, ports.cancelConfirmCount)
        assertEquals(t0 + 8 * 3600_000L + 30_000L, s.lastActivityAt)
        assertEquals(s.lastActivityAt + 8 * 3600_000L, s.deadlineAt)
    }

    // ---------- 确认超时 → 拨号序列 ----------

    @Test
    fun `confirm end sends emergency sms to all contacts before dialing`() {
        // 群发紧急短信必须先于第一通拨号，且每位联系人都收到
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        clock.advance(2 * 60_000L)
        e.handle(Event.ConfirmEndAlarm)
        assertEquals(Phase.ALERTING, e.snapshot().phase)
        // ops 顺序：两条 EMERGENCY 短信都在第一通拨号之前
        val firstCall = ports.ops.indexOfFirst { it == "call:$num" }
        assertTrue(firstCall > 0)
        assertEquals(
            listOf("sms:EMERGENCY:0", "sms:EMERGENCY:1"),
            ports.ops.subList(0, firstCall)
        )
        val emergencySms = ports.sms.filter { it.kind == SmsKind.EMERGENCY }
        assertEquals(listOf(num, num2), emergencySms.map { it.number })
        assertTrue(emergencySms.all { it.body.contains("接通后若没人说话") })
        assertTrue(emergencySms.all { it.body.startsWith("【守护侠】紧急") })
    }

    @Test
    fun `confirm end runs full sequence and ends ALERTED with no-answer summary`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        clock.advance(2 * 60_000L)
        e.handle(Event.ConfirmEndAlarm)
        runAlertSeqToEnd(e, ports, clock)
        val s = e.snapshot()
        // 单联系人打满 2 轮无人接听 → NO_ANSWER 群发 → ALERTED
        assertEquals(listOf(num, num), ports.calls)
        assertEquals(Phase.ALERTED, s.phase)
        assertTrue(s.alertedAt > 0)
        assertFalse(s.selfDialing)
        assertEquals(SeqStep.DONE, s.alertSeq.step)
        assertEquals(1, ports.alertedNotifs.size)
        assertTrue(smsKinds(ports).count { it == SmsKind.EMERGENCY } == 1)
        assertTrue(smsKinds(ports).count { it == SmsKind.NO_ANSWER } == 1)
    }

    @Test
    fun `confirm end alarm fired early reschedules`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        clock.advance(30_000L)
        e.handle(Event.ConfirmEndAlarm)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
        assertTrue(ports.calls.isEmpty())
        assertEquals(e.snapshot().confirmEndAt, ports.confirmEnds.last())
    }

    @Test
    fun `confirm end alarm ignored when not confirming`() {
        val (e, _, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.ConfirmEndAlarm)
        assertTrue(ports.calls.isEmpty())
        assertEquals(Phase.MONITORING, e.snapshot().phase)
    }

    @Test
    fun `im ok cancels CONFIRMING`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        e.handle(Event.ImOk)
        val s = e.snapshot()
        assertEquals(Phase.MONITORING, s.phase)
        assertEquals(ActivityKind.MANUAL_IM_OK, s.lastActivityKind)
        assertTrue(ports.calls.isEmpty())
        assertEquals(1, ports.cancelConfirmCount)
    }

    // ---------- 接通策略 ----------

    @Test
    fun `stop on answer policy stops after first answered contact`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        assertEquals(SeqStep.DIALING, e.snapshot().alertSeq.step)
        endCall(e, ports, clock, CallLogResult.ANSWERED)
        val s = e.snapshot()
        // 第 1 位接通即停：不拨第 2 位，群发 ANSWERED，落 ALERTED
        assertEquals(listOf(num), ports.calls)
        assertEquals(Phase.ALERTED, s.phase)
        val answered = ports.sms.filter { it.kind == SmsKind.ANSWERED }
        assertEquals(listOf(num, num2), answered.map { it.number })
        assertTrue(answered.all { it.body.contains("张三") })
        assertEquals(SeqStep.DONE, s.alertSeq.step)
        assertEquals(1, s.alertSeq.answeredMask)
    }

    @Test
    fun `call all policy dials everyone and sends summary`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(
                contacts = listOf(c1, c2),
                answerPolicy = AnswerPolicy.CALL_ALL,
                quietEnabled = false,
            )
        )
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        // 第 1 位接通仍继续拨第 2 位
        endCall(e, ports, clock, CallLogResult.ANSWERED)
        assertEquals(listOf(num, num2), ports.calls)
        endCall(e, ports, clock, CallLogResult.NOT_ANSWERED)
        val s = e.snapshot()
        assertEquals(Phase.ALERTED, s.phase)
        val summary = ports.sms.filter { it.kind == SmsKind.SUMMARY }
        assertEquals(2, summary.size)
        assertTrue(summary.all { it.body.contains("张三") })
        assertTrue(summary.all { !it.body.contains("李四") })
    }

    @Test
    fun `no answer goes to round wait then redials until max rounds`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        // 第 1 轮无人接通 → ROUND_WAIT 并挂下一轮闹钟
        endCall(e, ports, clock, CallLogResult.NOT_ANSWERED)
        var s = e.snapshot()
        assertEquals(Phase.ALERTING, s.phase)
        assertEquals(SeqStep.ROUND_WAIT, s.alertSeq.step)
        assertEquals(listOf(clock.t + AlertRules.ROUND_INTERVAL_MS), ports.nextRounds)
        // 下一轮闹钟到点：从第 1 位重拨
        clock.t = s.alertSeq.nextRoundAt
        e.handle(Event.NextRoundAlarm)
        s = e.snapshot()
        assertEquals(2, s.alertSeq.round)
        assertEquals(0, s.alertSeq.index)
        assertEquals(SeqStep.DIALING, s.alertSeq.step)
        assertEquals(listOf(num, num), ports.calls)
        // 第 2 轮仍无人接通：已达 MAX_ROUNDS → 群发 NO_ANSWER → ALERTED
        endCall(e, ports, clock, CallLogResult.NOT_ANSWERED)
        s = e.snapshot()
        assertEquals(Phase.ALERTED, s.phase)
        assertTrue(smsKinds(ports).contains(SmsKind.NO_ANSWER))
    }

    @Test
    fun `placeCall returning false advances to next contact`() {
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        e.handle(Event.EnabledChanged(true))
        ports.placeCallResult = false
        e.handle(Event.AlertNow)
        // 第 1 位发起失败 → 直接判未接通走下一位（不等通话闹钟）
        assertEquals(listOf(num, num2), ports.calls)
        assertEquals(SeqStep.ROUND_WAIT, e.snapshot().alertSeq.step)
        assertEquals(0, e.snapshot().alertSeq.placedMask)
        assertTrue(ports.callLogQueries.isEmpty())
    }

    @Test
    fun `placeCall throwing exception advances to next contact`() {
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        e.handle(Event.EnabledChanged(true))
        ports.throwOnPlaceCall = SecurityException("no perm")
        e.handle(Event.AlertNow)
        assertEquals(SeqStep.ROUND_WAIT, e.snapshot().alertSeq.step)
        assertTrue(ports.logs.any { it.second.contains("拨号权限不足") })
        // 异常在记录 calls 之前抛出：两位都发起过但都没成功
        assertEquals(0, e.snapshot().alertSeq.placedMask)
    }

    @Test
    fun `call log unavailable counts as not answered`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        endCall(e, ports, clock, CallLogResult.UNAVAILABLE)
        // UNAVAILABLE 不重试：直接按未接通走下一位
        assertEquals(listOf(num, num2), ports.calls)
        assertEquals(1, ports.callLogQueries.size)
    }

    @Test
    fun `call log not found retries up to max checks`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        ports.callLog = { _, _ -> CallLogResult.NOT_FOUND }
        val firstDialedAt = e.snapshot().alertSeq.dialedAt
        e.handle(Event.CallOffhook)
        e.handle(Event.CallIdle)
        // 首查 + 重试：共查 CALL_LOG_MAX_CHECKS 次后按未接通走下一位
        for (i in 0 until AlertRules.CALL_LOG_MAX_CHECKS) {
            clock.advance(AlertRules.CALL_LOG_RETRY_MS)
            e.handle(Event.CallCheckAlarm)
        }
        assertEquals(AlertRules.CALL_LOG_MAX_CHECKS, ports.callLogQueries.size)
        assertEquals(listOf(num, num2), ports.calls)
        // since = 本通 dialedAt - 容差
        assertEquals(
            firstDialedAt - AlertRules.CALL_LOG_SINCE_TOLERANCE_MS,
            ports.callLogQueries.first().second
        )
    }

    @Test
    fun `fallback alarm advances when IDLE broadcast was lost`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        // IDLE 广播丢了：兜底闹钟到点、已不在通话 → 立即查记录并推进
        clock.advance(AlertRules.CALL_FALLBACK_MS)
        ports.inCall = false
        ports.callLog = { _, _ -> CallLogResult.ANSWERED }
        e.handle(Event.CallFallbackAlarm)
        assertEquals(Phase.ALERTED, e.snapshot().phase)
        assertEquals(1, ports.callLogQueries.size)
    }

    @Test
    fun `fallback alarm reschedules while still in call`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        clock.advance(AlertRules.CALL_FALLBACK_MS)
        ports.inCall = true
        e.handle(Event.CallFallbackAlarm)
        // 仍在通话中：再等 60 秒，不查记录不推进
        assertEquals(SeqStep.DIALING, e.snapshot().alertSeq.step)
        assertTrue(ports.callLogQueries.isEmpty())
        assertEquals(clock.t + AlertRules.CALL_FALLBACK_RECHECK_MS, ports.callFallbacks.last())
    }

    @Test
    fun `early fallback alarm is rescheduled to the original point`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        e.handle(Event.CallFallbackAlarm) // 兜底闹钟提前到
        assertEquals(SeqStep.DIALING, e.snapshot().alertSeq.step)
        assertEquals(
            e.snapshot().alertSeq.dialedAt + AlertRules.CALL_FALLBACK_MS,
            ports.callFallbacks.last()
        )
        assertTrue(ports.callLogQueries.isEmpty())
    }

    @Test
    fun `idle without offhook is ignored as stale broadcast`() {
        val (e, _, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        e.handle(Event.CallIdle) // 没见 OFFHOOK 的 IDLE：陈旧广播
        assertEquals(SeqStep.DIALING, e.snapshot().alertSeq.step)
        assertTrue(ports.callLogQueries.isEmpty())
        assertTrue(ports.callChecks.isEmpty())
    }

    // ---------- 解除 ----------

    @Test
    fun `unlock during alerting stops calls and sends resolved`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        assertEquals(Phase.ALERTING, e.snapshot().phase)
        clock.advance(5_000L)
        e.handle(Event.UserPresent)
        val s = e.snapshot()
        assertEquals(Phase.MONITORING, s.phase)
        assertEquals(SeqStep.IDLE, s.alertSeq.step)
        assertTrue(ports.cancelCallAlarmsCount >= 1)
        // 紧急短信已发过 → 群发"已解除"
        val resolved = ports.sms.filter { it.kind == SmsKind.RESOLVED }
        assertEquals(1, resolved.size)
        assertTrue(resolved[0].body.contains("已解除"))
        // 之后再解锁不再重复发
        ports.sms.clear()
        clock.advance(60_000L)
        e.handle(Event.UserPresent)
        assertTrue(ports.sms.isEmpty())
    }

    @Test
    fun `im ok during alerted also sends resolved`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        runAlertSeqToEnd(e, ports, clock)
        assertEquals(Phase.ALERTED, e.snapshot().phase)
        ports.sms.clear()
        e.handle(Event.ImOk)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
        assertEquals(1, ports.sms.count { it.kind == SmsKind.RESOLVED })
    }

    @Test
    fun `resolve before emergency sms does not send resolved`() {
        // CONFIRMING 就解除：紧急短信还没发过，不发"已解除"
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        e.handle(Event.ImOk)
        assertTrue(ports.sms.isEmpty())
    }

    // ---------- 通话 / 自拨号 ----------

    @Test
    fun `call offhook counts as activity`() {
        val (e, clock, _) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(3600_000L)
        e.handle(Event.CallOffhook)
        val s = e.snapshot()
        assertEquals(t0 + 3600_000L, s.lastActivityAt)
        assertEquals(ActivityKind.CALL, s.lastActivityKind)
    }

    @Test
    fun `call offhook ignored while self dialing`() {
        val (e, _, _) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.ALERTING, lastActivityAt = t0, selfDialing = true),
        )
        e.handle(Event.CallOffhook)
        assertEquals(t0, e.snapshot().lastActivityAt)
        assertEquals(Phase.ALERTING, e.snapshot().phase)
    }

    @Test
    fun `own call offhook during dialing marks seen not activity`() {
        val (e, clock, _) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        val activityAt = e.snapshot().lastActivityAt
        clock.advance(5_000L)
        e.handle(Event.CallOffhook)
        val s = e.snapshot()
        assertTrue(s.alertSeq.offhookSeen)
        assertEquals(activityAt, s.lastActivityAt)
        assertEquals(Phase.ALERTING, s.phase)
    }

    @Test
    fun `offhook during round wait is own call not activity`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        endCall(e, ports, clock, CallLogResult.NOT_ANSWERED)
        assertEquals(SeqStep.ROUND_WAIT, e.snapshot().alertSeq.step)
        e.handle(Event.CallOffhook)
        assertEquals(Phase.ALERTING, e.snapshot().phase)
    }

    @Test
    fun `own alert call offhook within guard window is ignored`() {
        val (e, clock, _) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.ALERTED,
                lastActivityAt = t0,
                alertedAt = t0,
                alertSeq = CallSeq(step = SeqStep.DONE, dialedAt = t0),
            ),
        )
        clock.advance(5_000L) // 我们刚拨出的报警电话的 OFFHOOK 到了
        e.handle(Event.CallOffhook)
        assertEquals(Phase.ALERTED, e.snapshot().phase)
        assertEquals(t0, e.snapshot().lastActivityAt)
    }

    @Test
    fun `offhook after alert window counts as activity`() {
        val (e, clock, _) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.ALERTED,
                lastActivityAt = t0,
                alertedAt = t0,
                alertSeq = CallSeq(step = SeqStep.DONE, dialedAt = t0),
            ),
        )
        clock.advance(GuardEngine.ALERT_CALL_GUARD_MS + 1000)
        e.handle(Event.CallOffhook)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
    }

    @Test
    fun `answered incoming call right after alert counts as activity`() {
        val (e, clock, _) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.ALERTED,
                lastActivityAt = t0,
                alertedAt = t0,
                alertSeq = CallSeq(step = SeqStep.DONE, dialedAt = t0),
            ),
        )
        clock.advance(30_000L)
        e.handle(Event.CallRinging) // 有新的来电响铃
        clock.advance(5_000L)
        e.handle(Event.CallOffhook) // 被守护的人接听了 → 真实活动
        assertEquals(Phase.MONITORING, e.snapshot().phase)
        assertEquals(ActivityKind.CALL, e.snapshot().lastActivityKind)
    }

    @Test
    fun `answered incoming call during alert sequence counts as activity`() {
        // 序列期间有新来电响铃后再摘机 = 有人在用手机 → 解除
        val (e, clock, _) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        clock.advance(5_000L)
        e.handle(Event.CallRinging)
        clock.advance(2_000L)
        e.handle(Event.CallOffhook)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
    }

    @Test
    fun `ringing alone is not activity`() {
        val (e, clock, _) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(60_000L)
        e.handle(Event.CallRinging)
        assertEquals(t0, e.snapshot().lastActivityAt) // 未接来电不重置计时
        assertEquals(Phase.MONITORING, e.snapshot().phase)
    }

    // ---------- AlertNow ----------

    @Test
    fun `alert now from MONITORING starts sequence immediately`() {
        val (e, _, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        assertEquals(listOf(num), ports.calls)
        assertEquals(Phase.ALERTING, e.snapshot().phase)
        assertEquals(SeqStep.DIALING, e.snapshot().alertSeq.step)
        assertEquals(1, ports.sms.count { it.kind == SmsKind.EMERGENCY })
    }

    @Test
    fun `alert now from CONFIRMING starts sequence immediately`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        e.handle(Event.AlertNow)
        assertEquals(listOf(num), ports.calls)
        assertEquals(Phase.ALERTING, e.snapshot().phase)
    }

    @Test
    fun `alert now ignored while alerting or alerted`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.AlertNow)
        ports.calls.clear()
        e.handle(Event.AlertNow)
        assertTrue(ports.calls.isEmpty())
        runAlertSeqToEnd(e, ports, clock)
        e.handle(Event.AlertNow)
        assertEquals(listOf(num), ports.calls)
    }

    @Test
    fun `alert now ignored when disabled`() {
        val (e, _, ports) = fixture()
        e.handle(Event.AlertNow)
        assertTrue(ports.calls.isEmpty())
    }

    // ---------- 看门狗 ----------

    @Test
    fun `watchdog heals missed deadline`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L) // DeadlineAlarm 被系统吞了
        e.handle(Event.WatchdogAlarm)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
        assertEquals(1, ports.ringingStarts.size)
        assertEquals(2, ports.watchdogDelays.size) // 自愈后仍重挂
    }

    @Test
    fun `watchdog heals missed confirm end`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        clock.advance(2 * 60_000L) // ConfirmEndAlarm 被吞
        e.handle(Event.WatchdogAlarm)
        assertEquals(Phase.ALERTING, e.snapshot().phase)
        assertEquals(listOf(num), ports.calls)
    }

    @Test
    fun `watchdog ensures service and reschedules`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(60_000L)
        e.handle(Event.WatchdogAlarm)
        assertTrue(ports.ensureCount >= 2)
        assertEquals(2, ports.watchdogDelays.size)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
    }

    // ---------- 每日短信 ----------

    @Test
    fun `daily sms alarm sends to all contacts and reschedules`() {
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.DailySmsAlarm)
        val daily = ports.sms.filter { it.kind == SmsKind.DAILY }
        assertEquals(listOf(num, num2), daily.map { it.number })
        assertTrue(daily.all { it.body.startsWith("【守护侠】一切正常") })
        assertEquals(2, ports.dailySmsSchedules.size)
        assertTrue(e.snapshot().nextDailySmsAt > t0)
    }

    @Test
    fun `daily sms skipped when flag off but still reschedules`() {
        val (e, _, ports) = fixture(
            cfg = GuardConfig(
                contacts = listOf(c1), enabled = true, dailySmsEnabled = false)
        )
        e.handle(Event.DailySmsAlarm)
        assertTrue(ports.sms.isEmpty())
        assertEquals(1, ports.dailySmsSchedules.size)
    }

    @Test
    fun `daily sms skipped during alert flow`() {
        // 报警流程中不发平安短信——不能在报警时告诉家人"一切正常"
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(phase = Phase.ALERTING, lastActivityAt = t0),
        )
        e.handle(Event.DailySmsAlarm)
        assertTrue(ports.sms.isEmpty())
        assertTrue(ports.logs.any { it.second.contains("不发平安短信") })
        // ALERTED 同样不发
        val (e2, _, ports2) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(phase = Phase.ALERTED, lastActivityAt = t0, alertedAt = t0),
        )
        e2.handle(Event.DailySmsAlarm)
        assertTrue(ports2.sms.isEmpty())
    }

    // ---------- SystemReset ----------

    @Test
    fun `system reset with overdue deadline goes CONFIRMING not dial`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.SystemReset)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
        assertTrue(ports.calls.isEmpty())
        assertEquals(1, ports.ringingStarts.size)
    }

    @Test
    fun `system reset not overdue reschedules deadline`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(3600_000L)
        ports.deadlines.clear()
        e.handle(Event.SystemReset)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
        assertEquals(e.snapshot().deadlineAt, ports.deadlines.last())
        assertTrue(ports.ringingStarts.isEmpty())
    }

    @Test
    fun `system reset clamps future lastActivity and recomputes deadline`() {
        val future = t0 + 999_000L
        val (e, _, _) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.MONITORING,
                lastActivityAt = future,
                deadlineAt = future + 8 * 3600_000L,
            ),
        )
        e.handle(Event.SystemReset)
        val s = e.snapshot()
        assertEquals(t0, s.lastActivityAt)
        assertEquals(t0 + 8 * 3600_000L, s.deadlineAt)
    }

    @Test
    fun `system reset restores CONFIRMING ringing and alarms`() {
        val confirmEnd = t0 + 60_000L
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.CONFIRMING,
                lastActivityAt = t0,
                deadlineAt = t0,
                confirmEndAt = confirmEnd,
            ),
        )
        e.handle(Event.SystemReset)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
        assertEquals(listOf(confirmEnd), ports.ringingStarts)
        assertEquals(listOf(confirmEnd), ports.confirmEnds)
        assertEquals(1, ports.confirmNotifs.size)
        // 日报/看门狗补齐
        assertEquals(1, ports.dailySmsSchedules.size)
        assertEquals(1, ports.watchdogDelays.size)
    }

    @Test
    fun `system reset ignored when disabled`() {
        val (e, _, ports) = fixture()
        e.handle(Event.SystemReset)
        assertEquals(0, ports.ensureCount)
        assertTrue(ports.ringingStarts.isEmpty())
    }

    // ---------- 进程恢复（序列续走） ----------

    @Test
    fun `restart during dialing past fallback resumes with call log check`() {
        // 拨出后进程死掉：重建引擎，SystemReset → 超兜底时长 → 直接查记录判定
        val store = FakeStore(
            GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        store.state = store.state.copy(qualifiedPass = passedDrillRecord(listOf(c1, c2)))
        val clock = FakeClock(t0)
        val ports = FakePorts()
        val e1 = GuardEngine(store, clock, ports)
        e1.handle(Event.EnabledChanged(true))
        e1.handle(Event.AlertNow)
        assertEquals(listOf(num), ports.calls)
        clock.advance(AlertRules.CALL_FALLBACK_MS + 1000) // 超过兜底时长
        ports.callLog = { _, _ -> CallLogResult.ANSWERED }
        val e2 = GuardEngine(store, clock, ports) // 进程重建
        e2.handle(Event.SystemReset)
        // 不重拨已拨的那通、不重复群发，直接按通话记录判定推进
        assertEquals(listOf(num), ports.calls)
        // 两位联系人各收到一条紧急短信，群发未重复
        assertEquals(
            listOf(num, num2),
            ports.sms.filter { it.kind == SmsKind.EMERGENCY }.map { it.number }
        )
        assertEquals(Phase.ALERTED, e2.snapshot().phase)
        assertTrue(ports.sms.any { it.kind == SmsKind.ANSWERED })
    }

    @Test
    fun `restart during dialing within fallback only reschedules`() {
        val store = FakeStore(
            GuardConfig(contacts = listOf(c1), quietEnabled = false)
        )
        store.state = store.state.copy(qualifiedPass = passedDrillRecord(listOf(c1)))
        val clock = FakeClock(t0)
        val ports = FakePorts()
        val e1 = GuardEngine(store, clock, ports)
        e1.handle(Event.EnabledChanged(true))
        e1.handle(Event.AlertNow)
        val dialedAt = e1.snapshot().alertSeq.dialedAt
        clock.advance(10_000L) // 远未到兜底时刻：拨号可能刚开始，不查记录
        val e2 = GuardEngine(store, clock, ports)
        e2.handle(Event.SystemReset)
        assertEquals(SeqStep.DIALING, e2.snapshot().alertSeq.step)
        assertTrue(ports.callLogQueries.isEmpty())
        assertEquals(dialedAt + AlertRules.CALL_FALLBACK_MS, ports.callFallbacks.last())
    }

    @Test
    fun `restart during checking resumes the call log check`() {
        val store = FakeStore(
            GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        store.state = store.state.copy(qualifiedPass = passedDrillRecord(listOf(c1, c2)))
        val clock = FakeClock(t0)
        val ports = FakePorts()
        val e1 = GuardEngine(store, clock, ports)
        e1.handle(Event.EnabledChanged(true))
        e1.handle(Event.AlertNow)
        // 进入 CHECKING（见过摘机、已回 IDLE）
        e1.handle(Event.CallOffhook)
        e1.handle(Event.CallIdle)
        assertEquals(SeqStep.CHECKING, e1.snapshot().alertSeq.step)
        val e2 = GuardEngine(store, clock, ports)
        ports.callLog = { _, _ -> CallLogResult.NOT_ANSWERED }
        e2.handle(Event.SyncSession) // 恢复点：立即查记录
        assertEquals(1, ports.callLogQueries.size)
        // 判定后继续走下一位
        assertEquals(listOf(num, num2), ports.calls)
    }

    @Test
    fun `restart during round wait resumes next round`() {
        val store = FakeStore(
            GuardConfig(contacts = listOf(c1), quietEnabled = false)
        )
        store.state = store.state.copy(qualifiedPass = passedDrillRecord(listOf(c1)))
        val clock = FakeClock(t0)
        val ports = FakePorts()
        val e1 = GuardEngine(store, clock, ports)
        e1.handle(Event.EnabledChanged(true))
        e1.handle(Event.AlertNow)
        endCall(e1, ports, clock, CallLogResult.NOT_ANSWERED)
        assertEquals(SeqStep.ROUND_WAIT, e1.snapshot().alertSeq.step)
        val nextRoundAt = e1.snapshot().alertSeq.nextRoundAt
        val e2 = GuardEngine(store, clock, ports)
        e2.handle(Event.WatchdogAlarm) // 未到点：只补挂闹钟不重拨
        assertEquals(SeqStep.ROUND_WAIT, e2.snapshot().alertSeq.step)
        assertEquals(nextRoundAt, ports.nextRounds.last())
        assertEquals(1, ports.calls.size)
        clock.t = nextRoundAt
        e2.handle(Event.SyncSession) // 到点：开第 2 轮
        assertEquals(2, e2.snapshot().alertSeq.round)
        assertEquals(listOf(num, num), ports.calls)
    }

    @Test
    fun `restart at start step resends emergency only if not sent`() {
        // 序列停在 START 且紧急短信未发：恢复时先发短信再拨号
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.ALERTING,
                lastActivityAt = t0 - 9 * 3600_000L,
                alertSeq = CallSeq(step = SeqStep.START, startedAt = t0, round = 1),
            ),
        )
        e.handle(Event.SystemReset)
        assertEquals(1, ports.sms.count { it.kind == SmsKind.EMERGENCY })
        assertEquals(listOf(num), ports.calls)
        // 已发过紧急短信的 START（群发完成后进程死）：恢复时绝不重发
        val (e2, _, ports2) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), enabled = true),
            st = GuardState(
                phase = Phase.ALERTING,
                lastActivityAt = t0 - 9 * 3600_000L,
                alertSeq = CallSeq(step = SeqStep.START, startedAt = t0, round = 1),
                alertSmsFlags = GuardState.SMS_EMERGENCY_SENT,
            ),
        )
        e2.handle(Event.SystemReset)
        assertTrue(ports2.sms.none { it.kind == SmsKind.EMERGENCY })
        assertEquals(listOf(num), ports2.calls)
    }

    @Test
    fun `stale sequence events ignored when not in sequence`() {
        val (e, _, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.CallIdle)
        e.handle(Event.CallCheckAlarm)
        e.handle(Event.CallFallbackAlarm)
        e.handle(Event.NextRoundAlarm)
        assertTrue(ports.calls.isEmpty())
        assertTrue(ports.callLogQueries.isEmpty())
    }

    // ---------- 快速阈值 / 配置变更 ----------

    @Test
    fun `dev fast thresholds use 30s threshold and 15s confirm window`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(
                contacts = listOf(c1), enabled = true, devFastThresholds = true)
        )
        // 设备不在使用中：锁屏后倒计时才被快速阈值缩短到 30 秒
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        assertEquals(t0 + 30_000L, e.snapshot().deadlineAt)
        clock.advance(30_000L)
        e.handle(Event.DeadlineAlarm)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
        assertEquals(t0 + 45_000L, e.snapshot().confirmEndAt)
        clock.advance(15_000L)
        e.handle(Event.ConfirmEndAlarm)
        assertEquals(listOf(num), ports.calls)
    }

    @Test
    fun `config changed recomputes deadline with new threshold`() {
        val store = FakeStore(
            GuardConfig(contacts = listOf(c1)),
            GuardState(qualifiedPass = passedDrillRecord(listOf(c1)))
        )
        val clock = FakeClock(t0)
        val ports = FakePorts()
        val e = GuardEngine(store, clock, ports)
        e.handle(Event.EnabledChanged(true))
        clock.advance(60_000L)
        store.config = store.config.copy(thresholdMinutes = 120)
        e.handle(Event.ConfigChanged)
        assertEquals(t0 + 120 * 60_000L, e.snapshot().deadlineAt)
        assertEquals(e.snapshot().deadlineAt, ports.deadlines.last())
    }

    @Test
    fun `config changed moves daily sms schedule`() {
        val store = FakeStore(
            GuardConfig(contacts = listOf(c1)),
            GuardState(qualifiedPass = passedDrillRecord(listOf(c1)))
        )
        val clock = FakeClock(t0)
        val ports = FakePorts()
        val e = GuardEngine(store, clock, ports)
        e.handle(Event.EnabledChanged(true))
        val before = ports.dailySmsSchedules.last()
        store.config = store.config.copy(dailySmsMinuteOfDay = 20 * 60)
        e.handle(Event.ConfigChanged)
        assertTrue(ports.dailySmsSchedules.last() != before || ports.dailySmsSchedules.size >= 2)
    }

    // ---------- 使用会话（inUse） ----------

    @Test
    fun `enable while device in use marks inUse and uses cap not test threshold`() {
        val (e, _, _) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), devFastThresholds = true)
        )
        // FakePorts.deviceInUse 默认 true
        e.handle(Event.EnabledChanged(true))
        val s = e.snapshot()
        assertTrue(s.inUse)
        // 使用中上限不受快速阈值影响：仍是 8 小时，不是 30 秒
        assertEquals(t0 + 480 * 60_000L, s.deadlineAt)
    }

    @Test
    fun `enable while device locked starts countdown immediately`() {
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), devFastThresholds = true)
        )
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        val s = e.snapshot()
        assertFalse(s.inUse)
        assertEquals(t0 + 30_000L, s.deadlineAt)
    }

    @Test
    fun `screen off after use ends session and starts after-lock countdown`() {
        val (e, clock, _) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(3600_000L)
        e.handle(Event.ScreenOff)
        val s = e.snapshot()
        assertFalse(s.inUse)
        assertEquals(ActivityKind.LOCK_AFTER_USE, s.lastActivityKind)
        assertEquals(t0 + 3600_000L, s.lastActivityAt)
        assertEquals(t0 + 3600_000L, s.lastLockAt)
        assertEquals(t0 + 3600_000L + 8 * 3600_000L, s.deadlineAt)
    }

    @Test
    fun `screen off after use with fast thresholds counts down 30s`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), devFastThresholds = true)
        )
        // 先锁屏态开启，再解锁进入会话
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        clock.advance(10_000L)
        e.handle(Event.UserPresent)
        clock.advance(5_000L)
        e.handle(Event.ScreenOff)
        val s = e.snapshot()
        assertFalse(s.inUse)
        assertEquals(t0 + 15_000L, s.lastLockAt)
        assertEquals(t0 + 15_000L + 30_000L, s.deadlineAt)
    }

    @Test
    fun `screen off without session is a no-op`() {
        val (e, _, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        val before = e.snapshot()
        val deadlinesBefore = ports.deadlines.size
        e.handle(Event.ScreenOff)
        assertEquals(before, e.snapshot())
        assertEquals(deadlinesBefore, ports.deadlines.size)
    }

    @Test
    fun `screen off ignored when disabled`() {
        val (e, _, ports) = fixture()
        e.handle(Event.ScreenOff)
        assertFalse(e.snapshot().inUse)
        assertTrue(ports.deadlines.isEmpty())
    }

    @Test
    fun `user present starts session and deadline uses in-use cap`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), devFastThresholds = true)
        )
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        clock.advance(10_000L)
        e.handle(Event.UserPresent)
        val s = e.snapshot()
        assertTrue(s.inUse)
        assertEquals(ActivityKind.UNLOCK, s.lastActivityKind)
        assertEquals(t0 + 10_000L, s.lastActivityAt)
        // 使用中：走正常阈值上限（快速阈值不缩短）
        assertEquals(t0 + 10_000L + 480 * 60_000L, s.deadlineAt)
    }

    @Test
    fun `screen off during CONFIRMING only ends session and keeps ringing`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        e.handle(Event.ScreenOff)
        val s = e.snapshot()
        assertEquals(Phase.CONFIRMING, s.phase)
        assertFalse(s.inUse)
        // 熄屏不算平安：不停止响铃、不取消确认闹钟、不重置确认截止
        assertEquals(0, ports.ringStopCount)
        assertEquals(0, ports.cancelConfirmCount)
        assertEquals(t0 + 8 * 3600_000L + 2 * 60_000L, s.confirmEndAt)
    }

    @Test
    fun `sync session records missed unlock`() {
        val (e, _, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        ports.deviceInUse = true
        e.handle(Event.SyncSession)
        val s = e.snapshot()
        assertTrue(s.inUse)
        assertEquals(ActivityKind.UNLOCK, s.lastActivityKind)
        assertEquals(t0, s.lastActivityAt)
        assertEquals(t0 + 480 * 60_000L, s.deadlineAt)
    }

    @Test
    fun `sync session ends session without screen off broadcast`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(3600_000L)
        ports.deviceInUse = false
        e.handle(Event.SyncSession)
        val s = e.snapshot()
        assertFalse(s.inUse)
        // 保守：仍按上次活动起算锁屏后阈值
        assertEquals(t0, s.lastActivityAt)
        assertEquals(t0 + 8 * 3600_000L, s.deadlineAt)
        assertEquals(s.deadlineAt, ports.deadlines.last())
    }

    @Test
    fun `sync session with overdue recomputed deadline enters CONFIRMING`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(9 * 3600_000L)
        ports.deviceInUse = false
        e.handle(Event.SyncSession)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
        assertEquals(1, ports.ringingStarts.size)
    }

    @Test
    fun `sync session during CONFIRMING does nothing`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        val before = e.snapshot()
        e.handle(Event.SyncSession)
        val after = e.snapshot()
        // 只补挂闹钟/更新 nextDailySmsAt，不推进报警流程
        assertEquals(before.copy(nextDailySmsAt = after.nextDailySmsAt), after)
        assertTrue(ports.calls.isEmpty())
    }

    @Test
    fun `sync session re-arms lost alarms when monitoring`() {
        // 进程被强停后闹钟全丢：MONITORING 且截止未到 → 补挂截止/平安短信/看门狗
        val (e, _, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        ports.deadlines.clear()
        ports.dailySmsSchedules.clear()
        ports.watchdogDelays.clear()
        e.handle(Event.SyncSession)
        val s = e.snapshot()
        assertEquals(Phase.MONITORING, s.phase)
        assertEquals(s.deadlineAt, ports.deadlines.last())
        assertEquals(s.nextDailySmsAt, ports.dailySmsSchedules.last())
        assertEquals(GuardEngine.WATCHDOG_INTERVAL_MS, ports.watchdogDelays.last())
        assertTrue(ports.calls.isEmpty())
    }

    @Test
    fun `sync session schedules nothing when disabled`() {
        val (e, _, ports) = fixture()
        e.handle(Event.SyncSession)
        assertTrue(ports.deadlines.isEmpty())
        assertTrue(ports.confirmEnds.isEmpty())
        assertTrue(ports.dailySmsSchedules.isEmpty())
        assertTrue(ports.watchdogDelays.isEmpty())
        assertTrue(ports.calls.isEmpty())
    }

    @Test
    fun `sync session in CONFIRMING with overdue window never dials`() {
        // 过期确认窗口交给看门狗：SyncSession 不得拨号
        val (e, clock, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        val before = e.snapshot()
        clock.advance(3 * 60_000L)   // 超过 2 分钟确认窗口
        e.handle(Event.SyncSession)
        val s = e.snapshot()
        assertEquals(Phase.CONFIRMING, s.phase)
        assertEquals(before.confirmEndAt, s.confirmEndAt)
        assertTrue(ports.calls.isEmpty())
        // 闹钟仍补挂：过期窗口不重复挂确认结束闹钟，平安短信/看门狗照旧
        assertEquals(1, ports.confirmEnds.count())
        assertEquals(GuardEngine.WATCHDOG_INTERVAL_MS, ports.watchdogDelays.last())
    }

    @Test
    fun `sync session in CONFIRMING re-arms pending confirm end`() {
        val (e, clock, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        clock.advance(8 * 3600_000L)
        e.handle(Event.DeadlineAlarm)
        clock.advance(30_000L)       // 窗口未过期
        e.handle(Event.SyncSession)
        val s = e.snapshot()
        assertEquals(Phase.CONFIRMING, s.phase)
        assertEquals(s.confirmEndAt, ports.confirmEnds.last())
        assertTrue(ports.calls.isEmpty())
    }

    @Test
    fun `watchdog syncs session but never starts service when disabled`() {
        val (e, _, ports) = fixture()
        e.handle(Event.WatchdogAlarm)
        assertEquals(0, ports.ensureCount)
        assertTrue(ports.watchdogDelays.isEmpty())

        val (e2, _, ports2) = fixture()
        ports2.deviceInUse = false
        e2.handle(Event.EnabledChanged(true))
        ports2.deviceInUse = true
        e2.handle(Event.WatchdogAlarm)
        // 看门狗顺带找回漏掉的解锁会话
        assertTrue(e2.snapshot().inUse)
        assertEquals(ActivityKind.UNLOCK, e2.snapshot().lastActivityKind)
        assertEquals(2, ports2.watchdogDelays.size)
    }

    @Test
    fun `disable stops foreground service and clears inUse`() {
        val (e, _, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        assertTrue(e.snapshot().inUse)
        e.handle(Event.EnabledChanged(false))
        assertEquals(1, ports.stopServiceCount)
        assertFalse(e.snapshot().inUse)
    }

    @Test
    fun `in-use cap deadline still enters CONFIRMING`() {
        val (e, clock, _) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), devFastThresholds = true)
        )
        // 持续使用中：30 秒闹钟到点不算数，8 小时上限到点仍响铃
        e.handle(Event.EnabledChanged(true))
        clock.advance(30_000L)
        e.handle(Event.DeadlineAlarm)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
        clock.advance(8 * 3600_000L - 30_000L)
        e.handle(Event.DeadlineAlarm)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
    }

    @Test
    fun `call keeps inUse unchanged`() {
        val (e, clock, _) = fixture()
        e.handle(Event.EnabledChanged(true))
        clock.advance(60_000L)
        e.handle(Event.CallOffhook)
        assertTrue(e.snapshot().inUse)
        assertEquals(ActivityKind.CALL, e.snapshot().lastActivityKind)

        val (e2, clock2, ports2) = fixture()
        ports2.deviceInUse = false
        e2.handle(Event.EnabledChanged(true))
        clock2.advance(60_000L)
        e2.handle(Event.CallOffhook)
        assertFalse(e2.snapshot().inUse)
        assertEquals(ActivityKind.CALL, e2.snapshot().lastActivityKind)
    }

    // ---------- 实机演练 ----------

    /** 推进到演练等待中：点开始（解锁态）→ 锁屏。返回计划响铃时刻。 */
    private fun GuardEngine.startDrillWaiting(
        ports: FakePorts, clock: FakeClock, waitMinutes: Int = 30
    ): Long {
        ports.deviceInUse = true
        handle(Event.DrillStart(waitMinutes))
        clock.advance(5_000L)
        handle(Event.ScreenOff)
        return clock.t + waitMinutes * 60_000L
    }

    /** 推进到演练响铃：到点闹钟触发（设备已锁屏）。 */
    private fun GuardEngine.fireDrill(ports: FakePorts, clock: FakeClock, at: Long) {
        ports.deviceInUse = false
        clock.t = at
        handle(Event.DrillAlarm)
    }

    /** 推进到拨号：演练确认窗口结束闹钟（群发测试短信 + 拨第一位）。 */
    private fun GuardEngine.dialDrill(ports: FakePorts, clock: FakeClock) {
        ports.deviceInUse = false
        handle(Event.DrillConfirmEndAlarm)
    }

    @Test
    fun `drill cannot start without contact`() {
        val (e, _, ports) = fixture(
            cfg = GuardConfig(quietEnabled = false),
            withRecord = false,
        )
        e.handle(Event.DrillStart(30))
        assertEquals(DrillPhase.NONE, e.snapshot().drill.phase)
        assertTrue(ports.drillAlarms.isEmpty())
        assertTrue(ports.logs.any { it.second.contains("紧急联系人") })
    }

    @Test
    fun `drill waits for screen off then schedules alarm at lock plus wait`() {
        val (e, clock, ports) = fixture(withRecord = false)
        ports.deviceInUse = true
        e.handle(Event.DrillStart(30))
        // 还在使用中：等锁屏，不挂闹钟
        assertEquals(DrillPhase.WAIT_LOCK, e.snapshot().drill.phase)
        assertTrue(ports.drillAlarms.isEmpty())
        assertTrue(ports.ensureCount >= 1)
        clock.advance(10_000L)
        e.handle(Event.ScreenOff)
        val d = e.snapshot().drill
        assertEquals(DrillPhase.WAITING, d.phase)
        assertEquals(t0 + 10_000L, d.lockedAt)
        assertEquals(t0 + 10_000L + 30 * 60_000L, d.plannedAt)
        assertEquals(d.plannedAt, ports.drillAlarms.last())
    }

    @Test
    fun `drill starts waiting immediately when already locked`() {
        val (e, _, ports) = fixture(withRecord = false)
        ports.deviceInUse = false
        e.handle(Event.DrillStart(30))
        val d = e.snapshot().drill
        assertEquals(DrillPhase.WAITING, d.phase)
        assertEquals(t0 + 30 * 60_000L, d.plannedAt)
        assertEquals(d.plannedAt, ports.drillAlarms.last())
    }

    @Test
    fun `unlock during drill wait aborts without failing`() {
        val (e, clock, ports) = fixture(withRecord = false)
        e.startDrillWaiting(ports, clock)
        clock.advance(60_000L) // 还没到计划时刻
        e.handle(Event.UserPresent)
        val s = e.snapshot()
        assertEquals(DrillPhase.NONE, s.drill.phase)
        assertEquals(DrillOutcome.ABORTED, s.lastDrillResult?.outcome)
        assertTrue(s.lastDrillResult?.reason.orEmpty().contains("解锁"))
        // 守护未开启：演练结束前台服务要停
        assertTrue(ports.stopServiceCount >= 1)
    }

    @Test
    fun `planned time passed without firing detected on user present as failure`() {
        val (e, clock, ports) = fixture(withRecord = false)
        e.startDrillWaiting(ports, clock)
        clock.advance(31 * 60_000L) // 计划时刻已过、闹钟没响（后台被系统清理）
        e.handle(Event.UserPresent)
        val r = e.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertTrue(r.reason.contains("没有响铃"))
        assertFalse(r.onTime)
        assertTrue(ports.drillRingStarts.isEmpty())
    }

    @Test
    fun `planned time passed detected on sync session as failure`() {
        val (e, clock, ports) = fixture(withRecord = false)
        e.startDrillWaiting(ports, clock)
        clock.advance(31 * 60_000L)
        e.handle(Event.SyncSession)
        assertEquals(DrillOutcome.FAILED, e.snapshot().lastDrillResult?.outcome)
    }

    @Test
    fun `drill alarm fires ringing and schedules confirm end`() {
        val (e, clock, ports) = fixture(withRecord = false)
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        val d = e.snapshot().drill
        assertEquals(DrillPhase.RINGING, d.phase)
        assertEquals(planned, d.firedAt)
        // 确认窗口用正式配置的确认时长（默认 2 分钟）
        assertEquals(planned + 2 * 60_000L, d.confirmEndAt)
        assertEquals(listOf(d.confirmEndAt), ports.drillRingStarts)
        assertEquals(listOf(d.confirmEndAt), ports.drillConfirmEnds)
    }

    @Test
    fun `drill alarm early reschedules and keeps waiting`() {
        val (e, clock, ports) = fixture(withRecord = false)
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned - 10_000L)
        assertEquals(DrillPhase.WAITING, e.snapshot().drill.phase)
        assertTrue(ports.drillRingStarts.isEmpty())
        assertEquals(planned, ports.drillAlarms.last())
    }

    @Test
    fun `drill fired late beyond 60s marks on-time item failed`() {
        val (e, clock, ports) = fixture(withRecord = false)
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned + 61_000L) // 晚 61 秒
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        // 短信回执 ok + 用户答都收到
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        answerAllContacts(e, 1)
        val r = e.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertFalse(r.onTime)
        assertTrue(r.reason.contains("系统限制"))
    }

    @Test
    fun `drill full happy path passes and writes record`() {
        val (e, clock, ports) = fixture(withRecord = false)
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned + 8_000L) // 晚 8 秒，容差内
        clock.advance(2_000L)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L - 2_000L)
        e.dialDrill(ports, clock)
        assertEquals(DrillPhase.CALLING, e.snapshot().drill.phase)
        // 测试短信先群发（【守护侠·测试】），再拨号
        val testSms = ports.sms.filter { it.kind == SmsKind.DRILL_TEST }
        assertEquals(1, testSms.size)
        assertTrue(testSms[0].body.startsWith("【守护侠·测试】"))
        assertTrue(ports.sms.none { it.kind != SmsKind.DRILL_TEST })
        assertEquals(listOf(num), ports.calls)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        assertEquals(DrillPhase.ASK_ANSWER, e.snapshot().drill.phase)
        // 短信发出回执 + 用户逐项确认
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        e.handle(Event.DrillContactAnswer(0, smsReceived = true, callReceived = true))
        val s = e.snapshot()
        assertEquals(DrillPhase.NONE, s.drill.phase)
        val r = s.lastDrillResult!!
        assertEquals(DrillOutcome.PASSED, r.outcome)
        assertTrue(r.onTime && r.fullscreenOk &&
            r.contactResults.isNotEmpty() && r.contactResults.all { it.ok })
        assertEquals(30, r.waitMinutes)
        assertEquals(8_000L, r.delayMillis)
        assertEquals(1, r.contactResults.size)
        assertTrue(r.contactResults[0].ok)
        // 记录带环境指纹与联系人 key，供开启门槛判定
        assertEquals(1L, r.versionCode)
        assertEquals("fake-fp", r.fingerprint)
        assertEquals(DrillRules.contactsKey(listOf(c1)), r.contactsKey)
        assertEquals(DrillRules.SCHEMA, r.schema)
        // 演练结束且守护未开启 → 前台服务停止
        assertTrue(ports.stopServiceCount >= 1)
    }

    @Test
    fun `drill dials every contact once even under stop-on-answer`() {
        // 演练不论策略每位只拨一次：第 1 位接通也拨第 2 位
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false),
            withRecord = false,
        )
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        // 测试短信群发给了所有联系人
        assertEquals(listOf(num, num2),
            ports.sms.filter { it.kind == SmsKind.DRILL_TEST }.map { it.number })
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        assertEquals(listOf(num, num2), ports.calls)
        assertEquals(DrillPhase.ASK_ANSWER, e.snapshot().drill.phase)
        assertEquals(0, e.snapshot().drill.askIndex)
        // 乱序回答被忽略（先问第 0 位）
        e.handle(Event.DrillContactAnswer(1, smsReceived = true, callReceived = true))
        assertEquals(0, e.snapshot().drill.askIndex)
        // 短信回执 ok + 逐位问答
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 1, ok = true))
        answerAllContacts(e, 2)
        val r = e.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.PASSED, r.outcome)
        assertEquals(2, r.contactResults.size)
        assertTrue(r.contactResults.all { it.ok })
    }

    @Test
    fun `drill fails when one contact item fails with per-contact reason`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false),
            withRecord = false,
        )
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        // 第 1 位接通、第 2 位未接通
        endCall(e, ports, clock, CallLogResult.ANSWERED)
        endCall(e, ports, clock, CallLogResult.NOT_ANSWERED)
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 1, ok = true))
        answerAllContacts(e, 2)
        val r = e.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertFalse(r.contactResults[1].ok)
        assertFalse(r.contactResults[1].callAnswered)
        assertTrue(r.reason.contains("李四") && r.reason.contains("电话未接通"))
    }

    @Test
    fun `drill fails when sms was not delivered`() {
        val (e, clock, ports) = fixture(withRecord = false)
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        // 回执失败（多段任一失败即失败）
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = false))
        answerAllContacts(e, 1)
        val r = e.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertFalse(r.contactResults[0].smsSent)
        assertTrue(r.reason.contains("测试短信未发出"))
    }

    @Test
    fun `drill fails when user says sms or call not received`() {
        val (e, clock, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2), quietEnabled = false),
            withRecord = false,
        )
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 1, ok = true))
        // 第 0 位全收，第 1 位没收到短信也没接到电话
        e.handle(Event.DrillContactAnswer(0, smsReceived = true, callReceived = true))
        e.handle(Event.DrillContactAnswer(1, smsReceived = false, callReceived = false))
        val r = e.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertTrue(r.contactResults[0].ok)
        assertFalse(r.contactResults[1].ok)
        assertTrue(r.reason.contains("没收到测试短信"))
        assertTrue(r.reason.contains("没接到电话"))
    }

    @Test
    fun `drill without fullscreen report fails that item`() {
        val (e, clock, ports) = fixture(withRecord = false)
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        answerAllContacts(e, 1)
        val r = e.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertFalse(r.fullscreenOk)
        assertTrue(r.reason.contains("全屏"))
    }

    @Test
    fun `drill placeCall failure fails call item with perm reason`() {
        val (e, clock, ports) = fixture(withRecord = false)
        ports.placeCallResult = false
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        // 发起失败直接判未接通 → 单联系人直接进问答
        assertEquals(DrillPhase.ASK_ANSWER, e.snapshot().drill.phase)
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        e.handle(Event.DrillContactAnswer(0, smsReceived = true, callReceived = true))
        val r = e.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertFalse(r.contactResults[0].callAnswered)
        assertTrue(r.reason.contains("拨号权限不足"))
        // 演练绝不发 EMERGENCY/DAILY
        assertTrue(ports.sms.all { it.kind == SmsKind.DRILL_TEST })
    }

    @Test
    fun `drill own call offhook is not real activity when enabled`() {
        val (e, clock, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        val activityAt = e.snapshot().lastActivityAt
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        clock.advance(5_000L)
        e.handle(Event.CallOffhook)
        val s = e.snapshot()
        // 演练拨号的摘机不算"人在使用手机"
        assertEquals(activityAt, s.lastActivityAt)
        assertEquals(Phase.MONITORING, s.phase)
        assertTrue(s.drill.calls.offhookSeen)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        answerAllContacts(e, 1)
        assertEquals(DrillOutcome.PASSED, e.snapshot().lastDrillResult?.outcome)
    }

    @Test
    fun `drill stop aborts`() {
        val (e, clock, ports) = fixture(withRecord = false)
        e.startDrillWaiting(ports, clock)
        e.handle(Event.DrillStop)
        assertEquals(DrillPhase.NONE, e.snapshot().drill.phase)
        assertEquals(DrillOutcome.ABORTED, e.snapshot().lastDrillResult?.outcome)
        assertTrue(ports.cancelDrillCount >= 1)
    }

    @Test
    fun `enable refused without valid drill record`() {
        val (e, _, ports) = fixture(withRecord = false)
        e.handle(Event.EnabledChanged(true))
        assertFalse(e.snapshotConfig().enabled)
        assertEquals(0, ports.ensureCount)
        assertTrue(ports.logs.any { it.second.contains("演练记录") })
    }

    @Test
    fun `drill pass under 30 minutes does not satisfy enable gate`() {
        val (e, clock, ports) = fixture(withRecord = false)
        // 快速检查：1 分钟等待完整走完并通过
        ports.deviceInUse = true
        e.handle(Event.DrillStart(1))
        e.handle(Event.ScreenOff)
        clock.advance(60_000L)
        ports.deviceInUse = false
        e.handle(Event.DrillAlarm)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.handle(Event.DrillConfirmEndAlarm)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        answerAllContacts(e, 1)
        assertEquals(DrillOutcome.PASSED, e.snapshot().lastDrillResult?.outcome)
        assertEquals(1, e.snapshot().lastDrillResult?.waitMinutes)
        // 等待不足 30 分钟的通过记录不满足开启门槛
        e.handle(Event.EnabledChanged(true))
        assertFalse(e.snapshotConfig().enabled)
    }

    @Test
    fun `drill record invalidated by version fingerprint or contact change`() {
        // versionCode 变化（App 升级）
        val (e1, _, p1) = fixture()
        p1.versionCode = 2L
        e1.handle(Event.EnabledChanged(true))
        assertFalse(e1.snapshotConfig().enabled)
        // 指纹变化（系统升级）
        val (e2, _, p2) = fixture()
        p2.fingerprint = "new-fp"
        e2.handle(Event.EnabledChanged(true))
        assertFalse(e2.snapshotConfig().enabled)
        // 联系人号码更换（记录里是旧号码）
        val (e3, _, _) = fixture(
            cfg = GuardConfig(contacts = listOf(EmergencyContact("张三", "13900002222"))),
            st = GuardState(qualifiedPass = passedDrillRecord(listOf(c1))),
        )
        e3.handle(Event.EnabledChanged(true))
        assertFalse(e3.snapshotConfig().enabled)
        // 联系人增/删/换顺序同样失效
        val (e4, _, _) = fixture(
            cfg = GuardConfig(contacts = listOf(c1, c2)),
            st = GuardState(qualifiedPass = passedDrillRecord(listOf(c1))),
        )
        e4.handle(Event.EnabledChanged(true))
        assertFalse(e4.snapshotConfig().enabled)
        // 只改名字不失效（contactsKey 不看姓名）
        val (e5, _, _) = fixture(
            cfg = GuardConfig(contacts = listOf(EmergencyContact("爸爸", num))),
            st = GuardState(qualifiedPass = passedDrillRecord(listOf(c1))),
        )
        e5.handle(Event.EnabledChanged(true))
        assertTrue(e5.snapshotConfig().enabled)
    }

    @Test
    fun `schema1 legacy record is invalidated`() {
        val legacy = passedDrillRecord(listOf(c1)).copy(schema = 1)
        val reasons = DrillRules.invalidReasons(
            legacy, 1L, "fake-fp", DrillRules.contactsKey(listOf(c1))
        )
        assertTrue(reasons.any { it.contains("演练规则已更新") })
    }

    @Test
    fun `contacts key ignores name and formatting but tracks order and digits`() {
        val k1 = DrillRules.contactsKey(listOf(c1, c2))
        // 改名不失效
        assertEquals(
            k1,
            DrillRules.contactsKey(
                listOf(EmergencyContact("改名", num), EmergencyContact("又改", num2))
            )
        )
        // 号码格式（空格/横线）不影响 key
        assertEquals(
            DrillRules.contactsKey(listOf(EmergencyContact("a", "138-0000-1111"))),
            DrillRules.contactsKey(listOf(EmergencyContact("b", num)))
        )
        // 换顺序失效
        assertTrue(k1 != DrillRules.contactsKey(listOf(c2, c1)))
        // 开头 + 保留
        assertEquals(
            "+8613800001111",
            DrillRules.contactsKey(listOf(EmergencyContact("a", "+86 138 0000 1111")))
        )
    }

    @Test
    fun `changing contacts during drill aborts it`() {
        val store = FakeStore(
            GuardConfig(contacts = listOf(c1, c2), quietEnabled = false)
        )
        val clock = FakeClock(t0)
        val ports = FakePorts()
        val e = GuardEngine(store, clock, ports)
        e.startDrillWaiting(ports, clock)
        assertEquals(DrillPhase.WAITING, e.snapshot().drill.phase)
        // 演练进行中修改联系人 → 中止（改名不算修改）
        store.config = store.config.copy(
            contacts = listOf(EmergencyContact("改名", num), c2)
        )
        e.handle(Event.ConfigChanged)
        assertEquals(DrillPhase.WAITING, e.snapshot().drill.phase)
        store.config = store.config.copy(contacts = listOf(c1))
        e.handle(Event.ConfigChanged)
        assertEquals(DrillPhase.NONE, e.snapshot().drill.phase)
        assertEquals(DrillOutcome.ABORTED, e.snapshot().lastDrillResult?.outcome)
        assertTrue(e.snapshot().lastDrillResult?.reason.orEmpty().contains("联系人"))
    }

    @Test
    fun `stale drill record does not disable enabled guard`() {
        // 已开启的老用户升级后记录失效：不能因此自动关闭守护
        val (e, _, ports) = fixture(
            cfg = GuardConfig(
                contacts = listOf(c1), enabled = true, quietEnabled = false),
            st = GuardState(
                phase = Phase.MONITORING,
                lastActivityAt = t0,
                deadlineAt = t0 + 3600_000L,
                qualifiedPass = passedDrillRecord(listOf(c1), versionCode = 1),
            ),
        )
        ports.versionCode = 99L // 环境已变，记录失效
        e.handle(Event.ConfigChanged)
        e.handle(Event.WatchdogAlarm)
        assertTrue(e.snapshotConfig().enabled)
        assertEquals(Phase.MONITORING, e.snapshot().phase)
    }

    @Test
    fun `real confirm during drill ringing aborts drill without stopping real ringing`() {
        val (e, clock, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        assertEquals(DrillPhase.RINGING, e.snapshot().drill.phase)
        // 真实守护到期进入确认 → 演练中止；真实响铃照常开始、不额外停铃
        val ringStopsBefore = ports.ringStopCount
        e.handle(Event.ForceDeadline)
        assertEquals(Phase.CONFIRMING, e.snapshot().phase)
        assertEquals(DrillPhase.NONE, e.snapshot().drill.phase)
        assertEquals(DrillOutcome.ABORTED, e.snapshot().lastDrillResult?.outcome)
        assertEquals(1, ports.ringingStarts.size) // 真实响铃启动
        assertEquals(ringStopsBefore, ports.ringStopCount) // 没有多余的停铃
        assertTrue(ports.cancelDrillCount >= 1)
    }

    @Test
    fun `disable during drill keeps drill running and service`() {
        val (e, clock, ports) = fixture()
        e.handle(Event.EnabledChanged(true))
        e.startDrillWaiting(ports, clock)
        e.handle(Event.EnabledChanged(false))
        val s = e.snapshot()
        assertFalse(e.snapshotConfig().enabled)
        // 演练继续：不被守护关闭影响，前台服务不能停
        assertEquals(DrillPhase.WAITING, s.drill.phase)
        assertEquals(0, ports.stopServiceCount)
        assertTrue(ports.cancelAllCount >= 1)
    }

    @Test
    fun `system reset aborts drill`() {
        val (e, clock, ports) = fixture(withRecord = false)
        e.startDrillWaiting(ports, clock)
        e.handle(Event.SystemReset)
        assertEquals(DrillPhase.NONE, e.snapshot().drill.phase)
        assertEquals(DrillOutcome.ABORTED, e.snapshot().lastDrillResult?.outcome)
        assertTrue(ports.cancelDrillCount >= 1)
    }

    @Test
    fun `drill state survives engine rebuild and overdue is judged`() {
        // 模拟进程被杀：同一 Store 重建引擎后，演练状态继续判定
        val store = FakeStore(
            GuardConfig(contacts = listOf(c1), quietEnabled = false)
        )
        val clock = FakeClock(t0)
        val ports = FakePorts()
        val e1 = GuardEngine(store, clock, ports)
        ports.deviceInUse = true
        e1.handle(Event.DrillStart(30))
        e1.handle(Event.ScreenOff)
        val e2 = GuardEngine(store, clock, ports) // 重建（从 Store 重载）
        assertEquals(DrillPhase.WAITING, e2.snapshot().drill.phase)
        clock.advance(31 * 60_000L) // 闹钟没响
        e2.handle(Event.SyncSession)
        val r = e2.snapshot().lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertFalse(r.onTime)
    }

    @Test
    fun `fast thresholds only when flag set`() {
        // devFastThresholds=false：锁屏后阈值仍是正式 8 小时（不缩短）
        val (e, _, ports) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), devFastThresholds = false)
        )
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        assertEquals(t0 + 8 * 3600_000L, e.snapshot().deadlineAt)
        // devFastThresholds=true：缩到 30 秒
        val (e2, _, ports2) = fixture(
            cfg = GuardConfig(contacts = listOf(c1), devFastThresholds = true)
        )
        ports2.deviceInUse = false
        e2.handle(Event.EnabledChanged(true))
        assertEquals(t0 + 30_000L, e2.snapshot().deadlineAt)
    }

    // ---------- qualifiedPass（开启门槛凭据）与 lastDrillResult 分离 ----------

    @Test
    fun `short drill pass while enabled does not overwrite qualified pass`() {
        // fixture 已预置 30 分钟合格记录；开启后做 1 分钟快速检查通过，
        // lastDrillResult 更新但 qualifiedPass（门槛凭据）不被冲掉
        val (e, clock, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.DrillStart(1))
        clock.advance(60_000L)
        e.handle(Event.DrillAlarm)
        e.handle(Event.DrillScreenShown(keyguardLocked = true))
        clock.advance(2 * 60_000L)
        e.handle(Event.DrillConfirmEndAlarm)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        answerAllContacts(e, 1)
        val s = e.snapshot()
        assertEquals(DrillOutcome.PASSED, s.lastDrillResult?.outcome)
        assertEquals(1, s.lastDrillResult?.waitMinutes)
        assertEquals(30, s.qualifiedPass?.waitMinutes) // 门槛凭据不变
        // 关再开仍可通过门槛
        e.handle(Event.EnabledChanged(false))
        e.handle(Event.EnabledChanged(true))
        assertTrue(e.snapshotConfig().enabled)
    }

    @Test
    fun `aborted drill keeps qualified pass`() {
        // 中止不算失败：1 分钟演练点"停止测试"，lastDrillResult=ABORTED，
        // qualifiedPass 保持不变（不产生异常、门槛仍有效）
        val (e, clock, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        e.handle(Event.DrillStart(1))
        clock.advance(60_000L)
        e.handle(Event.DrillStop)
        val s = e.snapshot()
        assertEquals(DrillOutcome.ABORTED, s.lastDrillResult?.outcome)
        assertEquals(30, s.qualifiedPass?.waitMinutes)
        e.handle(Event.EnabledChanged(false))
        e.handle(Event.EnabledChanged(true))
        assertTrue(e.snapshotConfig().enabled)
    }

    @Test
    fun `failed drill clears qualified pass`() {
        // 严谨原则：任何时长的 FAILED 都清空门槛凭据（这台手机刚被证明靠不住）
        val (e, clock, ports) = fixture()
        ports.deviceInUse = false
        e.handle(Event.EnabledChanged(true))
        val planned = e.startDrillWaiting(ports, clock)
        e.fireDrill(ports, clock, planned)
        // 不报全屏页 → 必失败
        clock.advance(2 * 60_000L)
        e.dialDrill(ports, clock)
        runDrillCalls(e, ports, clock, CallLogResult.ANSWERED)
        e.handle(Event.SmsSent(SmsKind.DRILL_TEST, 0, ok = true))
        answerAllContacts(e, 1)
        val s = e.snapshot()
        assertEquals(DrillOutcome.FAILED, s.lastDrillResult?.outcome)
        assertNull(s.qualifiedPass)
        // 门槛随之失效：关闭后无法再开启
        e.handle(Event.EnabledChanged(false))
        e.handle(Event.EnabledChanged(true))
        assertFalse(e.snapshotConfig().enabled)
    }

    @Test
    fun `drill alarm delivered while device in use is failure not abort`() {
        // 服务活着时解锁会先走 onDrillUserPresent 结束演练；到点仍是"等待中+使用中"
        // 只能是广播丢了（进程被杀）或闹钟推迟到解锁后送达——都判 FAILED
        val (e, clock, ports) = fixture(withRecord = false)
        val planned = e.startDrillWaiting(ports, clock)
        clock.t = planned
        ports.deviceInUse = true // 解锁了但没收到广播
        e.handle(Event.DrillAlarm)
        val s = e.snapshot()
        assertEquals(DrillPhase.NONE, s.drill.phase)
        val r = s.lastDrillResult!!
        assertEquals(DrillOutcome.FAILED, r.outcome)
        assertEquals(planned, r.firedAt) // 记录实际触发时刻供界面显示延迟
        assertTrue(ports.drillRingStarts.isEmpty())
        assertTrue(ports.calls.isEmpty())
    }

    // ---------- 确认结束闹钟逾期（强停后恢复） ----------

    /** 推进到演练 RINGING 并返回 confirmEndAt。 */
    private fun GuardEngine.toRinging(ports: FakePorts, clock: FakeClock): Long {
        val planned = startDrillWaiting(ports, clock)
        fireDrill(ports, clock, planned)
        return snapshot().drill.confirmEndAt
    }

    @Test
    fun `overdue confirm end within grace still dials on sync`() {
        // 确认结束闹钟丢了，恢复时逾期 ≤60 秒：仍按正常流程拨号
        val (e, clock, ports) = fixture(withRecord = false)
        val confirmEnd = e.toRinging(ports, clock)
        clock.t = confirmEnd + 30_000L // 逾期 30 秒
        ports.deviceInUse = false
        e.handle(Event.SyncSession)
        assertEquals(DrillPhase.CALLING, e.snapshot().drill.phase)
        assertEquals(listOf(num), ports.calls)
        // 演练只发测试短信
        assertTrue(ports.sms.all { it.kind == SmsKind.DRILL_TEST })
    }

    @Test
    fun `overdue confirm end beyond grace fails without dialing`() {
        // 逾期 >60 秒：绝不能突然拨号（用户几小时后打开 App），判失败
        val (e, clock, ports) = fixture(withRecord = false)
        val confirmEnd = e.toRinging(ports, clock)
        clock.t = confirmEnd + 61_000L // 逾期 61 秒
        ports.deviceInUse = false
        e.handle(Event.SyncSession)
        val s = e.snapshot()
        assertEquals(DrillPhase.NONE, s.drill.phase)
        assertEquals(DrillOutcome.FAILED, s.lastDrillResult?.outcome)
        assertTrue(s.lastDrillResult?.reason.orEmpty().contains("没有自动拨号"))
        assertTrue(ports.calls.isEmpty())
        assertTrue(ports.sms.isEmpty())
    }

    @Test
    fun `late confirm end alarm beyond grace fails without dialing`() {
        // 闹钟本身迟到超宽限送达也一样：不拨号，判失败
        val (e, clock, ports) = fixture(withRecord = false)
        val confirmEnd = e.toRinging(ports, clock)
        clock.t = confirmEnd + 90_000L
        ports.deviceInUse = false
        e.handle(Event.DrillConfirmEndAlarm)
        assertEquals(DrillOutcome.FAILED, e.snapshot().lastDrillResult?.outcome)
        assertTrue(ports.calls.isEmpty())
    }

    // ---------- 对时（ClockShift）与演练计时基准 ----------

    @Test
    fun `small clock shift does not abort drill`() {
        // 网络自动对时偏移 ≤60 秒：演练继续，计划时刻仍有效
        val (e, clock, ports) = fixture(withRecord = false)
        val planned = e.startDrillWaiting(ports, clock)
        clock.t += 30_000L // 墙钟快 30 秒，elapsed 不变 → 漂移 30s
        e.handle(Event.ClockShift)
        assertEquals(DrillPhase.WAITING, e.snapshot().drill.phase)
        assertEquals(planned, e.snapshot().drill.plannedAt)
    }

    @Test
    fun `large clock shift aborts drill`() {
        // 手动改时间偏移 >60 秒：计时基准失效，中止（不算失败）
        val (e, clock, ports) = fixture(withRecord = false)
        e.startDrillWaiting(ports, clock)
        clock.t += 5 * 60_000L // 墙钟快 5 分钟
        e.handle(Event.ClockShift)
        val s = e.snapshot()
        assertEquals(DrillPhase.NONE, s.drill.phase)
        assertEquals(DrillOutcome.ABORTED, s.lastDrillResult?.outcome)
        assertTrue(ports.drillRingStarts.isEmpty())
    }

    @Test
    fun `system reset still aborts drill`() {
        // 重启/包升级仍是计时基准作废：一律中止
        val (e, clock, ports) = fixture(withRecord = false)
        e.startDrillWaiting(ports, clock)
        e.handle(Event.SystemReset)
        assertEquals(DrillOutcome.ABORTED, e.snapshot().lastDrillResult?.outcome)
        assertEquals(DrillPhase.NONE, e.snapshot().drill.phase)
    }
}
