package io.github.xiaohaoz1.jiarenxia.guardian.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContactPhone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.xiaohaoz1.jiarenxia.guardian.core.AnswerPolicy
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillOutcome
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardianFormat
import io.github.xiaohaoz1.jiarenxia.guardian.core.Phase
import io.github.xiaohaoz1.jiarenxia.guardian.core.SeqStep
import io.github.xiaohaoz1.jiarenxia.guardian.core.TimeRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.maskNumber
import io.github.xiaohaoz1.jiarenxia.guardian.sys.Permissions
import io.github.xiaohaoz1.jiarenxia.ui.designsystem.PageHeader
import io.github.xiaohaoz1.jiarenxia.ui.designsystem.JrxCard
import io.github.xiaohaoz1.jiarenxia.ui.theme.LocalJrxColors
import kotlinx.coroutines.delay

/** 守护主页 UI 状态（stateless 内容渲染输入，截图可直传）。 */
data class GuardianHomeUi(
    val config: GuardConfig,
    val state: GuardState,
    val perms: List<Permissions.PermissionItem>,
    /** 需手动确认的必需项（含检测 UNKNOWN 降级的项）。 */
    val manualItems: List<Permissions.ManualItem> = emptyList(),
    /** 当前已确认的手动项 key 集合（含 "perm_xxx" 降级项）。 */
    val manualConfirmed: Set<String> = emptySet(),
    /** 是否调试包：决定是否显示"开发快速阈值"开关。 */
    val debuggable: Boolean = false,
    /** 环境信息：演练记录有效性判定。 */
    val versionCode: Long = 0,
    val fingerprint: String = "",
    /** 开启后异常原因（空 = 正常）。 */
    val anomalies: List<String> = emptyList(),
    /** 开启前确认对话框（权限请求通过后由外层置 true）。 */
    val confirmingEnable: Boolean = false,
    val now: Long
) {
    val hasContact: Boolean get() = config.validContacts.isNotEmpty()

    /** 自动检测必需项完成进度。 */
    val autoRequired: List<Permissions.PermissionItem> get() = perms.filter { !it.optional }
    val autoDone: Int get() = autoRequired.count { it.state == Permissions.CheckState.ON }

    /**
     * 全部"需手动确认"键：静态手动项 + 检测为 UNKNOWN 而降级的必需自动项。
     * 首页进度与权限就绪判定共用同一口径。
     */
    val manualRequiredKeys: Set<String>
        get() = (manualItems.map { it.key } +
            autoRequired.filter { it.state == Permissions.CheckState.UNKNOWN }
                .map { "perm_" + it.key }).toSet()

    /** 手动确认完成数 / 总数（步骤②与权限进度卡共用）。 */
    val manualDone: Int
        get() = manualRequiredKeys.count { manualConfirmed.contains(it) }
    val manualTotal: Int get() = manualRequiredKeys.size

    /**
     * 权限就绪：必需自动项非 OFF（UNKNOWN 降级项需已在手动组确认）；
     * 所有手动确认项已勾选。
     */
    val permsReady: Boolean
        get() = autoRequired.all {
            it.state == Permissions.CheckState.ON ||
                (it.state == Permissions.CheckState.UNKNOWN &&
                    manualConfirmed.contains("perm_" + it.key))
        } && manualItems.all { manualConfirmed.contains(it.key) }

    /** 有效演练记录（qualifiedPass）：通过 + 等待≥30分 + 版本/指纹/联系人未变。 */
    val drillValid: Boolean
        get() = DrillRules.isRecordValid(
            state.qualifiedPass, versionCode, fingerprint,
            DrillRules.contactsKey(config.validContacts))

    /** 演练记录失效原因（有效时为空）。 */
    val drillInvalidReasons: List<String>
        get() = DrillRules.invalidReasons(
            state.qualifiedPass, versionCode, fingerprint,
            DrillRules.contactsKey(config.validContacts))

    /** 开启凭据（qualifiedPass）状态描述：有效日期 / 缺失 / 已失效原因。 */
    val drillCredentialStatus: String
        get() {
            val q = state.qualifiedPass
                ?: return "还没有通过 ≥30 分钟的完整测试"
            return if (drillInvalidReasons.isEmpty()) {
                "开启凭据有效：" + TimeRules.formatDateTime(q.finishedAt) +
                    " 锁屏 ${q.waitMinutes} 分钟通过"
            } else {
                "开启凭据已失效：" + drillInvalidReasons.joinToString("、")
            }
        }

    /**
     * 演练可发起的最低条件：有联系人 + 当前设备上存在的必需运行时权限全部已授。
     * 按 runtimePermission 判定而不是按 key——notifications 项仅 API33+ 存在，
     * 低版本按 key 查会永远找不到。
     */
    val drillMinReady: Boolean
        get() = hasContact && perms
            .filter { it.runtimePermission != null && !it.optional }
            .all { it.state == Permissions.CheckState.ON }
}

