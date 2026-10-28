package io.github.xiaohaoz1.jiarenxia.guardian

import io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact
import io.github.xiaohaoz1.jiarenxia.guardian.core.SmsText
import io.github.xiaohaoz1.jiarenxia.guardian.core.maskNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 号码脱敏与短信文案（纯 JVM）。 */
class SmsTextMaskTest {

    @Test
    fun `maskNumber keeps first3 last4`() {
        assertEquals("138****8000", maskNumber("13800138000"))
        assertEquals("131****5678", maskNumber("13112345678"))
    }

    @Test
    fun `maskNumber short numbers`() {
        assertEquals("**11", maskNumber("0011".take(4))) // 4位→后2
        assertEquals("12", maskNumber("12"))
        assertEquals("", maskNumber(""))
        assertEquals("***78", maskNumber("12378"))
    }

    @Test
    fun `config toString masks numbers`() {
        val c = io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig(
            contacts = listOf(
                EmergencyContact("家人", "13800138000"),
                EmergencyContact("朋友", "13112345678"),
            )
        )
        assertTrue(c.toString().contains("138****8000"))
        assertTrue(c.toString().contains("131****5678"))
        assertTrue(!c.toString().contains("13800138000"))
        assertTrue(!c.toString().contains("13112345678"))
        assertTrue(!c.toString().contains("家人"))
    }

    @Test
    fun `daily sms text`() {
        val t = SmsText.daily(
            lastActivityAt = 1_700_000_000_000L,
            now = 1_700_003_600_000L,  // +1h
            batteryPct = 87, charging = true, inUse = false
        )
        assertTrue(t.startsWith("【守护侠】"))
        assertTrue(t.contains("1小时前"))
        assertTrue(t.contains("电量87%，充电中"))
        assertTrue(t.contains("请检查手机是否关机"))
    }

    @Test
    fun `daily sms without record`() {
        val t = SmsText.daily(0, 1_700_003_600_000L, 55, false, inUse = false)
        assertTrue(t.contains("无记录"))
        assertTrue(t.contains("未知"))
        assertTrue(t.contains("电量55%"))
    }

    @Test
    fun `daily sms while in use says in use`() {
        val t = SmsText.daily(
            lastActivityAt = 1_700_000_000_000L,
            now = 1_700_003_600_000L,
            batteryPct = 87, charging = false, inUse = true,
        )
        assertTrue(t.contains("最后一次使用手机：正在使用。"))
        assertFalse(t.contains("小时前"))
    }

    @Test
    fun `emergency sms text`() {
        val t = SmsText.emergency(
            lastActivityAt = 1_700_000_000_000L,
            now = 1_700_003_600_000L + 1_800_000L, // 无人使用 1.5 小时
            batteryPct = 62, charging = false,
        )
        assertTrue(t.startsWith("【守护侠】紧急"))
        assertTrue(t.contains("自动发出的提醒"))
        assertTrue(t.contains("1小时30分"))
        assertTrue(t.contains("最后使用"))
        assertTrue(t.contains("电量62%"))
        assertTrue(t.contains("接通后若没人说话"))
    }

    @Test
    fun `emergency sms unknown record and battery`() {
        val t = SmsText.emergency(0, 1_700_003_600_000L, -1, false)
        assertTrue(t.contains("无记录"))
        assertTrue(t.contains("电量未知"))
    }

    @Test
    fun `answered summary no-answer resolved and drill texts`() {
        assertEquals(
            "【守护侠】报警电话已由 张三 接听，不再拨打其他联系人。",
            SmsText.answered("张三")
        )
        assertTrue(SmsText.summary(listOf("张三", "李四")).contains("张三、李四"))
        assertTrue(SmsText.noAnswer().contains("无人接听"))
        assertTrue(SmsText.resolved().contains("已解除"))
        assertTrue(SmsText.drillTest().startsWith("【守护侠·测试】"))
        assertTrue(SmsText.drillTest().contains("不是真实报警"))
    }

    @Test
    fun `contact label falls back to number`() {
        assertEquals("张三", EmergencyContact("张三", "138").label())
        assertEquals("138", EmergencyContact("", "138").label())
    }
}
