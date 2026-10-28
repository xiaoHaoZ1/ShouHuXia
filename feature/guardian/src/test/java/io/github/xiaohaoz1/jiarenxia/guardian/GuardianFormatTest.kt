package io.github.xiaohaoz1.jiarenxia.guardian

import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardianFormat
import io.github.xiaohaoz1.jiarenxia.guardian.core.Phase
import io.github.xiaohaoz1.jiarenxia.guardian.core.TimeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 守护 UI 纯函数：相对时间 / 倒计时 / 号码校验 / 权限进度。 */
class GuardianFormatTest {

    private val z = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, z).toInstant().toEpochMilli()

    @Test
    fun `relative day time`() {
        val now = at(2026, 10, 2, 20, 0)
        assertEquals("今天 19:05", GuardianFormat.formatRelativeDayTime(at(2026, 10, 2, 19, 5), now, z))
        assertEquals("昨天 08:12", GuardianFormat.formatRelativeDayTime(at(2026, 10, 1, 8, 12), now, z))
        assertEquals("9月28日 08:12", GuardianFormat.formatRelativeDayTime(at(2026, 9, 28, 8, 12), now, z))
    }

    @Test
    fun `ago`() {
        val now = at(2026, 10, 2, 20, 0)
        assertEquals("刚刚", GuardianFormat.formatAgo(now - 30_000, now))
        assertEquals("25 分钟前", GuardianFormat.formatAgo(at(2026, 10, 2, 19, 35), now))
        assertEquals("3 小时前", GuardianFormat.formatAgo(at(2026, 10, 2, 17, 0), now))
        assertEquals("2 天前", GuardianFormat.formatAgo(at(2026, 9, 30, 19, 0), now))
        // 时钟回拨不输出负数
        assertEquals("刚刚", GuardianFormat.formatAgo(at(2026, 10, 2, 21, 0), now))
    }

    @Test
    fun `countdown`() {
        assertEquals("4小时55分", GuardianFormat.formatCountdown((4 * 3600 + 55 * 60) * 1000L))
        assertEquals("3分20秒", GuardianFormat.formatCountdown(200_000L))
        assertEquals("40秒", GuardianFormat.formatCountdown(40_000L))
        assertEquals("0秒", GuardianFormat.formatCountdown(-5_000L))
    }

    @Test
    fun `contact number validator`() {
        assertTrue(GuardianFormat.isValidContactNumber("13800138000"))
        assertTrue(GuardianFormat.isValidContactNumber("+86 138-0013-8000"))
        assertTrue(GuardianFormat.isValidContactNumber("12345"))
        assertFalse(GuardianFormat.isValidContactNumber(""))
        assertFalse(GuardianFormat.isValidContactNumber("1234"))
        assertFalse(GuardianFormat.isValidContactNumber("abc"))
        assertFalse(GuardianFormat.isValidContactNumber("1380@123"))
    }

    @Test
    fun `status text by phase`() {
        val at = at(2026, 10, 2, 8, 30)
        assertEquals(
            "守护中",
            GuardianFormat.statusText(GuardState(phase = Phase.MONITORING)),
        )
        assertEquals(
            "守护中｜上次使用 " + TimeRules.formatClock(at),
            GuardianFormat.statusText(
                GuardState(phase = Phase.MONITORING, lastActivityAt = at)
            ),
        )
        assertEquals(
            "守护中｜正在使用",
            GuardianFormat.statusText(
                GuardState(phase = Phase.MONITORING, lastActivityAt = at, inUse = true)
            ),
        )
        assertEquals(
            "正在等待确认",
            GuardianFormat.statusText(GuardState(phase = Phase.CONFIRMING)),
        )
        // ALERTED 显示报警结果（含接听者/无人接）
        assertEquals(
            "已于 -- 完成报警：多次拨打无人接听，已发短信",
            GuardianFormat.statusText(GuardState(phase = Phase.ALERTED)),
        )
        assertEquals(
            "已于 " + TimeRules.formatClock(at) + " 完成报警：多次拨打无人接听，已发短信",
            GuardianFormat.statusText(GuardState(phase = Phase.ALERTED, alertedAt = at)),
        )
    }

    private val c1 = io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact(
        "张三", "13800001111")
    private val c2 = io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact(
        "李四", "13900002222")

    @Test
    fun `seq contact states`() {
        val dialing = io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq(
            step = io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.DIALING,
            round = 1, index = 1, placedMask = 0b11, answeredMask = 0)
        assertEquals(
            listOf(
                GuardianFormat.SeqContactState.NO_ANSWER,
                GuardianFormat.SeqContactState.DIALING,
            ),
            GuardianFormat.seqContactStates(dialing, 2)
        )
        // 已接通过的联系人永远显示已接听
        assertEquals(
            listOf(
                GuardianFormat.SeqContactState.ANSWERED,
                GuardianFormat.SeqContactState.DIALING,
            ),
            GuardianFormat.seqContactStates(dialing.copy(answeredMask = 0b01), 2)
        )
        // 轮间等待：本轮拨过的都未接
        val wait = dialing.copy(
            step = io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.ROUND_WAIT,
            index = 2)
        assertEquals(
            listOf(
                GuardianFormat.SeqContactState.NO_ANSWER,
                GuardianFormat.SeqContactState.NO_ANSWER,
            ),
            GuardianFormat.seqContactStates(wait, 2)
        )
        // 第 2 轮重新拨：placedMask 跨轮累计也不能把正在等的人误判未接
        assertEquals(
            listOf(
                GuardianFormat.SeqContactState.DIALING,
                GuardianFormat.SeqContactState.WAITING,
            ),
            GuardianFormat.seqContactStates(
                dialing.copy(
                    step = io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.DIALING,
                    round = 2, index = 0, placedMask = 0b11),
                2)
        )
        // placeCall 失败后 index 前进：失败者显示"未接通"而非一直"等待拨打"
        assertEquals(
            listOf(
                GuardianFormat.SeqContactState.NO_ANSWER,
                GuardianFormat.SeqContactState.DIALING,
            ),
            GuardianFormat.seqContactStates(
                dialing.copy(placedMask = 0b01), 2)
        )
        // DONE 提前结束（有人接通）：未拨到的位显示"未拨打"（WAITING，UI 换文案）
        assertEquals(
            listOf(
                GuardianFormat.SeqContactState.NO_ANSWER,
                GuardianFormat.SeqContactState.WAITING,
            ),
            GuardianFormat.seqContactStates(
                dialing.copy(
                    step = io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.DONE,
                    index = 0, answeredMask = 0),
                2)
        )
        // START/未开始时全部等待
        assertEquals(
            listOf(
                GuardianFormat.SeqContactState.WAITING,
                GuardianFormat.SeqContactState.WAITING,
            ),
            GuardianFormat.seqContactStates(
                io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq(
                    step = io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.START),
                2)
        )
    }

    @Test
    fun `alert outcome and result text`() {
        val answered1 = GuardState(
            alertedAt = 1000L,
            alertSeq = io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq(
                answeredMask = 0b01))
        assertEquals(
            "张三已接听",
            GuardianFormat.alertOutcomeText(answered1, listOf(c1, c2)))
        assertEquals(
            "张三、李四已接听",
            GuardianFormat.alertOutcomeText(
                GuardState(alertSeq =
                    io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq(answeredMask = 0b11)),
                listOf(c1, c2)))
        assertEquals(
            "多次拨打无人接听，已发短信",
            GuardianFormat.alertOutcomeText(
                GuardState(), listOf(c1, c2)))
        assertTrue(GuardianFormat.alertResultText(answered1, listOf(c1, c2))
            .contains("张三已接听"))
    }

    @Test
    fun `status text alerting rounds and drill calling`() {
        val seq = io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq(
            step = io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.DIALING,
            round = 2, index = 0)
        val s = GuardState(phase = Phase.ALERTING, alertSeq = seq)
        assertEquals(
            "正在报警：第 2 / 2 轮，正在拨打 张三",
            GuardianFormat.statusText(s, listOf(c1, c2)))
        // 轮间等待：报下一轮时刻；各 step 都不能出现"完成报警"
        val waiting = GuardState(phase = Phase.ALERTING,
            alertSeq = seq.copy(
                step = io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.ROUND_WAIT,
                nextRoundAt = at(2026, 10, 2, 8, 30)))
        assertEquals(
            "正在报警：本轮无人接听，" + TimeRules.formatClock(at(2026, 10, 2, 8, 30)) +
                " 开始第 3 轮",
            GuardianFormat.statusText(waiting, listOf(c1, c2)))
        assertFalse(GuardianFormat.statusText(waiting, listOf(c1, c2))
            .contains("完成报警"))
        val starting = GuardState(phase = Phase.ALERTING,
            alertSeq = seq.copy(
                step = io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.START))
        assertEquals("正在报警：正在发紧急短信",
            GuardianFormat.statusText(starting, listOf(c1, c2)))
        assertFalse(GuardianFormat.statusText(s, listOf(c1, c2))
            .contains("完成报警"))
        assertEquals(
            "守护测试：正在发测试短信并依次拨号",
            GuardianFormat.statusText(
                GuardState(drill = io.github.xiaohaoz1.jiarenxia.guardian.core.Drill(
                    phase = io.github.xiaohaoz1.jiarenxia.guardian.core.DrillPhase.CALLING))))
        assertEquals(
            "守护测试：等待逐位确认",
            GuardianFormat.statusText(
                GuardState(drill = io.github.xiaohaoz1.jiarenxia.guardian.core.Drill(
                    phase = io.github.xiaohaoz1.jiarenxia.guardian.core.DrillPhase.ASK_ANSWER))))
    }
}
