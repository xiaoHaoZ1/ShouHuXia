package io.github.xiaohaoz1.jiarenxia.guardian.ui

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillPhase
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardState
import io.github.xiaohaoz1.jiarenxia.guardian.core.Phase
import io.github.xiaohaoz1.jiarenxia.guardian.sys.ServiceLocator
import io.github.xiaohaoz1.jiarenxia.ui.theme.JrxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 响铃确认全屏页：errorContainer 整屏高对比，锁屏之上显示。
 * 真实确认（CONFIRMING/ALERTING）永远优先；否则演练响铃（drill RINGING）
 * 显示演练专用样式并上报"已显示 + 当时是否锁屏"参与演练判定。
 * 两者都不在时自动关闭。
 */
class PreAlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            var drillReported = false
            ServiceLocator.store.stateFlow.collect { s ->
                val realAlerting =
                    s.phase == Phase.CONFIRMING || s.phase == Phase.ALERTING
                val drillRinging = s.drill.phase == DrillPhase.RINGING
                // 真实确认优先；演练响铃时上报"全屏页已显示"（只报一次）
                if (!realAlerting && drillRinging && !drillReported) {
                    drillReported = true
                    val kg = getSystemService(KeyguardManager::class.java)
                    ServiceLocator.engine.handle(
                        Event.DrillScreenShown(kg?.isKeyguardLocked == true)
                    )
                }
                if (!realAlerting && !drillRinging) finish()
            }
        }

        setContent {
            // 强制浅色高对比（紧急确认场景，不看系统深色）
            JrxTheme(dark = false) {
                val state by ServiceLocator.store.stateFlow.collectAsStateWithLifecycle()
                val config by ServiceLocator.store.configFlow.collectAsStateWithLifecycle()
                val realAlerting =
                    state.phase == Phase.CONFIRMING || state.phase == Phase.ALERTING
                if (state.drill.phase == DrillPhase.RINGING && !realAlerting) {
                    DrillPreAlarmScreen(
                        state = state,
                        contactCount = config.validContacts.size,
                        onStop = { ServiceLocator.engine.handle(Event.DrillStop) },
                    )
                } else {
                    PreAlarmScreen(
                        state = state,
                        config = config,
                        onImOk = { ServiceLocator.engine.handle(Event.ImOk) },
                        onAlertNow = { ServiceLocator.engine.handle(Event.AlertNow) },
                    )
                }
            }
        }
    }
}

@Composable
fun PreAlarmScreen(
    state: GuardState,
    config: GuardConfig,
    onImOk: () -> Unit,
    onAlertNow: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            now = System.currentTimeMillis()
        }
    }
    val remainSec = ((state.confirmEndAt - now) / 1000).coerceAtLeast(0)
    // 多联系人：先短信群发再依次拨打，提示总人数与第 1 位
    val contacts = config.validContacts
    val firstLabel = contacts.firstOrNull()?.label() ?: "紧急联系人"
    val dialText = if (contacts.size <= 1) {
        "$remainSec 秒后将发短信并拨打 $firstLabel"
    } else {
        "$remainSec 秒后将通知 ${contacts.size} 位紧急联系人，先打给 $firstLabel"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "很久没检测到使用手机",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = dialText,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onImOk,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        ) {
            Text(
                text = "我没事",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onAlertNow,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
        ) {
            Text(text = "立即呼叫", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * 演练专用全屏页：明确标明"这是守护测试"，提示不要操作等待自动拨号，
 * 只提供一个次要的「停止测试」按钮（点了 = 中止演练）。
 */
@Composable
fun DrillPreAlarmScreen(
    state: GuardState,
    contactCount: Int = 1,
    onStop: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            now = System.currentTimeMillis()
        }
    }
    val remainSec = ((state.drill.confirmEndAt - now) / 1000).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "这是守护测试",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "请不要操作，$remainSec 秒后将给 $contactCount 位联系人发测试短信并依次拨打",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        TextButton(onClick = onStop) {
            Text(
                text = "停止测试",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
