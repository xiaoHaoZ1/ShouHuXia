package com.jiarenxia.guardian

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.jiarenxia.guardian.ui.PrivacyScreen
import com.jiarenxia.guardian.ui.standaloneHomeOptions
import io.github.xiaohaoz1.jiarenxia.guardian.api.GuardianTab
import io.github.xiaohaoz1.jiarenxia.guardian.sys.OemSettings
import io.github.xiaohaoz1.jiarenxia.ui.theme.JrxTheme

/**
 * 守护侠主界面：守护首页 + 设置二级页（无底部 tab），设置页可进入隐私说明。
 * 固定使用家人侠品牌的暖白底，避免长辈手机跟随系统深色模式后出现近黑页面；
 * 字号仍跟随系统缩放。
 * 无家庭数据：紧急联系人只有通讯录与手动输入两条路。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 守护侠固定暖色浅色主题，系统栏透明并使用深色图标。
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ),
                    navigationBarStyle = SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                )
            }
            JrxTheme(dark = false) {
                var showPrivacy by rememberSaveable { mutableStateOf(false) }
                val stateHolder = rememberSaveableStateHolder()
                if (showPrivacy) {
                    PrivacyScreen(onBack = { showPrivacy = false })
                    BackHandler { showPrivacy = false }
                } else {
                    stateHolder.SaveableStateProvider("guardian") {
                        GuardianTab(
                            onOpenAppSettings = {
                                OemSettings.openAppDetails(this@MainActivity)
                            },
                            options = standaloneHomeOptions(
                                onPrivacy = { showPrivacy = true }
                            )
                        )
                    }
                }
            }
        }
    }
}
