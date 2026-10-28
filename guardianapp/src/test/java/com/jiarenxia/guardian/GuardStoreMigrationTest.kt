package com.jiarenxia.guardian

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.xiaohaoz1.jiarenxia.guardian.core.AnswerPolicy
import io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillOutcome
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep
import io.github.xiaohaoz1.jiarenxia.guardian.data.GuardStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GuardStore 旧键迁移测试（Robolectric）：
 * 老版本只有一条 drill_result_* 记录，新版本拆成
 * drill_result_*（lastDrillResult）与 drill_qual_*（qualifiedPass）；
 * 老版本只有一位联系人（contact_name/contact_number），迁移为 contacts 列表第 1 位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardStoreMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("guardian", Context.MODE_PRIVATE)

    /** 写入一条老版本的 drill_result_* 记录。 */
    private fun seedLegacy(outcome: String, waitMin: Int) {
        prefs().edit().clear()
            .putBoolean("drill_result_has", true)
            .putString("drill_result_outcome", outcome)
            .putInt("drill_result_wait_min", waitMin)
            .putLong("drill_result_version_code", 1L)
            .putString("drill_result_fingerprint", "fp")
            .putString("drill_result_contact", "13800000000")
            .commit()
    }

    @Test
    fun `legacy qualified pass migrates to qualifiedPass`() {
        seedLegacy(outcome = "PASSED", waitMin = 30)
        val st = GuardStore(context).loadState()
        assertNotNull(st.qualifiedPass)
        assertEquals(30, st.qualifiedPass?.waitMinutes)
        // lastDrillResult 保持同一条（展示不受影响）
        assertEquals(DrillOutcome.PASSED, st.lastDrillResult?.outcome)
    }

    @Test
    fun `legacy short pass does not migrate`() {
        seedLegacy(outcome = "PASSED", waitMin = 1)
        val st = GuardStore(context).loadState()
        assertNull(st.qualifiedPass)
        assertEquals(DrillOutcome.PASSED, st.lastDrillResult?.outcome)
    }

    @Test
    fun `legacy failed or aborted does not migrate`() {
        seedLegacy(outcome = "FAILED", waitMin = 30)
        assertNull(GuardStore(context).loadState().qualifiedPass)
        seedLegacy(outcome = "ABORTED", waitMin = 30)
        assertNull(GuardStore(context).loadState().qualifiedPass)
    }

    @Test
    fun `explicit qualified key wins over legacy record`() {
        // drill_qual_has 已存在（含显式 false）时以它为准，不回读旧键
        seedLegacy(outcome = "PASSED", waitMin = 30)
        prefs().edit().putBoolean("drill_qual_has", false).commit()
        assertNull(GuardStore(context).loadState().qualifiedPass)
    }

    @Test
    fun `save then load round trips both records`() {
        seedLegacy(outcome = "PASSED", waitMin = 30)
        val store = GuardStore(context)
        val st = store.loadState()
        store.saveState(st) // 触发新格式写回
        val reloaded = GuardStore(context).loadState()
        assertNotNull(reloaded.qualifiedPass)
        assertNotNull(reloaded.lastDrillResult)
    }

    @Test
    fun `legacy single contact migrates to first contact`() {
        prefs().edit().clear()
            .putString("contact_name", "家人")
            .putString("contact_number", "13800000000")
            .commit()
        val cfg = GuardStore(context).loadConfig()
        assertEquals(1, cfg.contacts.size)
        assertEquals("家人", cfg.contacts[0].name)
        assertEquals("13800000000", cfg.contacts[0].number)
        // 旧号码空白则不迁移出任何联系人
        prefs().edit().clear().putString("contact_number", "").commit()
        assertTrue(GuardStore(context).loadConfig().contacts.isEmpty())
    }

    @Test
    fun `contacts list and policy round trip`() {
        prefs().edit().clear().commit()
        val store = GuardStore(context)
        val cfg = store.loadConfig().copy(
            contacts = listOf(
                EmergencyContact("张三", "13800001111"),
                EmergencyContact("李四", "13800002222"),
            ),
            answerPolicy = AnswerPolicy.CALL_ALL,
        )
        store.saveConfig(cfg)
        val reloaded = GuardStore(context).loadConfig()
        assertEquals(2, reloaded.contacts.size)
        assertEquals("李四", reloaded.contacts[1].name)
        assertEquals("13800002222", reloaded.contacts[1].number)
        assertEquals(AnswerPolicy.CALL_ALL, reloaded.answerPolicy)
        // 写新键后旧键已删除，不会重复迁移
        assertFalse(prefs().contains("contact_number"))
    }

    @Test
    fun `legacy record without schema is invalidated by new rules`() {
        seedLegacy(outcome = "PASSED", waitMin = 30)
        val st = GuardStore(context).loadState()
        val record = st.qualifiedPass ?: st.lastDrillResult
        assertNotNull(record)
        // 老记录 schema 缺省读为 1：被"演练规则已更新"判失效，需重新测试
        assertEquals(1, record!!.schema)
        val reasons = DrillRules.invalidReasons(
            record, 1L, "fp",
            DrillRules.contactsKey(
                listOf(EmergencyContact(number = "13800000000"))
            ),
        )
        assertTrue(reasons.any { it.contains("演练规则已更新") })
    }

    @Test
    fun `call seq and alert sms flags round trip`() {
        prefs().edit().clear().commit()
        val store = GuardStore(context)
        val seq = CallSeq(
            step = SeqStep.DIALING,
            startedAt = 100L,
            round = 2,
            index = 1,
            dialedAt = 200L,
            dialedNumber = "13800001111",
            offhookSeen = true,
            checks = 2,
            placedMask = 3,
            answeredMask = 1,
            roundAnsweredMask = 1,
            nextRoundAt = 300L,
        )
        store.saveState(
            GuardState(alertSeq = seq, alertSmsFlags = GuardState.SMS_EMERGENCY_SENT)
        )
        val reloaded = GuardStore(context).loadState()
        assertEquals(seq, reloaded.alertSeq)
        assertEquals(GuardState.SMS_EMERGENCY_SENT, reloaded.alertSmsFlags)
    }
}