/**
 * 守护侠（:guardianapp）首页可定制项：家人侠已于 C3 移除守护，
 * 差异全部经这里的参数/插槽注入，默认值即守护侠通用首页。
 */
class GuardianHomeOptions(
    /** 页头标题。 */
    val title: String = "守护侠",
    /** 设置页末尾的页尾插槽（如隐私说明入口与版本号）。 */
    val footer: (@Composable () -> Unit)? = null,
)

/** 主页回调：引擎事件与系统跳转全部由外层执行。 */
class GuardianHomeCallbacks {
    var onEnableRequest: () -> Unit = {}
    var onEnableConfirmed: () -> Unit = {}
    var onDismissEnable: () -> Unit = {}
    var onDisable: () -> Unit = {}
    var onImOk: () -> Unit = {}
    var onAlertNow: () -> Unit = {}
    /** 打开系统通讯录选择器，选中后追加到联系人末尾。 */
    var onAddFromContacts: () -> Unit = {}
    /** 手动输入联系人（name 可为空），追加到末尾。 */
    var onAddManual: (String, String) -> Unit = { _, _ -> }
    /** 删除第 index 位联系人。 */
    var onRemoveContact: (Int) -> Unit = {}
    /** 把第 from 位移到 to 位（调整拨打顺序）。 */
    var onMoveContact: (Int, Int) -> Unit = { _, _ -> }
    /** 「有人接通后」策略：接通即停 / 继续拨打所有人。 */
    var onAnswerPolicy: (AnswerPolicy) -> Unit = {}
    var onThresholdHours: (Int) -> Unit = {}
    var onConfirmMinutes: (Int) -> Unit = {}
    var onDailySmsEnabled: (Boolean) -> Unit = {}
    var onDailySmsTime: (Int) -> Unit = {}
    var onQuietEnabled: (Boolean) -> Unit = {}
    var onQuietStart: (Int) -> Unit = {}
    var onQuietEnd: (Int) -> Unit = {}
    var onDevFastThresholds: (Boolean) -> Unit = {}
    var onOpenSettings: () -> Unit = {}
    var onOpenPermissions: () -> Unit = {}
    var onOpenLog: () -> Unit = {}
    var onSendSms: () -> Unit = {}
    // 演练
    var onOpenDrill: () -> Unit = {}
    var onStartDrill: (Int) -> Unit = {}
    var onStopDrill: () -> Unit = {}
    /** 演练逐位问答：第 index 位是否收到测试短信 / 接到测试电话。 */
    var onDrillContactAnswer: (Int, Boolean, Boolean) -> Unit = { _, _, _ -> }
    var onConfirmManual: (String) -> Unit = {}
    var onPermItem: (Permissions.PermissionItem) -> Unit = {}
}

