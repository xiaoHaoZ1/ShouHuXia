package io.github.xiaohaoz1.jiarenxia.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 日历方块：52×58dp，上带“N月”，下显日号。
 * highlight 时顶带用主色（今天/重点生日）。
 */
@Composable
fun DateTile(month: Int, day: Int, highlight: Boolean = false) {
    val bandBg = if (highlight) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondaryContainer
    val bandFg = if (highlight) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSecondaryContainer
    Column(
        modifier = Modifier.width(52.dp).height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(20.dp).background(bandBg),
            contentAlignment = Alignment.Center
        ) {
            Text(month.toString() + "月", style = MaterialTheme.typography.labelSmall, color = bandFg)
        }
        Box(modifier = Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
            Text(day.toString(), style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** 生日倒计时徽章：0→今天（主色实心），1→明天，其他→N天后（主色容器）。 */
@Composable
fun CountdownBadge(days: Int) {
    val (text, filled) = when (days) {
        0 -> "今天" to true
        1 -> "明天" to false
        else -> days.toString() + "天后" to false
    }
    val (bg, fg) = if (filled) MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
