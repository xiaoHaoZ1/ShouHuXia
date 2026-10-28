package io.github.xiaohaoz1.jiarenxia.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xiaohaoz1.jiarenxia.ui.theme.LocalJrxColors

/**
 * 页头：与背景同色的平底，内容绘制在状态栏之下（内部 statusBarsPadding）。
 * 默认内容内边距 左右20dp / 上16dp / 下16dp，可用 [contentPadding] 覆盖。
 */
@Composable
fun PageHeader(
    contentPadding: PaddingValues = PaddingValues(
        start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp
    ),
    content: @Composable () -> Unit
) {
    val jrx = LocalJrxColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(jrx.pageHeaderBackground)
    ) {
        Box(Modifier.statusBarsPadding().padding(contentPadding)) { content() }
    }
}
