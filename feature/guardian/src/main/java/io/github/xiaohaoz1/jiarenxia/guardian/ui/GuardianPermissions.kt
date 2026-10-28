package io.github.xiaohaoz1.jiarenxia.guardian.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xiaohaoz1.jiarenxia.guardian.sys.Permissions
import io.github.xiaohaoz1.jiarenxia.ui.designsystem.JrxCard
import io.github.xiaohaoz1.jiarenxia.ui.designsystem.TitleChip
import io.github.xiaohaoz1.jiarenxia.ui.theme.LocalJrxColors

/**
 * 权限清单页（stateless；检测/跳转/勾选全部由外层执行）。
 * 上组：可自动检测项（三态：已开启/未开启/检测不到）。
 * 下组：需手动确认项——静态指引项 + 检测不到而降级的自动项，逐项勾选"我已设置"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianPermissions(
    ui: GuardianHomeUi,
    onItemClick: (Permissions.PermissionItem) -> Unit,
    onManualToggle: (String, Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("权限清单") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
            }
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "改完系统设置后回到本页，状态会自动刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // 上组：可自动检测（UNKNOWN 项移到下面的手动组里确认）
            JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("自动检测", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                ui.perms.forEach { item ->
                    if (item.state != Permissions.CheckState.UNKNOWN) {
                        PermRow(item, onItemClick)
                    }
                }
            }

            // 下组：需手动确认（静态指引项 + 检测不到而降级的自动项）
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val unknownItems = ui.perms.filter {
                !it.optional && it.state == Permissions.CheckState.UNKNOWN
            }
            JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("需要手动确认", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "这些项代码检测不到，请逐项去系统设置确认后勾选。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                unknownItems.forEach { item ->
                    ManualRow(
                        key = "perm_" + item.key,
                        title = item.title,
                        desc = item.desc + "（代码检测不到，请手动确认）",
                        confirmed = ui.manualConfirmed.contains("perm_" + item.key),
                        onToggle = onManualToggle,
                        onOpen = { onItemClick(item) }
                    )
                }
                ui.manualItems.forEach { m ->
                    ManualRow(
                        key = m.key,
                        title = m.title,
                        desc = m.desc,
                        confirmed = ui.manualConfirmed.contains(m.key),
                        onToggle = onManualToggle,
                        onOpen = m.open?.let { op -> { op(ctx) } }
                    )
                }
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

/** 自动检测项行：三态图标 + 标题 + 说明 + 状态文字 + "去开启"。 */
@Composable
private fun PermRow(
    item: Permissions.PermissionItem,
    onItemClick: (Permissions.PermissionItem) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        val ok = item.state == Permissions.CheckState.ON
        val tint = if (ok) LocalJrxColors.current.success
            else MaterialTheme.colorScheme.error
        Surface(
            shape = CircleShape,
            color = tint.copy(alpha = 0.15f),
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(10.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                if (item.optional) {
                    Spacer(Modifier.width(6.dp))
                    TitleChip("可选")
                }
            }
            Text(
                item.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (ok) "✓ 已开启" else "未开启",
                style = MaterialTheme.typography.bodySmall,
                color = tint
            )
        }
        if (!ok) {
            TextButton(onClick = { onItemClick(item) }) { Text("去开启") }
        }
    }
}

/** 手动确认项行：可勾选"我已设置"，有跳转动作时提供"去设置"。 */
@Composable
private fun ManualRow(
    key: String,
    title: String,
    desc: String,
    confirmed: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onOpen: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        val tint = if (confirmed) LocalJrxColors.current.success
            else MaterialTheme.colorScheme.onSurfaceVariant
        Surface(
            shape = CircleShape,
            color = tint.copy(alpha = 0.15f),
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                if (confirmed) Icons.Rounded.CheckCircle
                    else Icons.AutoMirrored.Rounded.HelpOutline,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(10.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpen != null) {
                    TextButton(onClick = onOpen) { Text("去设置") }
                }
            }
        }
        Checkbox(
            checked = confirmed,
            onCheckedChange = { onToggle(key, it) }
        )
    }
}