/** 守护侠主页（无 TopAppBar，单页直出）。stateless，便于截图。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GuardianHome(
    ui: GuardianHomeUi,
    cb: GuardianHomeCallbacks,
    options: GuardianHomeOptions = GuardianHomeOptions()
) {
    var stopConfirm by remember { mutableStateOf(false) }
    var pickerSheet by remember { mutableStateOf(false) }
    var manualDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PageHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(options.title, style = MaterialTheme.typography.headlineSmall)
                        val subtitle = statusSubtitle(ui)
                        if (subtitle.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                if (!ui.config.enabled) {
                    SetupWizard(ui, cb, onAddContact = { pickerSheet = true })
                } else {
                    EnabledSection(ui, cb, onAskStop = { stopConfirm = true })
                }
                Spacer(Modifier.height(96.dp))
            }
        }
    }

    // 联系人选择弹层（首页第①步与设置页共用同一套回调；选中追加到末尾）
    if (pickerSheet) {
        ContactPickerSheet(
            onPick = { pickerSheet = false; cb.onAddFromContacts() },
            onManual = { pickerSheet = false; manualDialog = true },
            onDismiss = { pickerSheet = false }
        )
    }
    if (manualDialog) {
        ManualContactDialog(
            onSubmit = { n, num -> cb.onAddManual(n, num); manualDialog = false },
            onDismiss = { manualDialog = false }
        )
    }

    if (ui.confirmingEnable) {
        AlertDialog(
            onDismissRequest = cb.onDismissEnable,
            title = { Text("开启守护") },
            text = {
                val vc = ui.config.validContacts
                Text(
                    "开启后，这台手机超过 ${ui.config.thresholdMinutes / 60} 小时无人使用会响铃；" +
                        "${ui.config.confirmWindowMinutes} 分钟内无人确认：" +
                        "先给 ${vc.size} 位紧急联系人发紧急短信，再依次拨打：" +
                        vc.joinToString(" → ") { it.label() } + "；" +
                        (if (ui.config.answerPolicy == AnswerPolicy.STOP_ON_ANSWER)
                            "谁先接听就由谁处理，其他人只收到短信。"
                        else "每个人都会接到电话。") +
                        (if (ui.config.quietEnabled)
                            "夜间免打扰（" + fmtMinute(ui.config.quietStartMinuteOfDay) + "–" +
                                fmtMinute(ui.config.quietEndMinuteOfDay) +
                                "）内到点的报警顺延到时段结束。" else "")
                )
            },
            confirmButton = {
                TextButton(onClick = cb.onEnableConfirmed) { Text("开启") }
            },
            dismissButton = { TextButton(onClick = cb.onDismissEnable) { Text("取消") } }
        )
    }

    if (stopConfirm) {
        AlertDialog(
            onDismissRequest = { stopConfirm = false },
            title = { Text("停止守护") },
            text = { Text("停止后将不再响铃提醒和自动联系家人。") },
            confirmButton = {
                TextButton(onClick = { stopConfirm = false; cb.onDisable() }) {
                    Text("停止", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { stopConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 联系人选择弹层：首页向导与设置页共用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactPickerSheet(
    onPick: () -> Unit, onManual: () -> Unit, onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("选择紧急联系人", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.ContactPhone, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("从通讯录选择")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("手动输入")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 守护侠设置页：联系人、守护规则、系统权限与平安短信集中在这里。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GuardianSettings(
    ui: GuardianHomeUi,
    cb: GuardianHomeCallbacks,
    options: GuardianHomeOptions = GuardianHomeOptions(),
    onBack: () -> Unit
) {
    var pickerSheet by remember { mutableStateOf(false) }
    var manualDialog by remember { mutableStateOf(false) }
    var smsTimeDialog by remember { mutableStateOf(false) }
    var quietStartDialog by remember { mutableStateOf(false) }
    var quietEndDialog by remember { mutableStateOf(false) }
    var sendSmsConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.width(2.dp))
                Text("设置", style = MaterialTheme.typography.titleLarge)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                ContactCard(
                    ui = ui,
                    cb = cb,
                    onAddContact = { pickerSheet = true }
                )
                AnswerPolicyCard(ui, cb)
                SettingsCard(
                    ui = ui,
                    cb = cb,
                    onSmsTime = { smsTimeDialog = true },
                    onQuietTime = { isStart ->
                        if (isStart) quietStartDialog = true else quietEndDialog = true
                    },
                    onSendSmsTry = { sendSmsConfirm = true }
                )
                PermissionsProgressCard(ui, cb.onOpenPermissions)
                DrillEntryCard(ui, cb.onOpenDrill)
                options.footer?.invoke()
                Spacer(Modifier.height(96.dp))
            }
        }
    }

    if (pickerSheet) {
        ContactPickerSheet(
            onPick = { pickerSheet = false; cb.onAddFromContacts() },
            onManual = { pickerSheet = false; manualDialog = true },
            onDismiss = { pickerSheet = false }
        )
    }
    if (manualDialog) {
        ManualContactDialog(
            onSubmit = { n, num -> cb.onAddManual(n, num); manualDialog = false },
            onDismiss = { manualDialog = false }
        )
    }
    if (smsTimeDialog) {
        TimePickDialog(
            title = "平安短信发送时间",
            minuteOfDay = ui.config.dailySmsMinuteOfDay,
            onPick = { cb.onDailySmsTime(it); smsTimeDialog = false },
            onDismiss = { smsTimeDialog = false }
        )
    }
    if (quietStartDialog) {
        TimePickDialog(
            title = "免打扰开始时间",
            minuteOfDay = ui.config.quietStartMinuteOfDay,
            onPick = { cb.onQuietStart(it); quietStartDialog = false },
            onDismiss = { quietStartDialog = false }
        )
    }
    if (quietEndDialog) {
        TimePickDialog(
            title = "免打扰结束时间",
            minuteOfDay = ui.config.quietEndMinuteOfDay,
            onPick = { cb.onQuietEnd(it); quietEndDialog = false },
            onDismiss = { quietEndDialog = false }
        )
    }
    if (sendSmsConfirm) {
        AlertDialog(
            onDismissRequest = { sendSmsConfirm = false },
            title = { Text("发送平安短信") },
            text = { Text("这会真实地发送一条平安短信给紧急联系人，确定吗？") },
            confirmButton = {
                TextButton(onClick = { sendSmsConfirm = false; cb.onSendSms() }) {
                    Text("发送")
                }
            },
            dismissButton = {
                TextButton(onClick = { sendSmsConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** "HH:mm"，免打扰时段与平安短信时间共用。 */
private fun fmtMinute(m: Int): String = "%02d:%02d".format(m / 60 % 24, m % 60)

