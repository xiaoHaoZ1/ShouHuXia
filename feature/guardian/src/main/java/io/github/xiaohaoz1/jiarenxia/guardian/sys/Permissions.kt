package io.github.xiaohaoz1.jiarenxia.guardian.sys

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import io.github.xiaohaoz1.jiarenxia.guardian.R
import io.github.xiaohaoz1.jiarenxia.guardian.core.DrillRules

/**
 * 权限清单页数据源：逐项检测（三态） + 需手动确认项 + 对应跳转。
 *
 * 检测三态：ON 已开启 / OFF 未开启 / UNKNOWN 检测不到（降级为"需手动确认"，
 * 不能一直卡死用户）。所有"必需"检测项 OFF 或手动确认项未确认 → 不能开启守护。
 */
object Permissions {

    /** 检测结果三态。 */
    enum class CheckState { ON, OFF, UNKNOWN }

    data class PermissionItem(
        val key: String,
        val title: String,
        val desc: String,
        val state: CheckState,
        /** 可选权限（缺失只提示不算红灯，不卡开启）。 */
        val optional: Boolean = false,
        /** 点击"去开启"要启动的系统设置页 Intent。 */
        val settingsIntent: Intent? = null,
        /** 运行时权限：非空时点击应弹系统授权框，而不是跳设置页。 */
        val runtimePermission: String? = null,
    ) {
        val granted: Boolean get() = state == CheckState.ON
    }

    /**
     * 需手动确认的必需项：无法用代码检测，逐项跳系统页面 + 用户勾选"我已设置"。
     * open 为 null 表示纯文字指引（如最近任务加锁没有可跳的系统页面）。
     * 持久化与指纹失效逻辑在 GuardStore（isManualConfirmed/confirmManual）。
     */
    data class ManualItem(
        val key: String,
        val title: String,
        val desc: String,
        /** 跳转动作；null = 仅文字指引。 */
        val open: ((Context) -> Unit)? = null,
    )

