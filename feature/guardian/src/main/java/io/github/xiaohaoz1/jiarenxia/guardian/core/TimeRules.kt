package io.github.xiaohaoz1.jiarenxia.guardian.core

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** 纯函数计时规则，全部可 JVM 单测。 */
object TimeRules {

    /**
     * deadline = 上次活动 + 阈值，再经免打扰顺延。
     * inUse（正在使用的解锁会话中）时使用中上限，否则用锁屏后阈值；
     * 使用中上限不随开发快速阈值缩短。
     */
    fun computeDeadline(
        lastActivityAt: Long,
        cfg: GuardConfig,
        inUse: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = deferQuiet(
        lastActivityAt + if (inUse) cfg.inUseCapMillis() else cfg.effectiveThresholdMillis(),
        cfg, zone
    )

    /**
     * 夜间免打扰顺延：deadline 落在 [quietStart, quietEnd) 时段内 → 顺延到该时段结束。
     * 时段支持跨零点（如 23:00–次日 07:00）；起止相等视为未开启；开发快速阈值下不顺延，
     * 否则回归时夜里要等真实到点。对过期 deadline 同样适用：
     * 顺延结果仍在过去则照常报警（那是很久以前的事），落在未来则重挂闹钟。
     */
    fun deferQuiet(
        deadline: Long,
        cfg: GuardConfig,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        if (!cfg.quietEnabled || cfg.devFastThresholds) return deadline
        val s = cfg.quietStartMinuteOfDay
        val e = cfg.quietEndMinuteOfDay
        if (s == e) return deadline
        val zdt = Instant.ofEpochMilli(deadline).atZone(zone)
        val m = zdt.hour * 60 + zdt.minute
        val inside = if (s < e) m in s until e else m >= s || m < e
        if (!inside) return deadline
        // 跨零点时段的深夜段（m>=s）归属次日结束点，其余归当天结束点
        val endDate = if (s < e || m < e) zdt.toLocalDate() else zdt.toLocalDate().plusDays(1)
        return endDate.atTime(LocalTime.of(e / 60 % 24, e % 60))
            .atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * 下一次每日平安短信时间：今天 minuteOfDay 若已过（含恰好等于 now）则取明天同刻。
     */
    fun nextDailySmsAt(
        now: Long,
        minuteOfDay: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val zdt = Instant.ofEpochMilli(now).atZone(zone)
        val time = LocalTime.of(minuteOfDay / 60 % 24, minuteOfDay % 60)
        var candidate = zdt.toLocalDate().atTime(time).atZone(zone)
        if (candidate.toInstant().toEpochMilli() <= now) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    /** 中文时长：≥1h → "X小时Y分"；≥1min → "X分Y秒"；否则 "N秒"。 */
    fun formatDuration(millis: Long): String {
        val sec = millis / 1000
        if (sec <= 0) return "0秒"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> if (m > 0) "${h}小时${m}分" else "${h}小时"
            m > 0 -> if (s > 0) "${m}分${s}秒" else "${m}分"
            else -> "${sec}秒"
        }
    }

    /** "HH:mm" */
    fun formatClock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    /** "MM-dd HH:mm" */
    fun formatDateTime(millis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
