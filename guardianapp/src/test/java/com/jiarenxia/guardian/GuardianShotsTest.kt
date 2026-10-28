package com.jiarenxia.guardian

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.jiarenxia.guardian.BuildConfig
import com.jiarenxia.guardian.ui.PrivacyScreen
import com.jiarenxia.guardian.ui.standaloneHomeOptions
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillOutcome
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.core.Phase
import io.github.xiaohaoz1.jiarenxia.guardian.sys.Permissions
import io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianDrill
import io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianHome
import io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianHomeCallbacks
import io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianHomeUi
import io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianSettings
import io.github.xiaohaoz1.jiarenxia.ui.theme.JrxTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 守护侠 Roborazzi 截图：全部使用合成数据与静态状态，不触碰 ServiceLocator、
 * 不触发真实响铃/拨号/短信。产物在 build/outputs/roborazzi/g_*.png。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class GuardianShotsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // 测试只提供主题，不垫 Surface：正式页面必须自己绘制背景，
    // 不能靠测试包装补齐页面缺失的背景（隐私页深色曾因此漏检）。
    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            JrxTheme(dark = dark) { content() }
        }
        composeRule.onRoot().captureRoboImage(
            File("build/outputs/roborazzi/g_$name.png").absolutePath
        )
    }

    /** 大字号（×1.3）截图：LocalDensity 覆盖模拟系统大字体，文件名加 _lg。 */
    private fun shotLarge(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, d.fontScale * 1.3f)
            ) {
                JrxTheme(dark = false) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage(
            File("build/outputs/roborazzi/g_${name}_lg.png").absolutePath
        )
    }

    /** 滚到目标文字所在位置再截整屏：用于页底（按钮/隐私入口/版本号）截图。 */
    private fun shotScrolledTo(name: String, anchor: String, large: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            if (large) {
                val d = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(d.density, d.fontScale * 1.3f)
                ) {
                    JrxTheme(dark = false) { content() }
                }
            } else {
                JrxTheme(dark = false) { content() }
            }
        }
        // substring：版本号在 debug 构建渲染为“版本 <versionName>-debug”
        composeRule.onNodeWithText(anchor, substring = true).performScrollTo()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            File("build/outputs/roborazzi/g_$name.png").absolutePath
        )
    }

    private fun assertTexts(vararg texts: String) {
        texts.forEach {
            check(
                composeRule.onAllNodes(hasText(it, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            ) { "assertTexts: 未找到包含文本的节点: " + it }
        }
    }

    private fun assertNoTexts(vararg texts: String) {
        texts.forEach {
            check(
                composeRule.onAllNodes(hasText(it, substring = true))
                    .fetchSemanticsNodes().isEmpty()
            ) { "assertNoTexts: 不应出现文本: " + it }
        }
    }

    // ---------- 合成数据 ----------

    private val demoNow = java.time.LocalDateTime.of(2026, 10, 18, 19, 0)
        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun demoPerms() = listOf(
        Permissions.PermissionItem(
            key = "call_phone", title = "拨打电话",
            desc = "紧急时自动拨号", state = Permissions.CheckState.ON),
        Permissions.PermissionItem(
            key = "phone_state", title = "电话状态",
            desc = "接听电话也算使用", state = Permissions.CheckState.ON),
        Permissions.PermissionItem(
            key = "send_sms", title = "发送短信",
            desc = "紧急与平安短信", state = Permissions.CheckState.ON),
        Permissions.PermissionItem(
            key = "call_log", title = "读取通话记录",
            desc = "判断拨出的电话是否接通", state = Permissions.CheckState.ON),
        Permissions.PermissionItem(
            key = "notifications", title = "通知",
            desc = "响铃提醒与状态通知", state = Permissions.CheckState.OFF),
        Permissions.PermissionItem(
            key = "battery", title = "忽略电池优化",
            desc = "防止后台被清理", state = Permissions.CheckState.OFF),
        Permissions.PermissionItem(
            key = "usage_stats", title = "使用情况访问",
            desc = "漏报兜底", state = Permissions.CheckState.OFF, optional = true),
    )

    /** 全部已授权的权限快照（步骤②完成态）。 */
    private fun permsAllOn() = demoPerms().map {
        it.copy(state = Permissions.CheckState.ON)
    }

    /** 演练通过记录（合成数据；等待 30 分钟、每位判定全过）。 */
    private fun passedDrill(
        contacts: List<io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact>,
    ) = DrillResult(
        outcome = DrillOutcome.PASSED,
        reason = "",
        waitMinutes = 30,
        plannedAt = demoNow - 40 * 60_000L,
        firedAt = demoNow - 10 * 60_000L,
        delayMillis = 3_000L,
        onTime = true, fullscreenOk = true,
        contactResults = contacts.map {
            io.github.xiaohaoz1.jiarenxia.guardian.core.DrillContactResult(
                name = it.name, number = it.number,
                smsSent = true, callAnswered = true,
                smsConfirmed = true, callConfirmed = true,
            )
        },
        finishedAt = demoNow - 5 * 60_000L,
        versionCode = 3L, fingerprint = "demo-fp",
        contactsKey = io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
            .contactsKey(contacts),
    )

    /** 截图用联系人（两位）：杨明、李华。 */
    private val cA = io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact(
        "杨明", "138 0013 8000")
    private val cB = io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact(
        "李华", "139 0013 9000")
    private val cC = io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact(
        "王芳", "137 0013 7000")

    private fun homeUi(
        enabled: Boolean,
        contact: Boolean,
        perms: List<Permissions.PermissionItem> = demoPerms(),
        drillResult: DrillResult? = null,
        // 不传则与 drillResult 同一条（合成数据默认一致）；
        // 需要"上次结果≠门槛凭据"时显式传
        qualifiedPass: DrillResult? = drillResult,
        anomalies: List<String> = emptyList(),
        manualItems: List<Permissions.ManualItem> = emptyList(),
        manualConfirmed: Set<String> = emptySet(),
        contacts: List<io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact>? = null,
        state: GuardState? = null,
        policy: io.github.xiaohaoz1.jiarenxia.guardian.core.AnswerPolicy =
            io.github.xiaohaoz1.jiarenxia.guardian.core.AnswerPolicy.STOP_ON_ANSWER,
    ) = GuardianHomeUi(
        config = GuardConfig(
            enabled = enabled,
            answerPolicy = policy,
            contacts = contacts
                ?: (if (contact) listOf(cA, cB) else emptyList())),
        state = state ?: GuardState(
            phase = Phase.MONITORING,
            lastActivityAt = demoNow - 3 * 3600_000L,
            deadlineAt = demoNow + 5 * 3600_000L,
            confirmEndAt = demoNow + 90_000L,
            nextDailySmsAt = demoNow + 14 * 3600_000L,
            lastDrillResult = drillResult,
            qualifiedPass = qualifiedPass),
        // 守护侠无家庭数据：紧急联系人只有通讯录与手动输入两条路
        perms = perms,
        manualItems = manualItems,
        manualConfirmed = manualConfirmed,
        versionCode = 3L,
        fingerprint = "demo-fp",
        anomalies = anomalies,
        now = demoNow)

    @Composable
    private fun Home(enabled: Boolean, contact: Boolean) {
        GuardianHome(
            homeUi(enabled, contact),
            GuardianHomeCallbacks(),
            standaloneHomeOptions(onPrivacy = {})
        )
    }

    @Composable
    private fun Settings(enabled: Boolean, contact: Boolean) {
        GuardianSettings(
            ui = homeUi(enabled, contact),
            cb = GuardianHomeCallbacks(),
            options = standaloneHomeOptions(onPrivacy = {}),
            onBack = {}
        )
    }

    // ---------- 首页：未开启（暖白固定主题 / 大字号） ----------

    @Test
    fun homeDisabled() = shot("home_disabled") {
        Home(enabled = false, contact = false)
    }.also {
        // 新装四步向导：说明卡 + ①联系人 ②权限 ③演练 + 主按钮指向下一步
        assertTexts(
            "守护侠", "未开启", "适合装在长辈的手机上",
            "守护是什么", "紧急联系人", "还没有设置",
            "权限与系统设置", "实机演练", "还没有测试过",
            "设置", "选择紧急联系人", "8 小时无活动"
        )
        // 旧的"先用测试模式"提示卡已移除；守护侠无家人入口
        assertNoTexts("测试模式", "从家人中选择", "家人资料里还没有其他家人",
            "还没填手机号", "如果家人侠里开着守护")
    }

    @Test
    fun homeContactSet() = shot("home_contact_set") {
        GuardianHome(
            homeUi(enabled = false, contact = true),
            GuardianHomeCallbacks(),
            standaloneHomeOptions(onPrivacy = {})
        )
    }.also {
        // ①完成 → 主按钮指向②
        assertTexts("杨明", "138 0013 8000", "继续设置权限")
    }

    @Test
    fun homePermsReady() = shot("home_perms_ready") {
        GuardianHome(
            homeUi(enabled = false, contact = true, perms = permsAllOn()),
            GuardianHomeCallbacks(),
            standaloneHomeOptions(onPrivacy = {})
        )
    }.also {
        // ①②完成 → 主按钮指向③
        assertTexts("开始实机测试")
    }

    @Test
    fun homeDrillPassed() = shot("home_drill_passed") {
        GuardianHome(
            homeUi(
                enabled = false, contact = true, perms = permsAllOn(),
                drillResult = passedDrill(listOf(cA, cB))),
            GuardianHomeCallbacks(),
            standaloneHomeOptions(onPrivacy = {})
        )
    }.also {
        // 全部就绪 → 主按钮"开启守护"；演练卡显示上次通过
        assertTexts("开启守护", "上次通过")
    }

    @Test
    fun homeEnabledAnomaly() = shot("home_enabled_anomaly") {
        GuardianHome(
            homeUi(
                enabled = true, contact = true, perms = permsAllOn(),
                drillResult = passedDrill(listOf(cA, cB)),
                anomalies = listOf("必需项已被关闭：忽略电池优化")),
            GuardianHomeCallbacks(),
            standaloneHomeOptions(onPrivacy = {})
        )
    }.also {
        // 开启后检测到异常 → 红色置顶卡 + 处理入口
        assertTexts("守护可能失效", "必需项已被关闭", "检查权限", "重新测试", "实机测试")
    }

    @Test
    fun homeDisabledLarge() = shotLarge("home_disabled") {
        Home(enabled = false, contact = false)
    }

    // ---------- 实机演练页（等待时长五档） ----------

    @Test
    fun drillPage() = shot("drill_page") {
        GuardianDrill(
            homeUi(enabled = false, contact = true),
            GuardianHomeCallbacks(),
            onBack = {}
        )
    }.also {
        assertTexts(
            "实机测试", "做一次真机演练", "等待时长",
            "1 分钟", "10 分钟", "30 分钟", "1 小时", "2 小时",
            "完整测试，计入开启条件", "开始测试"
        )
    }

    /** 凭据有效 + 上次是 1 分钟快速检查：结果卡两行分开展示。 */
    @Test
    fun drillPageCredentialValid() = shot("drill_page_credential_valid") {
        GuardianDrill(
            homeUi(
                enabled = true, contact = true,
                drillResult = passedDrill(listOf(cA, cB)).copy(waitMinutes = 1),
                qualifiedPass = passedDrill(listOf(cA, cB))),
            GuardianHomeCallbacks(),
            onBack = {}
        )
    }.also {
        assertTexts("上次测试通过", "开启凭据有效", "锁屏 30 分钟通过")
    }

    /** 无凭据 + 上次是 1 分钟快速检查：提示还需完整测试。 */
    @Test
    fun drillPageNoCredential() = shot("drill_page_no_credential") {
        GuardianDrill(
            homeUi(
                enabled = false, contact = true,
                drillResult = passedDrill(listOf(cA, cB)).copy(waitMinutes = 1),
                qualifiedPass = null),
            GuardianHomeCallbacks(),
            onBack = {}
        )
    }.also {
        assertTexts("上次测试通过", "还没有通过 ≥30 分钟的完整测试")
    }

    // ---------- 首页：已开启 ----------

    @Test
    fun homeEnabled() = shot("home_enabled") {
        Home(enabled = true, contact = true)
    }.also {
        assertTexts(
            "守护侠", "守护已开启", "就短信+电话通知", "查看守护记录",
            "运行状态", "设置", "杨明", "停止守护"
        )
        assertNoTexts("从家人中选择", "家人资料里还没有其他家人")
    }

    // ---------- 设置页与联系人选择 ----------

    @Test
    fun settings() = shot("settings") {
        Settings(enabled = false, contact = false)
    }.also {
        assertTexts(
            "设置", "紧急联系人", "还没有设置", "添加联系人",
            "守护设置", "无活动阈值", "确认时长", "夜间免打扰",
            "每日平安短信", "权限与系统设置", "实机测试"
        )
    }

    @Test
    fun contactPicker() {
        composeRule.setContent {
            JrxTheme(dark = false) { Settings(enabled = false, contact = true) }
        }
        composeRule.onNodeWithText("添加联系人").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(
            File("build/outputs/roborazzi/g_contact_picker.png").absolutePath
        )
        assertTexts("选择紧急联系人", "从通讯录选择", "手动输入")
        assertNoTexts("王秀兰", "家人资料里还没有其他家人", "还没填手机号")
    }

    // ---------- 设置页底（隐私入口、版本号） ----------

    @Test
    fun settingsBottom() = shotScrolledTo("settings_bottom", "版本") {
        Settings(enabled = false, contact = false)
    }.also {
        assertTexts("隐私说明", "版本 " + BuildConfig.VERSION_NAME)
    }

    @Test
    fun settingsBottomLarge() =
        shotScrolledTo("settings_bottom_lg", "版本", large = true) {
            Settings(enabled = false, contact = false)
        }.also {
            assertTexts("隐私说明", "版本 " + BuildConfig.VERSION_NAME)
        }

    // ---------- 多联系人：列表编辑 / 策略 / 报警卡 / 演练 ----------

    private fun seq(
        step: io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep,
        round: Int = 1, index: Int = 0,
        placedMask: Int = 0, answeredMask: Int = 0, nextRoundAt: Long = 0,
    ) = io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq(
        step = step, round = round, index = index,
        placedMask = placedMask, answeredMask = answeredMask,
        nextRoundAt = nextRoundAt)

    @Test
    fun contactsThree() = shotScrolledTo("contacts_three", "最多 3 位") {
        GuardianSettings(
            ui = homeUi(enabled = false, contact = true,
                contacts = listOf(cA, cB, cC)),
            cb = GuardianHomeCallbacks(),
            options = standaloneHomeOptions(onPrivacy = {}), onBack = {})
    }.also {
        assertTexts("最多 3 位", "王芳", "顺序就是拨打顺序")
    }

    @Test
    fun contactsOne() = shot("contacts_one") {
        GuardianSettings(
            ui = homeUi(enabled = false, contact = true,
                contacts = listOf(cA)),
            cb = GuardianHomeCallbacks(),
            options = standaloneHomeOptions(onPrivacy = {}), onBack = {})
    }.also {
        assertTexts("杨明", "添加联系人")
        assertNoTexts("最多 3 位")
    }

    @Test
    fun policyStopOnAnswer() = shotScrolledTo("policy", "有人接通后") {
        Settings(enabled = false, contact = true)
    }.also {
        assertTexts("有人接通就停止拨打其他人", "继续拨打所有联系人",
            "最多 2 轮")
    }

    @Test
    fun alertingProgress() = shot("alerting_progress") {
        GuardianHome(
            homeUi(
                enabled = true, contact = true,
                state = GuardState(
                    phase = Phase.ALERTING,
                    lastActivityAt = demoNow - 9 * 3600_000L,
                    alertSeq = seq(
                        io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.DIALING,
                        round = 1, index = 1,
                        placedMask = 0b11, answeredMask = 0),
                    alertSmsFlags = GuardState.SMS_EMERGENCY_SENT)),
            GuardianHomeCallbacks(),
            standaloneHomeOptions(onPrivacy = {}))
    }.also {
        assertTexts("正在报警", "第 1 / 2 轮", "未接通", "正在拨打",
            "紧急短信已发给所有联系人", "我没事")
    }

    @Test
    fun alertRoundWait() = shot("alert_round_wait") {
        GuardianHome(
            homeUi(
                enabled = true, contact = true,
                state = GuardState(
                    phase = Phase.ALERTING,
                    lastActivityAt = demoNow - 9 * 3600_000L,
                    alertSeq = seq(
                        io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.ROUND_WAIT,
                        round = 1, index = 2,
                        placedMask = 0b11, answeredMask = 0,
                        nextRoundAt = demoNow + 2 * 60_000L),
                    alertSmsFlags = GuardState.SMS_EMERGENCY_SENT)),
            GuardianHomeCallbacks(),
            standaloneHomeOptions(onPrivacy = {}))
    }.also {
        assertTexts("本轮无人接听", "开始第 2 轮", "我没事")
    }

    @Test
    fun alertedAnswered() = shot("alerted_answered") {
        GuardianHome(
            homeUi(
                enabled = true, contact = true,
                state = GuardState(
                    phase = Phase.ALERTED,
                    alertedAt = demoNow - 10 * 60_000L,
                    lastActivityAt = demoNow - 9 * 3600_000L,
                    alertSeq = seq(
                        io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.DONE,
                        round = 1, index = 1,
                        placedMask = 0b11, answeredMask = 0b10),
                    alertSmsFlags = GuardState.SMS_EMERGENCY_SENT or
                        GuardState.SMS_FINAL_SENT)),
            GuardianHomeCallbacks(),
            standaloneHomeOptions(onPrivacy = {}))
    }.also {
        assertTexts("李华已接听", "我没事（恢复守护）", "点了会通知家人：已解除")
    }

    @Test
    fun drillCalling() = shot("drill_calling") {
        GuardianDrill(
            homeUi(
                enabled = true, contact = true,
                state = GuardState(
                    drill = io.github.xiaohaoz1.jiarenxia.guardian.core.Drill(
                        phase = io.github.xiaohaoz1.jiarenxia.guardian.core.DrillPhase.CALLING,
                        calls = seq(
                            io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.DIALING,
                            index = 1, placedMask = 0b11),
                        smsSentFlag = true, smsSubmittedMask = 0b11))),
            GuardianHomeCallbacks(), onBack = {})
    }.also {
        assertTexts("正在依次拨打", "现在拨打第 2 位：李华", "停止测试")
    }

    @Test
    fun drillAsk() = shot("drill_ask") {
        GuardianDrill(
            homeUi(
                enabled = true, contact = true,
                state = GuardState(
                    drill = io.github.xiaohaoz1.jiarenxia.guardian.core.Drill(
                        phase = io.github.xiaohaoz1.jiarenxia.guardian.core.DrillPhase.ASK_ANSWER,
                        askIndex = 0,
                        calls = seq(
                            io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep.DONE,
                            index = 1, placedMask = 0b11, answeredMask = 0b11),
                        smsSentFlag = true,
                        smsSubmittedMask = 0b11, smsOkMask = 0b11))),
            GuardianHomeCallbacks(), onBack = {})
    }.also {
        assertTexts("第 1 / 2 位：杨明", "收到测试短信了吗", "接到测试电话了吗",
            "系统检测", "确认，下一位")
    }

    @Test
    fun drillResultMulti() = shot("drill_result_multi") {
        val fail = DrillResult(
            outcome = DrillOutcome.FAILED,
            reason = "李华：电话未接通，请确认号码正确、对方手机可接听",
            waitMinutes = 30,
            plannedAt = demoNow - 40 * 60_000L,
            firedAt = demoNow - 10 * 60_000L,
            delayMillis = 3_000L,
            onTime = true, fullscreenOk = true,
            contactResults = listOf(
                io.github.xiaohaoz1.jiarenxia.guardian.core.DrillContactResult(
                    name = "杨明", number = cA.number,
                    smsSent = true, callAnswered = true,
                    smsConfirmed = true, callConfirmed = true),
                io.github.xiaohaoz1.jiarenxia.guardian.core.DrillContactResult(
                    name = "李华", number = cB.number,
                    smsSent = true, callAnswered = false,
                    smsConfirmed = true, callConfirmed = false),
            ),
            finishedAt = demoNow - 5 * 60_000L,
            versionCode = 3L, fingerprint = "demo-fp",
            contactsKey = io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
                .contactsKey(listOf(cA, cB)),
        )
        GuardianDrill(
            homeUi(enabled = false, contact = true, drillResult = fail,
                qualifiedPass = null),
            GuardianHomeCallbacks(), onBack = {})
    }.also {
        assertTexts("上次测试未通过", "李华", "电话未接通",
            "测试短信已发出", "电话已接通", "确认收到短信", "确认接到电话")
    }

    @Test
    fun prealarmMulti() = shot("prealarm_multi") {
        io.github.xiaohaoz1.jiarenxia.guardian.ui.PreAlarmScreen(
            state = GuardState(phase = Phase.CONFIRMING,
                confirmEndAt = System.currentTimeMillis() + 90_000L),
            config = GuardConfig(contacts = listOf(cA, cB, cC)),
            onImOk = {}, onAlertNow = {})
    }.also {
        assertTexts("3 位紧急联系人", "先打给 杨明", "我没事")
    }

    // ---------- 隐私说明 ----------

    @Test
    fun privacy() = shot("privacy") {
        PrivacyScreen(onBack = {})
    }.also {
        assertTexts(
            "隐私说明",
            "不联网、不注册账号，所有配置只保存在这台手机上",
            "拨打你设置的紧急联系人", "运营商按你的套餐收取",
            "系统选择器读取你选中的那一个号码",
            "不进入系统云备份和设备间迁移"
        )
    }
}
