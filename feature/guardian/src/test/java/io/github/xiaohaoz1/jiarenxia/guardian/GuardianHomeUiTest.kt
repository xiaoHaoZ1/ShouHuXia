package io.github.xiaohaoz1.jiarenxia.guardian

import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.sys.Permissions
import io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianHomeUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GuardianHomeUi 门槛判定的纯 JVM 测试（不构造 Intent 字段）。
 * P0-1：drillMinReady 按 runtimePermission 判定而非按 key——
 * notifications 项仅 API33+ 存在，低版本按 key 查永远找不到。
 */
class GuardianHomeUiTest {

    private fun perm(
        key: String,
        runtime: String?,
        state: Permissions.CheckState,
        optional: Boolean = false,
    ) = Permissions.PermissionItem(
        key = key, title = key, desc = "", state = state,
        optional = optional, runtimePermission = runtime,
    )

    private fun ui(
        contact: String = "13800000000",
        perms: List<Permissions.PermissionItem>,
    ) = GuardianHomeUi(
        config = GuardConfig(
            contacts = if (contact.isBlank()) emptyList()
            else listOf(io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact("", contact))
        ),
        state = GuardState(),
        perms = perms,
        now = 0,
    )

    @Test
    fun `drillMinReady does not require notifications item on older apis`() {
        // API 26–32：snapshot 里没有 notifications 项；必需项（含短信/通话记录）全授 → 可演练
        val u = ui(
            perms = listOf(
                perm("call_phone", "android.permission.CALL_PHONE",
                    Permissions.CheckState.ON),
                perm("phone_state", "android.permission.READ_PHONE_STATE",
                    Permissions.CheckState.ON),
                perm("send_sms", "android.permission.SEND_SMS",
                    Permissions.CheckState.ON),
                perm("call_log", "android.permission.READ_CALL_LOG",
                    Permissions.CheckState.ON),
                perm("battery", null, Permissions.CheckState.ON),
            )
        )
        assertTrue(u.drillMinReady)
    }

    @Test
    fun `drillMinReady true on api33 with all runtime perms granted`() {
        val u = ui(
            perms = listOf(
                perm("call_phone", "android.permission.CALL_PHONE",
                    Permissions.CheckState.ON),
                perm("phone_state", "android.permission.READ_PHONE_STATE",
                    Permissions.CheckState.ON),
                perm("send_sms", "android.permission.SEND_SMS",
                    Permissions.CheckState.ON),
                perm("call_log", "android.permission.READ_CALL_LOG",
                    Permissions.CheckState.ON),
                perm("notifications", "android.permission.POST_NOTIFICATIONS",
                    Permissions.CheckState.ON),
            )
        )
        assertTrue(u.drillMinReady)
    }

    @Test
    fun `drillMinReady false when a required runtime perm is off`() {
        val u = ui(
            perms = listOf(
                perm("call_phone", "android.permission.CALL_PHONE",
                    Permissions.CheckState.OFF),
                perm("phone_state", "android.permission.READ_PHONE_STATE",
                    Permissions.CheckState.ON),
                perm("notifications", "android.permission.POST_NOTIFICATIONS",
                    Permissions.CheckState.ON),
            )
        )
        assertFalse(u.drillMinReady)
        assertEquals(listOf("call_phone"), u.drillMissingRuntimePermissions.map { it.key })
    }

    @Test
    fun `drillMinReady requires sms and call log permissions`() {
        // 发送短信与读取通话记录都是必需项：缺一即不能发起演练
        val base = listOf(
            perm("call_phone", "android.permission.CALL_PHONE",
                Permissions.CheckState.ON),
            perm("phone_state", "android.permission.READ_PHONE_STATE",
                Permissions.CheckState.ON),
        )
        assertFalse(ui(perms = base + listOf(
            perm("send_sms", "android.permission.SEND_SMS",
                Permissions.CheckState.ON),
            perm("call_log", "android.permission.READ_CALL_LOG",
                Permissions.CheckState.OFF),
        )).drillMinReady)
        assertFalse(ui(perms = base + listOf(
            perm("send_sms", "android.permission.SEND_SMS",
                Permissions.CheckState.OFF),
            perm("call_log", "android.permission.READ_CALL_LOG",
                Permissions.CheckState.ON),
        )).drillMinReady)
        assertTrue(ui(perms = base + listOf(
            perm("send_sms", "android.permission.SEND_SMS",
                Permissions.CheckState.ON),
            perm("call_log", "android.permission.READ_CALL_LOG",
                Permissions.CheckState.ON),
        )).drillMinReady)
    }

    @Test
    fun `drillMinReady false without contact`() {
        val u = ui(
            contact = "",
            perms = listOf(
                perm("call_phone", "android.permission.CALL_PHONE",
                    Permissions.CheckState.ON),
            )
        )
        assertFalse(u.drillMinReady)
    }
}