    /**
     * 开启守护时需要一起申请的运行时权限。
     * 发送短信（紧急/平安短信群发）与读取通话记录（判断拨出的电话是否接通）
     * 都是必需项——缺了它们报警链路不完整。
     */
    val runtimePerms: List<String>
        get() = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }

    /** 演练可发起的最低条件：联系人 + 拨号 + 电话状态 + 通知。 */
    fun drillMinimumReady(context: Context): Boolean =
        ServiceLocator.store.configFlow.value.validContacts.isNotEmpty() &&
            runtimePerms.all {
                ContextCompat.checkSelfPermission(context, it) ==
                    PackageManager.PERMISSION_GRANTED
            }

    fun snapshot(context: Context): List<PermissionItem> {
        val items = mutableListOf<PermissionItem>()

        fun runtime(
            key: String, titleRes: Int, descRes: Int, perm: String,
            optional: Boolean = false
        ) {
            items += PermissionItem(
                key = key,
                title = context.getString(titleRes),
                desc = context.getString(descRes),
                state = if (
                    ContextCompat.checkSelfPermission(context, perm) ==
                    PackageManager.PERMISSION_GRANTED
                ) CheckState.ON else CheckState.OFF,
                optional = optional,
                settingsIntent = appDetails(context),
                runtimePermission = perm,
            )
        }

        runtime("call_phone", R.string.perm_call_phone, R.string.perm_call_phone_desc,
            Manifest.permission.CALL_PHONE)
        runtime("phone_state", R.string.perm_phone_state, R.string.perm_phone_state_desc,
            Manifest.permission.READ_PHONE_STATE)
        // 发送短信：报警时先群发紧急短信，每日平安短信也靠它——必需项
        runtime("send_sms", R.string.perm_send_sms, R.string.perm_send_sms_desc,
            Manifest.permission.SEND_SMS)
        // 读取通话记录：报警/测试拨号结束后判断自己拨出的那通是否接通——必需项
        runtime("call_log", R.string.perm_call_log, R.string.perm_call_log_desc,
            Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) {
            runtime("notifications", R.string.perm_notifications,
                R.string.perm_notifications_desc, Manifest.permission.POST_NOTIFICATIONS)
        }

        // 忽略电池优化
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        items += PermissionItem(
            key = "battery",
            title = context.getString(R.string.perm_battery),
            desc = context.getString(R.string.perm_battery_desc),
            state = if (ignoring) CheckState.ON else CheckState.OFF,
            settingsIntent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
        )

        // 使用情况访问（可选兜底）
        items += PermissionItem(
            key = "usage_stats",
            title = context.getString(R.string.perm_usage_stats),
            desc = context.getString(R.string.perm_usage_stats_desc),
            state = if (UsageStatsReconcile(context).hasAccess()) CheckState.ON
                else CheckState.OFF,
            optional = true,
            settingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )

        // 精确闹钟：API31/32 需要 SCHEDULE_EXACT_ALARM；33+ USE_EXACT_ALARM 自动授予
        val exactState = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> CheckState.ON
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> CheckState.ON
            else -> if (context.getSystemService(AlarmManager::class.java)
                ?.canScheduleExactAlarms() == true
            ) CheckState.ON else CheckState.OFF
        }
        items += PermissionItem(
            key = "exact_alarm",
            title = context.getString(R.string.perm_exact_alarm),
            desc = context.getString(R.string.perm_exact_alarm_desc),
            state = exactState,
            settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                )
            } else {
                appDetails(context)
            },
        )

        // 全屏通知：API34+ 需 canUseFullScreenIntent
        val fsiState = if (Build.VERSION.SDK_INT >= 34) {
            if (context.getSystemService(NotificationManager::class.java)
                ?.canUseFullScreenIntent() == true
            ) CheckState.ON else CheckState.OFF
        } else {
            CheckState.ON
        }
        items += PermissionItem(
            key = "fullscreen",
            title = context.getString(R.string.perm_fullscreen),
            desc = context.getString(R.string.perm_fullscreen_desc),
            state = fsiState,
            settingsIntent = if (Build.VERSION.SDK_INT >= 34) {
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:${context.packageName}"),
                )
            } else {
                appDetails(context)
            },
        )

        // 未使用应用限制豁免（应用休眠/自动收回权限）：长辈从不打开守护侠，必须豁免。
        // 检测是异步的——最近一次查询结果缓存在 unusedRestrictionsCache，
        // snapshot 优先用缓存，未查过先按 UNKNOWN 占位。
        items += PermissionItem(
            key = "unused_restrictions",
            title = context.getString(R.string.perm_unused_restrictions),
            desc = context.getString(R.string.perm_unused_restrictions_desc),
            state = unusedRestrictionsCache ?: CheckState.UNKNOWN,
            settingsIntent = runCatching {
                IntentCompat.createManageUnusedAppRestrictionsIntent(
                    context, context.packageName
                )
            }.getOrNull() ?: appDetails(context),
        )

        // 小米系额外一道闸：appops 10021「后台弹出界面」。
        // 反射读取失败是常态（不同 MIUI/澎湃版本行为不一）——读不到归 UNKNOWN
        // 降级为手动确认，而不是永远红灯。
        if (isMiuiDevice()) {
            items += PermissionItem(
                key = "miui_bg_popup",
                title = context.getString(R.string.perm_miui_bg_popup),
                desc = context.getString(R.string.perm_miui_bg_popup_desc),
                state = when (checkMiuiOp(context, MIUI_OP_BACKGROUND_POPUP)) {
                    AppOpsManager.MODE_ALLOWED -> CheckState.ON
                    AppOpsManager.MODE_ERRORED -> CheckState.UNKNOWN
                    else -> CheckState.OFF
                },
                settingsIntent = appDetails(context),
            )
        }

        return items
    }

    /** 最近一次"未使用应用限制"查询结果（异步回填；snapshot 与异常判定共用）。 */
    @Volatile
    private var unusedRestrictionsCache: CheckState? = null

    /** 主动刷新缓存（服务启动/看门狗/回前台调用）；不阻塞主线程。 */
    fun refreshUnusedRestrictions(context: Context) {
        unusedRestrictionsStatus(context) {}
    }

    /**
     * 异步查"未使用应用限制/应用休眠"状态：PackageManagerCompat 返回
     * ListenableFuture，绝不能阻塞主线程——结果先写缓存再经主线程回调。
     * DISABLED/FEATURE_NOT_AVAILABLE 视为已满足；API_30* / API_31 未满足；
     * ERROR 或异常 → UNKNOWN。
     */
    fun unusedRestrictionsStatus(context: Context, onResult: (CheckState) -> Unit) {
        try {
            val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
            future.addListener({
                val st = runCatching { future.get() }.getOrNull()
                val state = when (st) {
                    UnusedAppRestrictionsConstants.DISABLED,
                    UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE,
                    -> CheckState.ON
                    UnusedAppRestrictionsConstants.API_30_BACKPORT,
                    UnusedAppRestrictionsConstants.API_30,
                    UnusedAppRestrictionsConstants.API_31,
                    -> CheckState.OFF
                    else -> CheckState.UNKNOWN
                }
                unusedRestrictionsCache = state
                onResult(state)
            }, ContextCompat.getMainExecutor(context))
        } catch (t: Throwable) {
            onResult(CheckState.UNKNOWN)
        }
    }

    /** 需手动确认的必需项清单（不含"检测为 UNKNOWN 而降级"的项——那些由 UI 动态并入）。 */
    fun manualItems(context: Context): List<ManualItem> = buildList {
        add(
            ManualItem(
                key = "autostart",
                title = context.getString(R.string.manual_autostart),
                desc = context.getString(R.string.manual_autostart_desc),
                open = { OemSettings.openAutostart(it) },
            )
        )
        add(
            ManualItem(
                key = "battery_no_limit",
                title = context.getString(R.string.manual_battery),
                desc = context.getString(R.string.manual_battery_desc),
                open = { OemSettings.openBattery(it) },
            )
        )
        add(
            ManualItem(
                key = "recents_lock",
                title = context.getString(R.string.manual_recents_lock),
                desc = context.getString(R.string.manual_recents_lock_desc),
            )
        )
        if (isMiuiDevice()) {
            add(
                ManualItem(
                    key = "dial_always",
                    title = context.getString(R.string.manual_dial_always),
                    desc = context.getString(R.string.manual_dial_always_desc),
                    open = { OemSettings.openAppDetails(it) },
                )
            )
        }
        add(
            ManualItem(
                key = "lockscreen_notify",
                title = context.getString(R.string.manual_lockscreen_notify),
                desc = context.getString(R.string.manual_lockscreen_notify_desc),
                open = {
                    it.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
            )
        )
    }

    /**
     * 守护开启后的异常判定（首页异常卡 + 状态通知文案共用）：
     * 必需自动检测项被收回 / 演练记录失效（App 升级、系统升级、换联系人）/
     * 手动确认因系统升级失效。
     */
    fun anomalyReasons(context: Context): List<String> {
        val store = ServiceLocator.store
        val cfg = store.configFlow.value
        if (!cfg.enabled) return emptyList()
        val st = store.stateFlow.value
        val reasons = mutableListOf<String>()
        // 必需自动检测项被收回（UNKNOWN 不算异常——检测不到不冤枉用户）
        val lost = snapshot(context)
            .filter { !it.optional && it.state == CheckState.OFF }
        if (lost.isNotEmpty()) {
            reasons += "必需项已被关闭：" + lost.joinToString("、") { it.title }
        }
        // 演练记录失效（qualifiedPass：最近一次"通过且等待达标"的记录；
        // 快速检查/中止不影响它；最近一次 FAILED 会清空它——如实说明）
        reasons += if (
            st.qualifiedPass == null &&
            st.lastDrillResult?.outcome == io.github.xiaohaoz1.jiarenxia.guardian.core.DrillOutcome.FAILED
        ) {
            listOf("上次演练未通过：" + st.lastDrillResult.reason)
        } else {
            DrillRules.invalidReasons(
                st.qualifiedPass,
                ServiceLocator.ports.appVersionCode(),
                Build.FINGERPRINT,
                DrillRules.contactsKey(cfg.validContacts),
            )
        }
        // 手动确认项因系统升级整体失效
        if (store.manualFingerprintStale(Build.FINGERPRINT)) {
            reasons += "系统已升级，请重新确认权限清单中的手动项"
        }
        return reasons
    }

    /** 小米/红米/POCO（含澎湃OS）：厂商名或 ro.miui.ui.version.name 任一命中。 */
    fun isMiuiDevice(): Boolean {
        if (Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return true
        return runCatching {
            @Suppress("PrivateApi")
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, "ro.miui.ui.version.name") as? String
        }.getOrNull()?.isNotEmpty() == true
    }

    /** 读 MIUI 私有 appops 值：int op 的重载不在公开 SDK，走反射调用。 */
    private fun checkMiuiOp(context: Context, op: Int): Int {
        val ops = context.getSystemService(AppOpsManager::class.java)
            ?: return AppOpsManager.MODE_ERRORED
        return runCatching {
            @Suppress("DiscouragedPrivateApi")
            AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            ).invoke(ops, op, Process.myUid(), context.packageName) as Int
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
    }

    /** MIUI 私有 op：后台弹出界面。 */
    private const val MIUI_OP_BACKGROUND_POPUP = 10021

    private fun appDetails(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
}
