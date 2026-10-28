package io.github.xiaohaoz1.jiarenxia.guardian

import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.core.TimeRules
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRulesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(date: LocalDate, h: Int, m: Int = 0): Long =
        date.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `computeDeadline normal mode`() {
        val cfg = GuardConfig(thresholdMinutes = 480, quietEnabled = false)
        assertEquals(1_000L + 8 * 3600_000L, TimeRules.computeDeadline(1_000L, cfg, inUse = false))
    }

    @Test
    fun `computeDeadline fast thresholds use 30 seconds`() {
        val cfg = GuardConfig(thresholdMinutes = 480, devFastThresholds = true)
        assertEquals(1_000L + 30_000L, TimeRules.computeDeadline(1_000L, cfg, inUse = false))
    }

    @Test
    fun `computeDeadline in use uses cap that ignores fast thresholds`() {
        val cfg = GuardConfig(thresholdMinutes = 480, devFastThresholds = true)
        // 使用中上限不受快速阈值影响
        assertEquals(
            1_000L + 480 * 60_000L,
            TimeRules.computeDeadline(1_000L, cfg, inUse = true),
        )
    }

    @Test
    fun `computeDeadline in use normal mode equals threshold`() {
        val cfg = GuardConfig(thresholdMinutes = 360, quietEnabled = false)
        assertEquals(
            1_000L + 6 * 3600_000L,
            TimeRules.computeDeadline(1_000L, cfg, inUse = true),
        )
    }

    // ---------- 夜间免打扰顺延 ----------

    private fun quietCfg(
        start: Int = 23 * 60, end: Int = 7 * 60,
        enabled: Boolean = true, devFastThresholds: Boolean = false
    ) = GuardConfig(
        quietEnabled = enabled, quietStartMinuteOfDay = start,
        quietEndMinuteOfDay = end, devFastThresholds = devFastThresholds
    )

    @Test
    fun `deferQuiet late night defers to next morning`() {
        val d = LocalDate.of(2024, 1, 1)
        // 23:30 落在 23:00–07:00 内 → 顺延到次日 07:00
        assertEquals(
            at(d.plusDays(1), 7, 0),
            TimeRules.deferQuiet(at(d, 23, 30), quietCfg(), zone)
        )
    }

    @Test
    fun `deferQuiet early morning defers to same morning`() {
        val d = LocalDate.of(2024, 1, 1)
        // 03:00 落在 23:00–07:00 的凌晨段 → 顺延到当天 07:00
        assertEquals(
            at(d, 7, 0),
            TimeRules.deferQuiet(at(d, 3, 0), quietCfg(), zone)
        )
    }

    @Test
    fun `deferQuiet outside window unchanged`() {
        val d = LocalDate.of(2024, 1, 1)
        assertEquals(at(d, 14, 0), TimeRules.deferQuiet(at(d, 14, 0), quietCfg(), zone))
    }

    @Test
    fun `deferQuiet boundaries start inclusive end exclusive`() {
        val d = LocalDate.of(2024, 1, 1)
        // 恰好 23:00 在时段内 → 顺延；恰好 07:00 已到时段结束 → 不动
        assertEquals(at(d.plusDays(1), 7, 0), TimeRules.deferQuiet(at(d, 23, 0), quietCfg(), zone))
        assertEquals(at(d, 7, 0), TimeRules.deferQuiet(at(d, 7, 0), quietCfg(), zone))
    }

    @Test
    fun `deferQuiet non wrap window same day end`() {
        val d = LocalDate.of(2024, 1, 1)
        // 午休时段 12:00–14:00：13:00 → 当天 14:00
        assertEquals(
            at(d, 14, 0),
            TimeRules.deferQuiet(at(d, 13, 0), quietCfg(start = 12 * 60, end = 14 * 60), zone)
        )
    }

    @Test
    fun `deferQuiet disabled or equal endpoints or fast thresholds unchanged`() {
        val d = LocalDate.of(2024, 1, 1)
        val inside = at(d, 3, 0)
        assertEquals(inside, TimeRules.deferQuiet(inside, quietCfg(enabled = false), zone))
        assertEquals(inside, TimeRules.deferQuiet(inside, quietCfg(start = 3 * 60, end = 3 * 60), zone))
        assertEquals(inside, TimeRules.deferQuiet(inside, quietCfg(devFastThresholds = true), zone))
    }

    @Test
    fun `computeDeadline defers into quiet window`() {
        val d = LocalDate.of(2024, 1, 1)
        // 20:00 最后使用 + 8 小时 → 04:00 落在免打扰时段 → 顺延 07:00
        assertEquals(
            at(d.plusDays(1), 7, 0),
            TimeRules.computeDeadline(at(d, 20, 0), quietCfg(), inUse = false, zone = zone)
        )
    }

    @Test
    fun `computeDeadline past quiet window unchanged`() {
        val d = LocalDate.of(2024, 1, 1)
        // 08:00 + 8 小时 → 16:00，不在时段内
        assertEquals(
            at(d, 16, 0),
            TimeRules.computeDeadline(at(d, 8, 0), quietCfg(), inUse = false, zone = zone)
        )
    }

    @Test
    fun `nextDailySmsAt later today`() {
        val d = LocalDate.of(2024, 1, 1)
        val now = at(d, 8, 0)
        assertEquals(at(d, 9, 0), TimeRules.nextDailySmsAt(now, 9 * 60, zone))
    }

    @Test
    fun `nextDailySmsAt past time rolls to tomorrow`() {
        val d = LocalDate.of(2024, 1, 1)
        val now = at(d, 10, 0)
        assertEquals(at(d.plusDays(1), 9, 0), TimeRules.nextDailySmsAt(now, 9 * 60, zone))
    }

    @Test
    fun `nextDailySmsAt exactly now rolls to tomorrow`() {
        val d = LocalDate.of(2024, 1, 1)
        val now = at(d, 9, 0)
        assertEquals(at(d.plusDays(1), 9, 0), TimeRules.nextDailySmsAt(now, 9 * 60, zone))
    }

    @Test
    fun `nextDailySmsAt crosses month boundary`() {
        val d = LocalDate.of(2024, 1, 31)
        val now = at(d, 23, 30)
        val next = TimeRules.nextDailySmsAt(now, 9 * 60, zone)
        assertEquals(at(LocalDate.of(2024, 2, 1), 9, 0), next)
    }

    @Test
    fun `formatDuration seconds`() {
        assertEquals("0秒", TimeRules.formatDuration(0))
        assertEquals("0秒", TimeRules.formatDuration(-5_000))
        assertEquals("30秒", TimeRules.formatDuration(30_000))
    }

    @Test
    fun `formatDuration minutes`() {
        assertEquals("1分30秒", TimeRules.formatDuration(90_000))
        assertEquals("2分", TimeRules.formatDuration(120_000))
    }

    @Test
    fun `formatDuration hours`() {
        assertEquals("1小时", TimeRules.formatDuration(3_600_000))
        assertEquals("8小时5分", TimeRules.formatDuration(8 * 3600_000L + 5 * 60_000L))
    }

    @Test
    fun `daily sms minute of day custom`() {
        val d = LocalDate.of(2024, 6, 15)
        val now = at(d, 12, 0)
        assertTrue(
            TimeRules.nextDailySmsAt(now, 18 * 60 + 30, zone) == at(d, 18, 30)
        )
    }
}
