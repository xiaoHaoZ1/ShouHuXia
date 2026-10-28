package io.github.xiaohaoz1.jiarenxia.guardian

import io.github.xiaohaoz1.jiarenxia.guardian.core.CallLogResult
import io.github.xiaohaoz1.jiarenxia.guardian.sys.CallLogMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通话记录号码比对与接通判定（pick/digitsTail 为纯 Kotlin）。 */
class CallLogMatchTest {

    private val num = "13800001111"

    private fun row(number: String, duration: Long, date: Long) =
        CallLogMatch.Row(number = number, durationSec = duration, date = date)

    @Test
    fun `pick returns answered when matching row has duration`() {
        val rows = listOf(
            row(num, 42, 200L),
            row("139", 0, 100L),
        )
        assertEquals(
            CallLogResult.ANSWERED,
            CallLogMatch.pick(rows, num) { a, b -> a == b }
        )
    }

    @Test
    fun `pick returns not answered when matching row has zero duration`() {
        val rows = listOf(row(num, 0, 200L))
        assertEquals(
            CallLogResult.NOT_ANSWERED,
            CallLogMatch.pick(rows, num) { a, b -> a == b }
        )
    }

    @Test
    fun `pick returns not found when no row matches`() {
        val rows = listOf(row("139", 5, 200L))
        assertEquals(
            CallLogResult.NOT_FOUND,
            CallLogMatch.pick(rows, num) { a, b -> a == b }
        )
        assertEquals(
            CallLogResult.NOT_FOUND,
            CallLogMatch.pick(emptyList(), num) { a, b -> a == b }
        )
    }

    @Test
    fun `pick uses first matching row in given order`() {
        // 调用方已按时间倒序：第一通同号未接、第二通（更早）接通 → 判 NOT_ANSWERED
        val rows = listOf(row(num, 0, 200L), row(num, 9, 100L))
        assertEquals(
            CallLogResult.NOT_ANSWERED,
            CallLogMatch.pick(rows, num) { a, b -> a == b }
        )
    }

    @Test
    fun `digitsTail keeps only digits and last 11`() {
        assertEquals("13800001111", CallLogMatch.digitsTail(num))
        assertEquals("13800001111", CallLogMatch.digitsTail("+86 138-0000-1111"))
        // 超过 11 位取尾部
        assertEquals("13800001111", CallLogMatch.digitsTail("008613800001111"))
        // 不足 11 位取全部
        assertEquals("10086", CallLogMatch.digitsTail("10086"))
        assertEquals("", CallLogMatch.digitsTail("+-"))
    }

    @Test
    fun `tail equality treats country code and formatting as same number`() {
        val a = CallLogMatch.digitsTail("+8613800001111")
        val b = CallLogMatch.digitsTail(num)
        assertEquals(a, b)
        assertTrue(a.isNotEmpty() && a == b)
        // 不同号码尾部不相等
        val c = CallLogMatch.digitsTail("13900001111")
        assertFalse(c == b)
        // 空号码不匹配任何目标
        assertFalse(CallLogMatch.digitsTail("") == b)
    }
}
