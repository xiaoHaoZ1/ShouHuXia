package io.github.xiaohaoz1.jiarenxia.guardian.api

import androidx.compose.runtime.Composable
import io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianHomeOptions

/**
 * 守护家人侠首页内容（家人侠已于 C3 移除守护，本模块只由守护家人侠使用）。
 * [options] 注入标题、提示卡与页尾等定制，默认值即守护家人侠通用首页。
 */
@Composable
fun GuardianTab(
    onOpenAppSettings: () -> Unit,
    options: GuardianHomeOptions = GuardianHomeOptions()
) {
    io.github.xiaohaoz1.jiarenxia.guardian.ui.GuardianTabContent(
        onOpenAppSettings, options
    )
}