/** 头图副标题：按相位输出；守护中不显示，状态摘要卡已有同样信息。 */
private fun statusSubtitle(ui: GuardianHomeUi): String {
    val c = ui.config
    val s = ui.state
    return when {
        s.drill.active -> "实机测试进行中"
        !c.enabled -> "未开启 · 适合装在长辈的手机上"
        s.phase == Phase.MONITORING -> ""
        s.phase == Phase.CONFIRMING -> "正在等待确认"
        s.phase == Phase.ALERTING ->
            GuardianFormat.statusText(s, c.validContacts)
        else -> GuardianFormat.alertResultText(s, c.validContacts)
    }
}

// ---------- 未开启：四步向导 ----------

@Composable
private fun SetupWizard(
    ui: GuardianHomeUi,
    cb: GuardianHomeCallbacks,
    onAddContact: () -> Unit
) {
    // 0. 说明卡
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("守护是什么", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        IntroStep(Icons.Rounded.PhoneAndroid, "手机长时间没人使用（默认 8 小时）")
        IntroStep(Icons.Rounded.NotificationsActive, "先大声响铃，请对方确认“我没事”")
        IntroStep(Icons.Rounded.Call, "无人响应时，先给紧急联系人发短信，再依次打电话")
        Spacer(Modifier.height(6.dp))
        Text(
            "不联网，只会联系你设定的人。建议装在独居长辈的手机上。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ① 紧急联系人（与设置页共用同一个列表编辑组件）
    StepCard(
        index = "①", title = "紧急联系人", done = ui.hasContact,
        summary = if (ui.hasContact)
            "已设置 ${ui.config.validContacts.size} 位：" +
                ui.config.validContacts.joinToString("、") { it.label() }
        else "还没有设置"
    ) {
        Spacer(Modifier.height(6.dp))
        ContactListEditor(ui, cb, onAdd = onAddContact)
    }

    // ② 权限与系统设置（手动确认口径与 permsReady 一致：含 UNKNOWN 降级项）
    StepCard(
        index = "②", title = "权限与系统设置", done = ui.permsReady,
        summary = "自动检测 ${ui.autoDone}/${ui.autoRequired.size} · " +
            "手动确认 ${ui.manualDone}/${ui.manualTotal}"
    ) {
        OutlinedButton(onClick = cb.onOpenPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("去设置权限")
        }
    }

    // ③ 实机演练
    StepCard(
        index = "③", title = "实机演练", done = ui.drillValid,
        summary = drillSummary(ui)
    ) {
        if (!ui.hasContact || !ui.permsReady) {
            Text(
                "完成前两步后再测试更准确",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
        }
        OutlinedButton(onClick = cb.onOpenDrill, modifier = Modifier.fillMaxWidth()) {
            Text(if (ui.state.lastDrillResult == null) "开始实机测试" else "查看/重新测试")
        }
    }

    SettingsEntryCard(ui, cb.onOpenSettings)

    // 主按钮永远可点、永远指向下一步
    val (label, action) = when {
        !ui.hasContact -> "选择紧急联系人" to onAddContact
        !ui.permsReady -> "继续设置权限" to cb.onOpenPermissions
        !ui.drillValid -> "开始实机测试" to cb.onOpenDrill
        else -> "开启守护" to cb.onEnableRequest
    }
    Button(
        onClick = action,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(56.dp)
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

/**
 * 演练卡摘要：第一行是上次测试的结局/时间/锁屏时长/原因（lastDrillResult），
 * 第二行是开启凭据状态（qualifiedPass）——两者分开，互不解释。
 */
private fun drillSummary(ui: GuardianHomeUi): String {
    val r = ui.state.lastDrillResult
    val last = if (r == null) {
        "还没有测试过"
    } else {
        when (r.outcome) {
            DrillOutcome.PASSED ->
                "上次通过：" + TimeRules.formatDateTime(r.finishedAt) +
                    "（锁屏${r.waitMinutes}分钟，延迟${r.delayMillis / 1000}秒）"
            DrillOutcome.FAILED -> "上次未通过：${r.reason}"
            DrillOutcome.ABORTED -> "上次中止：${r.reason}"
        }
    }
    return last + "\n" + ui.drillCredentialStatus
}

/** 向导步骤卡：序号 + 标题 + 完成态（✓ 或待办） + 内容。 */
@Composable
private fun StepCard(
    index: String, title: String, done: Boolean, summary: String,
    content: @Composable () -> Unit
) {
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = if (done) LocalJrxColors.current.success
                else MaterialTheme.colorScheme.primary
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.15f),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (done) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = tint,
                            modifier = Modifier.size(18.dp))
                    } else {
                        Text(index, style = MaterialTheme.typography.labelLarge,
                            color = tint, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done) LocalJrxColors.current.success
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        content()
    }
}

@Composable
private fun IntroStep(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 守护中的摘要卡：绿调底色，一句话说清“多久没动静、打给谁”。 */
@Composable
private fun MonitoringCard(c: GuardConfig, s: GuardState, now: Long, onOpenLog: () -> Unit) {
    val success = LocalJrxColors.current.success
    val onTertiary = MaterialTheme.colorScheme.onTertiaryContainer
    val thresholdText =
        if (c.devFastThresholds) "30 秒" else (c.thresholdMinutes / 60).toString() + " 小时"
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Shield, null, tint = success, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "守护已开启",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = success
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                thresholdText + "没动静，就短信+电话通知 " +
                    c.validContacts.size + " 位紧急联系人（先打给 " +
                    (c.validContacts.firstOrNull()?.label() ?: "紧急联系人") + "）" +
                    if (c.quietEnabled && !c.devFastThresholds)
                        "；" + fmtMinute(c.quietStartMinuteOfDay) + "–" +
                            fmtMinute(c.quietEndMinuteOfDay) + " 不响铃"
                    else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onTertiary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                activityLine(s, now),
                style = MaterialTheme.typography.bodySmall,
                color = onTertiary
            )
            HorizontalDivider(
                Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLog)
            ) {
                Text(
                    "查看守护记录",
                    style = MaterialTheme.typography.labelLarge,
                    color = success,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = success)
            }
        }
    }
}

