package io.github.xiaohaoz1.jiarenxia.guardian.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xiaohaoz1.jiarenxia.guardian.core.Event
import io.github.xiaohaoz1.jiarenxia.guardian.core.GuardConfig
import io.github.xiaohaoz1.jiarenxia.guardian.sys.Permissions
import io.github.xiaohaoz1.jiarenxia.guardian.sys.ServiceLocator

/**
 * 守护侠首页内容：HOME / SETTINGS / PERMISSIONS / LOG 四屏手动导航，返回键回 HOME。
 * 所有引擎操作都通过 Event / GuardStore。
 * [options] 注入标题、提示卡与页尾等定制，默认值即守护侠通用首页。
 */
@Composable
fun GuardianTabContent(
    onOpenAppSettings: () -> Unit = {},
    options: GuardianHomeOptions = GuardianHomeOptions()
) {
    val context = LocalContext.current
    var screenName by rememberSaveable { mutableStateOf(GuardianScreen.HOME.name) }
    val screen = GuardianScreen.valueOf(screenName)

    val config by ServiceLocator.store.configFlow.collectAsStateWithLifecycle()
    val state by ServiceLocator.store.stateFlow.collectAsStateWithLifecycle()
    val manualConfirmed by ServiceLocator.store.manualFlow.collectAsStateWithLifecycle()

    var perms by remember { mutableStateOf<List<Permissions.PermissionItem>>(emptyList()) }
    var anomalies by remember { mutableStateOf<List<String>>(emptyList()) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var logTick by remember { mutableIntStateOf(0) }
    var confirmEnable by remember { mutableStateOf(false) }

    // 进入页面 / 回前台时刷新权限快照；"未使用应用限制"是异步查询，结果回填
    fun refreshPerms() {
        val snap = Permissions.snapshot(context)
        perms = snap
        if (snap.any { it.key == "unused_restrictions" }) {
            Permissions.unusedRestrictionsStatus(context) { st ->
                perms = perms.map {
                    if (it.key == "unused_restrictions") it.copy(state = st) else it
                }
                anomalies = Permissions.anomalyReasons(context)
            }
        }
        anomalies = Permissions.anomalyReasons(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        refreshPerms()
    }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                refreshPerms()
                // 页面可见说明手机正在使用，顺手对账使用会话
                ServiceLocator.engine.handle(Event.SyncSession)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(screen, logTick) {
        if (screen == GuardianScreen.LOG) {
            logLines = ServiceLocator.eventLog.tail(200)
        }
    }

    // 开启流程：先申请运行时权限，缺拨号权限则提示并中止
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refreshPerms()
        if (result[android.Manifest.permission.CALL_PHONE] == false) {
            Toast.makeText(context, "需要“拨打电话”权限才能在紧急时自动拨号", Toast.LENGTH_LONG).show()
        } else {
            confirmEnable = true
        }
    }
    // 发送短信是必需权限（紧急短信依赖它）：开启平安短信或测试发送时再单独补一次申请入口
    val smsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPerms()
        if (!granted) {
            Toast.makeText(
                context,
                "未授予“发送短信”权限，紧急短信与平安短信都无法发出（请在权限清单中开启）",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    // 权限清单里的运行时权限点"去开启"：直接弹系统授权框而不是跳详情页；
    // 被拒（含永久拒绝）后回退应用详情页让用户手动开
    val singlePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPerms()
        if (!granted) {
            Toast.makeText(
                context, "未授予权限，可在应用信息页手动开启",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    fun smsGranted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    // 通讯录选择器：选中后追加到联系人末尾（重复/满员时拒绝并提示）
    fun tryAddContact(name: String, number: String): Boolean {
        val newKey = io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules
            .contactsKey(listOf(
                io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact(name, number)))
        val cur = ServiceLocator.store.configFlow.value
        if (cur.validContacts.size >= GuardConfig.MAX_CONTACTS) {
            Toast.makeText(context, "最多 3 位", Toast.LENGTH_LONG).show()
            return false
        }
        val dup = cur.contacts.any {
            io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules.contactsKey(
                listOf(it)) == newKey
        }
        if (dup) {
            Toast.makeText(context, "该号码已在列表中", Toast.LENGTH_LONG).show()
            return false
        }
        ServiceLocator.store.updateConfig {
            it.copy(contacts = it.contacts +
                io.github.xiaohaoz1.jiarenxia.guardian.core.EmergencyContact(
                    name = name, number = number))
        }
        ServiceLocator.engine.handle(Event.ConfigChanged)
        return true
    }
    val pickLauncher = rememberLauncherForActivityResult(PickPhoneContact()) { picked ->
        picked?.let { p ->
            tryAddContact(p.name ?: "", p.number)
        } ?: run {
            Toast.makeText(context, "未找到通讯录应用", Toast.LENGTH_SHORT).show()
        }
    }

    // 权限清单可来自首页向导或设置页：返回时回到来源页
    var permsFrom by rememberSaveable { mutableStateOf(GuardianScreen.HOME.name) }
    BackHandler(enabled = screen != GuardianScreen.HOME) {
        screenName = when (screen) {
            GuardianScreen.PERMISSIONS -> permsFrom
            else -> GuardianScreen.HOME.name
        }
    }

    val cb = remember {
        GuardianHomeCallbacks()
    }.apply {
        onEnableRequest = {
            val missing = Permissions.runtimePerms.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) confirmEnable = true
            else permLauncher.launch(missing.toTypedArray())
        }
        onEnableConfirmed = {
            confirmEnable = false
            ServiceLocator.engine.handle(Event.EnabledChanged(true))
        }
        onDismissEnable = { confirmEnable = false }
        onDisable = { ServiceLocator.engine.handle(Event.EnabledChanged(false)) }
        onImOk = { ServiceLocator.engine.handle(Event.ImOk) }
        onAlertNow = { ServiceLocator.engine.handle(Event.AlertNow) }
        onAddFromContacts = {
            runCatching { pickLauncher.launch(Unit) }
                .onFailure {
                    Toast.makeText(context, "未找到通讯录应用", Toast.LENGTH_SHORT).show()
                }
        }
        onAddManual = { name, number -> tryAddContact(name, number) }
        onRemoveContact = { index ->
            ServiceLocator.store.updateConfig {
                it.copy(contacts = it.contacts.filterIndexed { i, _ -> i != index })
            }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onMoveContact = { from, to ->
            ServiceLocator.store.updateConfig {
                val list = it.contacts.toMutableList()
                if (from in list.indices && to in list.indices) {
                    list.add(to, list.removeAt(from))
                }
                it.copy(contacts = list)
            }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onAnswerPolicy = { p ->
            // 策略不含在 contactsKey 中：改策略不影响演练记录
            ServiceLocator.store.updateConfig { it.copy(answerPolicy = p) }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onThresholdHours = { h ->
            ServiceLocator.store.updateConfig {
                it.copy(thresholdMinutes = (h * 60).coerceIn(
                    GuardConfig.MIN_THRESHOLD_MINUTES,
                    GuardConfig.MAX_THRESHOLD_MINUTES))
            }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onConfirmMinutes = { m ->
            ServiceLocator.store.updateConfig { it.copy(confirmWindowMinutes = m) }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onDailySmsEnabled = { v ->
            ServiceLocator.store.updateConfig { it.copy(dailySmsEnabled = v) }
            ServiceLocator.engine.handle(Event.ConfigChanged)
            // 开关照常记录；缺权限时补一次申请，拒绝后由权限清单提示兜底
            if (v && !smsGranted()) {
                smsPermLauncher.launch(android.Manifest.permission.SEND_SMS)
            }
        }
        onDailySmsTime = { min ->
            ServiceLocator.store.updateConfig { it.copy(dailySmsMinuteOfDay = min) }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onQuietEnabled = { v ->
            ServiceLocator.store.updateConfig { it.copy(quietEnabled = v) }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onQuietStart = { min ->
            ServiceLocator.store.updateConfig { it.copy(quietStartMinuteOfDay = min) }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onQuietEnd = { min ->
            ServiceLocator.store.updateConfig { it.copy(quietEndMinuteOfDay = min) }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onDevFastThresholds = { v ->
            ServiceLocator.store.updateConfig { it.copy(devFastThresholds = v) }
            ServiceLocator.engine.handle(Event.ConfigChanged)
        }
        onOpenSettings = { screenName = GuardianScreen.SETTINGS.name }
        onOpenPermissions = {
            permsFrom = screen.name
            screenName = GuardianScreen.PERMISSIONS.name
        }
        onOpenLog = { screenName = GuardianScreen.LOG.name }
        onSendSms = {
            if (smsGranted()) ServiceLocator.engine.handle(Event.DailySmsAlarm)
            else smsPermLauncher.launch(android.Manifest.permission.SEND_SMS)
        }
        // 演练
        onOpenDrill = { screenName = GuardianScreen.DRILL.name }
        onStartDrill = { w -> ServiceLocator.engine.handle(Event.DrillStart(w)) }
        onStopDrill = { ServiceLocator.engine.handle(Event.DrillStop) }
        // 逐位问答：第 index 位 短信收到? / 电话接到?
        onDrillContactAnswer = { index, sms, call ->
            ServiceLocator.engine.handle(
                Event.DrillContactAnswer(index, sms, call)
            )
        }
        onConfirmManual = { /* 见下方 onManualToggle 封装 */ }
        onPermItem = { item ->
            val rp = item.runtimePermission
            val grantedNow = rp == null ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, rp
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!grantedNow) {
                singlePermLauncher.launch(rp)
            } else {
                item.settingsIntent?.let { runCatching { context.startActivity(it) } }
                    ?: onOpenAppSettings()
            }
        }
    }

    // 装配共享 UI 状态：环境信息、手动确认集（指纹一致才算确认）、异常列表
    val fp = ServiceLocator.ports.buildFingerprint()
    val debuggable =
        context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    val manualConfirmedSet =
        if (ServiceLocator.store.manualFingerprintStale(fp)) emptySet()
        else manualConfirmed
    val ui = GuardianHomeUi(
        config = config,
        state = state,
        perms = perms,
        manualItems = remember { Permissions.manualItems(context) },
        manualConfirmed = manualConfirmedSet,
        debuggable = debuggable,
        versionCode = ServiceLocator.ports.appVersionCode(),
        fingerprint = fp,
        anomalies = anomalies,
        confirmingEnable = confirmEnable,
        now = System.currentTimeMillis()
    )

    fun onManualToggle(key: String, on: Boolean) {
        if (on) ServiceLocator.store.confirmManual(key, fp)
        else ServiceLocator.store.revokeManual(key)
        refreshPerms()
    }

    when (screen) {
        GuardianScreen.HOME -> GuardianHome(ui, cb, options)
        GuardianScreen.SETTINGS -> GuardianSettings(
            ui = ui,
            cb = cb,
            options = options,
            onBack = { screenName = GuardianScreen.HOME.name }
        )
        GuardianScreen.PERMISSIONS -> GuardianPermissions(
            ui = ui,
            onItemClick = cb.onPermItem,
            onManualToggle = ::onManualToggle,
            onBack = { screenName = permsFrom }
        )
        GuardianScreen.DRILL -> GuardianDrill(
            ui = ui,
            cb = cb,
            onBack = { screenName = GuardianScreen.HOME.name }
        )
        GuardianScreen.LOG -> GuardianLog(
            lines = logLines,
            onRefresh = { logTick++ },
            onBack = { screenName = GuardianScreen.HOME.name }
        )
    }
}

enum class GuardianScreen { HOME, SETTINGS, PERMISSIONS, DRILL, LOG }
