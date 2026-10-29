package io.github.xiaohaoz1.jiarenxia.guardian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillOutcome
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillPhase
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillResult
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.TimeRules
import io.github.xiaohaoz1.jiarenxia.guardian.core.maskNumber
import io.github.xiaohaoz1.jiarenxia.guardian.sys.Permissions
import io.github.xiaohaoz1.jiarenxia.ui.designsystem.JrxCard
import io.github.xiaohaoz1.jiarenxia.ui.theme.LocalJrxColors
import kotlinx.coroutines.delay

/**
 * 实机演练页：等待时长按 DrillRules.WAIT_OPTIONS_MIN 渲染
 * （1/10/30/60/120 分钟；<30 分钟只作快速检查，不计入开启条件），
 * 进行中显示当前阶段与停止按钮，结束后显示逐项判定结果。
 * stateless：全部状态来自 ui.state.drill / ui.state.lastDrillResult。
 */
@Composable
fun GuardianDrill(
    ui: GuardianHomeUi,
    cb: GuardianHomeCallbacks,
    onBack: () -> Unit
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(500); now = System.currentTimeMillis() }
    }

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
                Text("实机测试", style = MaterialTheme.typography.titleLarge)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                val d = ui.state.drill
                when {
                    d.phase == DrillPhase.WAIT_LOCK -> DrillWaitingCard(
                        title = "等待锁屏",
                        body = "请按电源键锁屏，然后把手机放下别动。\n锁屏后开始计时。",
                        onStop = cb.onStopDrill
                    )
                    d.phase == DrillPhase.WAITING -> DrillWaitingCard(
                        title = "等待响铃",
                        body = "已锁屏，预计 " +
                            TimeRules.formatClock(d.plannedAt) +
                            " 响铃。\n期间请不要操作手机、不要点亮屏幕。" +
                            "\n中途解锁 = 测试中止。",
                        onStop = cb.onStopDrill
                    )
                    d.phase == DrillPhase.RINGING -> DrillWaitingCard(
                        title = "正在响铃",
                        body = "测试响铃中，到点会自动拨打紧急联系人。" +
                            "\n请留意锁屏上是否弹出测试页。",
                        onStop = cb.onStopDrill
                    )
                    d.phase == DrillPhase.CALLING -> DrillCallingCard(ui, cb, now)
                    d.phase == DrillPhase.ASK_ANSWER -> DrillAnswerCard(ui, cb)
                    else -> DrillStartCard(ui, cb)
                }

                ui.state.lastDrillResult?.let { DrillResultCard(it, ui) }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

/** 开始卡：等待时长选择 + 注意事项 + 开始按钮。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DrillStartCard(ui: GuardianHomeUi, cb: GuardianHomeCallbacks) {
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("做一次真机演练", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "锁屏放着别动，到点会响铃、弹出测试页，然后给每位联系人发一条测试短信" +
                "并依次拨打（每人一次，对方需要接听）。结束后逐位确认。不影响守护开关。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("等待时长", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        var wait by remember {
            androidx.compose.runtime.mutableIntStateOf(DrillRules.MIN_QUALIFY_MINUTES)
        }
        androidx.compose.foundation.layout.FlowRow {
            DrillRules.WAIT_OPTIONS_MIN.forEach { m ->
                FilterChip(
                    selected = wait == m,
                    onClick = { wait = m },
                    label = {
                        Text(if (m >= 60) "${m / 60} 小时" else "$m 分钟")
                    },
                    modifier = Modifier.padding(end = 6.dp, top = 4.dp)
                )
            }
        }
        Text(
            if (wait < DrillRules.MIN_QUALIFY_MINUTES)
                "快速检查，不计入开启条件；开启守护前还需要一次 ≥30 分钟测试"
            else "完整测试，计入开启条件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(12.dp))
        val missingPermissions = ui.drillMissingRuntimePermissions
        val buttonLabel = when {
            !ui.hasContact -> "先设置紧急联系人"
            missingPermissions.isNotEmpty() -> "先开启必要权限"
            else -> "开始测试"
        }
        Button(
            onClick = {
                when {
                    !ui.hasContact -> cb.onOpenSettings()
                    missingPermissions.isNotEmpty() -> cb.onOpenPermissions()
                    else -> cb.onStartDrill(wait)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(buttonLabel, style = MaterialTheme.typography.titleMedium) }
        if (!ui.drillMinReady) {
            Text(
                if (!ui.hasContact) {
                    "测试前需要先设置至少 1 位紧急联系人"
                } else {
                    "测试前需要开启：" +
                        missingPermissions.joinToString("、") { it.title }
                },
                style = MaterialTheme.typography.bodySmall,
                color = LocalJrxColors.current.warning,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** 进行中卡：阶段说明 + 停止测试。 */
@Composable
private fun DrillWaitingCard(title: String, body: String, onStop: () -> Unit) {
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onStop,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("停止测试") }
    }
}

