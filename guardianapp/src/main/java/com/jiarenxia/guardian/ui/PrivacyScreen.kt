package com.jiarenxia.guardian.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xiaohaoz1.jiarenxia.ui.designsystem.JrxCard

/**
 * 隐私说明二级页：逐条说明数据边界（不联网、只存本机、不进云备份/换机迁移）。
 * 页面自己绘制主题背景：窗口底色固定浅色，深色模式不能依赖宿主或调用方垫 Surface。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("隐私说明") },
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
                JrxCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    PrivacyItem("守护侠不联网、不注册账号，所有配置只保存在这台手机上。")
                    PrivacyItem("开启守护后，手机长时间无人使用会先响铃，仍无人确认时会拨打你设置的紧急联系人；开启平安短信后，每天会按设定时间发出一条短信。电话与短信费用由运营商按你的套餐收取。")
                    PrivacyItem("选择紧急联系人时，通讯录只通过系统选择器读取你选中的那一个号码，守护侠不读取全部联系人。")
                    PrivacyItem("守护配置不进入系统云备份和设备间迁移；换新手机后需要重新设置一遍。")
                }
                Spacer(Modifier.height(96.dp))
            }
        }
    }
}

@Composable
private fun PrivacyItem(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    )
}
