package com.jiarenxia.guardian.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jiarenxia.guardian.BuildConfig
import io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianHomeOptions
import io.github.xiaohaoz1.jiarenxia.ui.designsystem.JrxCard

/**
 * 守护侠首页定制：标题“守护侠”，设置页末尾加隐私说明入口与版本号。
 * MainActivity 与截图测试共用此工厂。
 */
fun standaloneHomeOptions(onPrivacy: () -> Unit) = GuardianHomeOptions(
    title = "守护侠",
    footer = { StandaloneFooter(onPrivacy = onPrivacy) }
)

/** 设置页页尾：隐私说明入口 + 版本号。 */
@Composable
fun StandaloneFooter(onPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPrivacy)
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    "隐私说明",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "版本 " + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        )
    }
}