/** 拨号中卡：当前在拨第几位 + 每位一行进度（复用报警序列进度组件）。 */
@Composable
private fun DrillCallingCard(ui: GuardianHomeUi, cb: GuardianHomeCallbacks, now: Long) {
    val d = ui.state.drill
    val n = ui.config.validContacts.size
    val current = ui.config.validContacts.getOrNull(d.calls.index)?.label()
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("正在依次拨打", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "正在给 $n 位联系人发测试短信并依次拨打" +
                (if (current != null) "，现在拨打第 ${d.calls.index + 1} 位：$current"
                else "") +
                "\n请提醒对方接听测试电话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        AlertSeqProgress(ui, now, seq = d.calls, showSmsFlag = false)
        Text(
            if (d.smsSubmittedMask != 0) "测试短信已提交发送" else "测试短信发送中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = cb.onStopDrill,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("停止测试") }
    }
}

/**
 * 逐位问答卡：当前联系人两个问题各一组单选（收到短信了吗 / 接到电话了吗），
 * 两个都选了才能提交；上方显示系统检测到的结果作参考。
 */
@Composable
private fun DrillAnswerCard(ui: GuardianHomeUi, cb: GuardianHomeCallbacks) {
    val d = ui.state.drill
    val n = ui.config.validContacts.size
    val contact = ui.config.validContacts.getOrNull(d.askIndex)
    val label = contact?.label() ?: "紧急联系人"
    var smsGot by androidx.compose.runtime.remember(
        d.askIndex, d.contactsKey) {
        androidx.compose.runtime.mutableStateOf<Boolean?>(null)
    }
    var callGot by androidx.compose.runtime.remember(
        d.askIndex, d.contactsKey) {
        androidx.compose.runtime.mutableStateOf<Boolean?>(null)
    }
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "第 ${d.askIndex + 1} / $n 位：$label",
            style = MaterialTheme.typography.titleMedium
        )
        // 系统已检测到的结果（短信回执 / 通话记录），只作参考
        val smsOk = d.smsSubmittedMask and (1 shl d.askIndex) != 0 &&
            d.smsOkMask and (1 shl d.askIndex) != 0 &&
            d.smsFailMask and (1 shl d.askIndex) == 0
        val callOk = d.calls.answeredMask and (1 shl d.askIndex) != 0
        Text(
            "系统检测：测试短信" + (if (smsOk) "已发出 ✓" else "未确认发出") +
                " · 电话" + (if (callOk) "已接通 ✓" else "未接通"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "以对方实际情况为准：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        @Composable
        fun YesNoRow(
            title: String, value: Boolean?, onPick: (Boolean) -> Unit,
            yesTexts: List<Pair<Boolean, String>> =
                listOf(true to "收到了", false to "没收到"),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth()) {
                yesTexts.forEach { (v, text) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = value == v,
                            onClick = { onPick(v) }
                        )
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        YesNoRow("$label 收到测试短信了吗？", smsGot,
            onPick = { smsGot = it })
        YesNoRow("$label 接到测试电话了吗？", callGot,
            onPick = { callGot = it },
            yesTexts = listOf(true to "接到了", false to "没接到"))
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                cb.onDrillContactAnswer(
                    d.askIndex, smsGot == true, callGot == true)
            },
            enabled = smsGot != null && callGot != null,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                if (d.askIndex + 1 >= n) "完成" else "确认，下一位",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

/** 结果卡：上次结局 + 逐项判定 + 开启凭据状态（凭据看 qualifiedPass，与本次结果分开）。 */
@Composable
private fun DrillResultCard(r: DrillResult, ui: GuardianHomeUi) {
    JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint, label) = when (r.outcome) {
                DrillOutcome.PASSED -> Triple(
                    Icons.Rounded.CheckCircle,
                    LocalJrxColors.current.success,
                    "上次测试通过")
                DrillOutcome.FAILED -> Triple(
                    Icons.Rounded.Cancel,
                    MaterialTheme.colorScheme.error,
                    "上次测试未通过")
                DrillOutcome.ABORTED -> Triple(
                    Icons.Rounded.Warning,
                    LocalJrxColors.current.warning,
                    "上次测试中止")
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium,
                color = tint, fontWeight = FontWeight.Bold)
        }
        Text(
            TimeRules.formatDateTime(r.finishedAt) +
                " · 锁屏 ${r.waitMinutes} 分钟后响铃" +
                if (r.delayMillis > 0) "（延迟 ${r.delayMillis / 1000} 秒）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (r.reason.isNotBlank()) {
            Text(r.reason, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        CheckRow("到点响铃（延迟不超过 60 秒）", r.onTime)
        CheckRow("锁屏上弹出测试页", r.fullscreenOk)
        // 逐位小节：中止/未到问答时 contactResults 为空则不显示
        if (r.schema < io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules.SCHEMA) {
            Text(
                "旧版本测试记录（单联系人），需要重新测试",
                style = MaterialTheme.typography.bodySmall,
                color = LocalJrxColors.current.warning,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            r.contactResults.forEach { cr ->
                Spacer(Modifier.height(8.dp))
                Text(
                    cr.name.ifEmpty { "未命名" } + " " + maskNumber(cr.number),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                CheckRow("测试短信已发出", cr.smsSent)
                CheckRow("电话已接通", cr.callAnswered)
                CheckRow("确认收到短信", cr.smsConfirmed)
                CheckRow("确认接到电话", cr.callConfirmed)
            }
        }
        // 开启凭据与上次结果分开：凭据看 qualifiedPass，不随快速检查/中止变化
        Text(
            ui.drillCredentialStatus,
            style = MaterialTheme.typography.bodySmall,
            color = if (ui.state.qualifiedPass != null && ui.drillInvalidReasons.isEmpty())
                LocalJrxColors.current.success else LocalJrxColors.current.warning,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
            contentDescription = null,
            tint = if (ok) LocalJrxColors.current.success
                else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