/** 守护卡第三行：使用中 / 上次使用的相对时间 / 无记录。 */
private fun activityLine(s: GuardState, now: Long): String = when {
    s.inUse -> "正在使用手机 · 使用期间不计入无活动时间"
    s.lastActivityAt > 0 -> "上次使用 " +
        GuardianFormat.formatRelativeDayTime(s.lastActivityAt, now) +
        "（" + GuardianFormat.formatAgo(s.lastActivityAt, now) + "）"
    else -> "暂无使用记录"
}

/** 运行状态明细：上次使用、上次锁屏、响铃倒计时、平安短信；末尾是机制说明。 */
@Composable
private fun RunDetails(c: GuardConfig, s: GuardState, now: Long) {
    InfoRow(
        "上次使用手机",
        when {
            s.phase == Phase.MONITORING && s.inUse -> "正在使用"
            s.lastActivityAt > 0 ->
                GuardianFormat.formatRelativeDayTime(s.lastActivityAt, now) +
                    "（" + GuardianFormat.formatAgo(s.lastActivityAt, now) + "）"
            else -> "无记录"
        }
    )
    if (s.phase == Phase.MONITORING && s.inUse && s.lastLockAt > 0) {
        InfoRow(
            "上次锁屏",
            GuardianFormat.formatRelativeDayTime(s.lastLockAt, now) +
                "（" + GuardianFormat.formatAgo(s.lastLockAt, now) + "）"
        )
    }
    if (s.phase == Phase.MONITORING && s.deadlineAt > 0) {
        InfoRow(
            "距离响铃检查",
            if (s.inUse) "锁屏后开始计时"
            else GuardianFormat.formatCountdown(s.deadlineAt - now)
        )
    }
    if (c.dailySmsEnabled && s.nextDailySmsAt > 0) {
        InfoRow(
            "下次平安短信",
            GuardianFormat.formatRelativeDayTime(s.nextDailySmsAt, now)
        )
    }
    // 机制说明：锁屏才计时；使用中由“使用中上限”兜底
    // （报警/确认相位下这段与当前情况无关，只保留上面"上次使用手机"行）
    if (s.phase == Phase.MONITORING) {
        val thresholdDesc =
            if (c.devFastThresholds) "30 秒" else (c.thresholdMinutes / 60).toString() + " 小时"
        Text(
            "锁屏后超过 " + thresholdDesc +
                " 没有再使用手机（解锁、接打电话），会先响铃确认" +
                if (c.quietEnabled && !c.devFastThresholds)
                    "；免打扰时段内到点的报警顺延到 " + fmtMinute(c.quietEndMinuteOfDay)
                else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// ---------- 已开启 ----------

@Composable
private fun EnabledSection(
    ui: GuardianHomeUi,
    cb: GuardianHomeCallbacks,
    onAskStop: () -> Unit
) {
    val s = ui.state
    val c = ui.config

    // 倒计时用每秒刷新（仅展示，业务时序仍由 AlarmManager 驱动）
    var now by remember { mutableLongStateOf(ui.now) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = System.currentTimeMillis() }
    }

    // 异常卡：红色、置顶——必需项被收回 / 演练记录失效 / 手动确认失效
    if (ui.anomalies.isNotEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("守护可能失效", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
                ui.anomalies.forEach { a ->
                    Text(a, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = cb.onOpenPermissions) { Text("检查权限") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = cb.onOpenDrill) { Text("重新测试") }
                }
            }
        }
    }

    if (s.phase == Phase.MONITORING) {
        MonitoringCard(c, s, now, cb.onOpenLog)
        JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("运行状态", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            RunDetails(c, s, now)
        }
    } else {
        JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (tint, label) = when (s.phase) {
                    Phase.CONFIRMING -> LocalJrxColors.current.warning to "正在等待确认"
                    Phase.ALERTING -> MaterialTheme.colorScheme.error to "正在报警"
                    else -> MaterialTheme.colorScheme.error to "已完成报警"
                }
                Surface(
                    shape = CircleShape,
                    color = tint.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Shield, null, tint = tint,
                            modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Text(label, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
            // 报警序列进度：轮次 + 每位一行状态
            if (s.phase == Phase.ALERTING || s.phase == Phase.ALERTED) {
                AlertSeqProgress(ui, now)
                Spacer(Modifier.height(8.dp))
            }
            RunDetails(c, s, now)
        }
    }

    // CONFIRMING / ALERTED 大卡片
    if (s.phase == Phase.CONFIRMING) {
        val remain = ((s.confirmEndAt - now) / 1000).coerceAtLeast(0)
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("正在等待确认",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (c.validContacts.size <= 1)
                        "$remain 秒后将发短信并拨打 " +
                            (c.validContacts.firstOrNull()?.label() ?: "紧急联系人")
                    else "$remain 秒后将通知 ${c.validContacts.size} 位紧急联系人，先打给 " +
                        (c.validContacts.firstOrNull()?.label() ?: "紧急联系人"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = cb.onImOk,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("我没事", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = cb.onAlertNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) { Text("立即呼叫", style = MaterialTheme.typography.titleMedium) }
            }
        }
    } else if (s.phase == Phase.ALERTING) {
        // 报警中卡：只放"我没事"解除（进度在上面的顶部状态卡里）
        JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Button(
                onClick = cb.onImOk,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("我没事", style = MaterialTheme.typography.titleMedium) }
            Text(
                "点了会通知家人：已解除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    } else if (s.phase == Phase.ALERTED) {
        JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                GuardianFormat.alertResultText(s, c.validContacts),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = cb.onImOk,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("我没事（恢复守护）") }
            Text(
                "点了会通知家人：已解除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    // 常驻的实机测试入口（显示上次结果摘要）
    DrillEntryCard(ui, cb.onOpenDrill)

    SettingsEntryCard(ui, cb.onOpenSettings)
    if (s.phase != Phase.MONITORING) {
        JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            NavRow("查看守护记录", cb.onOpenLog)
        }
    }

    OutlinedButton(
        onClick = onAskStop,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(52.dp)
    ) { Text("停止守护") }
    Spacer(Modifier.height(8.dp))
}

/**
 * 报警序列进度行：第几轮 + 每位联系人一行状态 + 紧急短信是否已发。
 * 演练页 CALLING 卡也共用（传入 drill.calls）。
 */
@Composable
fun AlertSeqProgress(
    ui: GuardianHomeUi, now: Long,
    seq: io.github.xiaohaoz1.jiarenxia.guardian.core.CallSeq = ui.state.alertSeq,
    showSmsFlag: Boolean = true,
) {
    val contacts = ui.config.validContacts
    if (contacts.isEmpty()) return
    Column {
        if (seq.round > 0 && seq.step != SeqStep.IDLE) {
            Text(
                "第 ${seq.round} / ${io.github.xiaohaoz1.jiarenxia.guardian.core.AlertRules.MAX_ROUNDS} 轮",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (seq.step == SeqStep.ROUND_WAIT && seq.nextRoundAt > 0) {
            Text(
                "本轮无人接听，" + TimeRules.formatClock(seq.nextRoundAt) +
                    " 开始第 ${seq.round + 1} 轮",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val states = GuardianFormat.seqContactStates(seq, contacts.size)
        contacts.forEachIndexed { i, contact ->
            val (label, tint) = when (states[i]) {
                GuardianFormat.SeqContactState.DIALING ->
                    "正在拨打" to MaterialTheme.colorScheme.error
                GuardianFormat.SeqContactState.ANSWERED ->
                    "已接听" to LocalJrxColors.current.success
                GuardianFormat.SeqContactState.NO_ANSWER ->
                    "未接通" to MaterialTheme.colorScheme.onSurfaceVariant
                GuardianFormat.SeqContactState.WAITING ->
                    // DONE 下的是"没轮到拨"（有人接通提前收尾），其余是等待中
                    (if (seq.step == SeqStep.DONE) "未拨打" else "等待拨打") to
                        MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Text(
                    "${i + 1}. " + contact.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
            }
        }
        if (showSmsFlag) {
            Text(
                if (ui.state.alertSmsFlags and GuardState.SMS_EMERGENCY_SENT != 0)
                    "紧急短信已发给所有联系人"
                else "紧急短信发送中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 实机演练入口卡：显示上次结果摘要，随时可测。 */
@Composable
private fun DrillEntryCard(ui: GuardianHomeUi, onOpenDrill: () -> Unit) {
    JrxCard(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onOpenDrill)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("实机测试", style = MaterialTheme.typography.titleMedium)
                Text(
                    drillSummary(ui),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 首页设置入口：把联系人、规则、权限的关键摘要收成一行。 */
@Composable
private fun SettingsEntryCard(ui: GuardianHomeUi, onClick: () -> Unit) {
    val c = ui.config
    val contact = if (c.validContacts.isEmpty()) "未设置联系人"
    else "${c.validContacts.size} 位：" +
        c.validContacts.joinToString("、") { it.label() }
    val threshold = "${c.thresholdMinutes / 60} 小时无活动"
    JrxCard(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$contact · $threshold",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 设置页的权限进度入口。 */
@Composable
private fun PermissionsProgressCard(ui: GuardianHomeUi, onOpenPermissions: () -> Unit) {
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "权限与系统设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "检测 ${ui.autoDone}/${ui.autoRequired.size} · 手动 ${ui.manualDone}/${ui.manualTotal}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = {
                val total = ui.autoRequired.size + ui.manualTotal
                if (total == 0) 1f else (ui.autoDone + ui.manualDone).toFloat() / total
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("查看权限清单")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    // 大字号下允许右列折行（最多两行，右对齐），避免挤压标签或溢出
    // 合并语义：标签与值读成一条
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.4f))
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- 公共卡片 ----------

@Composable
private fun ContactCard(
    ui: GuardianHomeUi, cb: GuardianHomeCallbacks,
    onAddContact: () -> Unit
) {
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("紧急联系人", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ContactListEditor(ui, cb, onAdd = onAddContact)
    }
}

/**
 * 联系人列表编辑：序号 + 姓名/号码 + 上移/下移/删除，底部"添加联系人"。
 * 首页向导①与设置页联系人卡共用；增删改由外层回调写配置。
 */
@Composable
private fun ContactListEditor(
    ui: GuardianHomeUi, cb: GuardianHomeCallbacks, onAdd: () -> Unit
) {
    val contacts = ui.config.validContacts
    if (contacts.isEmpty()) {
        Text(
            "还没有设置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    contacts.forEachIndexed { i, c ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        ) {
            Text(
                "${i + 1}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    c.name.ifEmpty { "未命名" },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    c.number,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (i > 0) {
                IconButton(onClick = { cb.onMoveContact(i, i - 1) }) {
                    Icon(Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "上移第 ${i + 1} 位")
                }
            }
            if (i < contacts.size - 1) {
                IconButton(onClick = { cb.onMoveContact(i, i + 1) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "下移第 ${i + 1} 位")
                }
            }
            if (contacts.size > 1) {
                IconButton(onClick = { cb.onRemoveContact(i) }) {
                    Icon(Icons.Rounded.Delete,
                        contentDescription = "删除第 ${i + 1} 位",
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (contacts.size < GuardConfig.MAX_CONTACTS) {
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加联系人")
        }
    } else {
        Text(
            "最多 3 位",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
    Text(
        "出事时先给所有人发短信，再按顺序依次打电话；顺序就是拨打顺序。" +
            "增删、改号码或调整顺序后需要重新做实机测试。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/** 「有人接通后」策略卡：两个单选 + 共同规则说明。 */
@Composable
private fun AnswerPolicyCard(ui: GuardianHomeUi, cb: GuardianHomeCallbacks) {
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("有人接通后", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        @Composable
        fun PolicyRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        PolicyRow(
            selected = ui.config.answerPolicy == AnswerPolicy.STOP_ON_ANSWER,
            title = "有人接通就停止拨打其他人（推荐）",
            desc = "谁先接听就由谁处理，其他人只收到短信。",
            onClick = { cb.onAnswerPolicy(AnswerPolicy.STOP_ON_ANSWER) }
        )
        PolicyRow(
            selected = ui.config.answerPolicy == AnswerPolicy.CALL_ALL,
            title = "继续拨打所有联系人",
            desc = "每个人都会接到电话，适合希望所有人都知情的家庭。",
            onClick = { cb.onAnswerPolicy(AnswerPolicy.CALL_ALL) }
        )
        Text(
            "一轮都没人接，3 分钟后从第 1 位再打一轮，最多 2 轮。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    ui: GuardianHomeUi, cb: GuardianHomeCallbacks,
    onSmsTime: (() -> Unit)?, onQuietTime: ((Boolean) -> Unit)?,
    onSendSmsTry: () -> Unit
) {
    val c = ui.config
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("守护设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        // 小时数自定义：点行弹出滑杆（1–24 小时），不再罗列预设档位
        var showThresholdPick by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
                .clickable { showThresholdPick = true }
                .padding(vertical = 6.dp)
        ) {
            Text("无活动阈值", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${c.thresholdMinutes / 60} 小时",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (showThresholdPick) {
            ThresholdPickDialog(
                current = c.thresholdMinutes / 60,
                onPick = { h -> showThresholdPick = false; cb.onThresholdHours(h) },
                onDismiss = { showThresholdPick = false }
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("确认时长", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow {
            listOf(1, 2, 5).forEach { m ->
                FilterChip(
                    selected = c.confirmWindowMinutes == m,
                    onClick = { cb.onConfirmMinutes(m) },
                    label = { Text("${m}分钟") },
                    modifier = Modifier.padding(end = 6.dp, top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 整行 toggleable：标签与开关语义关联给 TalkBack
            modifier = Modifier.toggleable(
                value = c.quietEnabled, role = Role.Switch,
                onValueChange = cb.onQuietEnabled
            )
        ) {
            Text("夜间免打扰",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            if (c.quietEnabled && onQuietTime != null) {
                TextButton(onClick = { onQuietTime(true) }) {
                    Text(fmtMinute(c.quietStartMinuteOfDay))
                }
                Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onQuietTime(false) }) {
                    Text(fmtMinute(c.quietEndMinuteOfDay))
                }
            }
            Switch(checked = c.quietEnabled, onCheckedChange = null)
        }
        if (c.quietEnabled) {
            Text(
                "这段时间不响铃不拨号，到点的报警顺延到时段结束",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 整行 toggleable：标签与开关语义关联给 TalkBack
            modifier = Modifier.toggleable(
                value = c.dailySmsEnabled, role = Role.Switch,
                onValueChange = cb.onDailySmsEnabled
            )
        ) {
            Text("每日平安短信",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            if (c.dailySmsEnabled && onSmsTime != null) {
                TextButton(onClick = onSmsTime) {
                    Text(
                        "%02d:%02d".format(
                            c.dailySmsMinuteOfDay / 60, c.dailySmsMinuteOfDay % 60
                        )
                    )
                }
            }
            Switch(checked = c.dailySmsEnabled, onCheckedChange = null)
        }
        if (c.dailySmsEnabled) {
            // 平安短信的另一层意义：家人每天收到它，就说明守护在正常工作
            Text(
                "家人每天收到它，就说明守护在正常工作；哪天没收到，说明手机或守护可能出了问题",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onSendSmsTry) { Text("发送一条试试") }
        }
        // 发送短信为必需权限：没授权时紧急短信与平安短信都发不出，给出明确提示
        val smsGranted = ui.perms.firstOrNull { it.key == "send_sms" }?.granted != false
        if (c.dailySmsEnabled && !smsGranted) {
            Text(
                "未授予“发送短信”权限，紧急短信与平安短信都无法发出（请在权限清单中开启）",
                style = MaterialTheme.typography.bodySmall,
                color = LocalJrxColors.current.warning
            )
        }

        // 开发快速阈值：仅调试包显示，供开发者真机回归
        if (ui.debuggable) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.toggleable(
                    value = c.devFastThresholds, role = Role.Switch,
                    onValueChange = cb.onDevFastThresholds
                )
            ) {
                Text("开发快速阈值（仅调试包）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = c.devFastThresholds, onCheckedChange = null)
            }
            if (c.devFastThresholds) {
                Text(
                    "阈值 30 秒、确认 15 秒；仅开发者回归用",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalJrxColors.current.warning
                )
            }
        }
    }
}

// ---------- 对话框 ----------

@Composable
private fun ManualContactDialog(
    onSubmit: (String, String) -> Unit, onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    val valid = GuardianFormat.isValidContactNumber(number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入联系人") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("姓名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = number,
                    onValueChange = { v ->
                        number = v.filter { it.isDigit() || it == '+' || it == '-' || it == ' ' }
                    },
                    label = { Text("号码") },
                    isError = number.isNotBlank() && !valid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name.trim(), number.trim()) },
                enabled = valid
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(
    title: String, minuteOfDay: Int,
    onPick: (Int) -> Unit, onDismiss: () -> Unit
) {
    val tp = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = tp) },
        confirmButton = {
            TextButton(onClick = { onPick(tp.hour * 60 + tp.minute) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 无活动阈值滑杆：1–24 小时。 */
@Composable
private fun ThresholdPickDialog(
    current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit
) {
    var h by remember { mutableStateOf(current.coerceIn(1, 24).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无活动阈值") },
        text = {
            Column {
                Text("${h.roundToInt()} 小时",
                    style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = h, onValueChange = { h = it },
                    valueRange = 1f..24f, steps = 22
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(h.roundToInt()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
